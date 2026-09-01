use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;
use uuid::Uuid;

use super::cred;

pub const SOURCE_USER: &str = "user";
pub const SOURCE_CATALOG: &str = "catalog";

fn default_source() -> String {
    SOURCE_USER.into()
}

/// ocserv / AnyConnect profile (desktop subset of Android prefs).
/// Password is never written here — see `cred` (DPAPI files under ProgramData).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Profile {
    #[serde(default)]
    pub id: String,
    #[serde(default, alias = "profile_name")]
    pub name: String,
    #[serde(default, alias = "server_address")]
    pub server: String,
    #[serde(default)]
    pub username: String,
    #[serde(default, skip_serializing)]
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
    #[serde(default = "default_true")]
    pub accept_insecure_cert: bool,
    /// disabled | empty_only | enabled  (Android batch_mode)
    #[serde(default = "default_batch")]
    pub batch_mode: String,
    #[serde(default)]
    pub save_password: bool,
    #[serde(default)]
    pub use_shared_login: bool,
    #[serde(default)]
    pub disable_username_caching: bool,
    #[serde(default, skip_deserializing)]
    pub has_saved_password: bool,
    #[serde(default, skip_deserializing)]
    pub password_target: String,
    #[serde(default, skip_deserializing)]
    pub login_domain: String,
    #[serde(default, skip_deserializing)]
    pub has_domain_password: bool,
    #[serde(default, skip_deserializing)]
    pub has_own_password: bool,
    #[serde(default, skip_deserializing)]
    pub realm_username: String,
    #[serde(default)]
    pub geo_iso: String,
    #[serde(default)]
    pub geo_country: String,
    #[serde(default)]
    pub geo_ip4: String,
    /// `user` = created on this PC. `catalog` = from the online JSON list.
    #[serde(default = "default_source")]
    pub source: String,
}

fn default_protocol() -> String {
    "anyconnect".into()
}

fn default_true() -> bool {
    true
}

fn default_batch() -> String {
    "empty_only".into()
}

#[derive(Debug, Default, Clone)]
pub struct CatalogApply {
    pub added: u32,
    pub updated: u32,
    pub removed: u32,
    pub skipped: u32,
}

impl Profile {
    pub fn is_catalog(&self) -> bool {
        self.source == SOURCE_CATALOG
    }

    pub fn minimal(name: impl Into<String>, server: impl Into<String>) -> Self {
        Self {
            id: String::new(),
            name: name.into(),
            server: server.into(),
            username: String::new(),
            password: String::new(),
            group: String::new(),
            protocol: default_protocol(),
            network_mode: "tunnel".into(),
            use_dtls: true,
            disable_ipv6: true,
            sni: String::new(),
            ca_file: String::new(),
            accept_insecure_cert: true,
            batch_mode: default_batch(),
            save_password: false,
            use_shared_login: false,
            disable_username_caching: false,
            has_saved_password: false,
            password_target: String::new(),
            login_domain: String::new(),
            has_domain_password: false,
            has_own_password: false,
            realm_username: String::new(),
            geo_iso: String::new(),
            geo_country: String::new(),
            geo_ip4: String::new(),
            source: SOURCE_USER.into(),
        }
    }

    fn sanitize_for_disk(&mut self) {
        if self.protocol.trim().is_empty() {
            self.protocol = default_protocol();
        }
        if self.batch_mode.trim().is_empty() {
            self.batch_mode = default_batch();
        }
        if self.source != SOURCE_CATALOG {
            self.source = SOURCE_USER.into();
        }
        if self.disable_username_caching {
            self.username.clear();
        }
        self.password.clear();
        self.has_saved_password = false;
        self.password_target.clear();
        self.login_domain.clear();
        self.has_domain_password = false;
        self.has_own_password = false;
        self.realm_username.clear();
    }

