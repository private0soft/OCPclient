use crate::vpn::{
    self, AppSettings, ConnectRequest, ImportResult, Profile, ProfileStore, UpdateInfo, VpnRuntime,
    VpnStatus,
};
use serde::Serialize;
use std::fs;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Emitter, State};

pub struct AppState {
    pub vpn: Arc<VpnRuntime>,
    pub profiles: Mutex<Option<ProfileStore>>,
}

impl AppState {
    pub fn new() -> Self {
        Self {
            vpn: Arc::new(VpnRuntime::new()),
            profiles: Mutex::new(None),
        }
    }

    pub fn store(&self, _app: &AppHandle) -> Result<ProfileStore, String> {
        let mut guard = self.profiles.lock().map_err(|e| e.to_string())?;
        if guard.is_none() {
            *guard = Some(ProfileStore::open(data_dir()?)?);
        }
        guard
            .as_ref()
            .cloned()
            .ok_or_else(|| "profile store not ready".into())
    }
}

fn data_dir() -> Result<std::path::PathBuf, String> {
    crate::vpn::paths::data_dir()
}

#[derive(Serialize)]
pub struct StorageInfo {
    pub data_dir: String,
    pub profiles_file: String,
    pub secrets_dir: String,
}

#[tauri::command]
pub fn get_status(state: State<'_, AppState>) -> VpnStatus {
    vpn::vpn_status(&state.vpn)
}

#[tauri::command]
pub fn get_logs(state: State<'_, AppState>) -> Vec<String> {
    vpn::vpn_logs(&state.vpn)
}

#[tauri::command]
pub fn storage_info(app: AppHandle, state: State<'_, AppState>) -> Result<StorageInfo, String> {
    let store = state.store(&app)?;
    Ok(StorageInfo {
        data_dir: data_dir()?.display().to_string(),
        profiles_file: store.path().display().to_string(),
        secrets_dir: crate::vpn::paths::secrets_dir()?.display().to_string(),
    })
}

#[tauri::command]
pub fn list_profiles(app: AppHandle, state: State<'_, AppState>) -> Result<Vec<Profile>, String> {
    vpn::list_profiles(&state.store(&app)?)
}

#[tauri::command]
pub fn save_profile(
    app: AppHandle,
    state: State<'_, AppState>,
    profile: Profile,
) -> Result<Profile, String> {
    let store = state.store(&app)?;
    if profile.id.is_empty() || vpn::get_profile(&store, &profile.id).is_err() {
        vpn::add_profile(&store, profile)
    } else {
        vpn::update_profile(&store, profile)
    }
}

#[tauri::command]
pub fn set_profile_mode(
    app: AppHandle,
    state: State<'_, AppState>,
    id: String,
    network_mode: String,
) -> Result<(), String> {
    vpn::set_profile_mode(&state.store(&app)?, &id, &network_mode)
}

#[tauri::command]
pub fn delete_profile(
    app: AppHandle,
    state: State<'_, AppState>,
    id: String,
) -> Result<(), String> {
    vpn::delete_profile(&state.store(&app)?, &id)
}

#[tauri::command]
pub fn clear_saved_password(
    app: AppHandle,
    state: State<'_, AppState>,
    id: String,
) -> Result<Profile, String> {
    vpn::clear_saved_password(&state.store(&app)?, &id)
}

#[tauri::command]
pub fn connect_vpn(
    app: AppHandle,
    state: State<'_, AppState>,
    profile_id: String,
    password: Option<String>,
    save_password: Option<bool>,
    save_for_domain: Option<bool>,
    username: Option<String>,
) -> Result<VpnStatus, String> {
    let store = state.store(&app)?;
    let mut profile = vpn::get_profile(&store, &profile_id)?;
    if let Some(ref u) = username {
        let t = u.trim();
        if !t.is_empty() {
            profile.username = t.to_string();
        }
    }
    let mut req = ConnectRequest::from_profile(&profile, password);
    req.save_password = save_password.unwrap_or(false);
    req.save_for_domain = save_for_domain.unwrap_or(false);
    req.app_data = data_dir()?;
    let status = vpn::connect(Arc::clone(&state.vpn), req)?;
    crate::tray::refresh_tooltip(&app);
    Ok(status)
}

