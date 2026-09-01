//! Public IP + country (same pipeline as Android GeoLookup) and flag PNGs.
//!
//! In proxy mode there is no system default route through the VPN, so geo
//! HTTP must go through our local HTTP CONNECT proxy (127.0.0.1:8118).
//!
//! In tunnel mode we force IPv4-only HTTPS (see `ipv4_http`) so broken AAAA
//! paths cannot stall the lookup for tens of seconds.
//!
//! Geo never runs unless Connected, and aborts if geo_epoch changes (switch /
//! disconnect) so we do not probe the public internet on the real IP.

use super::ipv4_http;
use super::profile::ProfileStore;
use super::proxy;
use super::settings::AppSettings;
use super::state::{ConnectionState, VpnRuntime};
use base64::Engine;
use serde::Deserialize;
use std::fs;
use std::io::Read;
use std::path::PathBuf;
use std::sync::Arc;
use std::thread;
use std::time::Duration;

const UA: &str = "OpenConnect-PlusP-Windows";

#[derive(Debug, Clone, Default)]
pub struct GeoResult {
    pub iso: String,
    pub country: String,
    pub ip4: String,
}

pub fn spawn_after_connect(
    runtime: Arc<VpnRuntime>,
    profile_id: String,
    app_data: PathBuf,
    via_proxy: bool,
) {
    let epoch = runtime.geo_epoch();
    thread::spawn(move || {
        // Tunnel: wait for vpnc-script DNS/routes. Proxy: wait for listener + ifindex.
        let warm = if via_proxy { 900 } else { 2000 };
        thread::sleep(Duration::from_millis(warm));
        if !geo_guard_ok(&runtime, epoch, &profile_id) {
            return;
        }
        let settings = AppSettings::load(&app_data);
        if !settings.lookup_public_ip {
            return;
        }
        if via_proxy {
            runtime.push_log(format!(
                "looking up public IP / country via {}…",
                proxy::HTTP_ADDR
            ));
        } else {
            runtime.push_log("looking up public IP / country (IPv4)…");
        }
        match fetch(via_proxy, &runtime, epoch, &profile_id) {
            Ok(geo) => {
                if !geo_guard_ok(&runtime, epoch, &profile_id) {
                    runtime.push_log("geo result discarded (switched/disconnected)");
                    return;
                }
                runtime.with_status(|s| {
                    if s.state != ConnectionState::Connected {
                        return;
                    }
                    if s.active_profile_id.as_deref() != Some(profile_id.as_str()) {
                        return;
                    }
                    s.public_iso = geo.iso.clone();
                    s.public_country = geo.country.clone();
                    s.public_ip4 = geo.ip4.clone();
                    if !geo.country.is_empty() {
                        s.message = geo.country.clone();
                    }
                });
                if let Ok(store) = ProfileStore::open(app_data.clone()) {
                    let _ = store.set_geo(&profile_id, &geo.iso, &geo.country, &geo.ip4);
                }
                if geo.iso.len() == 2 {
                    if let Err(e) = ensure_flag(&app_data, &geo.iso, via_proxy) {
                        runtime.push_log(format!("flag download failed: {e}"));
                    }
                }
                runtime.push_log(format!(
                    "exit {} · {} ({})",
                    geo.ip4, geo.country, geo.iso
                ));
            }
            Err(e) => {
                if geo_guard_ok(&runtime, epoch, &profile_id) {
                    runtime.push_log(format!("geo lookup failed: {e}"));
                }
            }
        }
    });
}

fn geo_guard_ok(runtime: &VpnRuntime, epoch: u64, profile_id: &str) -> bool {
    if runtime.geo_epoch() != epoch {
        return false;
    }
    let Ok(s) = runtime.status.lock() else {
        return false;
    };
    s.state == ConnectionState::Connected
        && s.active_profile_id.as_deref() == Some(profile_id)
}

pub fn fetch(
    via_proxy: bool,
    runtime: &VpnRuntime,
    epoch: u64,
    profile_id: &str,
) -> Result<GeoResult, String> {
    let mut last = "geo lookup failed".to_string();
    for attempt in 0..6 {
        if !geo_guard_ok(runtime, epoch, profile_id) {
            return Err("geo aborted".into());
        }
        if attempt > 0 {
            thread::sleep(Duration::from_millis(1000));
            if !geo_guard_ok(runtime, epoch, profile_id) {
                return Err("geo aborted".into());
            }
        }
        match fetch_once(via_proxy) {
            Ok(geo) => return Ok(geo),
            Err(e) => last = e,
        }
    }
    Err(last)
}

fn fetch_once(via_proxy: bool) -> Result<GeoResult, String> {
    let ip4 = fetch_ipv4(via_proxy)?;
    let mut geo = country_for(&ip4, via_proxy).unwrap_or_default();
    geo.ip4 = ip4;
    Ok(geo)
}