    fn hydrate(mut self) -> Self {
        // Drop Android CA paths that do not exist on this PC (breaks HTTPS if applied).
        let ca = self.ca_file.trim();
        if !ca.is_empty() && !std::path::Path::new(ca).is_file() {
            self.ca_file.clear();
        }
        self.login_domain = cred::login_domain(&self.server).unwrap_or_default();
        self.has_own_password = cred::has(&self.id);
        self.has_domain_password =
            !self.login_domain.is_empty() && cred::has_realm(&self.login_domain);
        self.realm_username = if self.has_domain_password {
            cred::realm_username(&self.login_domain)
        } else {
            String::new()
        };
        self.has_saved_password =
            self.has_own_password || (self.use_shared_login && self.has_domain_password);
        self.password_target.clear();
        self.password.clear();
        self
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
struct ProfileFile {
    format: String,
    version: u32,
    profiles: Vec<Profile>,
}

#[derive(Clone)]
pub struct ProfileStore {
    path: PathBuf,
}

static FILE_LOCK: Mutex<()> = Mutex::new(());

fn store_lock() -> std::sync::MutexGuard<'static, ()> {
    FILE_LOCK.lock().unwrap_or_else(|p| p.into_inner())
}

impl ProfileStore {
    pub fn open(app_data_dir: PathBuf) -> Result<Self, String> {
        fs::create_dir_all(&app_data_dir)
            .map_err(|e| format!("cannot create {}: {e}", app_data_dir.display()))?;
        Ok(Self {
            path: app_data_dir.join("profiles.json"),
        })
    }

    pub fn path(&self) -> &PathBuf {
        &self.path
    }

    fn load(&self) -> Result<ProfileFile, String> {
        if !self.path.exists() {
            return Ok(ProfileFile {
                format: "myoc-profiles".into(),
                version: 3,
                profiles: Vec::new(),
            });
        }
        let raw = fs::read_to_string(&self.path).map_err(|e| e.to_string())?;
        let mut file: ProfileFile = serde_json::from_str(&raw).map_err(|e| e.to_string())?;
        let mut dirty = false;
        for p in &mut file.profiles {
            if !p.password.is_empty() {
                let _ = cred::write(&p.id, &p.username, &p.password);
                p.password.clear();
                p.save_password = true;
                dirty = true;
            }
        }
        if dirty {
            self.save(&file)?;
        }
        Ok(file)
    }

    fn save(&self, file: &ProfileFile) -> Result<(), String> {
        let mut out = file.clone();
        out.version = 3;
        for p in &mut out.profiles {
            p.sanitize_for_disk();
        }
        let raw = serde_json::to_string_pretty(&out).map_err(|e| e.to_string())?;
        super::paths::write_atomic(&self.path, raw)
    }

    pub fn list(&self) -> Result<Vec<Profile>, String> {
        let _g = store_lock();
        self.list_inner()
    }

    fn list_inner(&self) -> Result<Vec<Profile>, String> {
        Ok(self.load()?.profiles.into_iter().map(Profile::hydrate).collect())
    }

    pub fn get(&self, id: &str) -> Result<Profile, String> {
        let _g = store_lock();
        self.get_inner(id)
    }

    fn get_inner(&self, id: &str) -> Result<Profile, String> {
        self.list_inner()?
            .into_iter()
            .find(|p| p.id == id)
            .ok_or_else(|| format!("profile not found: {id}"))
    }

    pub fn add(&self, mut profile: Profile) -> Result<Profile, String> {
        let _g = store_lock();
        if profile.id.is_empty() {
            profile.id = Uuid::new_v4().to_string();
        }
        profile.source = SOURCE_USER.into();
        self.apply_secret(&mut profile)?;
        let mut file = self.load()?;
        let id = profile.id.clone();
        file.profiles.push(profile);
        self.save(&file)?;
        self.get_inner(&id)
    }

