use crate::models::{ActiveProtocol, ProxyMode, RoutingMode, ServerProfile};
use crate::rules;
use crate::storage::app_data_dir;
use crate::sysproxy;
use serde_json::{json, Value};
use std::collections::VecDeque;
use std::fs;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::{Child, Command};

#[cfg(target_os = "windows")]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

pub const CLASH_API_PORT: u16 = 19090;

pub fn build_singbox_config(
    profile: &ServerProfile,
    routing_mode: &RoutingMode,
    proxy_mode: &ProxyMode,
    local_proxy_port: u16,
) -> Value {
    let use_hy2 =
        profile.active_protocol == ActiveProtocol::Hysteria2 && profile.hysteria2.enabled;
    let regional_rules = rules::rule_paths_for_mode(routing_mode);

    // 1. Typed DNS servers (sing-box 1.12+ schema)
    let dns_servers = json!([
        {
            "tag": "dns_remote",
            "type": "tcp",
            "server": "1.1.1.1",
            "server_port": 53,
            "detour": "proxy"
        },
        {
            "tag": "dns_local",
            "type": "local"
        }
    ]);

    let mut dns_rules: Vec<Value> = vec![json!({
        "domain": [profile.host],
        "server": "dns_local"
    })];
    if regional_rules.is_some() {
        dns_rules.push(json!({
            "rule_set": ["geosite-direct"],
            "server": "dns_local"
        }));
    }

    let dns_final = if *routing_mode == RoutingMode::Direct {
        "dns_local"
    } else {
        "dns_remote"
    };

    // 2. Inbounds: always expose mixed local proxy; add TUN inbound when ProxyMode::Tun
    let mut inbounds: Vec<Value> = vec![json!({
        "type": "mixed",
        "tag": "mixed-in",
        "listen": "127.0.0.1",
        "listen_port": local_proxy_port,
        "set_system_proxy": false
    })];

    if *proxy_mode == ProxyMode::Tun {
        inbounds.push(json!({
            "type": "tun",
            "tag": "tun-in",
            "address": ["172.19.0.1/30"],
            "mtu": 1500,
            "auto_route": true,
            "strict_route": true,
            "stack": "gvisor"
        }));
    }

    // 3. Outbound: VLESS+REALITY (default) or opt-in Hysteria2
    let proxy_outbound = if use_hy2 {
        let mut tls = json!({
            "enabled": true,
            "server_name": if profile.hysteria2.tls_mode == "acme" {
                profile.host.clone()
            } else {
                "build-myvpn.local".to_string()
            },
            "alpn": ["h3"]
        });
        if profile.hysteria2.tls_mode == "self_signed_pinned"
            && !profile.hysteria2.certificate_sha256.is_empty()
        {
            tls["certificate_public_key_sha256"] = json!([profile.hysteria2.certificate_sha256]);
        }
        json!({
            "type": "hysteria2",
            "tag": "proxy",
            "server": profile.host,
            "server_port": profile.vpn_port,
            "password": profile.hysteria2.password,
            "up_mbps": profile.hysteria2.up_mbps.max(10),
            "down_mbps": profile.hysteria2.down_mbps.max(20),
            "tls": tls
        })
    } else {
        json!({
            "type": "vless",
            "tag": "proxy",
            "server": profile.host,
            "server_port": profile.vpn_port,
            "uuid": profile.reality.uuid,
            "flow": "xtls-rprx-vision",
            "tls": {
                "enabled": true,
                "server_name": if profile.reality.sni.is_empty() { "www.apple.com" } else { &profile.reality.sni },
                "utls": {
                    "enabled": true,
                    "fingerprint": "chrome"
                },
                "reality": {
                    "enabled": true,
                    "public_key": profile.reality.public_key,
                    "short_id": profile.reality.short_id
                }
            }
        })
    };

    // 4. Route rules (with QUIC UDP 443 reject on TCP REALITY to prevent stalls)
    let mut route_rules: Vec<Value> = vec![
        json!({ "action": "sniff" }),
        json!({ "protocol": "dns", "action": "hijack-dns" }),
    ];
    if !use_hy2 {
        route_rules.push(json!({
            "network": "udp",
            "port": 443,
            "action": "reject"
        }));
    }
    route_rules.push(json!({
        "ip_is_private": true,
        "outbound": "direct"
    }));
    route_rules.push(json!({
        "domain": [profile.host],
        "outbound": "direct"
    }));

    let mut rule_sets: Vec<Value> = Vec::new();
    if let Some((gs_path, gi_path)) = regional_rules {
        rule_sets.push(json!({
            "tag": "geosite-direct",
            "type": "local",
            "format": "binary",
            "path": gs_path.to_string_lossy()
        }));
        rule_sets.push(json!({
            "tag": "geoip-direct",
            "type": "local",
            "format": "binary",
            "path": gi_path.to_string_lossy()
        }));
        route_rules.push(json!({
            "rule_set": ["geosite-direct", "geoip-direct"],
            "outbound": "direct"
        }));
    }

    let route_final = if *routing_mode == RoutingMode::Direct {
        "direct"
    } else {
        "proxy"
    };

    let mut route_obj = json!({
        "rules": route_rules,
        "final": route_final,
        "auto_detect_interface": true,
        "default_domain_resolver": "dns_local"
    });
    if !rule_sets.is_empty() {
        route_obj["rule_set"] = json!(rule_sets);
    }

    json!({
        "log": {
            "level": "info",
            "timestamp": true
        },
        "experimental": {
            "clash_api": {
                "external_controller": format!("127.0.0.1:{CLASH_API_PORT}")
            }
        },
        "dns": {
            "servers": dns_servers,
            "rules": dns_rules,
            "final": dns_final,
            "strategy": "ipv4_only",
            "independent_cache": true
        },
        "inbounds": inbounds,
        "outbounds": [
            proxy_outbound,
            { "type": "direct", "tag": "direct" }
        ],
        "route": route_obj
    })
}

