mod commands;
mod tray;
mod vpn;

use commands::AppState;
use tauri::image::Image;
use tauri::Manager;

/// Branded window/taskbar/tray icon (embedded PNG — never the stock Tauri logo).
pub(crate) fn brand_icon() -> Option<Image<'static>> {
    Image::from_bytes(include_bytes!("../icons/32x32.png"))
        .ok()
        .or_else(|| Image::from_bytes(include_bytes!("../icons/128x128.png")).ok())
        .or_else(|| Image::from_bytes(include_bytes!("../icons/icon.png")).ok())
}

fn apply_brand_icon(app: &tauri::AppHandle) {
    let Some(icon) = brand_icon() else {
        return;
    };
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.set_icon(icon);
    }
    // Tray gets its own copy — Image may not be Clone across all Tauri versions.
    if let Some(tray_icon) = app.tray_by_id("main") {
        if let Some(icon) = brand_icon() {
            let _ = tray_icon.set_icon(Some(icon));
        }
    }
}

/// Must run before WebView2 is created: domain/Administrator profiles often
/// cannot host the default LocalAppData cache, which crashes the process.
fn prepare_runtime() {
    match vpn::paths::prepare() {
        Ok(_) => {
            if let Ok(webview) = vpn::paths::webview_dir() {
                std::env::set_var("WEBVIEW2_USER_DATA_FOLDER", &webview);
            }
        }
        Err(e) => {
            eprintln!("OpenConnect-P data dir: {e}");
            if let Ok(tmp) = std::env::var("TEMP") {
                let webview = std::path::PathBuf::from(tmp)
                    .join("OpenConnect-P")
                    .join("webview");
                let _ = std::fs::create_dir_all(&webview);
                std::env::set_var("WEBVIEW2_USER_DATA_FOLDER", &webview);
            }
        }
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    prepare_runtime();
    tauri::Builder::default()
        // Must be early: second process exits and asks the live one to show.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            tray::show_main(app);
            apply_brand_icon(app);
        }))
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .manage(AppState::new())
        .invoke_handler(tauri::generate_handler![
            commands::get_status,
            commands::get_logs,
            commands::storage_info,
            commands::list_profiles,
            commands::save_profile,
            commands::set_profile_mode,
            commands::delete_profile,
            commands::clear_saved_password,
            commands::connect_vpn,
            commands::switch_vpn,
            commands::disconnect_vpn,
            commands::backend_mode,
            commands::get_app_settings,
            commands::save_app_settings,
            commands::check_update,
            commands::start_update_download,
            commands::snooze_update,
            commands::flag_data_url,
            commands::export_profiles,
            commands::import_profiles,
            commands::sync_catalog,
            commands::list_saved_logins,
            commands::forget_saved_login,
        ])
        .setup(|app| {
            tray::install(app)?;
            apply_brand_icon(app.handle());
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                tray::hide_to_tray(window.app_handle());
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
