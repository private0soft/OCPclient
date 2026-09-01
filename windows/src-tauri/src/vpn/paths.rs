//! Machine-wide data directory for an elevated Windows VPN client.
//!
//! The exe uses `requireAdministrator`. On domain PCs the process often runs
//! as the local Administrator, whose AppData profile may be missing, redirected
//! to a network share, or not writable. Tauri's default
//! `%APPDATA%\com.openconnectplusp.client` then fails, and WebView2 crashes
//! because its cache also lives under that broken profile.
//!
//! Layout (preferred):
//! ```text
//! %ProgramData%\OpenConnect-P\
//!   profiles.json
//!   settings.json
//!   secrets\          DPAPI-encrypted passwords
//!   flags\
//!   scripts\          extracted vpnc-script*.js
//!   webview\          WebView2 user data (WEBVIEW2_USER_DATA_FOLDER)
//! ```

use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

const DIR_NAME: &str = "OpenConnect-P";
const LEGACY_ID: &str = "com.openconnectplusp.client";

static DATA_DIR: OnceLock<PathBuf> = OnceLock::new();

pub fn data_dir() -> Result<PathBuf, String> {
    if let Some(p) = DATA_DIR.get() {
        return Ok(p.clone());
    }
    let resolved = resolve()?;
    Ok(DATA_DIR.get_or_init(|| resolved).clone())
}

/// Create subfolders and return the root. Safe to call more than once.
pub fn prepare() -> Result<PathBuf, String> {
    let dir = data_dir()?;
    for sub in ["secrets", "secrets/realm", "flags", "scripts", "webview"] {
        let p = dir.join(sub);
        fs::create_dir_all(&p).map_err(|e| format!("cannot create {}: {e}", p.display()))?;
    }
    Ok(dir)
}

pub fn secrets_dir() -> Result<PathBuf, String> {
    let d = data_dir()?.join("secrets");
    fs::create_dir_all(&d).map_err(|e| format!("cannot create {}: {e}", d.display()))?;
    Ok(d)
}

pub fn realm_dir() -> Result<PathBuf, String> {
    let d = secrets_dir()?.join("realm");
    fs::create_dir_all(&d).map_err(|e| format!("cannot create {}: {e}", d.display()))?;
    Ok(d)
}

pub fn webview_dir() -> Result<PathBuf, String> {
    let d = data_dir()?.join("webview");
    fs::create_dir_all(&d).map_err(|e| format!("cannot create {}: {e}", d.display()))?;
    Ok(d)
}

pub fn write_atomic(path: &Path, bytes: impl AsRef<[u8]>) -> Result<(), String> {
    let parent = path.parent().ok_or_else(|| format!("invalid path: {}", path.display()))?;
    fs::create_dir_all(parent).map_err(|e| format!("{}: {e}", parent.display()))?;
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, bytes.as_ref()).map_err(|e| format!("write {}: {e}", tmp.display()))?;
    if fs::rename(&tmp, path).is_ok() {
        return Ok(());
    }
    // Windows can refuse rename-over-existing; replace then retry.
    let _ = fs::remove_file(path);
    fs::rename(&tmp, path).map_err(|e| {
        let _ = fs::remove_file(&tmp);
        format!("replace {}: {e}", path.display())
    })
}

fn resolve() -> Result<PathBuf, String> {
    let mut tried = Vec::new();
    for candidate in candidates() {
        match probe_writable(&candidate) {
            Ok(dir) => {
                migrate_legacy(&dir);
                return Ok(dir);
            }
            Err(e) => tried.push(format!("{} ({e})", candidate.display())),
        }
    }
    Err(format!(
        "cannot create a writable data folder. tried: {}",
        tried.join("; ")
    ))
}

fn candidates() -> Vec<PathBuf> {
    let mut v = Vec::new();
    if let Some(p) = env::var_os("ProgramData") {
        v.push(PathBuf::from(p).join(DIR_NAME));
    } else {
        v.push(PathBuf::from(r"C:\ProgramData").join(DIR_NAME));
    }
    if let Some(p) = env::var_os("LOCALAPPDATA") {
        v.push(PathBuf::from(p).join(DIR_NAME));
    }
    if let Some(p) = env::var_os("TEMP") {
        v.push(PathBuf::from(p).join(DIR_NAME));
    }
    v
}

fn probe_writable(dir: &Path) -> Result<PathBuf, String> {
    fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    let probe = dir.join(".write-ok");
    fs::write(&probe, b"ok").map_err(|e| e.to_string())?;
    let _ = fs::remove_file(&probe);
    Ok(dir.to_path_buf())
}

fn migrate_legacy(dest: &Path) {
    if dest.join("profiles.json").is_file() {
        return;
    }
    for src in legacy_dirs() {
        if !src.join("profiles.json").is_file() {
            continue;
        }
        let _ = copy_if_present(&src.join("profiles.json"), &dest.join("profiles.json"));
        let _ = copy_if_present(&src.join("settings.json"), &dest.join("settings.json"));
        let flags_src = src.join("flags");
        let flags_dst = dest.join("flags");
        if flags_src.is_dir() {
            let _ = fs::create_dir_all(&flags_dst);
            if let Ok(entries) = fs::read_dir(&flags_src) {
                for e in entries.flatten() {
                    let p = e.path();
                    if p.is_file() {
                        if let Some(name) = p.file_name() {
                            let _ = fs::copy(&p, flags_dst.join(name));
                        }
                    }
                }
            }
        }
        break;
    }
}

fn copy_if_present(from: &Path, to: &Path) -> std::io::Result<()> {
    if from.is_file() {
        fs::copy(from, to).map(|_| ())
    } else {
        Ok(())
    }
}

fn legacy_dirs() -> Vec<PathBuf> {
    let mut v = Vec::new();
    for key in ["APPDATA", "LOCALAPPDATA"] {
        if let Some(p) = env::var_os(key) {
            v.push(PathBuf::from(p).join(LEGACY_ID));
        }
    }
    v
}
