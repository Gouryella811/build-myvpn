use crate::models::RoutingMode;
use crate::storage::app_data_dir;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::time::Duration;

const RULES_BASE_URL: &str =
    "https://github.com/Gouryella811/build-myvpn/releases/download/rules-latest";

#[derive(Debug, Deserialize)]
struct RuleFileMeta {
    sha256: String,
    size: u64,
}

#[derive(Debug, Deserialize)]
struct RulesManifest {
    files: HashMap<String, RuleFileMeta>,
}

pub fn rules_dir() -> PathBuf {
    let dir = app_data_dir().join("rules");
    let _ = fs::create_dir_all(&dir);
    dir
}

pub fn rule_paths_for_mode(mode: &RoutingMode) -> Option<(PathBuf, PathBuf)> {
    let tag = match mode {
        RoutingMode::AutoCn => "cn",
        RoutingMode::AutoIr => "ir",
        RoutingMode::AutoRu => "ru",
        RoutingMode::Global | RoutingMode::Direct => return None,
    };
    let dir = rules_dir();
    let gs = dir.join(format!("geosite-{tag}.srs"));
    let gi = dir.join(format!("geoip-{tag}.srs"));
    if gs.is_file() && gi.is_file() {
        Some((gs, gi))
    } else {
        None
    }
}

pub async fn update_rules_for_mode(mode: &RoutingMode) -> Result<String, String> {
    let tag = match mode {
        RoutingMode::AutoCn => "cn",
        RoutingMode::AutoIr => "ir",
        RoutingMode::AutoRu => "ru",
        RoutingMode::Global | RoutingMode::Direct => {
            return Ok("No regional rules needed for this mode".to_string())
        }
    };

    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(25))
        .build()
        .map_err(|e| e.to_string())?;

    let manifest_url = format!("{RULES_BASE_URL}/manifest.json");
    let manifest: RulesManifest = client
        .get(&manifest_url)
        .send()
        .await
        .map_err(|e| format!("Failed downloading rule manifest: {e}"))?
        .error_for_status()
        .map_err(|e| format!("Rule manifest HTTP error: {e}"))?
        .json()
        .await
        .map_err(|e| format!("Invalid rule manifest JSON: {e}"))?;

    let targets = [format!("geosite-{tag}.srs"), format!("geoip-{tag}.srs")];
    let dir = rules_dir();

    for filename in targets {
        let meta = manifest
            .files
            .get(&filename)
            .ok_or_else(|| format!("Rule {filename} missing from manifest"))?;
        let dest = dir.join(&filename);

        // Skip re-download if local file already matches SHA-256
        if let Ok(existing) = fs::read(&dest) {
            let hash = hex::encode(Sha256::digest(&existing));
            if hash.eq_ignore_ascii_case(&meta.sha256) {
                continue;
            }
        }

        let url = format!("{RULES_BASE_URL}/{filename}");
        let bytes = client
            .get(&url)
            .send()
            .await
            .map_err(|e| format!("Failed downloading {filename}: {e}"))?
            .error_for_status()
            .map_err(|e| format!("HTTP error downloading {filename}: {e}"))?
            .bytes()
            .await
            .map_err(|e| e.to_string())?;

        if (bytes.len() as u64) != meta.size {
            return Err(format!("Size mismatch for {filename}"));
        }
        let actual_hash = hex::encode(Sha256::digest(&bytes));
        if !actual_hash.eq_ignore_ascii_case(&meta.sha256) {
            return Err(format!("SHA-256 mismatch for {filename}"));
        }

        let tmp = dir.join(format!("{filename}.tmp"));
        fs::write(&tmp, &bytes).map_err(|e| e.to_string())?;
        fs::rename(&tmp, &dest).map_err(|e| e.to_string())?;
    }

    Ok(format!("Rules updated ({tag})"))
}