pub fn resolve_sidecar_binary() -> Result<PathBuf, String> {
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let exe_dir = exe
        .parent()
        .ok_or_else(|| "Cannot resolve executable directory".to_string())?;

    let bin_name = if cfg!(target_os = "windows") {
        "sing-box.exe"
    } else {
        "sing-box"
    };

    let candidate = exe_dir.join(bin_name);
    if candidate.is_file() {
        return Ok(candidate);
    }

    // Dev fallback inside src-tauri/binaries/
    if let Ok(entries) = fs::read_dir(exe_dir.join("binaries")) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if name.starts_with("sing-box") && entry.path().is_file() {
                return Ok(entry.path());
            }
        }
    }

    Err(format!(
        "Bundled sing-box binary not found next to app executable ({})",
        candidate.display()
    ))
}

pub struct SingBoxManager {
    child: Option<Child>,
    tun_elevated: bool,
    pub logs: Arc<Mutex<VecDeque<String>>>,
}

impl SingBoxManager {
    pub fn new() -> Self {
        Self {
            child: None,
            tun_elevated: false,
            logs: Arc::new(Mutex::new(VecDeque::with_capacity(140))),
        }
    }

    pub fn push_log(&self, line: String) {
        if let Ok(mut q) = self.logs.lock() {
            if q.len() >= 120 {
                q.pop_front();
            }
            q.push_back(line);
        }
    }

    pub fn get_logs(&self) -> Vec<String> {
        self.logs
            .lock()
            .map(|q| q.iter().cloned().collect())
            .unwrap_or_default()
    }

    pub async fn start(
        &mut self,
        profile: &ServerProfile,
        routing_mode: &RoutingMode,
        proxy_mode: &ProxyMode,
        local_proxy_port: u16,
    ) -> Result<(), String> {
        self.stop().await;

        let bin = resolve_sidecar_binary()?;
        let cfg = build_singbox_config(profile, routing_mode, proxy_mode, local_proxy_port);
        let cfg_path = app_data_dir().join("runtime-config.json");
        let cfg_text = serde_json::to_string_pretty(&cfg).map_err(|e| e.to_string())?;
        fs::write(&cfg_path, cfg_text).map_err(|e| format!("Failed writing config: {e}"))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = fs::set_permissions(&cfg_path, fs::Permissions::from_mode(0o600));
        }

        self.push_log(format!(
            "[BuildMyVpn] Starting sing-box ({:?}) -> {}:{}",
            proxy_mode, profile.host, profile.vpn_port
        ));

