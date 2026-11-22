use futures_util::StreamExt;
use serde::Serialize;
use serde_json::Value;
use std::path::PathBuf;
use std::time::Duration;
use tauri::{AppHandle, Emitter};
use tokio::io::AsyncWriteExt;

#[cfg(target_os = "windows")]
use std::os::windows::process::CommandExt;

const GITHUB_LATEST_API: &str =
    "https://api.github.com/repos/Gouryella811/build-myvpn/releases/latest";
const FALLBACK_VERSION_URL: &str = "https://build-myvpn.pages.dev/config/version.json";

#[derive(Debug, Clone, Serialize)]
pub struct UpdateCheckResult {
    pub has_update: bool,
    pub current_version: String,
    pub latest_version: String,
    pub download_url: String,
    pub asset_name: String,
    pub release_url: String,
    pub notes: String,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct UpdateDownloadProgress {
    pub pct: u8,
    pub downloaded_bytes: u64,
    pub total_bytes: u64,
    pub status: String,
}

fn parse_semver(v: &str) -> (u32, u32, u32, bool) {
    let trimmed = v.trim().trim_start_matches(['v', 'V']);
    let is_prerelease = trimmed.contains('-');
    let core = trimmed.split('-').next().unwrap_or(trimmed);
    let mut parts = core.split('.');
    let major = parts.next().and_then(|s| s.parse().ok()).unwrap_or(0);
    let minor = parts.next().and_then(|s| s.parse().ok()).unwrap_or(0);
    let patch = parts.next().and_then(|s| s.parse().ok()).unwrap_or(0);
    (major, minor, patch, is_prerelease)
}

pub fn is_newer_version(latest: &str, current: &str) -> bool {
    let (l1, l2, l3, l_pre) = parse_semver(latest);
    let (c1, c2, c3, c_pre) = parse_semver(current);
    match (l1, l2, l3).cmp(&(c1, c2, c3)) {
        std::cmp::Ordering::Greater => true,
        std::cmp::Ordering::Less => false,
        std::cmp::Ordering::Equal => !l_pre && c_pre,
    }
}

fn target_asset_suffix() -> &'static str {
    if cfg!(target_os = "macos") {
        "-macos-universal.dmg"
    } else {
        "-windows-x64-setup.exe"
    }
}

pub async fn check_for_update() -> UpdateCheckResult {
    let current = env!("CARGO_PKG_VERSION").to_string();
    let suffix = target_asset_suffix();

    let client = match reqwest::Client::builder()
        .timeout(Duration::from_secs(10))
        .user_agent(format!("BuildMyVpn/{current}"))
        .build()
    {
        Ok(c) => c,
        Err(e) => {
            return UpdateCheckResult {
                has_update: false,
                current_version: current.clone(),
                latest_version: current,
                download_url: String::new(),
                asset_name: String::new(),
                release_url: "https://github.com/Gouryella811/build-myvpn/releases/latest"
                    .to_string(),
                notes: String::new(),
                error: Some(e.to_string()),
            };
        }
    };

    // 1. Primary: Official GitHub Releases Latest API
    if let Ok(resp) = client.get(GITHUB_LATEST_API).send().await {
        if resp.status().is_success() {
            if let Ok(v) = resp.json::<Value>().await {
                if let Some(tag) = v["tag_name"].as_str() {
                    let latest = tag.trim_start_matches(['v', 'V']).to_string();
                    let release_url = v["html_url"]
                        .as_str()
                        .unwrap_or("https://github.com/Gouryella811/build-myvpn/releases/latest")
                        .to_string();
                    let notes = v["body"].as_str().unwrap_or("").chars().take(360).collect();
                    let mut download_url = String::new();
                    let mut asset_name = String::new();
                    if let Some(assets) = v["assets"].as_array() {
                        for a in assets {
                            let name = a["name"].as_str().unwrap_or("");
                            if name.ends_with(suffix) {
                                asset_name = name.to_string();
                                download_url =
                                    a["browser_download_url"].as_str().unwrap_or("").to_string();
                                break;
                            }
                        }
                    }
                    return UpdateCheckResult {
                        has_update: is_newer_version(&latest, &current),
                        current_version: current,
                        latest_version: latest,
                        download_url,
                        asset_name,
                        release_url,
                        notes,
                        error: None,
                    };
                }
            }
        }
    }

    // 2. Fallback: Cloudflare Pages static version.json (zero rate limit)
    if let Ok(resp) = client.get(FALLBACK_VERSION_URL).send().await {
        if resp.status().is_success() {
            if let Ok(v) = resp.json::<Value>().await {
                if let Some(ver) = v["version"].as_str() {
                    let latest = ver.trim_start_matches(['v', 'V']).to_string();
                    let key = if cfg!(target_os = "macos") {
                        "macos"
                    } else {
                        "windows_setup"
                    };
                    let download_url = v["assets"][key].as_str().unwrap_or("").to_string();
                    let asset_name = download_url
                        .rsplit('/')
                        .next()
                        .unwrap_or("")
                        .to_string();
                    return UpdateCheckResult {
                        has_update: is_newer_version(&latest, &current),
                        current_version: current,
                        latest_version: latest,
                        download_url,
                        asset_name,
                        release_url: v["release_url"]
                            .as_str()
                            .unwrap_or(
                                "https://github.com/Gouryella811/build-myvpn/releases/latest",
                            )
                            .to_string(),
                        notes: v["notes"].as_str().unwrap_or("").to_string(),
                        error: None,
                    };
                }
            }
        }
    }

    UpdateCheckResult {
        has_update: false,
        current_version: current.clone(),
        latest_version: current,
        download_url: String::new(),
        asset_name: String::new(),
        release_url: "https://github.com/Gouryella811/build-myvpn/releases/latest".to_string(),
        notes: String::new(),
        error: Some("Unable to reach update server".to_string()),
    }
}

