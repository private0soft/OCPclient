//! Passwords are DPAPI-encrypted files under ProgramData, never in profiles.json.
//!
//! Windows Credential Manager (`CRED_PERSIST_LOCAL_MACHINE`) is still *read*
//! so older installs migrate, but it is no longer used for writes — it fails
//! on many domain-joined PCs (GPO, over-the-shoulder elevation, incomplete
//! Administrator profile).

use super::paths;
use serde::{Deserialize, Serialize};
use std::ffi::OsStr;
use std::fs;
use std::os::windows::ffi::OsStrExt;
use std::path::PathBuf;
use std::ptr;

const ENTROPY: &[u8] = b"OpenConnect-P.dpapi.v1";
const CRYPTPROTECT_UI_FORBIDDEN: u32 = 0x1;
const CRYPTPROTECT_LOCAL_MACHINE: u32 = 0x4;

#[allow(dead_code)]
pub fn target(profile_id: &str) -> String {
    secret_path(profile_id)
        .map(|p| p.display().to_string())
        .unwrap_or_default()
}

pub fn has(profile_id: &str) -> bool {
    read(profile_id).map(|s| !s.is_empty()).unwrap_or(false)
}

pub fn read(profile_id: &str) -> Result<String, String> {
    if let Ok(text) = read_file(profile_id) {
        if !text.is_empty() {
            return Ok(text);
        }
    }
    let text = read_credman(profile_id)?;
    let _ = write_file(profile_id, &text);
    Ok(text)
}

pub fn write(profile_id: &str, _username: &str, password: &str) -> Result<(), String> {
    if password.is_empty() {
        delete(profile_id);
        return Ok(());
    }
    write_file(profile_id, password)?;
    delete_credman(profile_id);
    Ok(())
}

pub fn delete(profile_id: &str) {
    if let Ok(path) = secret_path(profile_id) {
        let _ = fs::remove_file(path);
    }
    delete_credman(profile_id);
}

#[derive(Debug, Clone, Default)]
pub struct ResolvedSecret {
    pub username: String,
    pub password: String,
}

/// Profile file first. Shared domain login only if the profile opted in.
pub fn resolve(
    profile_id: &str,
    server: &str,
    profile_username: &str,
    use_shared: bool,
) -> ResolvedSecret {
    let mut username = profile_username.trim().to_string();
    if let Ok(password) = read(profile_id) {
        if !password.is_empty() {
            return ResolvedSecret { username, password };
        }
    }
    if use_shared {
        if let Some(domain) = login_domain(server) {
            if let Ok(realm) = read_realm(&domain) {
                if username.is_empty() {
                    username = realm.username;
                }
                return ResolvedSecret {
                    username,
                    password: realm.password,
                };
            }
        }
    }
    ResolvedSecret {
        username,
        password: String::new(),
    }
}

/// `server1.example.com` / `server2.example.com` → `example.com`. IPs: none.
pub fn login_domain(server: &str) -> Option<String> {
    let host = host_of(server)?;
    if host.parse::<std::net::Ipv4Addr>().is_ok() {
        return None;
    }
    if host.contains(':') || host.starts_with('[') {
        return None;
    }
    let host = host.trim_end_matches('.').to_ascii_lowercase();
    let labels: Vec<&str> = host.split('.').filter(|s| !s.is_empty()).collect();
    if labels.len() < 2 {
        return None;
    }
    Some(format!(
        "{}.{}",
        labels[labels.len() - 2],
        labels[labels.len() - 1]
    ))
}

#[derive(Debug, Clone, Serialize)]
pub struct DomainLogin {
    pub domain: String,
    pub username: String,
}

pub fn has_realm(domain: &str) -> bool {
    read_realm(domain).map(|r| !r.password.is_empty()).unwrap_or(false)
}

pub fn realm_username(domain: &str) -> String {
    read_realm(domain).map(|r| r.username).unwrap_or_default()
}

pub fn write_realm(domain: &str, username: &str, password: &str) -> Result<(), String> {
    if password.is_empty() {
        delete_realm(domain);
        return Ok(());
    }
    let path = realm_path(domain)?;
    let payload = RealmSecret {
        username: username.to_string(),
        password: password.to_string(),
    };
    let raw = serde_json::to_vec(&payload).map_err(|e| e.to_string())?;
    let blob = protect(&raw)?;
    paths::write_atomic(&path, blob)
}

