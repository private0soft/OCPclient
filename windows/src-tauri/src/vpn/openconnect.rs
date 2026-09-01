//! OpenConnect session boundary — real dynload when DLL present, else mock.

use super::loader;
use super::request::ConnectRequest;
use super::session;
use super::state::{ConnectionState, VpnRuntime, VpnStatus};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

pub fn vpn_status(runtime: &VpnRuntime) -> VpnStatus {
    runtime
        .status
        .lock()
        .map(|g| g.clone())
        .unwrap_or_default()
}

pub fn vpn_logs(runtime: &VpnRuntime) -> Vec<String> {
    runtime.logs_snapshot()
}

pub fn backend_kind() -> &'static str {
    if loader::is_available() {
        "libopenconnect"
    } else {
        "mock"
    }
}

/// Topbar subtitle: app version (+ OpenConnect library version when loaded).
pub fn version_label() -> String {
    let app = super::update::VERSION_NAME;
    if !loader::is_available() {
        return format!("v{app}");
    }
    match loader::api() {
        Ok(api) => unsafe {
            let p = (api.get_version)();
            if p.is_null() {
                format!("v{app}")
            } else {
                let oc = std::ffi::CStr::from_ptr(p).to_string_lossy();
                let oc = oc.trim();
                if oc.is_empty() {
                    format!("v{app}")
                } else {
                    // e.g. "v1.0.0 · 9.12"
                    format!("v{app} · {oc}")
                }
            }
        },
        Err(_) => format!("v{app}"),
    }
}

pub fn connect(runtime: Arc<VpnRuntime>, req: ConnectRequest) -> Result<VpnStatus, String> {
    {
        let mut status = runtime.status.lock().map_err(|e| e.to_string())?;
        if matches!(
            status.state,
            ConnectionState::Connecting | ConnectionState::Connected
        ) {
            return Err("already connecting or connected".into());
        }
        // New session epoch — discard any geo still running from a prior tunnel.
        let _ = runtime.bump_geo_epoch();
        status.state = ConnectionState::Connecting;
        status.active_profile_id = Some(req.profile_id.clone());
        status.message = format!("Connecting to {}…", req.server);
        status.bytes_in = 0;
        status.bytes_out = 0;
        // Prefer cached profile geo for UI only — never probe while down.
        status.public_iso = req.geo_iso.clone();
        status.public_country = req.geo_country.clone();
        status.public_ip4 = req.geo_ip4.clone();
        status.socks_proxy = None;
        status.http_proxy = None;
    }
    runtime.push_log(format!("connect → {} ({})", req.server, backend_kind()));
    spawn_worker(Arc::clone(&runtime), req);
    Ok(vpn_status(&runtime))
}

/// Switch to another profile while connected/connecting without leaking real IP:
/// abort geo, clear live exit fields, tear down tunnel, then connect the target.
pub fn switch_profile(runtime: Arc<VpnRuntime>, req: ConnectRequest) -> Result<VpnStatus, String> {
    let current = vpn_status(&runtime);
    if current.active_profile_id.as_deref() == Some(req.profile_id.as_str())
        && matches!(
            current.state,
            ConnectionState::Connected | ConnectionState::Connecting
        )
    {
        return Ok(current);
    }
    if matches!(
        current.state,
        ConnectionState::Disconnected | ConnectionState::Error
    ) {
        return connect(runtime, req);
    }

    let token = runtime.bump_switch_gen();
    // Invalidate any in-flight geo BEFORE the tunnel drops — no public probe on real path.
    let _ = runtime.bump_geo_epoch();

    {
        let mut status = runtime.status.lock().map_err(|e| e.to_string())?;
        status.state = ConnectionState::Disconnecting;
        status.active_profile_id = Some(req.profile_id.clone());
        status.message = format!("Switching to {}…", req.name);
        // Do not look up or keep the previous exit IP as "ours" during the gap.
        status.public_iso.clear();
        status.public_country.clear();
        status.public_ip4.clear();
        status.socks_proxy = None;
        status.http_proxy = None;
    }
    runtime.push_log(format!("switch → {} ({})", req.server, req.profile_id));

    begin_teardown(&runtime);

    let worker = Arc::clone(&runtime);
    thread::spawn(move || {
        if !wait_until_idle(&worker, token) {
            worker.push_log("switch aborted");
            return;
        }
        if worker.switch_gen() != token {
            return;
        }
        // Show cached target geo only (no network). Fresh lookup after Connected.
        if let Err(e) = connect(Arc::clone(&worker), req) {
            worker.push_log(format!("switch connect failed: {e}"));
            worker.with_status(|s| {
                s.state = ConnectionState::Error;
                s.message = e;
            });
        }
    });

    Ok(vpn_status(&runtime))
}

