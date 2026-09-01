//! System tray next to the clock. Closing the window hides here; VPN keeps running.

use crate::vpn::{self, ConnectionState};
use crate::commands::AppState;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, Runtime};

pub fn install<R: Runtime>(app: &tauri::App<R>) -> Result<(), Box<dyn std::error::Error>> {
    let show = MenuItem::with_id(app, "show", "Show", true, None::<&str>)?;
    let disconnect = MenuItem::with_id(app, "disconnect", "Disconnect", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show, &disconnect, &quit])?;

    let icon = crate::brand_icon()
        .or_else(|| app.default_window_icon().cloned())
        .ok_or("missing window icon")?;

    TrayIconBuilder::with_id("main")
        .icon(icon)
        .tooltip("OpenConnect +P")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "show" => show_main(app),
            "disconnect" => {
                let _ = vpn::disconnect(&app.state::<AppState>().vpn);
                refresh_tooltip(app);
            }
            "quit" => quit_app(app),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_main(tray.app_handle());
            }
        })
        .build(app)?;
    Ok(())
}

pub fn show_main<R: Runtime>(app: &AppHandle<R>) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
        if let Some(icon) = crate::brand_icon() {
            let _ = window.set_icon(icon);
        }
    }
}

pub fn hide_to_tray<R: Runtime>(app: &AppHandle<R>) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.hide();
    }
    refresh_tooltip(app);
}

pub fn refresh_tooltip<R: Runtime>(app: &AppHandle<R>) {
    let Some(tray) = app.tray_by_id("main") else {
        return;
    };
    let status = vpn::vpn_status(&app.state::<AppState>().vpn);
    let tip = match status.state {
        ConnectionState::Connected => {
            let where_ = if !status.public_country.is_empty() {
                status.public_country
            } else if !status.message.is_empty() {
                status.message
            } else {
                "VPN".into()
            };
            format!("OpenConnect +P · Connected · {where_}")
        }
        ConnectionState::Connecting => "OpenConnect +P · Connecting…".into(),
        ConnectionState::Disconnecting => "OpenConnect +P · Disconnecting…".into(),
        ConnectionState::Error => format!("OpenConnect +P · {}", status.message),
        ConnectionState::Disconnected => "OpenConnect +P · Disconnected".into(),
    };
    let _ = tray.set_tooltip(Some(tip));
}

fn quit_app<R: Runtime>(app: &AppHandle<R>) {
    let state = app.state::<AppState>();
    let status = vpn::vpn_status(&state.vpn);
    if !matches!(status.state, ConnectionState::Disconnected) {
        let _ = vpn::disconnect(&state.vpn);
        std::thread::sleep(std::time::Duration::from_millis(400));
    }
    app.exit(0);
}