/// Switch (or connect) to a profile. Safe while already connected: tears down
/// first and never runs geo lookup on the real path during the gap.
#[tauri::command]
pub fn switch_vpn(
    app: AppHandle,
    state: State<'_, AppState>,
    profile_id: String,
    password: Option<String>,
    save_password: Option<bool>,
    save_for_domain: Option<bool>,
    username: Option<String>,
) -> Result<VpnStatus, String> {
    let store = state.store(&app)?;
    let mut profile = vpn::get_profile(&store, &profile_id)?;
    if let Some(ref u) = username {
        let t = u.trim();
        if !t.is_empty() {
            profile.username = t.to_string();
        }
    }
    let mut req = ConnectRequest::from_profile(&profile, password);
    req.save_password = save_password.unwrap_or(false);
    req.save_for_domain = save_for_domain.unwrap_or(false);
    req.app_data = data_dir()?;
    let status = vpn::switch_profile(Arc::clone(&state.vpn), req)?;
    crate::tray::refresh_tooltip(&app);
    Ok(status)
}

#[tauri::command]
pub fn disconnect_vpn(app: AppHandle, state: State<'_, AppState>) -> Result<VpnStatus, String> {
    let status = vpn::disconnect(&state.vpn)?;
    crate::tray::refresh_tooltip(&app);
    Ok(status)
}

#[tauri::command]
pub fn backend_mode() -> String {
    vpn::version_label()
}

#[tauri::command]
pub fn get_app_settings() -> Result<AppSettings, String> {
    Ok(AppSettings::load(&data_dir()?))
}

#[tauri::command]
pub fn save_app_settings(settings: AppSettings) -> Result<AppSettings, String> {
    let dir = data_dir()?;
    let mut cur = AppSettings::load(&dir);
    cur.update_manifest_url = settings.update_manifest_url;
    cur.custom_update = settings.custom_update;
    cur.lookup_public_ip = settings.lookup_public_ip;
    cur.theme = if settings.theme == "light" {
        "light".into()
    } else {
        "dark".into()
    };
    cur.catalog_url = settings.catalog_url.trim().to_string();
    cur.save(&dir)?;
    Ok(cur)
}

#[tauri::command]
pub fn check_update(manual: bool, via_proxy: Option<bool>) -> Result<UpdateInfo, String> {
    vpn::check_update(&data_dir()?, manual, via_proxy.unwrap_or(false))
}

#[derive(Clone, Serialize)]
struct UpdateDownloadEvent {
    state: String,
    percent: u8,
    received: u64,
    total: u64,
    message: String,
}

#[tauri::command]
pub fn start_update_download(app: AppHandle, state: State<'_, AppState>, url: String) -> Result<(), String> {
    let url = url.trim().to_string();
    if !url.starts_with("https://") {
        return Err("Update link must start with https://".into());
    }
    let status = vpn::vpn_status(&state.vpn);
    let via_proxy = status.state == crate::vpn::ConnectionState::Connected
        && status.network_mode == "proxy";
    std::thread::spawn(move || {
        let emit = |ev: UpdateDownloadEvent| {
            let _ = app.emit("update-download", ev);
        };
        emit(UpdateDownloadEvent {
            state: "progress".into(),
            percent: 0,
            received: 0,
            total: 0,
            message: "Downloading".into(),
        });
        match vpn::download_update(&url, via_proxy, |got, total| {
            let percent = match total {
                Some(t) if t > 0 => ((got.saturating_mul(100)) / t).min(99) as u8,
                _ => 0,
            };
            emit(UpdateDownloadEvent {
                state: "progress".into(),
                percent,
                received: got,
                total: total.unwrap_or(0),
                message: "Downloading".into(),
            });
        }) {
            Ok(path) => {
                emit(UpdateDownloadEvent {
                    state: "done".into(),
                    percent: 100,
                    received: 0,
                    total: 0,
                    message: "Opening installer".into(),
                });
                if let Err(e) = vpn::launch_update(&path) {
                    emit(UpdateDownloadEvent {
                        state: "error".into(),
                        percent: 0,
                        received: 0,
                        total: 0,
                        message: e,
                    });
                }
            }
            Err(e) => emit(UpdateDownloadEvent {
                state: "error".into(),
                percent: 0,
                received: 0,
                total: 0,
                message: e,
            }),
        }
    });
    Ok(())
}