fn agent(via_proxy: bool) -> Result<ureq::Agent, String> {
    let mut builder = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(6))
        .timeout_read(Duration::from_secs(8))
        .user_agent(UA);
    if via_proxy {
        let proxy = ureq::Proxy::new(format!("http://{}", proxy::HTTP_ADDR))
            .map_err(|e| format!("proxy config: {e}"))?;
        builder = builder.proxy(proxy);
    }
    Ok(builder.build())
}

fn http_get_text(url: &str, via_proxy: bool) -> Result<String, String> {
    if via_proxy {
        let body = agent(true)?
            .get(url)
            .call()
            .map_err(|e| e.to_string())?
            .into_string()
            .map_err(|e| e.to_string())?;
        Ok(body)
    } else {
        let bytes = ipv4_http::https_get(url, Duration::from_secs(8))?;
        String::from_utf8(bytes).map_err(|e| e.to_string())
    }
}

fn http_get_bytes(url: &str, via_proxy: bool) -> Result<Vec<u8>, String> {
    if via_proxy {
        let mut bytes = Vec::new();
        agent(true)?
            .get(url)
            .call()
            .map_err(|e| e.to_string())?
            .into_reader()
            .read_to_end(&mut bytes)
            .map_err(|e| e.to_string())?;
        Ok(bytes)
    } else {
        ipv4_http::https_get(url, Duration::from_secs(10))
    }
}

fn fetch_ipv4(via_proxy: bool) -> Result<String, String> {
    if let Ok(ip) = fetch_ipify("https://api.ipify.org?format=json", via_proxy) {
        if is_ipv4(&ip) {
            return Ok(ip);
        }
    }
    let body = http_get_text("https://ipv4.icanhazip.com/", via_proxy)?;
    let ip = body.trim().to_string();
    if is_ipv4(&ip) {
        Ok(ip)
    } else {
        Err("no public IPv4".into())
    }
}

fn fetch_ipify(url: &str, via_proxy: bool) -> Result<String, String> {
    #[derive(Deserialize)]
    struct Ipify {
        ip: String,
    }
    let body = http_get_text(url, via_proxy)?;
    let parsed: Ipify = serde_json::from_str(&body).map_err(|e| e.to_string())?;
    Ok(parsed.ip.trim().to_string())
}

fn country_for(ip4: &str, via_proxy: bool) -> Result<GeoResult, String> {
    #[derive(Deserialize)]
    struct Who {
        success: Option<bool>,
        country: Option<String>,
        country_code: Option<String>,
    }
    let url = format!("https://ipwho.is/{ip4}");
    let body = http_get_text(&url, via_proxy)?;
    let parsed: Who = serde_json::from_str(&body).map_err(|e| e.to_string())?;
    if parsed.success == Some(false) {
        return Err("ipwho.is failed".into());
    }
    let iso = parsed
        .country_code
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase();
    Ok(GeoResult {
        iso,
        country: parsed.country.unwrap_or_default().trim().to_string(),
        ip4: ip4.into(),
    })
}

fn is_ipv4(s: &str) -> bool {
    s.parse::<std::net::Ipv4Addr>().is_ok()
}

fn flag_path(app_data: &PathBuf, iso: &str) -> PathBuf {
    app_data.join("flags").join(format!("{iso}.png"))
}

pub fn normalize_iso(iso: &str) -> String {
    iso.trim().to_ascii_lowercase()
}

pub fn ensure_flag(app_data: &PathBuf, iso: &str, via_proxy: bool) -> Result<PathBuf, String> {
    let iso = normalize_iso(iso);
    if iso.len() != 2 || !iso.chars().all(|c| c.is_ascii_alphabetic()) {
        return Err("bad iso".into());
    }
    let out = flag_path(app_data, &iso);
    if out.is_file() && out.metadata().map(|m| m.len() > 0).unwrap_or(false) {
        return Ok(out);
    }
    if let Some(dir) = out.parent() {
        fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }
    let url = format!("https://flagcdn.com/w320/{iso}.png");
    let bytes = http_get_bytes(&url, via_proxy)?;
    if bytes.len() < 32 {
        return Err("flag too small".into());
    }
    let tmp = out.with_extension("png.tmp");
    fs::write(&tmp, &bytes).map_err(|e| e.to_string())?;
    fs::rename(&tmp, &out).map_err(|e| e.to_string())?;
    Ok(out)
}

pub fn flag_data_url(app_data: &PathBuf, iso: &str) -> Result<String, String> {
    // UI flag load: prefer cache; if missing, try direct (IPv4) then proxy.
    let path = ensure_flag(app_data, iso, false)
        .or_else(|_| ensure_flag(app_data, iso, true))?;
    let bytes = fs::read(path).map_err(|e| e.to_string())?;
    let b64 = base64::engine::general_purpose::STANDARD.encode(bytes);
    Ok(format!("data:image/png;base64,{b64}"))
}
