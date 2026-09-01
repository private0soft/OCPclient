//! Profile backup — same `myoc-profiles` format as Android ProfileBackup.
//! Export is name + server only. Credentials are never written.

use super::profile::{normalize_server, unique_name, Profile, ProfileStore};
use super::proxy;
use serde_json::{json, Map, Value};
use std::collections::HashSet;
use std::time::Duration;
use uuid::Uuid;

const FORMAT: &str = "myoc-profiles";
const FORMAT_VERSION: u32 = 3;
const CATALOG_MAX_BYTES: usize = 512 * 1024;

pub fn export_json(store: &ProfileStore) -> Result<String, String> {
    let profiles = store.list()?;
    let mut items = Vec::new();
    for p in profiles {
        if p.is_catalog() {
            continue;
        }
        items.push(export_one(&p));
    }
    let root = json!({
        "format": FORMAT,
        "version": FORMAT_VERSION,
        "profiles": items,
    });
    serde_json::to_string_pretty(&root).map_err(|e| e.to_string())
}

fn export_one(p: &Profile) -> Value {
    let mut prefs = Map::new();
    put_str(&mut prefs, "profile_name", &p.name);
    put_str(&mut prefs, "server_address", &p.server);
    json!({ "prefs": prefs })
}

fn put_str(prefs: &mut Map<String, Value>, key: &str, value: &str) {
    if value.trim().is_empty() {
        return;
    }
    prefs.insert(key.into(), wrap("s", json!(value)));
}

fn wrap(t: &str, v: Value) -> Value {
    json!({ "t": t, "v": v })
}

#[derive(Debug, serde::Serialize)]
pub struct ImportResult {
    pub imported: u32,
    pub skipped: u32,
}

pub fn import_json(store: &ProfileStore, raw: &str) -> Result<ImportResult, String> {
    let text = raw.trim_start_matches('\u{feff}').trim();
    let root: Value = serde_json::from_str(text).map_err(|e| e.to_string())?;
    let format = root
        .get("format")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    if format != FORMAT {
        return Err("not a MyOC profile backup (format must be myoc-profiles)".into());
    }
    let list = root
        .get("profiles")
        .and_then(|v| v.as_array())
        .ok_or("missing profiles array")?;

    let existing = store.list()?;
    let mut seen: HashSet<String> = existing
        .iter()
        .map(|p| normalize_server(&p.server))
        .filter(|s| !s.is_empty())
        .collect();
    let mut names: HashSet<String> = existing.iter().map(|p| p.name.clone()).collect();

    let mut imported = 0u32;
    let mut skipped = 0u32;
    for item in list {
        match import_one(store, item, &mut seen, &mut names) {
            Ok(true) => imported += 1,
            Ok(false) => skipped += 1,
            Err(_) => skipped += 1,
        }
    }
    Ok(ImportResult { imported, skipped })
}

#[derive(Debug, serde::Serialize)]
pub struct CatalogSyncResult {
    pub added: u32,
    pub updated: u32,
    pub removed: u32,
    pub skipped: u32,
    pub message: String,
}

pub fn sync_from_url(
    store: &ProfileStore,
    url: &str,
    via_proxy: bool,
) -> Result<CatalogSyncResult, String> {
    let raw = fetch_catalog(url, via_proxy)?;
    sync_from_json(store, &raw)
}

pub fn sync_from_json(store: &ProfileStore, raw: &str) -> Result<CatalogSyncResult, String> {
    let incoming = parse_catalog_entries(raw)?;
    let stats = store.apply_catalog(incoming)?;
    Ok(CatalogSyncResult {
        message: catalog_message(&stats),
        added: stats.added,
        updated: stats.updated,
        removed: stats.removed,
        skipped: stats.skipped,
    })
}

fn catalog_message(stats: &super::profile::CatalogApply) -> String {
    if stats.added == 0 && stats.updated == 0 && stats.removed == 0 {
        return String::new();
    }
    let mut parts = Vec::new();
    if stats.added > 0 {
        parts.push(format!("{} added", stats.added));
    }
    if stats.updated > 0 {
        parts.push(format!("{} updated", stats.updated));
    }
    if stats.removed > 0 {
        parts.push(format!("{} removed", stats.removed));
    }
    parts.join(", ")
}

fn fetch_catalog(url: &str, via_proxy: bool) -> Result<String, String> {
    let url = rewrite_github_blob(url.trim());
    if !url.starts_with("https://") {
        return Err("List URL must start with https://".into());
    }
    match fetch_once(&url, via_proxy) {
        Ok(body) => Ok(body),
        Err(first) => {
            let busted = cache_bust(&url);
            fetch_once(&busted, via_proxy).map_err(|_| first)
        }
    }
}