pub fn disconnect(runtime: &VpnRuntime) -> Result<VpnStatus, String> {
    let _ = runtime.bump_switch_gen(); // cancel pending switch
    let _ = runtime.bump_geo_epoch(); // abort geo — never probe while down
    {
        let mut status = runtime.status.lock().map_err(|e| e.to_string())?;
        if status.state == ConnectionState::Disconnected {
            return Ok(status.clone());
        }
        status.state = ConnectionState::Disconnecting;
        status.message = "Disconnecting…".into();
        status.public_iso.clear();
        status.public_country.clear();
        status.public_ip4.clear();
        status.socks_proxy = None;
        status.http_proxy = None;
    }
    runtime.push_log("disconnect requested");
    begin_teardown(runtime);

    if !loader::is_available() {
        runtime.with_status(|s| {
            *s = VpnStatus::default();
            s.message = "Disconnected".into();
        });
        runtime.push_log("disconnected (mock)");
    }

    Ok(vpn_status(runtime))
}

fn begin_teardown(runtime: &VpnRuntime) {
    if loader::is_available() {
        session::request_cancel();
        super::proxy::stop(&runtime.proxy_shutdown);
    }
}

fn wait_until_idle(runtime: &VpnRuntime, token: u64) -> bool {
    for _ in 0..150 {
        if runtime.switch_gen() != token {
            return false;
        }
        let state = runtime
            .status
            .lock()
            .map(|s| s.state)
            .unwrap_or(ConnectionState::Disconnected);
        if matches!(
            state,
            ConnectionState::Disconnected | ConnectionState::Error
        ) {
            // Mock path already idle; real path needs a beat after session free.
            thread::sleep(Duration::from_millis(150));
            return runtime.switch_gen() == token;
        }
        // Mock: force idle if still disconnecting without a session thread.
        if !loader::is_available() && state == ConnectionState::Disconnecting {
            runtime.with_status(|s| {
                *s = VpnStatus::default();
                s.message = "Disconnected".into();
            });
            return runtime.switch_gen() == token;
        }
        thread::sleep(Duration::from_millis(200));
    }
    runtime.push_log("switch timed out waiting for disconnect");
    false
}

fn spawn_worker(runtime: Arc<VpnRuntime>, req: ConnectRequest) {
    let worker = Arc::clone(&runtime);
    thread::spawn(move || {
        if loader::is_available() {
            if let Err(e) = session::run_session(worker.clone(), req) {
                worker.push_log(format!("error: {e}"));
                worker.with_status(|s| {
                    if matches!(s.state, ConnectionState::Connecting | ConnectionState::Connected) {
                        s.state = ConnectionState::Error;
                        s.message = e;
                    }
                });
            }
        } else {
            mock_worker(worker, req);
        }
    });
}

fn mock_worker(runtime: Arc<VpnRuntime>, req: ConnectRequest) {
    thread::sleep(Duration::from_millis(700));
    {
        let Ok(status) = runtime.status.lock() else {
            return;
        };
        if matches!(
            status.state,
            ConnectionState::Disconnecting | ConnectionState::Disconnected
        ) {
            return;
        }
        if status.active_profile_id.as_deref() != Some(req.profile_id.as_str()) {
            return;
        }
    }
    runtime.with_status(|s| {
        s.state = ConnectionState::Connected;
        s.active_profile_id = Some(req.profile_id.clone());
        s.network_mode = if req.network_mode == "proxy" {
            "proxy".into()
        } else {
            "tunnel".into()
        };
        if s.network_mode == "proxy" {
            s.message = "Connected (mock proxy) · 127.0.0.1:1080".into();
            s.socks_proxy = Some("127.0.0.1:1080".into());
            s.http_proxy = Some("127.0.0.1:8118".into());
        } else {
            s.message = format!("Connected (mock tunnel) → {}", req.server);
            s.socks_proxy = None;
            s.http_proxy = None;
        }
    });
    runtime.push_log(format!("connected mock → {}", req.server));
    if let Err(e) = super::persist::after_auth_ok(&req) {
        runtime.push_log(format!("login not saved: {e}"));
    }
    super::geo::spawn_after_connect(
        Arc::clone(&runtime),
        req.profile_id.clone(),
        req.app_data.clone(),
        req.network_mode == "proxy",
    );
}