pub fn delete_realm(domain: &str) {
    if let Ok(path) = realm_path(domain) {
        let _ = fs::remove_file(path);
    }
}

pub fn list_realms() -> Result<Vec<DomainLogin>, String> {
    let dir = paths::realm_dir()?;
    let mut out = Vec::new();
    let Ok(entries) = fs::read_dir(&dir) else {
        return Ok(out);
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("bin") {
            continue;
        }
        let Some(stem) = path.file_stem().and_then(|s| s.to_str()) else {
            continue;
        };
        let Ok(raw) = fs::read(&path) else {
            continue;
        };
        let Ok(plain) = unprotect(&raw) else {
            continue;
        };
        let Ok(secret) = serde_json::from_slice::<RealmSecret>(&plain) else {
            continue;
        };
        out.push(DomainLogin {
            domain: stem.to_string(),
            username: secret.username,
        });
    }
    out.sort_by(|a, b| a.domain.cmp(&b.domain));
    Ok(out)
}

#[derive(Serialize, Deserialize)]
struct RealmSecret {
    username: String,
    password: String,
}

fn read_realm(domain: &str) -> Result<RealmSecret, String> {
    let path = realm_path(domain)?;
    let blob = fs::read(&path).map_err(|_| "no saved password".to_string())?;
    let plain = unprotect(&blob)?;
    serde_json::from_slice(&plain).map_err(|e| e.to_string())
}

fn realm_path(domain: &str) -> Result<PathBuf, String> {
    let safe: String = domain
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '.' || *c == '-')
        .collect();
    if safe.is_empty() || !safe.contains('.') {
        return Err("invalid domain".into());
    }
    Ok(paths::realm_dir()?.join(format!("{safe}.bin")))
}

fn host_of(server: &str) -> Option<String> {
    let mut s = server.trim();
    if let Some(rest) = s.strip_prefix("https://") {
        s = rest;
    } else if let Some(rest) = s.strip_prefix("http://") {
        s = rest;
    }
    s = s.split('/').next().unwrap_or(s);
    s = s.split('@').next_back().unwrap_or(s);
    if let Some(stripped) = s.strip_prefix('[') {
        return stripped.split(']').next().map(|h| h.to_string());
    }
    let host = s.rsplit_once(':').map(|(h, port)| {
        if port.chars().all(|c| c.is_ascii_digit()) {
            h
        } else {
            s
        }
    }).unwrap_or(s);
    let host = host.trim();
    if host.is_empty() {
        None
    } else {
        Some(host.to_string())
    }
}

fn secret_path(profile_id: &str) -> Result<PathBuf, String> {
    let safe: String = profile_id
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_')
        .collect();
    if safe.is_empty() {
        return Err("empty profile id".into());
    }
    Ok(paths::secrets_dir()?.join(format!("{safe}.bin")))
}

fn write_file(profile_id: &str, password: &str) -> Result<(), String> {
    let path = secret_path(profile_id)?;
    let blob = protect(password.as_bytes())?;
    paths::write_atomic(&path, blob)
}

fn read_file(profile_id: &str) -> Result<String, String> {
    let path = secret_path(profile_id)?;
    let blob = fs::read(&path).map_err(|_| "no saved password".to_string())?;
    let plain = unprotect(&blob)?;
    String::from_utf8(plain).map_err(|e| e.to_string())
}

fn protect(plain: &[u8]) -> Result<Vec<u8>, String> {
    match protect_with(plain, CRYPTPROTECT_UI_FORBIDDEN | CRYPTPROTECT_LOCAL_MACHINE) {
        Ok(v) => Ok(v),
        Err(_) => protect_with(plain, CRYPTPROTECT_UI_FORBIDDEN),
    }
}

fn protect_with(plain: &[u8], flags: u32) -> Result<Vec<u8>, String> {
    let mut input = DataBlob {
        cb_data: plain.len() as u32,
        pb_data: plain.as_ptr() as *mut u8,
    };
    let mut entropy = entropy_blob();
    let mut output = DataBlob {
        cb_data: 0,
        pb_data: ptr::null_mut(),
    };
    let descr = wide("OpenConnect-P");
    let ok = unsafe {
        CryptProtectData(
            &mut input,
            descr.as_ptr(),
            &mut entropy,
            ptr::null_mut(),
            ptr::null_mut(),
            flags,
            &mut output,
        )
    };
    if ok == 0 {
        return Err(format!("DPAPI protect failed ({})", last_error()));
    }
    take_blob(output)
}

