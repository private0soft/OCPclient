//! App settings beside profiles.json (update URL, geo toggle).

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppSettings {
    #[serde(default)]
    pub update_manifest_url: String,
    /// When false, the baked-in (hidden) update URL is used.
    #[serde(default)]
    pub custom_update: bool,
    #[serde(default = "default_true")]
    pub lookup_public_ip: bool,
    #[serde(default)]
    pub update_last_ms: u64,
    #[serde(default)]
    pub update_snooze_code: i32,
    /// dark | light
    #[serde(default = "default_theme")]
    pub theme: String,
    /// HTTPS JSON list of profiles (name + server). Empty = unused.
    #[serde(default)]
    pub catalog_url: String,
}

fn default_true() -> bool {
    true
}

fn default_theme() -> String {
    "dark".into()
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            update_manifest_url: String::new(),
            custom_update: false,
            lookup_public_ip: true,
            update_last_ms: 0,
            update_snooze_code: 0,
            theme: default_theme(),
            catalog_url: String::new(),
        }
    }
}

impl AppSettings {
    fn path(app_data: &PathBuf) -> PathBuf {
        app_data.join("settings.json")
    }

    pub fn load(app_data: &PathBuf) -> Self {
        let path = Self::path(app_data);
        let Ok(raw) = fs::read_to_string(&path) else {
            return Self::default();
        };
        serde_json::from_str(&raw).unwrap_or_default()
    }

    pub fn save(&self, app_data: &PathBuf) -> Result<(), String> {
        fs::create_dir_all(app_data)
            .map_err(|e| format!("cannot create {}: {e}", app_data.display()))?;
        let raw = serde_json::to_string_pretty(self).map_err(|e| e.to_string())?;
        super::paths::write_atomic(&Self::path(app_data), raw)
    }
}
