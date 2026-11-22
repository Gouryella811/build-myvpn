use crate::models::{
    ActiveProtocol, DeployProgressEvent, DeployRequest, DeployResult, Hysteria2Config,
    RealityConfig, ServerProfile,
};
use russh::client;
use russh::keys::{decode_secret_key, HashAlg, PrivateKeyWithHashAlg, PublicKey};
use russh::ChannelMsg;
use serde_json::Value;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{AppHandle, Emitter};
use zeroize::Zeroizing;

const INSTALL_SCRIPT: &str = include_str!("../resources/install_singbox.sh");

struct TofuHandler {
    trusted_fingerprint: Option<String>,
    observed_fingerprint: Arc<Mutex<Option<String>>>,
}

impl client::Handler for TofuHandler {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        server_public_key: &PublicKey,
    ) -> Result<bool, Self::Error> {
        let fp = server_public_key.fingerprint(HashAlg::Sha256).to_string();
        if let Ok(mut guard) = self.observed_fingerprint.lock() {
            *guard = Some(fp.clone());
        }
        match &self.trusted_fingerprint {
            Some(expected) if expected.trim() == fp.trim() => Ok(true),
            _ => Ok(false),
        }
    }
}

fn sanitize_host(host: &str) -> Result<String, String> {
    let trimmed = host.trim();
    if trimmed.is_empty()
        || !trimmed
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | ':'))
    {
        return Err("Invalid VPS IP address or domain name".to_string());
    }
    Ok(trimmed.to_string())
}

fn emit_progress(app: &AppHandle, step: &str, status: &str, pct: u8, code: &str, message: &str) {
    let _ = app.emit(
        "deploy-progress",
        DeployProgressEvent {
            step: step.to_string(),
            status: status.to_string(),
            pct,
            code: code.to_string(),
            message: message.to_string(),
        },
    );
}

