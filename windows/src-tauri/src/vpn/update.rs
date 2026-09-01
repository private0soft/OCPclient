//! HTTPS version manifest + silent installer download.

use super::proxy;
use super::settings::AppSettings;
use serde::{Deserialize, Serialize};
use std::fs::File;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

include!(concat!(env!("OUT_DIR"), "/app_version.rs"));

const MAX_MANIFEST: usize = 16 * 1024;
const MAX_INSTALLER: u64 = 250 * 1024 * 1024;
static DOWNLOAD_BUSY: AtomicBool = AtomicBool::new(false);

#[derive(Debug, Clone, Serialize)]
pub struct UpdateInfo {
    pub available: bool,
    /// True when the manifest was fetched and parsed. False = network/config miss (retry later).
    pub checked: bool,
    pub version_code: i32,
    pub version_name: String,
    pub notes: String,
    pub page_url: String,
    pub installed_name: String,
    pub installed_code: i32,
    pub message: String,
}

impl UpdateInfo {
    fn none(message: impl Into<String>, checked: bool) -> Self {
        Self {
            available: false,
            checked,
            version_code: 0,
            version_name: String::new(),
            notes: String::new(),
            page_url: String::new(),
            installed_name: VERSION_NAME.into(),
            installed_code: VERSION_CODE,
            message: message.into(),
        }
    }
}

#[derive(Deserialize)]
struct Manifest {
    #[serde(rename = "versionCode", alias = "version_code")]
    version_code: i32,
    #[serde(rename = "versionName", alias = "version_name", default)]
    version_name: String,
    #[serde(default)]
    notes: String,
    #[serde(default, alias = "download")]
    url: String,
}

pub fn check(app_data: &PathBuf, manual: bool, via_proxy: bool) -> Result<UpdateInfo, String> {
    let mut settings = AppSettings::load(app_data);
    let url = resolve_url(&settings);
    if url.is_empty() {
        return Ok(UpdateInfo::none(
            if manual {
                "Could not check for updates."
            } else {
                ""
            },
            true,
        ));
    }
    if !url.starts_with("https://") {
        return Ok(UpdateInfo::none(
            if manual {
                "Update URL must start with https://"
            } else {
                ""
            },
            true,
        ));
    }

    let body = match fetch_text(&url, via_proxy, Duration::from_secs(8)) {
        Ok(b) => b,
        Err(_) => {
            return Ok(UpdateInfo::none(
                if manual {
                    "Could not check for updates."
                } else {
                    ""
                },
                false,
            ));
        }
    };
    if body.len() > MAX_MANIFEST {
        return Ok(UpdateInfo::none(
            if manual {
                "Update file is too large."
            } else {
                ""
            },
            false,
        ));
    }
    let man: Manifest = match serde_json::from_str(&body) {
        Ok(m) => m,
        Err(_) => {
            return Ok(UpdateInfo::none(
                if manual {
                    if settings.custom_update {
                        "That file is not a valid update list."
                    } else {
                        "Could not check for updates."
                    }
                } else {
                    ""
                },
                false,
            ));
        }
    };

    settings.update_last_ms = now_ms();
    let _ = settings.save(app_data);

    if man.version_code <= VERSION_CODE {
        return Ok(UpdateInfo::none(
            if manual { "You are up to date." } else { "" },
            true,
        ));
    }

    let page = https_page(man.url.trim());
    Ok(UpdateInfo {
        available: true,
        checked: true,
        version_code: man.version_code,
        version_name: man.version_name,
        notes: clip(&man.notes, 280),
        page_url: page,
        installed_name: VERSION_NAME.into(),
        installed_code: VERSION_CODE,
        message: "Update available".into(),
    })
}

pub fn download_update(
    url: &str,
    via_proxy: bool,
    mut on_progress: impl FnMut(u64, Option<u64>),
) -> Result<PathBuf, String> {
    if !try_begin_download() {
        return Err("Already downloading".into());
    }
    let result = download_inner(url, via_proxy, &mut on_progress);
    DOWNLOAD_BUSY.store(false, Ordering::SeqCst);
    result
}

fn try_begin_download() -> bool {
    DOWNLOAD_BUSY
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_ok()
}

