use crate::models::{AppSettings, ServerProfile};
use aes_gcm::{
    aead::{Aead, KeyInit},
    Aes256Gcm, Nonce,
};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use rand::RngCore;
use std::fs;
use std::path::PathBuf;

const KEYRING_SERVICE: &str = "com.buildmyvpn.desktop";
const KEYRING_USER: &str = "profiles_aes256_key_v1";

pub fn app_data_dir() -> PathBuf {
    let base = dirs::data_local_dir().unwrap_or_else(|| PathBuf::from("."));
    let dir = base.join("BuildMyVpn");
    let _ = fs::create_dir_all(&dir);
    dir
}

fn get_or_create_master_key() -> Result<[u8; 32], String> {
    // 1. Prefer native OS Keychain (macOS) / Credential Manager (Windows)
    if let Ok(entry) = keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER) {
        if let Ok(b64) = entry.get_password() {
            if let Ok(raw) = B64.decode(b64.trim()) {
                if raw.len() == 32 {
                    let mut key = [0u8; 32];
                    key.copy_from_slice(&raw);
                    return Ok(key);
                }
            }
        }
        let mut key = [0u8; 32];
        rand::thread_rng().fill_bytes(&mut key);
        if entry.set_password(&B64.encode(key)).is_ok() {
            return Ok(key);
        }
    }

    // 2. Fallback to restricted local key file inside app data dir
    let key_path = app_data_dir().join(".master.key");
    if let Ok(text) = fs::read_to_string(&key_path) {
        if let Ok(raw) = B64.decode(text.trim()) {
            if raw.len() == 32 {
                let mut key = [0u8; 32];
                key.copy_from_slice(&raw);
                return Ok(key);
            }
        }
    }
    let mut key = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut key);
    fs::write(&key_path, B64.encode(key)).map_err(|e| format!("Failed saving local key: {e}"))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = fs::set_permissions(&key_path, fs::Permissions::from_mode(0o600));
    }
    Ok(key)
}

pub fn encrypt_bytes(key: &[u8; 32], plaintext: &[u8]) -> Result<Vec<u8>, String> {
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|e| e.to_string())?;
    let mut nonce_bytes = [0u8; 12];
    rand::thread_rng().fill_bytes(&mut nonce_bytes);
    let nonce = Nonce::from_slice(&nonce_bytes);
    let mut ciphertext = cipher
        .encrypt(nonce, plaintext)
        .map_err(|e| format!("Encrypt error: {e}"))?;
    let mut out = Vec::with_capacity(12 + ciphertext.len());
    out.extend_from_slice(&nonce_bytes);
    out.append(&mut ciphertext);
    Ok(out)
}

pub fn decrypt_bytes(key: &[u8; 32], data: &[u8]) -> Result<Vec<u8>, String> {
    if data.len() < 13 {
        return Err("Corrupted encrypted store".to_string());
    }
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|e| e.to_string())?;
    let nonce = Nonce::from_slice(&data[..12]);
    cipher
        .decrypt(nonce, &data[12..])
        .map_err(|e| format!("Decrypt error: {e}"))
}

pub fn load_profiles() -> Vec<ServerProfile> {
    let path = app_data_dir().join("profiles.enc");
    let Ok(raw) = fs::read(&path) else {
        return Vec::new();
    };
    let Ok(key) = get_or_create_master_key() else {
        return Vec::new();
    };
    let Ok(plain) = decrypt_bytes(&key, &raw) else {
        return Vec::new();
    };
    serde_json::from_slice(&plain).unwrap_or_default()
}

pub fn save_profiles(profiles: &[ServerProfile]) -> Result<(), String> {
    let key = get_or_create_master_key()?;
    let json = serde_json::to_vec(profiles).map_err(|e| e.to_string())?;
    let encrypted = encrypt_bytes(&key, &json)?;
    let path = app_data_dir().join("profiles.enc");
    let tmp = app_data_dir().join("profiles.enc.tmp");
    fs::write(&tmp, &encrypted).map_err(|e| e.to_string())?;
    fs::rename(&tmp, &path).map_err(|e| e.to_string())?;
    Ok(())
}

pub fn load_settings() -> AppSettings {
    let path = app_data_dir().join("settings.json");
    let Ok(text) = fs::read_to_string(&path) else {
        return AppSettings::default();
    };
    serde_json::from_str(&text).unwrap_or_default()
}

pub fn save_settings(settings: &AppSettings) -> Result<(), String> {
    let path = app_data_dir().join("settings.json");
    let json = serde_json::to_string_pretty(settings).map_err(|e| e.to_string())?;
    fs::write(&path, json).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn aes_gcm_roundtrip() {
        let key = [42u8; 32];
        let secret = b"{\"uuid\":\"1234-5678\"}";
        let enc = encrypt_bytes(&key, secret).unwrap();
        assert_ne!(enc, secret);
        let dec = decrypt_bytes(&key, &enc).unwrap();
        assert_eq!(dec, secret);
    }
}