        if *proxy_mode == ProxyMode::Tun {
            self.start_elevated_tun(&bin, &cfg_path).await?;
        } else {
            let mut cmd = Command::new(&bin);
            cmd.args(["run", "-c"])
                .arg(&cfg_path)
                .stdout(std::process::Stdio::piped())
                .stderr(std::process::Stdio::piped())
                .kill_on_drop(true);
            #[cfg(target_os = "windows")]
            cmd.creation_flags(CREATE_NO_WINDOW);

            let mut child = cmd
                .spawn()
                .map_err(|e| format!("Failed launching sing-box: {e}"))?;

            if let Some(stdout) = child.stdout.take() {
                let logs = self.logs.clone();
                tokio::spawn(async move {
                    let mut lines = BufReader::new(stdout).lines();
                    while let Ok(Some(line)) = lines.next_line().await {
                        if let Ok(mut q) = logs.lock() {
                            if q.len() >= 120 {
                                q.pop_front();
                            }
                            q.push_back(line);
                        }
                    }
                });
            }
            if let Some(stderr) = child.stderr.take() {
                let logs = self.logs.clone();
                tokio::spawn(async move {
                    let mut lines = BufReader::new(stderr).lines();
                    while let Ok(Some(line)) = lines.next_line().await {
                        if let Ok(mut q) = logs.lock() {
                            if q.len() >= 120 {
                                q.pop_front();
                            }
                            q.push_back(line);
                        }
                    }
                });
            }
            self.child = Some(child);
            self.tun_elevated = false;
        }

        // Wait up to 4.5s for Clash API to confirm sing-box initialized cleanly
        let client = reqwest::Client::builder()
            .timeout(Duration::from_millis(600))
            .build()
            .map_err(|e| e.to_string())?;
        let health_url = format!("http://127.0.0.1:{CLASH_API_PORT}/version");
        let mut ready = false;
        for _ in 0..18 {
            if let Some(child) = self.child.as_mut() {
                if let Ok(Some(status)) = child.try_wait() {
                    return Err(format!(
                        "sing-box exited immediately ({status}). Check diagnostics logs."
                    ));
                }
            }
            if let Ok(resp) = client.get(&health_url).send().await {
                if resp.status().is_success() {
                    ready = true;
                    break;
                }
            }
            tokio::time::sleep(Duration::from_millis(250)).await;
        }

        if !ready {
            self.stop().await;
            return Err("sing-box did not become ready within 4.5s".to_string());
        }

        if *proxy_mode == ProxyMode::SystemProxy {
            if let Err(e) = sysproxy::enable_system_proxy("127.0.0.1", local_proxy_port) {
                self.stop().await;
                return Err(e);
            }
        }