fn unprotect(blob: &[u8]) -> Result<Vec<u8>, String> {
    let mut input = DataBlob {
        cb_data: blob.len() as u32,
        pb_data: blob.as_ptr() as *mut u8,
    };
    let mut entropy = entropy_blob();
    let mut output = DataBlob {
        cb_data: 0,
        pb_data: ptr::null_mut(),
    };
    let ok = unsafe {
        CryptUnprotectData(
            &mut input,
            ptr::null_mut(),
            &mut entropy,
            ptr::null_mut(),
            ptr::null_mut(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
    };
    if ok == 0 {
        return Err(format!("DPAPI unprotect failed ({})", last_error()));
    }
    take_blob(output)
}

fn entropy_blob() -> DataBlob {
    DataBlob {
        cb_data: ENTROPY.len() as u32,
        pb_data: ENTROPY.as_ptr() as *mut u8,
    }
}

fn take_blob(blob: DataBlob) -> Result<Vec<u8>, String> {
    if blob.pb_data.is_null() {
        return Err("DPAPI returned empty blob".into());
    }
    let bytes = unsafe { std::slice::from_raw_parts(blob.pb_data, blob.cb_data as usize) }.to_vec();
    unsafe {
        LocalFree(blob.pb_data as *mut std::ffi::c_void);
    }
    Ok(bytes)
}

fn last_error() -> u32 {
    unsafe { GetLastError() }
}

fn wide(s: &str) -> Vec<u16> {
    OsStr::new(s).encode_wide().chain(std::iter::once(0)).collect()
}

// --- legacy Credential Manager (read / delete only) ---

fn cred_target(profile_id: &str) -> String {
    format!("OpenConnect-P/{profile_id}")
}

fn read_credman(profile_id: &str) -> Result<String, String> {
    let target = wide(&cred_target(profile_id));
    let mut cred: *mut CredentialW = ptr::null_mut();
    let ok = unsafe { CredReadW(target.as_ptr(), CRED_TYPE_GENERIC, 0, &mut cred) };
    if ok == 0 || cred.is_null() {
        return Err("no saved password".into());
    }
    let result = unsafe {
        let blob = std::slice::from_raw_parts((*cred).blob, (*cred).blob_size as usize);
        let text = String::from_utf8_lossy(blob).into_owned();
        CredFree(cred as *mut std::ffi::c_void);
        text
    };
    Ok(result)
}

fn delete_credman(profile_id: &str) {
    let target = wide(&cred_target(profile_id));
    unsafe {
        let _ = CredDeleteW(target.as_ptr(), CRED_TYPE_GENERIC, 0);
    }
}

const CRED_TYPE_GENERIC: u32 = 1;

#[repr(C)]
struct DataBlob {
    cb_data: u32,
    pb_data: *mut u8,
}

#[repr(C)]
struct CredentialW {
    flags: u32,
    ty: u32,
    target_name: *mut u16,
    comment: *mut u16,
    last_written: [u32; 2],
    blob_size: u32,
    _pad: u32,
    blob: *const u8,
    persist: u32,
    attr_count: u32,
    attrs: *const std::ffi::c_void,
    target_alias: *mut u16,
    user_name: *mut u16,
}

#[link(name = "crypt32")]
extern "system" {
    fn CryptProtectData(
        data_in: *mut DataBlob,
        descr: *const u16,
        optional_entropy: *mut DataBlob,
        reserved: *mut std::ffi::c_void,
        prompt: *mut std::ffi::c_void,
        flags: u32,
        data_out: *mut DataBlob,
    ) -> i32;
    fn CryptUnprotectData(
        data_in: *mut DataBlob,
        descr: *mut *mut u16,
        optional_entropy: *mut DataBlob,
        reserved: *mut std::ffi::c_void,
        prompt: *mut std::ffi::c_void,
        flags: u32,
        data_out: *mut DataBlob,
    ) -> i32;
}

#[link(name = "kernel32")]
extern "system" {
    fn GetLastError() -> u32;
    fn LocalFree(mem: *mut std::ffi::c_void) -> *mut std::ffi::c_void;
}

#[link(name = "advapi32")]
extern "system" {
    fn CredReadW(
        target: *const u16,
        ty: u32,
        flags: u32,
        credential: *mut *mut CredentialW,
    ) -> i32;
    fn CredDeleteW(target: *const u16, ty: u32, flags: u32) -> i32;
    fn CredFree(buffer: *mut std::ffi::c_void);
}