    pub fn update(&self, mut profile: Profile) -> Result<Profile, String> {
        let _g = store_lock();
        self.apply_secret(&mut profile)?;
        let mut file = self.load()?;
        let Some(slot) = file.profiles.iter_mut().find(|p| p.id == profile.id) else {
            return Err(format!("profile not found: {}", profile.id));
        };
        let id = profile.id.clone();
        let source = slot.source.clone();
        *slot = profile;
        slot.source = if source == SOURCE_CATALOG {
            SOURCE_CATALOG.into()
        } else {
            SOURCE_USER.into()
        };
        self.save(&file)?;
        self.get_inner(&id)
    }

    pub fn delete(&self, id: &str) -> Result<(), String> {
        let _g = store_lock();
        cred::delete(id);
        let mut file = self.load()?;
        let before = file.profiles.len();
        file.profiles.retain(|p| p.id != id);
        if file.profiles.len() == before {
            return Err(format!("profile not found: {id}"));
        }
        self.save(&file)
    }

    pub fn set_mode(&self, id: &str, network_mode: &str) -> Result<(), String> {
        let _g = store_lock();
        let mut file = self.load()?;
        let Some(p) = file.profiles.iter_mut().find(|p| p.id == id) else {
            return Err(format!("profile not found: {id}"));
        };
        p.network_mode = network_mode.to_string();
        self.save(&file)
    }

    pub fn set_geo(
        &self,
        id: &str,
        iso: &str,
        country: &str,
        ip4: &str,
    ) -> Result<(), String> {
        let _g = store_lock();
        let mut file = self.load()?;
        let Some(p) = file.profiles.iter_mut().find(|p| p.id == id) else {
            return Err(format!("profile not found: {id}"));
        };
        if iso.len() == 2 {
            p.geo_iso = iso.to_ascii_lowercase();
        }
        if !country.is_empty() {
            p.geo_country = country.to_string();
        }
        if !ip4.is_empty() {
            p.geo_ip4 = ip4.to_string();
        }
        self.save(&file)
    }

    pub fn set_shared_for_domain(&self, domain: &str, enable: bool) -> Result<(), String> {
        let _g = store_lock();
        let mut file = self.load()?;
        let mut dirty = false;
        for p in &mut file.profiles {
            if cred::login_domain(&p.server).as_deref() == Some(domain) {
                p.use_shared_login = enable;
                if enable {
                    p.save_password = true;
                }
                dirty = true;
            }
        }
        if dirty {
            self.save(&file)?;
        }
        Ok(())
    }

    pub fn clear_password(&self, id: &str) -> Result<Profile, String> {
        let _g = store_lock();
        cred::delete(id);
        let mut file = self.load()?;
        let Some(p) = file.profiles.iter_mut().find(|p| p.id == id) else {
            return Err(format!("profile not found: {id}"));
        };
        p.save_password = false;
        p.use_shared_login = false;
        p.password.clear();
        self.save(&file)?;
        self.get_inner(id)
    }

    /// Keep username + save flags without touching the secret file.
    pub fn remember_login(&self, id: &str, username: &str, shared: bool) -> Result<(), String> {
        let _g = store_lock();
        let mut file = self.load()?;
        let Some(p) = file.profiles.iter_mut().find(|p| p.id == id) else {
            return Err(format!("profile not found: {id}"));
        };
        p.save_password = true;
        p.use_shared_login = shared;
        if !p.disable_username_caching && !username.trim().is_empty() {
            p.username = username.trim().to_string();
        }
        self.save(&file)
    }

    fn apply_secret(&self, profile: &mut Profile) -> Result<(), String> {
        if profile.disable_username_caching {
            profile.username.clear();
        }
        if !profile.save_password {
            cred::delete(&profile.id);
        } else if !profile.password.is_empty() {
            cred::write(&profile.id, &profile.username, &profile.password)?;
        }
        profile.password.clear();
        Ok(())
    }

