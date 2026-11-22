use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum RoutingMode {
    AutoCn,
    AutoIr,
    AutoRu,
    Global,
    Direct,
}

impl Default for RoutingMode {
    fn default() -> Self {
        Self::AutoCn
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ProxyMode {
    SystemProxy,
    Tun,
}

impl Default for ProxyMode {
    fn default() -> Self {
        Self::SystemProxy
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ActiveProtocol {
    Reality,
    Hysteria2,
}

impl Default for ActiveProtocol {
    fn default() -> Self {
        Self::Reality
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct RealityConfig {
    pub enabled: bool,
    pub uuid: String,
    pub public_key: String,
    pub short_id: String,
    pub sni: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Hysteria2Config {
    pub enabled: bool,
    pub password: String,
    pub tls_mode: String,
    pub certificate_sha256: String,
    #[serde(default = "default_up_mbps")]
    pub up_mbps: u32,
    #[serde(default = "default_down_mbps")]
    pub down_mbps: u32,
}

fn default_up_mbps() -> u32 {
    50
}
fn default_down_mbps() -> u32 {
    200
}

impl Default for Hysteria2Config {
    fn default() -> Self {
        Self {
            enabled: false,
            password: String::new(),
            tls_mode: String::new(),
            certificate_sha256: String::new(),
            up_mbps: 50,
            down_mbps: 200,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServerProfile {
    pub id: String,
    pub name: String,
    pub host: String,
    #[serde(default = "default_ssh_port")]
    pub ssh_port: u16,
    #[serde(default = "default_vpn_port")]
    pub vpn_port: u16,
    #[serde(default)]
    pub active_protocol: ActiveProtocol,
    #[serde(default)]
    pub bbr_status: String,
    #[serde(default)]
    pub singbox_version: String,
    #[serde(default)]
    pub reality: RealityConfig,
    #[serde(default)]
    pub hysteria2: Hysteria2Config,
    #[serde(default)]
    pub ssh_host_key_fingerprint: String,
    #[serde(default)]
    pub region_flag: String,
    #[serde(default)]
    pub region_name: String,
    #[serde(default)]
    pub country_code: String,
    #[serde(default)]
    pub total_used_bytes: u64,
    #[serde(default)]
    pub created_at_ms: i64,
    #[serde(default)]
    pub last_connected_ms: i64,
}

fn default_ssh_port() -> u16 {
    22
}
fn default_vpn_port() -> u16 {
    443
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppSettings {
    #[serde(default)]
    pub routing_mode: RoutingMode,
    #[serde(default)]
    pub proxy_mode: ProxyMode,
    #[serde(default = "default_lang")]
    pub language: String,
    #[serde(default = "default_theme")]
    pub theme_mode: String,
    #[serde(default)]
    pub auto_start: bool,
    #[serde(default = "default_true")]
    pub minimize_to_tray: bool,
    #[serde(default = "default_local_port")]
    pub local_proxy_port: u16,
    #[serde(default)]
    pub selected_server_id: Option<String>,
}

fn default_lang() -> String {
    "auto".to_string()
}
fn default_theme() -> String {
    "auto".to_string()
}
fn default_true() -> bool {
    true
}
fn default_local_port() -> u16 {
    20808
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            routing_mode: RoutingMode::AutoCn,
            proxy_mode: ProxyMode::SystemProxy,
            language: default_lang(),
            theme_mode: default_theme(),
            auto_start: false,
            minimize_to_tray: true,
            local_proxy_port: default_local_port(),
            selected_server_id: None,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct DeployRequest {
    pub name: String,
    pub host: String,
    #[serde(default = "default_ssh_port")]
    pub ssh_port: u16,
    pub username: String,
    #[serde(default)]
    pub password: String,
    #[serde(default)]
    pub private_key_pem: String,
    #[serde(default)]
    pub passphrase: String,
    #[serde(default = "default_protocol_mode")]
    pub protocol_mode: String, // "reality" | "dual"
    #[serde(default)]
    pub trusted_fingerprint: Option<String>,
    #[serde(default = "default_action")]
    pub action: String, // "deploy" | "disable_hy2" | "uninstall"
    #[serde(default)]
    pub existing_server_id: Option<String>,
}

fn default_protocol_mode() -> String {
    "reality".to_string()
}
fn default_action() -> String {
    "deploy".to_string()
}

#[derive(Debug, Clone, Serialize)]
pub struct DeployProgressEvent {
    pub step: String,
    pub status: String,
    pub pct: u8,
    pub code: String,
    pub message: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct DeployResult {
    pub status: String, // "ok" | "needs_host_key_confirm" | "error"
    pub fingerprint: Option<String>,
    pub profile: Option<ServerProfile>,
    pub code: Option<String>,
    pub message: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct VpnStatus {
    pub connected: bool,
    pub connecting: bool,
    pub server_id: Option<String>,
    pub server_name: String,
    pub proxy_mode: ProxyMode,
    pub active_protocol: ActiveProtocol,
    pub local_proxy_port: u16,
    pub up_bps: u64,
    pub down_bps: u64,
    pub session_bytes: u64,
    pub last_error: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct VpsCheckResult {
    pub id: String,
    pub online: bool,
    pub tcp_ms: Option<u64>,
    pub flag: String,
    pub region: String,
    pub country_code: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct DelayResult {
    pub ok: bool,
    pub ms: Option<u64>,
    pub endpoint: String,
    pub error: Option<String>,
}