fn download_inner(
    url: &str,
    via_proxy: bool,
    on_progress: &mut impl FnMut(u64, Option<u64>),
) -> Result<PathBuf, String> {
    if !url.starts_with("https://") {
        return Err("Update link must start with https://".into());
    }
    let agent = http_agent(via_proxy, Duration::from_secs(20), Duration::from_secs(60))?;
    let resp = agent
        .get(url)
        .call()
        .map_err(|_| "Could not start the download.".to_string())?;
    let total = resp
        .header("Content-Length")
        .and_then(|s| s.parse::<u64>().ok())
        .filter(|n| *n > 0);
    if total.unwrap_or(0) > MAX_INSTALLER {
        return Err("Update file is too large.".into());
    }

    let dest_dir = std::env::temp_dir().join("OpenConnect-P-update");
    std::fs::create_dir_all(&dest_dir).map_err(|e| e.to_string())?;
    let dest = dest_dir.join(file_name_from_url(url));
    let tmp = dest.with_extension("tmp");
    let mut file = File::create(&tmp).map_err(|e| e.to_string())?;
    let mut reader = resp.into_reader();
    let mut buf = [0u8; 64 * 1024];
    let mut got = 0u64;
    let mut last_emit = 0u64;
    loop {
        let n = reader.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        got += n as u64;
        if got > MAX_INSTALLER {
            let _ = std::fs::remove_file(&tmp);
            return Err("Update file is too large.".into());
        }
        file.write_all(&buf[..n]).map_err(|e| e.to_string())?;
        if got.saturating_sub(last_emit) >= 256 * 1024 {
            on_progress(got, total);
            last_emit = got;
        }
    }
    file.flush().map_err(|e| e.to_string())?;
    drop(file);
    on_progress(got, total.or(Some(got)));
    let _ = std::fs::remove_file(&dest);
    std::fs::rename(&tmp, &dest).map_err(|e| {
        let _ = std::fs::remove_file(&tmp);
        e.to_string()
    })?;
    if dest
        .extension()
        .and_then(|e| e.to_str())
        .map(|e| e.eq_ignore_ascii_case("exe"))
        .unwrap_or(true)
    {
        let mut magic = [0u8; 2];
        let ok = File::open(&dest)
            .and_then(|mut f| f.read_exact(&mut magic))
            .is_ok()
            && magic == *b"MZ";
        if !ok {
            let _ = std::fs::remove_file(&dest);
            return Err("That file is not a Windows installer.".into());
        }
    }
    Ok(dest)
}

pub fn launch_update(path: &Path) -> Result<(), String> {
    std::process::Command::new(path)
        .spawn()
        .map(|_| ())
        .map_err(|e| format!("Could not open installer: {e}"))
}

pub fn snooze(app_data: &PathBuf, version_code: i32) -> Result<(), String> {
    let mut settings = AppSettings::load(app_data);
    settings.update_snooze_code = version_code;
    settings.save(app_data)
}

fn fetch_text(url: &str, via_proxy: bool, timeout: Duration) -> Result<String, String> {
    let agent = http_agent(via_proxy, timeout, timeout)?;
    let resp = agent
        .get(url)
        .call()
        .map_err(|_| "Could not check for updates.".to_string())?;
    resp.into_string()
        .map_err(|_| "Could not check for updates.".to_string())
}

fn http_agent(
    via_proxy: bool,
    connect: Duration,
    read: Duration,
) -> Result<ureq::Agent, String> {
    let mut builder = ureq::AgentBuilder::new()
        .timeout_connect(connect)
        .timeout_read(read)
        .redirects(4)
        .user_agent("OpenConnect-PlusP-Update");
    if via_proxy {
        let proxy = ureq::Proxy::new(format!("http://{}", proxy::HTTP_ADDR))
            .map_err(|e| format!("proxy: {e}"))?;
        builder = builder.proxy(proxy);
    }
    Ok(builder.build())
}

fn file_name_from_url(url: &str) -> String {
    let path = url.split('?').next().unwrap_or(url);
    let raw = path.rsplit('/').next().unwrap_or("");
    let safe: String = raw
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '.' || *c == '-' || *c == '_')
        .collect();
    let lower = safe.to_ascii_lowercase();
    if lower.ends_with(".exe") || lower.ends_with(".msi") {
        safe
    } else {
        "OpenConnect-P-setup.exe".into()
    }
}

fn resolve_url(settings: &AppSettings) -> String {
    if settings.custom_update {
        return settings.update_manifest_url.trim().to_string();
    }
    include_str!(concat!(env!("OUT_DIR"), "/update_url.txt"))
        .trim()
        .to_string()
}

fn clip(s: &str, max: usize) -> String {
    let t = s.trim();
    if t.chars().count() <= max {
        return t.to_string();
    }
    format!("{}…", t.chars().take(max.saturating_sub(1)).collect::<String>())
}

fn https_page(raw: &str) -> String {
    let t = raw.trim();
    if t.len() >= 8 && t[..8].eq_ignore_ascii_case("https://") {
        t.to_string()
    } else {
        String::new()
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