        self.push_log("[BuildMyVpn] Connected cleanly.".to_string());
        Ok(())
    }

    async fn start_elevated_tun(&mut self, bin: &PathBuf, cfg_path: &PathBuf) -> Result<(), String> {
        let pid_file = app_data_dir().join("tun.pid");
        let log_file = app_data_dir().join("tun.log");

        #[cfg(target_os = "macos")]
        {
            let script = format!(
                "'{}' run -c '{}' > '{}' 2>&1 & echo $! > '{}'",
                bin.display(),
                cfg_path.display(),
                log_file.display(),
                pid_file.display()
            );
            let osa = format!(
                "do shell script \"{}\" with administrator privileges",
                script.replace('\\', "\\\\").replace('"', "\\\"")
            );
            let status = Command::new("/usr/bin/osascript")
                .args(["-e", &osa])
                .status()
                .await
                .map_err(|e| format!("macOS admin prompt failed: {e}"))?;
            if !status.success() {
                return Err("Administrator authorization was cancelled for TUN mode.".to_string());
            }
            self.tun_elevated = true;
            return Ok(());
        }

        #[cfg(target_os = "windows")]
        {
            let _ = (&pid_file, &log_file);
            let ps_cmd = format!(
                "Start-Process -FilePath '{}' -ArgumentList 'run','-c','{}' -Verb RunAs -WindowStyle Hidden",
                bin.display().to_string().replace('\'', "''"),
                cfg_path.display().to_string().replace('\'', "''"),
            );
            let status = Command::new("powershell.exe")
                .args(["-NoProfile", "-Command", &ps_cmd])
                .creation_flags(CREATE_NO_WINDOW)
                .status()
                .await
                .map_err(|e| format!("Windows UAC launch failed: {e}"))?;
            if !status.success() {
                return Err("Administrator UAC authorization was cancelled for TUN mode.".to_string());
            }
            self.tun_elevated = true;
            return Ok(());
        }

        #[cfg(not(any(target_os = "macos", target_os = "windows")))]
        {
            let _ = (bin, cfg_path, pid_file, log_file);
            Err("TUN mode is only supported on macOS and Windows".to_string())
        }
    }

    pub async fn stop(&mut self) {
        sysproxy::disable_system_proxy();

        if let Some(mut child) = self.child.take() {
            let _ = child.kill().await;
            let _ = child.wait().await;
        }

        if self.tun_elevated {
            self.tun_elevated = false;
            #[cfg(target_os = "macos")]
            {
                let pid_file = app_data_dir().join("tun.pid");
                if let Ok(pid_str) = fs::read_to_string(&pid_file) {
                    if let Ok(pid) = pid_str.trim().parse::<u32>() {
                        let osa = format!(
                            "do shell script \"kill {} 2>/dev/null || true\" with administrator privileges",
                            pid
                        );
                        let _ = Command::new("/usr/bin/osascript").args(["-e", &osa]).status().await;
                    }
                    let _ = fs::remove_file(&pid_file);
                }
            }
            #[cfg(target_os = "windows")]
            {
                let _ = Command::new("powershell.exe")
                    .args([
                        "-NoProfile",
                        "-Command",
                        "Start-Process -FilePath 'taskkill.exe' -ArgumentList '/F','/IM','sing-box.exe' -Verb RunAs -WindowStyle Hidden",
                    ])
                    .creation_flags(CREATE_NO_WINDOW)
                    .status()
                    .await;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::{Hysteria2Config, RealityConfig};

    fn sample_profile() -> ServerProfile {
        ServerProfile {
            id: "s1".to_string(),
            name: "HK VPS".to_string(),
            host: "203.0.113.10".to_string(),
            ssh_port: 22,
            vpn_port: 443,
            active_protocol: ActiveProtocol::Reality,
            bbr_status: "enabled".to_string(),
            singbox_version: "1.12.12".to_string(),
            reality: RealityConfig {
                enabled: true,
                uuid: "11111111-2222-3333-4444-555555555555".to_string(),
                public_key: "pub_test".to_string(),
                short_id: "abcd1234".to_string(),
                sni: "www.apple.com".to_string(),
            },
            hysteria2: Hysteria2Config::default(),
            ssh_host_key_fingerprint: String::new(),
            region_flag: String::new(),
            region_name: String::new(),
            country_code: String::new(),
            total_used_bytes: 0,
            created_at_ms: 0,
            last_connected_ms: 0,
        }
    }

    #[test]
    fn system_proxy_config_has_mixed_and_quic_reject() {
        let cfg = build_singbox_config(
            &sample_profile(),
            &RoutingMode::Global,
            &ProxyMode::SystemProxy,
            20808,
        );
        assert_eq!(cfg["inbounds"][0]["type"], "mixed");
        assert_eq!(cfg["inbounds"][0]["listen_port"], 20808);
        assert_eq!(cfg["dns"]["strategy"], "ipv4_only");
        assert_eq!(cfg["outbounds"][0]["type"], "vless");
        let rules = cfg["route"]["rules"].as_array().unwrap();
        assert!(rules
            .iter()
            .any(|r| r["network"] == "udp" && r["port"] == 443 && r["action"] == "reject"));
    }

    #[test]
    fn tun_config_has_gvisor_mtu_1500() {
        let cfg = build_singbox_config(
            &sample_profile(),
            &RoutingMode::Global,
            &ProxyMode::Tun,
            20808,
        );
        let inbounds = cfg["inbounds"].as_array().unwrap();
        assert_eq!(inbounds.len(), 2);
        assert_eq!(inbounds[1]["type"], "tun");
        assert_eq!(inbounds[1]["stack"], "gvisor");
        assert_eq!(inbounds[1]["mtu"], 1500);
    }
}