#[tauri::command]
pub fn snooze_update(version_code: i32) -> Result<(), String> {
    vpn::snooze_update(&data_dir()?, version_code)
}

#[tauri::command]
pub fn flag_data_url(iso: String) -> Result<String, String> {
    vpn::flag_data_url(&data_dir()?, &iso)
}

#[derive(Serialize)]
pub struct BackupUiResult {
    pub cancelled: bool,
    pub message: String,
    pub imported: u32,
    pub skipped: u32,
}

#[tauri::command]
pub fn export_profiles(
    app: AppHandle,
    state: State<'_, AppState>,
    path: String,
) -> Result<BackupUiResult, String> {
    let dest = PathBuf::from(path.trim());
    if dest.as_os_str().is_empty() {
        return Err("no export path".into());
    }
    let json = vpn::export_profiles_json(&state.store(&app)?)?;
    fs::write(&dest, json).map_err(|e| e.to_string())?;
    Ok(BackupUiResult {
        cancelled: false,
        message: format!("Exported to {}", dest.display()),
        imported: 0,
        skipped: 0,
    })
}

#[tauri::command]
pub fn import_profiles(
    app: AppHandle,
    state: State<'_, AppState>,
    path: String,
) -> Result<BackupUiResult, String> {
    let src = PathBuf::from(path.trim());
    if src.as_os_str().is_empty() {
        return Err("no import path".into());
    }
    let raw = fs::read_to_string(&src).map_err(|e| e.to_string())?;
    let ImportResult { imported, skipped } =
        vpn::import_profiles_json(&state.store(&app)?, &raw)?;
    Ok(BackupUiResult {
        cancelled: false,
        message: format!("Imported {imported}, skipped {skipped}"),
        imported,
        skipped,
    })
}

#[tauri::command]
pub fn sync_catalog(
    app: AppHandle,
    state: State<'_, AppState>,
    url: String,
) -> Result<BackupUiResult, String> {
    let url = url.trim().to_string();
    if url.is_empty() {
        return Err("Enter a https:// JSON address.".into());
    }
    let store = state.store(&app)?;
    let status = vpn::vpn_status(&state.vpn);
    let via_proxy = status.state == crate::vpn::ConnectionState::Connected
        && status.network_mode == "proxy";
    let active = status.active_profile_id.clone();
    let result = vpn::sync_catalog_from_url(&store, &url, via_proxy)?;
    let dir = data_dir()?;
    let mut settings = AppSettings::load(&dir);
    settings.catalog_url = url;
    let _ = settings.save(&dir);
    if let Some(id) = active {
        if vpn::get_profile(&store, &id).is_err() {
            let _ = vpn::disconnect(&state.vpn);
            crate::tray::refresh_tooltip(&app);
        }
    }
    Ok(BackupUiResult {
        cancelled: false,
        message: result.message,
        imported: result.added + result.updated,
        skipped: result.skipped,
    })
}

#[tauri::command]
pub fn list_saved_logins(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<Vec<SavedLogin>, String> {
    let mut out = Vec::new();
    for p in vpn::list_profiles(&state.store(&app)?)? {
        if p.has_own_password {
            out.push(SavedLogin {
                kind: "profile".into(),
                key: p.id,
                title: p.name,
                username: p.username,
            });
        }
    }
    for r in crate::vpn::cred::list_realms()? {
        out.push(SavedLogin {
            kind: "domain".into(),
            key: r.domain.clone(),
            title: r.domain,
            username: r.username,
        });
    }
    Ok(out)
}

#[tauri::command]
pub fn forget_saved_login(
    app: AppHandle,
    state: State<'_, AppState>,
    kind: String,
    key: String,
) -> Result<(), String> {
    let key = key.trim().to_string();
    if kind == "profile" {
        vpn::clear_saved_password(&state.store(&app)?, &key)?;
    } else if kind == "domain" {
        crate::vpn::cred::delete_realm(&key);
        let _ = state.store(&app)?.set_shared_for_domain(&key, false);
    }
    Ok(())
}

#[derive(Serialize)]
pub struct SavedLogin {
    pub kind: String,
    pub key: String,
    pub title: String,
    pub username: String,
}