pub async fn download_and_launch_update(
    app: &AppHandle,
    url: &str,
    asset_name: &str,
) -> Result<String, String> {
    if !url.starts_with("https://") {
        return Err("Invalid update download URL".to_string());
    }
    let safe_name: String = asset_name
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_'))
        .collect();
    let filename = if safe_name.is_empty() {
        format!("Build-MyVpn-update{}", target_asset_suffix())
    } else {
        safe_name
    };

    let dest_dir: PathBuf = dirs::download_dir().unwrap_or_else(std::env::temp_dir);
    let _ = std::fs::create_dir_all(&dest_dir);
    let dest_path = dest_dir.join(&filename);
    let tmp_path = dest_dir.join(format!("{filename}.part"));

    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(300))
        .user_agent(format!("BuildMyVpn/{}", env!("CARGO_PKG_VERSION")))
        .build()
        .map_err(|e| e.to_string())?;

    let resp = client
        .get(url)
        .send()
        .await
        .map_err(|e| format!("Download failed: {e}"))?
        .error_for_status()
        .map_err(|e| format!("Download HTTP error: {e}"))?;

    let total = resp.content_length().unwrap_or(0);
    let mut file = tokio::fs::File::create(&tmp_path)
        .await
        .map_err(|e| format!("Cannot create file: {e}"))?;

    let mut downloaded: u64 = 0;
    let mut last_pct: u8 = 255;
    let mut stream = resp.bytes_stream();

    while let Some(chunk) = stream.next().await {
        let data = chunk.map_err(|e| format!("Download interrupted: {e}"))?;
        file.write_all(&data)
            .await
            .map_err(|e| format!("Write error: {e}"))?;
        downloaded = downloaded.saturating_add(data.len() as u64);
        let pct = if total > 0 {
            ((downloaded * 100) / total).min(100) as u8
        } else {
            50
        };
        if pct != last_pct {
            last_pct = pct;
            let _ = app.emit(
                "update-download-progress",
                UpdateDownloadProgress {
                    pct,
                    downloaded_bytes: downloaded,
                    total_bytes: total,
                    status: "downloading".to_string(),
                },
            );
        }
    }

    file.flush().await.map_err(|e| e.to_string())?;
    drop(file);
    std::fs::rename(&tmp_path, &dest_path).map_err(|e| format!("Failed saving installer: {e}"))?;

    let _ = app.emit(
        "update-download-progress",
        UpdateDownloadProgress {
            pct: 100,
            downloaded_bytes: downloaded,
            total_bytes: downloaded,
            status: "launching".to_string(),
        },
    );

    // Open downloaded DMG (macOS) or launch NSIS setup EXE (Windows)
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("/usr/bin/open")
            .arg(&dest_path)
            .spawn()
            .map_err(|e| format!("Failed opening DMG: {e}"))?;
    }
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new(&dest_path)
            .creation_flags(0x0000_0008) // DETACHED_PROCESS
            .spawn()
            .map_err(|e| format!("Failed launching installer: {e}"))?;
    }

    Ok(dest_path.to_string_lossy().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn semver_comparison() {
        assert!(is_newer_version("1.1.2", "1.1.1"));
        assert!(is_newer_version("v1.2.0", "1.1.9"));
        assert!(is_newer_version("2.0.0", "1.9.9"));
        assert!(is_newer_version("1.1.0", "1.1.0-beta"));
        assert!(!is_newer_version("1.1.2", "1.1.2"));
        assert!(!is_newer_version("1.1.0", "1.1.2"));
        assert!(!is_newer_version("1.1.2-beta", "1.1.2"));
    }
}
