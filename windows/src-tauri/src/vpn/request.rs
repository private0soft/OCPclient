//! Connection request passed from UI → Rust VPN layer.

use serde::{Deserialize, Serialize};

use super::profile::Profile;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConnectRequest {
    pub profile_id: String,
    #[serde(default)]
    pub name: String,
    pub server: String,
    #[serde(default)]
    pub username: String,
    #[serde(default)]
    pub password: String,
    #[serde(default)]
    pub group: String,
    #[serde(default = "default_protocol")]
    pub protocol: String,
    #[serde(default)]
    pub network_mode: String,
    #[serde(default = "default_true")]
    pub use_dtls: bool,
    #[serde(default = "default_true")]
    pub disable_ipv6: bool,
    #[serde(default)]
    pub sni: String,
    #[serde(default)]
    pub ca_file: String,
    #[serde(default)]
    pub accept_insecure_cert: bool,
    #[serde(skip)]
    pub app_data: std::path::PathBuf,
    #[serde(default)]
    pub geo_iso: String,
    #[serde(default)]
    pub geo_country: String,
    #[serde(default)]
    pub geo_ip4: String,
    #[serde(skip)]
    pub save_password: bool,
    #[serde(skip)]
    pub save_for_domain: bool,
    #[serde(skip)]
    pub used_shared: bool,
}

fn default_protocol() -> String {
    "anyconnect".into()
}

fn default_true() -> bool {
    true
}

impl ConnectRequest {
    pub fn from_profile(profile: &Profile, password_override: Option<String>) -> Self {
        let resolved = super::cred::resolve(
            &profile.id,
            &profile.server,
            &profile.username,
            profile.use_shared_login,
        );
        let override_pw = password_override.filter(|s| !s.is_empty());
        let used_shared = override_pw.is_none()
            && profile.use_shared_login
            && !super::cred::has(&profile.id)
            && !resolved.password.is_empty();
        let password = override_pw.unwrap_or(resolved.password);
        let username = if profile.username.trim().is_empty() {
            resolved.username
        } else {
            profile.username.clone()
        };
        Self {
            profile_id: profile.id.clone(),
            name: profile.name.clone(),
            server: profile.server.clone(),
            username,
            password,
            group: profile.group.clone(),
            protocol: if profile.protocol.trim().is_empty() {
                default_protocol()
            } else {
                profile.protocol.clone()
            },
            network_mode: profile.network_mode.clone(),
            use_dtls: profile.use_dtls,
            disable_ipv6: profile.disable_ipv6,
            sni: profile.sni.clone(),
            ca_file: profile.ca_file.clone(),
            accept_insecure_cert: profile.accept_insecure_cert,
            app_data: std::path::PathBuf::new(),
            geo_iso: profile.geo_iso.clone(),
            geo_country: profile.geo_country.clone(),
            geo_ip4: profile.geo_ip4.clone(),
            save_password: false,
            save_for_domain: false,
            used_shared,
        }
    }
}