    /// Follow the remote list: matching servers become catalog (name from JSON).
    /// Catalog rows whose server is gone are deleted. User-only servers stay.
    pub fn apply_catalog(&self, incoming: Vec<(String, String)>) -> Result<CatalogApply, String> {
        let _g = store_lock();
        let mut file = self.load()?;
        let wanted: Vec<(String, String, String)> = incoming
            .into_iter()
            .map(|(name, server)| {
                let key = normalize_server(&server);
                (name, server, key)
            })
            .filter(|(_, _, key)| !key.is_empty())
            .collect();
        let wanted_keys: HashSet<String> = wanted.iter().map(|(_, _, k)| k.clone()).collect();

        let mut stats = CatalogApply::default();

        for p in &mut file.profiles {
            if wanted_keys.contains(&normalize_server(&p.server)) {
                p.source = SOURCE_CATALOG.into();
            }
        }

        let mut dropped: Vec<String> = Vec::new();
        let mut seen_keys: HashSet<String> = HashSet::new();
        file.profiles.retain(|p| {
            if !p.is_catalog() {
                return true;
            }
            let key = normalize_server(&p.server);
            if !wanted_keys.contains(&key) || !seen_keys.insert(key) {
                dropped.push(p.id.clone());
                stats.removed += 1;
                false
            } else {
                true
            }
        });
        for id in dropped {
            cred::delete(&id);
        }

        let mut names: HashSet<String> = file.profiles.iter().map(|p| p.name.clone()).collect();

        for (name, server, key) in wanted {
            if let Some(slot) = file
                .profiles
                .iter_mut()
                .find(|p| p.is_catalog() && normalize_server(&p.server) == key)
            {
                let label = name.trim();
                if !label.is_empty() && slot.name != label {
                    names.remove(&slot.name);
                    names.insert(label.to_string());
                    slot.name = label.to_string();
                    stats.updated += 1;
                }
                continue;
            }
            let label = {
                let t = name.trim();
                if t.is_empty() {
                    server.clone()
                } else {
                    t.to_string()
                }
            };
            let mut profile = Profile::minimal(label, server);
            profile.id = Uuid::new_v4().to_string();
            profile.name = unique_name(&profile.name, &mut names);
            profile.source = SOURCE_CATALOG.into();
            file.profiles.push(profile);
            stats.added += 1;
        }

        self.save(&file)?;
        Ok(stats)
    }
}

pub fn list_profiles(store: &ProfileStore) -> Result<Vec<Profile>, String> {
    store.list()
}

pub fn get_profile(store: &ProfileStore, id: &str) -> Result<Profile, String> {
    store.get(id)
}

pub fn add_profile(store: &ProfileStore, profile: Profile) -> Result<Profile, String> {
    store.add(profile)
}

pub fn update_profile(store: &ProfileStore, profile: Profile) -> Result<Profile, String> {
    store.update(profile)
}

pub fn delete_profile(store: &ProfileStore, id: &str) -> Result<(), String> {
    store.delete(id)
}

pub fn set_profile_mode(store: &ProfileStore, id: &str, mode: &str) -> Result<(), String> {
    store.set_mode(id, mode)
}

pub fn clear_saved_password(store: &ProfileStore, id: &str) -> Result<Profile, String> {
    store.clear_password(id)
}

pub(super) fn normalize_server(raw: &str) -> String {
    let mut s = raw.trim().to_ascii_lowercase();
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

pub(super) fn unique_name(base: &str, names: &mut HashSet<String>) -> String {
    let base = {
        let t = base.trim();
        if t.is_empty() {
            "Imported"
        } else {
            t
        }
    };
    if !names.contains(base) {
        names.insert(base.to_string());
        return base.to_string();
    }
    for i in 2..1000 {
        let candidate = format!("{base} ({i})");
        if !names.contains(&candidate) {
            names.insert(candidate.clone());
            return candidate;
        }
    }
    let fallback = format!("{base} ({})", Uuid::new_v4());
    names.insert(fallback.clone());
    fallback
}
