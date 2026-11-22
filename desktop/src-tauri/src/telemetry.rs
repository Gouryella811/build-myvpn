use crate::models::{DelayResult, VpsCheckResult};
use crate::singbox::CLASH_API_PORT;
use serde::Deserialize;
use serde_json::Value;
use std::time::{Duration, Instant};
use tokio::net::TcpStream;

const MONETIZATION_PRIMARY: &str = "https://build-myvpn.pages.dev/config/monetization.json";
const MONETIZATION_FALLBACK: &str =
    "https://raw.githubusercontent.com/Gouryella811/build-myvpn/main/website/config/monetization.json";

#[derive(Debug, Deserialize)]
struct ClashDelayResp {
    delay: Option<u64>,
    message: Option<String>,
}

pub async fn measure_delay() -> DelayResult {
    let client = match reqwest::Client::builder()
        .timeout(Duration::from_secs(6))
        .no_proxy()
        .build()
    {
        Ok(c) => c,
        Err(e) => {
            return DelayResult {
                ok: false,
                ms: None,
                endpoint: String::new(),
                error: Some(e.to_string()),
            }
        }
    };

    let endpoints = [
        ("gstatic", "https://www.gstatic.com/generate_204"),
        ("cloudflare", "https://cp.cloudflare.com/generate_204"),
    ];
    let mut last_err = String::new();

    for (name, target) in endpoints {
        let url = format!(
            "http://127.0.0.1:{CLASH_API_PORT}/proxies/proxy/delay?timeout=5000&url={target}"
        );
        match client.get(&url).send().await {
            Ok(resp) if resp.status().is_success() => {
                if let Ok(parsed) = resp.json::<ClashDelayResp>().await {
                    if let Some(ms) = parsed.delay {
                        return DelayResult {
                            ok: true,
                            ms: Some(ms),
                            endpoint: name.to_string(),
                            error: None,
                        };
                    }
                    last_err = parsed.message.unwrap_or_else(|| "Empty delay".to_string());
                }
            }
            Ok(resp) => {
                last_err = format!("HTTP {}", resp.status().as_u16());
            }
            Err(e) => {
                last_err = e.to_string();
            }
        }
    }

    DelayResult {
        ok: false,
        ms: None,
        endpoint: String::new(),
        error: Some(if last_err.is_empty() {
            "Timeout".to_string()
        } else {
            last_err
        }),
    }
}

fn country_code_to_flag(code: &str) -> String {
    let upper = code.trim().to_uppercase();
    if upper.len() != 2 || !upper.chars().all(|c| c.is_ascii_uppercase()) {
        return "🌐".to_string();
    }
    upper
        .chars()
        .filter_map(|c| char::from_u32(0x1F1E6 + (c as u32 - 'A' as u32)))
        .collect()
}

pub async fn check_vps_status(
    id: String,
    host: String,
    port: u16,
    cached_flag: String,
    cached_region: String,
    cached_cc: String,
) -> VpsCheckResult {
    let addr = format!("{host}:{port}");
    let start = Instant::now();
    let (online, tcp_ms) =
        match tokio::time::timeout(Duration::from_secs(2), TcpStream::connect(&addr)).await {
            Ok(Ok(_)) => (true, Some(start.elapsed().as_millis() as u64)),
            _ => (false, None),
        };

    if !cached_region.is_empty() && !cached_flag.is_empty() {
        return VpsCheckResult {
            id,
            online,
            tcp_ms,
            flag: cached_flag,
            region: cached_region,
            country_code: cached_cc,
        };
    }

    let mut flag = cached_flag;
    let mut region = cached_region;
    let mut country_code = cached_cc;

    if let Ok(client) = reqwest::Client::builder()
        .timeout(Duration::from_secs(4))
        .build()
    {
        if let Ok(resp) = client.get(format!("https://ipwho.is/{host}")).send().await {
            if let Ok(v) = resp.json::<Value>().await {
                if v["success"].as_bool().unwrap_or(false) {
                    let cc = v["country_code"].as_str().unwrap_or("").to_string();
                    let country = v["country"].as_str().unwrap_or("");
                    let city = v["city"].as_str().unwrap_or("");
                    flag = country_code_to_flag(&cc);
                    country_code = cc;
                    region = if !city.is_empty() && city != country {
                        format!("{country} · {city}")
                    } else {
                        country.to_string()
                    };
                }
            }
        }
    }

    VpsCheckResult {
        id,
        online,
        tcp_ms,
        flag,
        region,
        country_code,
    }
}

pub async fn fetch_monetization_config() -> Value {
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(6))
        .build();
    if let Ok(c) = client {
        for url in [MONETIZATION_PRIMARY, MONETIZATION_FALLBACK] {
            if let Ok(resp) = c.get(url).send().await {
                if resp.status().is_success() {
                    if let Ok(v) = resp.json::<Value>().await {
                        return v;
                    }
                }
            }
        }
    }
    serde_json::json!({
        "website_url": "https://build-myvpn.pages.dev",
        "privacy_url": "https://build-myvpn.pages.dev/privacy.html",
        "connect_banner": {
            "enabled": true,
            "badge": "AD",
            "title": "Build MyVpn Official Sponsor Slot",
            "subtitle": "Contact us for sponsorship partnerships",
            "url": "https://build-myvpn.pages.dev/#sponsor"
        }
    })
}