/// github.com/user/repo/blob/branch/file.json → raw.githubusercontent.com/...
fn rewrite_github_blob(url: &str) -> String {
    let Some(rest) = url.strip_prefix("https://github.com/") else {
        return url.to_string();
    };
    let Some((repo, blob)) = rest.split_once("/blob/") else {
        return url.to_string();
    };
    let Some((branch, path)) = blob.split_once('/') else {
        return url.to_string();
    };
    if path.is_empty() {
        return url.to_string();
    }
    format!("https://raw.githubusercontent.com/{repo}/{branch}/{path}")
}

fn cache_bust(url: &str) -> String {
    let bust = now_ms();
    if url.contains('?') {
        format!("{url}&t={bust}")
    } else {
        format!("{url}?t={bust}")
    }
}

fn fetch_once(url: &str, via_proxy: bool) -> Result<String, String> {
    let mut builder = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(12))
        .timeout_read(Duration::from_secs(15))
        .redirects(4)
        .user_agent("OpenConnect-PlusP-Catalog");
    if via_proxy {
        let proxy = ureq::Proxy::new(format!("http://{}", proxy::HTTP_ADDR))
            .map_err(|e| format!("proxy: {e}"))?;
        builder = builder.proxy(proxy);
    }
    let resp = match builder
        .build()
        .get(url)
        .set("Cache-Control", "no-cache")
        .set("Pragma", "no-cache")
        .call()
    {
        Ok(r) => r,
        Err(ureq::Error::Status(code, _)) => {
            return Err(format!("List URL returned HTTP {code}."));
        }
        Err(_) => return Err("Could not download the profile list.".into()),
    };
    let body = resp
        .into_string()
        .map_err(|_| "Could not read the profile list.".to_string())?;
    if body.len() > CATALOG_MAX_BYTES {
        return Err("Profile list is too large.".into());
    }
    let trimmed = body.trim_start_matches('\u{feff}').trim_start();
    if trimmed.starts_with('<') {
        return Err(
            "That URL is a web page, not JSON. Use the Raw file link (raw.githubusercontent.com)."
                .into(),
        );
    }
    Ok(body)
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

const CATALOG_SHAPE: &str =
    r#"Need {"profiles":[{"name":"...","server":"..."}]} — not an app-update JSON."#;

fn parse_catalog_entries(raw: &str) -> Result<Vec<(String, String)>, String> {
    let list = profile_array(raw)?;
    let mut out = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
    let mut first_bad: Option<String> = None;
    for (i, item) in list.iter().enumerate() {
        match catalog_row(item) {
            Ok((name, server)) => {
                let key = normalize_server(&server);
                if key.is_empty() || !seen.insert(key) {
                    continue;
                }
                out.push((name, server));
            }
            Err(e) => {
                if first_bad.is_none() {
                    first_bad = Some(format!("Profile #{}: {e}", i + 1));
                }
            }
        }
    }
    if let Some(err) = first_bad {
        return Err(err);
    }
    Ok(out)
}

fn profile_array(raw: &str) -> Result<Vec<Value>, String> {
    let text = raw.trim_start_matches('\u{feff}').trim();
    if text.starts_with('<') {
        return Err(
            "That URL is a web page, not JSON. Use the Raw file link (raw.githubusercontent.com)."
                .into(),
        );
    }
    let root: Value = serde_json::from_str(text).map_err(|_| "That file is not valid JSON.")?;
    if let Some(arr) = root.as_array() {
        return Ok(arr.clone());
    }
    let has_profiles = root.get("profiles").and_then(|v| v.as_array()).is_some();
    if !has_profiles
        && (root.get("versionCode").is_some() || root.get("version_code").is_some())
    {
        return Err("That file is an app update list, not a profile list.".into());
    }
    root.get("profiles")
        .or_else(|| root.get("servers"))
        .and_then(|v| v.as_array())
        .cloned()
        .ok_or_else(|| CATALOG_SHAPE.into())
}