pub async fn run_ssh_deploy(app: &AppHandle, req: DeployRequest) -> DeployResult {
    let host = match sanitize_host(&req.host) {
        Ok(h) => h,
        Err(msg) => {
            return DeployResult {
                status: "error".to_string(),
                fingerprint: None,
                profile: None,
                code: Some("ERR_INVALID_HOST".to_string()),
                message: msg,
            };
        }
    };

    let mut password = Zeroizing::new(req.password);
    let private_key_pem = Zeroizing::new(req.private_key_pem);
    let passphrase = Zeroizing::new(req.passphrase);
    let username = if req.username.trim().is_empty() {
        "root".to_string()
    } else {
        req.username.trim().to_string()
    };

    emit_progress(app, "ssh_connect", "running", 5, "", "Connecting to VPS over SSH...");

    let observed_fp = Arc::new(Mutex::new(None::<String>));
    let handler = TofuHandler {
        trusted_fingerprint: req.trusted_fingerprint.clone(),
        observed_fingerprint: observed_fp.clone(),
    };

    let config = Arc::new(client::Config {
        inactivity_timeout: Some(Duration::from_secs(120)),
        keepalive_interval: Some(Duration::from_secs(15)),
        ..Default::default()
    });

    let addr = format!("{host}:{}", req.ssh_port);
    let connect_res = tokio::time::timeout(
        Duration::from_secs(20),
        client::connect(config, addr.as_str(), handler),
    )
    .await;

    let mut session = match connect_res {
        Ok(Ok(s)) => s,
        Ok(Err(e)) => {
            // Check if rejection was due to TOFU host key confirmation needed
            if let Ok(guard) = observed_fp.lock() {
                if let Some(fp) = guard.clone() {
                    if req.trusted_fingerprint.as_deref() != Some(fp.as_str()) {
                        return DeployResult {
                            status: "needs_host_key_confirm".to_string(),
                            fingerprint: Some(fp),
                            profile: None,
                            code: Some("HOST_KEY_VERIFY".to_string()),
                            message: "Please verify the VPS SSH host key fingerprint before continuing.".to_string(),
                        };
                    }
                }
            }
            return DeployResult {
                status: "error".to_string(),
                fingerprint: None,
                profile: None,
                code: Some("ERR_SSH_CONNECT".to_string()),
                message: format!("SSH connection failed: {e}"),
            };
        }
        Err(_) => {
            return DeployResult {
                status: "error".to_string(),
                fingerprint: None,
                profile: None,
                code: Some("ERR_SSH_TIMEOUT".to_string()),
                message: "SSH connection timed out (20s). Check IP, port 22, and firewall.".to_string(),
            };
        }
    };

    let verified_fp = observed_fp
        .lock()
        .ok()
        .and_then(|g| g.clone())
        .unwrap_or_default();

    emit_progress(app, "ssh_auth", "running", 12, "", "Authenticating SSH session...");

    let auth_ok = if !private_key_pem.trim().is_empty() {
        let pass_opt = if passphrase.is_empty() {
            None
        } else {
            Some(passphrase.as_str())
        };
        let key_pair = match decode_secret_key(private_key_pem.trim(), pass_opt) {
            Ok(k) => k,
            Err(e) => {
                return DeployResult {
                    status: "error".to_string(),
                    fingerprint: Some(verified_fp),
                    profile: None,
                    code: Some("ERR_AUTH_FAILED".to_string()),
                    message: format!("Invalid SSH private key: {e}"),
                };
            }
        };
        let hash_alg = session
            .best_supported_rsa_hash()
            .await
            .ok()
            .flatten()
            .flatten();
        let auth_key = match PrivateKeyWithHashAlg::new(Arc::new(key_pair), hash_alg) {
            Ok(k) => k,
            Err(e) => {
                return DeployResult {
                    status: "error".to_string(),
                    fingerprint: Some(verified_fp),
                    profile: None,
                    code: Some("ERR_AUTH_FAILED".to_string()),
                    message: format!("SSH key setup failed: {e}"),
                };
            }
        };
        match session.authenticate_publickey(&username, auth_key).await {
            Ok(res) => res.success(),
            Err(e) => {
                return DeployResult {
                    status: "error".to_string(),
                    fingerprint: Some(verified_fp),
                    profile: None,
                    code: Some("ERR_AUTH_FAILED".to_string()),
                    message: format!("SSH key authentication failed: {e}"),
                };
            }
        }
    } else {
        match session.authenticate_password(&username, password.as_str()).await {
            Ok(res) => res.success(),
            Err(e) => {
                return DeployResult {
                    status: "error".to_string(),
                    fingerprint: Some(verified_fp),
                    profile: None,
                    code: Some("ERR_AUTH_FAILED".to_string()),
                    message: format!("SSH password authentication failed: {e}"),
                };
            }
        }
    };

    // Erase private key & passphrase from memory immediately after auth
    drop(private_key_pem);
    drop(passphrase);

    if !auth_ok {
        password.clear();
        return DeployResult {
            status: "error".to_string(),
            fingerprint: Some(verified_fp),
            profile: None,
            code: Some("ERR_AUTH_FAILED".to_string()),
            message: "SSH authentication rejected. Check username and password/key.".to_string(),
        };
    }

    // 1. Upload install_singbox.sh cleanly via exec stdin (works without SFTP)
    emit_progress(app, "upload_script", "running", 18, "", "Uploading deployment script...");
    let upload_res: Result<(), String> = async {
        let mut ch = session
            .channel_open_session()
            .await
            .map_err(|e| e.to_string())?;
        ch.exec(
            true,
            "cat > /tmp/build_myvpn_install.sh && chmod 700 /tmp/build_myvpn_install.sh",
        )
        .await
        .map_err(|e| e.to_string())?;
        for chunk in INSTALL_SCRIPT.as_bytes().chunks(16384) {
            ch.data(chunk).await.map_err(|e| e.to_string())?;
        }
        ch.eof().await.map_err(|e| e.to_string())?;
        while let Some(msg) = ch.wait().await {
            if let ChannelMsg::ExitStatus { exit_status } = msg {
                if exit_status != 0 {
                    return Err(format!("Script upload failed with exit code {exit_status}"));
                }
            }
        }
        Ok(())
    }
    .await;

    if let Err(err) = upload_res {
        password.clear();
        return DeployResult {
            status: "error".to_string(),
            fingerprint: Some(verified_fp),
            profile: None,
            code: Some("ERR_UPLOAD_FAILED".to_string()),
            message: err,
        };
    }

    // 2. Execute deployment script and parse JSON Lines stream
    let script_args = match req.action.as_str() {
        "disable_hy2" => "--disable-hy2".to_string(),
        "uninstall" => "--uninstall".to_string(),
        _ => {
            let proto = if req.protocol_mode == "dual" {
                "dual"
            } else {
                "reality"
            };
            format!("--host '{host}' --protocol '{proto}'")
        }
    };

    let needs_sudo_pass = username != "root" && !password.is_empty();
    let remote_cmd = if username == "root" {
        format!(
            "bash /tmp/build_myvpn_install.sh {script_args}; RC=$?; rm -f /tmp/build_myvpn_install.sh; exit $RC"
        )
    } else if needs_sudo_pass {
        format!(
            "sudo -S -p '' bash /tmp/build_myvpn_install.sh {script_args}; RC=$?; rm -f /tmp/build_myvpn_install.sh; exit $RC"
        )
    } else {
        format!(
            "sudo -n bash /tmp/build_myvpn_install.sh {script_args}; RC=$?; rm -f /tmp/build_myvpn_install.sh; exit $RC"
        )
    };

    emit_progress(app, "deploy", "running", 22, "", "Running VPS setup script...");

    let mut ch = match session.channel_open_session().await {
        Ok(c) => c,
        Err(e) => {
            password.clear();
            return DeployResult {
                status: "error".to_string(),
                fingerprint: Some(verified_fp),
                profile: None,
                code: Some("ERR_EXEC".to_string()),
                message: format!("Failed opening SSH exec channel: {e}"),
            };
        }
    };

    if let Err(e) = ch.exec(true, remote_cmd.as_str()).await {
        password.clear();
        return DeployResult {
            status: "error".to_string(),
            fingerprint: Some(verified_fp),
            profile: None,
            code: Some("ERR_EXEC".to_string()),
            message: format!("Failed starting deploy script: {e}"),
        };
    }

    if needs_sudo_pass {
        let mut sudo_line = Zeroizing::new(format!("{}\n", password.as_str()));
        let _ = ch.data(sudo_line.as_bytes()).await;
        sudo_line.clear();
    }
    // Immediately wipe SSH password from memory once sent!
    password.clear();
    drop(password);

    let mut buf = String::new();
    let mut final_node: Option<Value> = None;
    let mut last_error_code = String::new();
    let mut last_error_msg = String::new();
    let mut exit_code: u32 = 0;

    while let Some(msg) = ch.wait().await {
        match msg {
            ChannelMsg::Data { ref data } => {
                buf.push_str(&String::from_utf8_lossy(data));
                while let Some(pos) = buf.find('\n') {
                    let line = buf[..pos].trim().to_string();
                    buf.drain(..=pos);
                    if line.starts_with('{') && line.ends_with('}') {
                        if let Ok(v) = serde_json::from_str::<Value>(&line) {
                            let step = v["step"].as_str().unwrap_or("deploy");
                            let status = v["status"].as_str().unwrap_or("running");
                            let pct = v["pct"].as_u64().unwrap_or(50).min(100) as u8;
                            let code = v["code"].as_str().unwrap_or("");
                            let message = v["message"].as_str().unwrap_or("");
                            emit_progress(app, step, status, pct, code, message);
                            if status == "error" {
                                last_error_code = code.to_string();
                                last_error_msg = message.to_string();
                            }
                            if v.get("node").is_some() && !v["node"].is_null() {
                                final_node = Some(v["node"].clone());
                            }
                        }
                    }
                }
            }
            ChannelMsg::ExtendedData { ref data, .. } => {
                let err_txt = String::from_utf8_lossy(data);
                let trimmed = err_txt.trim();
                if !trimmed.is_empty() && last_error_msg.is_empty() {
                    last_error_msg = trimmed.chars().take(240).collect();
                }
            }
            ChannelMsg::ExitStatus { exit_status } => {
                exit_code = exit_status;
            }
            _ => {}
        }
    }

    let _ = session
        .disconnect(russh::Disconnect::ByApplication, "done", "")
        .await;

    if exit_code != 0 {
        return DeployResult {
            status: "error".to_string(),
            fingerprint: Some(verified_fp),
            profile: None,
            code: Some(if last_error_code.is_empty() {
                "ERR_DEPLOY_FAILED".to_string()
            } else {
                last_error_code
            }),
            message: if last_error_msg.is_empty() {
                format!("Deployment script exited with code {exit_code}")
            } else {
                last_error_msg
            },
        };
    }

    if req.action == "uninstall" {
        emit_progress(app, "done", "ok", 100, "", "VPS service uninstalled cleanly.");
        return DeployResult {
            status: "ok".to_string(),
            fingerprint: Some(verified_fp),
            profile: None,
            code: None,
            message: "Uninstalled successfully".to_string(),
        };
    }

    let Some(node) = final_node else {
        return DeployResult {
            status: "error".to_string(),
            fingerprint: Some(verified_fp),
            profile: None,
            code: Some("ERR_NO_NODE_JSON".to_string()),
            message: "Deployment completed but no node credentials were returned.".to_string(),
        };
    };

    let now_ms = chrono::Utc::now().timestamp_millis();
    let label = if req.name.trim().is_empty() {
        host.clone()
    } else {
        req.name.trim().to_string()
    };

    let r = &node["reality"];
    let h = &node["hysteria2"];
    let reality = RealityConfig {
        enabled: r["enabled"].as_bool().unwrap_or(true),
        uuid: r["uuid"].as_str().unwrap_or("").to_string(),
        public_key: r["public_key"].as_str().unwrap_or("").to_string(),
        short_id: r["short_id"].as_str().unwrap_or("").to_string(),
        sni: r["sni"].as_str().unwrap_or("www.apple.com").to_string(),
    };
    let hysteria2 = Hysteria2Config {
        enabled: h["enabled"].as_bool().unwrap_or(false),
        password: h["password"].as_str().unwrap_or("").to_string(),
        tls_mode: h["tls_mode"].as_str().unwrap_or("").to_string(),
        certificate_sha256: h["certificate_sha256"].as_str().unwrap_or("").to_string(),
        up_mbps: 50,
        down_mbps: 200,
    };

    let profile = ServerProfile {
        id: req
            .existing_server_id
            .unwrap_or_else(|| uuid::Uuid::new_v4().to_string()),
        name: label,
        host: node["host"].as_str().unwrap_or(&host).to_string(),
        ssh_port: req.ssh_port,
        vpn_port: node["port"].as_u64().unwrap_or(443) as u16,
        active_protocol: ActiveProtocol::Reality,
        bbr_status: node["bbr_status"].as_str().unwrap_or("unknown").to_string(),
        singbox_version: node["singbox_version"].as_str().unwrap_or("1.12.12").to_string(),
        reality,
        hysteria2,
        ssh_host_key_fingerprint: verified_fp.clone(),
        region_flag: String::new(),
        region_name: String::new(),
        country_code: String::new(),
        total_used_bytes: 0,
        created_at_ms: now_ms,
        last_connected_ms: 0,
    };

    emit_progress(app, "done", "ok", 100, "", "Deployment complete! SSH password erased.");
    DeployResult {
        status: "ok".to_string(),
        fingerprint: Some(verified_fp),
        profile: Some(profile),
        code: None,
        message: "Deployment successful".to_string(),
    }
}