fn catalog_row(item: &Value) -> Result<(String, String), String> {
    if item.get("prefs").is_some() {
        let p = profile_from_android(item)?;
        return Ok(finish_row(p.name, p.server));
    }
    let server = json_str(
        item,
        &["server", "server_address", "host", "hostname", "address"],
    )
    .map(|s| normalize_imported_server(&s))
    .filter(|s| !s.is_empty())
    .ok_or_else(|| r#"missing "server""#.to_string())?;
    let name = json_str(item, &["name", "profile_name", "title", "label"])
        .unwrap_or_else(|| server.clone());
    Ok(finish_row(name, server))
}

fn finish_row(name: String, server: String) -> (String, String) {
    let name = {
        let t = name.trim();
        if t.is_empty() {
            server.clone()
        } else {
            t.to_string()
        }
    };
    (name, server)
}

fn parse_item(item: &Value) -> Result<Profile, String> {
    if item.get("prefs").is_some() {
        return profile_from_android(item);
    }
    if let Ok(p) = profile_from_flat(item) {
        if !p.server.trim().is_empty() {
            return Ok(p);
        }
    }
    profile_from_loose(item)
}

fn json_str(item: &Value, keys: &[&str]) -> Option<String> {
    let obj = item.as_object()?;
    for key in keys {
        if let Some(v) = obj.get(*key) {
            if let Some(s) = v.as_str() {
                let t = s.trim();
                if !t.is_empty() {
                    return Some(t.to_string());
                }
            }
        }
    }
    None
}

fn profile_from_loose(item: &Value) -> Result<Profile, String> {
    let server = json_str(
        item,
        &["server", "server_address", "host", "hostname", "address"],
    )
    .map(|s| normalize_imported_server(&s))
    .filter(|s| !s.is_empty())
    .ok_or_else(|| "missing server".to_string())?;
    let name = json_str(item, &["name", "profile_name", "title", "label"])
        .unwrap_or_else(|| server.clone());
    Ok(Profile::minimal(name, server))
}

fn import_one(
    store: &ProfileStore,
    item: &Value,
    seen: &mut HashSet<String>,
    names: &mut HashSet<String>,
) -> Result<bool, String> {
    let profile = parse_item(item)?;
    let server_key = normalize_server(&profile.server);
    if server_key.is_empty() {
        return Err("empty server".into());
    }
    if seen.contains(&server_key) {
        return Ok(false);
    }
    let mut profile = profile;
    profile.id = Uuid::new_v4().to_string();
    profile.name = unique_name(&profile.name, names);
    profile.password.clear();
    profile.save_password = false;
    store.add(profile)?;
    seen.insert(server_key);
    Ok(true)
}

fn profile_from_flat(item: &Value) -> Result<Profile, String> {
    let mut p: Profile = serde_json::from_value(item.clone()).map_err(|e| e.to_string())?;
    p.password.clear();
    if p.name.trim().is_empty() {
        p.name = "Imported".into();
    }
    if p.server.trim().is_empty() {
        return Err("missing server".into());
    }
    Ok(p)
}

fn profile_from_android(item: &Value) -> Result<Profile, String> {
    let prefs = item
        .get("prefs")
        .and_then(|v| v.as_object())
        .ok_or("missing prefs")?;
    let name = unwrap_str(prefs, "profile_name").unwrap_or_else(|| "Imported".into());
    let server = normalize_imported_server(
        &unwrap_str(prefs, "server_address").unwrap_or_default(),
    );
    if server.trim().is_empty() {
        return Err("missing server_address".into());
    }
    // Android CA / user cert paths are not valid on Windows.
    let ca_raw = unwrap_str(prefs, "ca_certificate")
        .or_else(|| unwrap_str(prefs, "ca_file"))
        .unwrap_or_default();
    let ca_file = sanitize_windows_path(ca_raw);
    let sni = unwrap_str(prefs, "sni")
        .or_else(|| unwrap_str(prefs, "custom_sni"))
        .or_else(|| unwrap_str(prefs, "hostname"))
        .unwrap_or_default();
    let mut p = Profile::minimal(name, server);
    p.group = unwrap_str(prefs, "group")
        .or_else(|| unwrap_str(prefs, "authgroup"))
        .unwrap_or_default();
    p.protocol = unwrap_str(prefs, "protocol").unwrap_or_else(|| "anyconnect".into());
    p.network_mode = unwrap_str(prefs, "network_mode").unwrap_or_else(|| "tunnel".into());
    p.use_dtls = unwrap_bool(prefs, "use_dtls").unwrap_or(true);
    // Android clients are usually IPv4-only; dual-stack on Windows often
    // stalls for tens of seconds when AAAA exists but IPv6 is broken.
    p.disable_ipv6 = true;
    p.sni = sni;
    p.ca_file = ca_file;
    p.accept_insecure_cert = true;
    p.batch_mode = unwrap_str(prefs, "batch_mode").unwrap_or_else(|| "empty_only".into());
    p.disable_username_caching =
        unwrap_bool(prefs, "disable_username_caching").unwrap_or(false);
    p.geo_iso = unwrap_str(prefs, "geo_iso").unwrap_or_default();
    p.geo_country = unwrap_str(prefs, "geo_country").unwrap_or_default();
    p.geo_ip4 = unwrap_str(prefs, "geo_ip4").unwrap_or_default();
    Ok(p)
}

/// Keep only CA paths that can exist on this Windows machine.
fn sanitize_windows_path(raw: String) -> String {
    let t = raw.trim();
    if t.is_empty() {
        return String::new();
    }
    // Typical Android private storage / content URIs — useless here.
    let lower = t.to_ascii_lowercase();
    if lower.starts_with("/data/")
        || lower.starts_with("/storage/")
        || lower.starts_with("/sdcard")
        || lower.starts_with("content://")
        || lower.starts_with("file:///data")
        || lower.contains("/org.openconnect")
        || lower.contains("/openconnect")
    {
        return String::new();
    }
    let path = std::path::Path::new(t);
    if path.is_file() {
        t.to_string()
    } else {
        String::new()
    }
}

fn normalize_imported_server(raw: &str) -> String {
    let mut s = raw.trim().to_string();
    if let Some(rest) = s.strip_prefix("https://") {
        s = rest.to_string();
    } else if let Some(rest) = s.strip_prefix("http://") {
        s = rest.to_string();
    }
    while s.ends_with('/') {
        s.pop();
    }
    s
}

fn unwrap_str(prefs: &Map<String, Value>, key: &str) -> Option<String> {
    let v = prefs.get(key)?;
    if let Some(obj) = v.as_object() {
        let t = obj.get("t").and_then(|x| x.as_str()).unwrap_or("s");
        let val = obj.get("v")?;
        if t == "s" || t == "i" || t == "l" || t == "f" {
            return Some(match val {
                Value::String(s) => s.clone(),
                Value::Number(n) => n.to_string(),
                Value::Bool(b) => b.to_string(),
                _ => val.to_string().trim_matches('"').to_string(),
            });
        }
        return None;
    }
    v.as_str().map(|s| s.to_string())
}

fn unwrap_bool(prefs: &Map<String, Value>, key: &str) -> Option<bool> {
    let v = prefs.get(key)?;
    if let Some(obj) = v.as_object() {
        let val = obj.get("v")?;
        return val.as_bool().or_else(|| {
            val.as_str()
                .map(|s| s.eq_ignore_ascii_case("true") || s == "1")
        });
    }
    v.as_bool()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_flat_object_and_prefs() {
        let wrapped = r#"{
            "format": "myoc-profiles",
            "profiles": [
                {"name": "Alpha", "server": "https://a.example.com/"},
                {"prefs": {"profile_name": {"t": "s", "v": "Beta"}, "server_address": {"t": "s", "v": "b.example.com"}}}
            ]
        }"#;
        let items = parse_catalog_entries(wrapped).unwrap();
        assert_eq!(items.len(), 2);
        assert_eq!(items[0], ("Alpha".into(), "a.example.com".into()));
        assert_eq!(items[1], ("Beta".into(), "b.example.com".into()));
    }

    #[test]
    fn reject_app_update_manifest() {
        let raw = r#"{"versionCode":105,"versionName":"1.0.5","url":"https://example.com/a.exe"}"#;
        let err = parse_catalog_entries(raw).unwrap_err();
        assert!(err.contains("update list"), "{err}");
    }

    #[test]
    fn parse_title_and_host() {
        let raw = r#"[{"title":"DE","host":"de.example.com"}]"#;
        let items = parse_catalog_entries(raw).unwrap();
        assert_eq!(items[0], ("DE".into(), "de.example.com".into()));
    }

    #[test]
    fn reject_bad_row_instead_of_empty() {
        let err = parse_catalog_entries(r#"{"profiles":[{"name":"x"}]}"#).unwrap_err();
        assert!(err.contains("server"), "{err}");
    }

    #[test]
    fn empty_profiles_is_ok() {
        let items = parse_catalog_entries(r#"{"profiles":[]}"#).unwrap();
        assert!(items.is_empty());
    }

    #[test]
    fn rewrite_blob_url() {
        let raw = rewrite_github_blob(
            "https://github.com/MrMinoo/ocp/blob/main/profiles.json",
        );
        assert_eq!(
            raw,
            "https://raw.githubusercontent.com/MrMinoo/ocp/main/profiles.json"
        );
    }

    #[test]
    fn catalog_renames_by_server_keeps_user() {
        let dir = std::env::temp_dir().join(format!("ocp-cat-{}", Uuid::new_v4()));
        let store = ProfileStore::open(dir.clone()).unwrap();
        store
            .add(Profile::minimal("Mine", "mine.example.com"))
            .unwrap();
        sync_from_json(
            &store,
            r#"{"profiles":[{"name":"Old","server":"vpn.example.com"}]}"#,
        )
        .unwrap();
        let list = store.list().unwrap();
        assert_eq!(list.len(), 2);
        sync_from_json(
            &store,
            r#"{"profiles":[{"name":"New","server":"vpn.example.com"}]}"#,
        )
        .unwrap();
        let list = store.list().unwrap();
        assert_eq!(list.len(), 2);
        let cat = list.iter().find(|p| p.is_catalog()).unwrap();
        assert_eq!(cat.name, "New");
        let user = list.iter().find(|p| !p.is_catalog()).unwrap();
        assert_eq!(user.name, "Mine");
        let _ = std::fs::remove_dir_all(dir);
    }
}
