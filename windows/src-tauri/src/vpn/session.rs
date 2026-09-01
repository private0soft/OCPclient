//! Real libopenconnect session via runtime dynload.

use super::ffi::{
    self, oc_auth_form, oc_form_opt, oc_form_opt_select, openconnect_info, OC_CMD_CANCEL,
    OC_FORM_OPT_IGNORE, OC_FORM_OPT_PASSWORD, OC_FORM_OPT_SELECT, OC_FORM_OPT_TEXT,
    OC_FORM_RESULT_CANCELLED, OC_FORM_RESULT_NEWGROUP, OC_FORM_RESULT_OK,
};
use super::loader::{self, OpenConnectApi};
use super::mode::NetworkMode;
use super::request::ConnectRequest;
use super::state::{ConnectionState, VpnRuntime};
use std::ffi::{CStr, CString};
use std::net::Ipv4Addr;
use std::os::raw::{c_char, c_int, c_void};
use std::path::{Path, PathBuf};
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering};
use std::sync::Arc;

static CMD_PIPE: AtomicIsize = AtomicIsize::new(-1);

/// Cisco-compatible identity — many ocserv configs fingerprint UA / OS / version.
const OC_USERAGENT: &str = "AnyConnect Windows 4.10.07061";
const OC_VERSION: &str = "4.10.07061";
const OC_REPORTED_OS: &str = "win";
const PRG_ERR: c_int = 0;
const PRG_INFO: c_int = 1;
const PRG_DEBUG: c_int = 2;

struct SessionPriv {
    runtime: Arc<VpnRuntime>,
    username: CString,
    password: CString,
    group: CString,
    accept_insecure_cert: bool,
    api: &'static OpenConnectApi,
    /// First group selection must return NEWGROUP (same as openconnect CLI).
    authgroup_set: AtomicBool,
    /// Only the first password field gets the stored password.
    password_used: AtomicBool,
    /// Prevent infinite empty-form loops (cert-only authgroup).
    last_form_empty: AtomicBool,
}

pub fn request_cancel() {
    let fd = CMD_PIPE.load(Ordering::SeqCst);
    if fd < 0 {
        return;
    }
    #[cfg(windows)]
    {
        #[link(name = "ws2_32")]
        extern "system" {
            fn send(s: usize, buf: *const u8, len: i32, flags: i32) -> i32;
        }
        let cmd = [OC_CMD_CANCEL];
        unsafe {
            let _ = send(fd as usize, cmd.as_ptr(), 1, 0);
        }
    }
    #[cfg(not(windows))]
    {
        let _ = fd;
    }
}

pub fn run_session(runtime: Arc<VpnRuntime>, req: ConnectRequest) -> Result<(), String> {
    let api = loader::api()?;
    runtime.push_log(format!(
        "libopenconnect {}",
        unsafe {
            let v = (api.get_version)();
            if v.is_null() {
                "?".into()
            } else {
                CStr::from_ptr(v).to_string_lossy().into_owned()
            }
        }
    ));

    unsafe {
        if (api.init_ssl)() != 0 {
            return Err("openconnect_init_ssl failed".into());
        }
    }

    ensure_wintun_beside_dll()?;

    // Must look like AnyConnect to modern ocserv (GitLab openconnect/ocserv).
    let useragent = loader::c_string(OC_USERAGENT)?;
    let url = normalize_url(&req.server)?;
    let protocol = {
        let p = req.protocol.trim();
        loader::c_string(if p.is_empty() { "anyconnect" } else { p })?
    };
    let reported_os = loader::c_string(OC_REPORTED_OS)?;
    let version = loader::c_string(OC_VERSION)?;

    let privdata = Box::new(SessionPriv {
        runtime: Arc::clone(&runtime),
        username: loader::c_string(&req.username)?,
        password: loader::c_string(&req.password)?,
        group: loader::c_string(req.group.trim())?,
        accept_insecure_cert: req.accept_insecure_cert,
        api,
        authgroup_set: AtomicBool::new(false),
        password_used: AtomicBool::new(false),
        last_form_empty: AtomicBool::new(false),
    });
    let priv_ptr = Box::into_raw(privdata) as *mut c_void;

    let vpninfo = unsafe {
        (api.vpninfo_new)(
            useragent.as_ptr(),
            Some(validate_peer_cert),
            None,
            Some(process_auth_form),
            Some(ffi::oc_progress_trampoline),
            priv_ptr,
        )
    };
    if vpninfo.is_null() {
        unsafe {
            drop(Box::from_raw(priv_ptr as *mut SessionPriv));
        }
        return Err("openconnect_vpninfo_new failed".into());
    }

    let result = unsafe {
        session_body(
            api,
            vpninfo,
            &url,
            &protocol,
            &reported_os,
            &version,
            &useragent,
            &runtime.clone(),
            &req,
        )
    };

    unsafe {
        (api.vpninfo_free)(vpninfo);
        drop(Box::from_raw(priv_ptr as *mut SessionPriv));
    }
    CMD_PIPE.store(-1, Ordering::SeqCst);

    // If a switch already moved us to Connecting, do not wipe that state
    // (otherwise the new session would be cancelled by a late "Disconnected").
    runtime.with_status(|s| {
        if matches!(s.state, ConnectionState::Error | ConnectionState::Connecting) {
            return;
        }
        *s = super::state::VpnStatus::default();
        s.message = "Disconnected".into();
    });
    runtime.push_log("session ended");
    result
}

unsafe fn session_body(
    api: &OpenConnectApi,
    vpninfo: *mut openconnect_info,
    url: &CString,
    protocol: &CString,
    reported_os: &CString,
    version: &CString,
    useragent: &CString,
    runtime: &Arc<VpnRuntime>,
    req: &ConnectRequest,
) -> Result<(), String> {
    use super::proxy;
    use std::sync::atomic::Ordering;

    let mode = NetworkMode::parse(&req.network_mode);
    runtime.proxy_shutdown.store(false, Ordering::SeqCst);

    // —— Client identity (ocserv / AnyConnect fingerprint) ——
    // INFO is enough for the UI ring; DEBUG floods and slows obtain_cookie on Windows.
    if let Some(f) = api.set_loglevel {
        f(vpninfo, PRG_INFO);
    }
    if let Some(f) = api.set_useragent {
        let _ = f(vpninfo, useragent.as_ptr());
    }
    if let Some(f) = api.set_version_string {
        let _ = f(vpninfo, version.as_ptr());
    }
    let _ = (api.set_reported_os)(vpninfo, reported_os.as_ptr());
    // XML POST auth is what modern ocserv expects for AnyConnect protocol.
    if let Some(f) = api.set_xmlpost {
        f(vpninfo, 1);
    }
    // Do NOT force PFS — stock openconnect CLI leaves it off; forcing it
    // breaks HTTPS on some ocserv / CDN / middlebox setups.
    if req.accept_insecure_cert {
        if let Some(f) = api.set_allow_insecure_crypto {
            let _ = f(vpninfo, 1);
        }
    }

    if (api.set_protocol)(vpninfo, protocol.as_ptr()) != 0 {
        return Err("unsupported protocol (ocserv uses anyconnect)".into());
    }

    // Must run BEFORE parse_url / obtain_cookie — otherwise libopenconnect may
    // already try AAAA and stall for tens of seconds (Android stays IPv4-fast).
    // Old profiles may still have disable_ipv6=false on disk; force IPv4-only.
    if let Some(f) = api.disable_ipv6 {
        let _ = f(vpninfo);
        runtime.push_log("IPv6 disabled (IPv4-only, Android-like)");
    }
    if !req.use_dtls {
        if let Some(f) = api.disable_dtls {
            let _ = f(vpninfo);
        }
    }

    if (api.parse_url)(vpninfo, url.as_ptr()) != 0 {
        return Err("invalid server URL — use host or host:port".into());
    }

    if !req.sni.trim().is_empty() {
        if let Some(f) = api.set_sni {
            let sni = loader::c_string(req.sni.trim())?;
            let _ = f(vpninfo, sni.as_ptr());
            runtime.push_log(format!("SNI → {}", req.sni.trim()));
        }
    }
    // Android imports often carry a phone filesystem CA path. Applying it here
    // and disabling system trust breaks HTTPS on Windows ("Failed to open HTTPS").
    let ca = req.ca_file.trim();
    if !ca.is_empty() {
        let ca_path = std::path::Path::new(ca);
        if ca_path.is_file() {
            if let Some(f) = api.set_cafile {
                let ca_c = loader::c_string(ca)?;
                let _ = f(vpninfo, ca_c.as_ptr());
                runtime.push_log(format!("CA file → {ca}"));
            }
            if let Some(f) = api.set_system_trust {
                f(vpninfo, 0);
            }
        } else {
            runtime.push_log(format!(
                "skipping missing CA path (likely Android import): {ca}"
            ));
            if let Some(f) = api.set_system_trust {
                f(vpninfo, 1);
            }
        }
    }

    let cmd = (api.setup_cmd_pipe)(vpninfo);
    CMD_PIPE.store(cmd, Ordering::SeqCst);

    runtime.push_log(format!(
        "auth as {} / OS {} / proto {}",
        OC_USERAGENT,
        OC_REPORTED_OS,
        protocol.to_string_lossy()
    ));
    runtime.push_log("authenticating…");
    runtime.set_auth_detail("");
    if (api.obtain_cookie)(vpninfo) != 0 {
        let detail = runtime.take_auth_detail();
        if detail.is_empty() {
            super::persist::after_auth_fail(&req);
            return Err(
                "authentication failed — check username, password, group, and server certificate"
                    .into(),
            );
        }
        // HTTPS open failures happen BEFORE the server can ask for a password.
        let lower = detail.to_ascii_lowercase();
        if lower.contains("https connection") || lower.contains("connect to") {
            return Err(format!(
                "{detail} — could not reach server (DNS/TLS/SNI/firewall). \
                 Try: Accept invalid cert ON, Disable IPv6 OFF, or set SNI to the hostname. \
                 Password is only sent after HTTPS succeeds (same as openconnect CLI)."
            ));
        }
        super::persist::after_auth_fail(&req);
        return Err(format!("authentication failed: {detail}"));
    }
    if let Err(e) = super::persist::after_auth_ok(req) {
        runtime.push_log(format!("login not saved: {e}"));
    }
    if let Some(get_cookie) = api.get_cookie {
        let c = get_cookie(vpninfo);
        if !c.is_null() {
            let cookie = CStr::from_ptr(c).to_string_lossy();
            if !cookie.is_empty() {
                runtime.push_log("cookie obtained");
            }
        }
    }

    runtime.push_log("opening CSTP…");
    if (api.make_cstp)(vpninfo) != 0 {
        return Err("CSTP connection failed".into());
    }

    let script = resolve_vpnc_script(mode, &req.app_data)?;
    let script_c = loader::c_string(&script.to_string_lossy())?;
    let script_ptr = script_c.as_ptr();
    runtime.push_log(format!("vpnc script → {}", script.display()));

    runtime.push_log(match mode {
        NetworkMode::Proxy => "setting up TUN (proxy — no default route)…",
        NetworkMode::Tunnel => "setting up TUN (full tunnel)…",
    });
    if (api.setup_tun)(vpninfo, script_ptr, ptr::null()) != 0 {
        return Err(
            "TUN setup failed. Run the app as Administrator and ensure wintun.dll is present."
                .into(),
        );
    }

    let vpn_ip = read_vpn_ipv4(api, vpninfo);
    if mode == NetworkMode::Proxy {
        let ip = vpn_ip.ok_or("proxy mode: could not read VPN IP")?;
        let proxy_log: proxy::LogFn = {
            let rt = Arc::clone(runtime);
            Arc::new(move |m: &str| rt.push_log(m))
        };
        match proxy::spawn(ip, Arc::clone(&runtime.proxy_shutdown), proxy_log) {
            Ok(()) => {
                runtime.push_log(format!(
                    "proxy ready  SOCKS5 {}  HTTP CONNECT {}",
                    proxy::SOCKS_ADDR,
                    proxy::HTTP_ADDR
                ));
                runtime.push_log(format!(
                    "set the app/browser proxy — traffic is not captured system-wide"
                ));
                runtime.push_log(format!("outbound via TUN {ip}"));
            }
            Err(e) => {
                runtime.push_log(format!("proxy listen failed: {e}"));
                return Err(format!("proxy listen failed: {e}"));
            }
        }
    }

    runtime.with_status(|s| {
        s.state = ConnectionState::Connected;
        s.active_profile_id = Some(req.profile_id.clone());
        s.network_mode = mode.as_str().into();
        if mode == NetworkMode::Proxy {
            s.message = format!("Proxy · {}", proxy::SOCKS_ADDR);
            s.socks_proxy = Some(proxy::SOCKS_ADDR.into());
            s.http_proxy = Some(proxy::HTTP_ADDR.into());
        } else {
            s.message = "Connected (tunnel)".into();
            s.socks_proxy = None;
            s.http_proxy = None;
        }
    });
    runtime.push_log("connected — mainloop");
    super::geo::spawn_after_connect(
        Arc::clone(runtime),
        req.profile_id.clone(),
        req.app_data.clone(),
        mode == NetworkMode::Proxy,
    );

    let _ = (api.mainloop)(vpninfo, 300, 10);
    if mode == NetworkMode::Proxy {
        proxy::stop(&runtime.proxy_shutdown);
    }
    Ok(())
}

unsafe fn read_vpn_ipv4(api: &OpenConnectApi, vpninfo: *mut openconnect_info) -> Option<Ipv4Addr> {
    use super::ffi::oc_ip_info;
    let mut info: *const oc_ip_info = ptr::null();
    let mut cstp = ptr::null();
    let mut dtls = ptr::null();
    if (api.get_ip_info)(vpninfo, &mut info, &mut cstp, &mut dtls) != 0 || info.is_null() {
        return None;
    }
    let addr = (*info).addr;
    if addr.is_null() {
        return None;
    }
    CStr::from_ptr(addr)
        .to_str()
        .ok()?
        .parse()
        .ok()
}

const EMBEDDED_VPNC_PROXY: &str =
    include_str!("../../../vendor/scripts/vpnc-script-proxy.js");
const EMBEDDED_VPNC_TUNNEL: &str = include_str!("../../../vendor/scripts/vpnc-script.js");

/// Always extract the copy compiled into this binary. A stale `vpnc-script-proxy.js`
/// next to the EXE used fire-and-forget netsh and left proxy mode with no TUN route.
fn resolve_vpnc_script(mode: NetworkMode, app_data: &Path) -> Result<PathBuf, String> {
    let (primary, embedded) = match mode {
        NetworkMode::Proxy => ("vpnc-script-proxy.js", EMBEDDED_VPNC_PROXY),
        NetworkMode::Tunnel => ("vpnc-script.js", EMBEDDED_VPNC_TUNNEL),
    };
    let dir = app_data.join("scripts");
    std::fs::create_dir_all(&dir).map_err(|e| format!("app data dir {}: {e}", dir.display()))?;
    let dest = dir.join(primary);
    std::fs::write(&dest, embedded).map_err(|e| format!("extract {primary}: {e}"))?;
    Ok(dest)
}

fn normalize_url(server: &str) -> Result<CString, String> {
    let trimmed = server.trim();
    if trimmed.is_empty() {
        return Err("empty server".into());
    }
    let with_scheme = if trimmed.contains("://") {
        trimmed.to_string()
    } else {
        format!("https://{trimmed}")
    };
    loader::c_string(&with_scheme)
}

fn ensure_wintun_beside_dll() -> Result<(), String> {
    let Some(dll) = loader::probe_dll_path() else {
        return Ok(());
    };
    let Some(dir) = dll.parent() else {
        return Ok(());
    };
    let target = dir.join("wintun.dll");
    if target.is_file() {
        return Ok(());
    }
    let candidates = [
        PathBuf::from(r"C:\Program Files\OpenConnect-GUI\wintun.dll"),
        PathBuf::from(env_vendor_wintun()),
    ];
    for src in candidates {
        if src.is_file() {
            let _ = std::fs::copy(&src, &target);
            if target.is_file() {
                return Ok(());
            }
        }
    }
    Err("wintun.dll missing next to libopenconnect".into())
}

fn env_vendor_wintun() -> String {
    if let Ok(manifest) = std::env::var("CARGO_MANIFEST_DIR") {
        return PathBuf::from(manifest)
            .join("..")
            .join("vendor")
            .join("wintun")
            .join("wintun.dll")
            .display()
            .to_string();
    }
    "vendor/wintun/wintun.dll".into()
}

unsafe extern "C" fn validate_peer_cert(priv_: *mut c_void, reason: *const c_char) -> c_int {
    if priv_.is_null() {
        return 1;
    }
    let session = &*(priv_ as *const SessionPriv);
    let why = if reason.is_null() {
        "certificate verification failed".into()
    } else {
        CStr::from_ptr(reason).to_string_lossy().trim().to_string()
    };
    if session.accept_insecure_cert {
        session
            .runtime
            .push_log(format!("accepting untrusted cert ({why})"));
        0
    } else {
        let lower = why.to_ascii_lowercase();
        let hint = if lower.contains("mismatch")
            || lower.contains("does not match")
            || lower.contains("not match")
            || lower.contains("hostname")
            || lower.contains("name")
        {
            // Common with CDN/fronting hosts like di.us1… while LE cert is for us1…
            "hostname does not match certificate — use the exact name on the cert \
             (e.g. us1.pi1.site not di.us1.pi1.site), or set SNI, or enable Accept invalid cert"
        } else if lower.contains("issuer")
            || lower.contains("unknown ca")
            || lower.contains("not trusted")
            || lower.contains("unable to get local")
        {
            "CA not trusted by this Windows OpenConnect build — enable Accept invalid cert \
             or install/set a CA bundle"
        } else {
            "enable “Accept invalid cert” only if you trust this server"
        };
        session
            .runtime
            .push_log(format!("server certificate not trusted ({why})"));
        session
            .runtime
            .set_auth_detail(format!("untrusted certificate: {why}. {hint}"));
        1
    }
}

unsafe extern "C" fn process_auth_form(priv_: *mut c_void, form: *mut oc_auth_form) -> c_int {
    if priv_.is_null() || form.is_null() {
        return OC_FORM_RESULT_CANCELLED;
    }
    let session = &*(priv_ as *const SessionPriv);
    let form = &mut *form;
    let mut filled = 0u32;

    // Reset per-form password consumption (new form after NEWGROUP).
    session.password_used.store(false, Ordering::SeqCst);

    if let Some(msg) = cstr_opt(form.error) {
        session.runtime.set_auth_detail(msg.clone());
        session.runtime.push_log(format!("auth error: {msg}"));
    }
    if let Some(msg) = cstr_opt(form.message) {
        session.runtime.push_log(format!("auth: {msg}"));
    }
    if let Some(msg) = cstr_opt(form.banner) {
        session.runtime.push_log(msg);
    }
    if let Some(id) = cstr_opt(form.auth_id) {
        session.runtime.push_log(format!("auth_id={id}"));
    }

    // —— Group / authgroup (ocserv + Cisco): must NEWGROUP once ——
    if !form.authgroup_opt.is_null() {
        let sel = &mut *form.authgroup_opt;
        log_group_choices(session, sel);
        let choice = pick_group_choice(sel, session.group.to_str().unwrap_or(""));
        match choice {
            Some(value) => {
                let c = match CString::new(value.as_str()) {
                    Ok(c) => c,
                    Err(_) => return OC_FORM_RESULT_CANCELLED,
                };
                let rc = (session.api.set_option_value)(&mut sel.form, c.as_ptr());
                if rc != 0 {
                    session.runtime.push_log(format!(
                        "authgroup '{value}' not accepted by server choices"
                    ));
                    session
                        .runtime
                        .set_auth_detail(format!("invalid group '{value}'"));
                    return OC_FORM_RESULT_CANCELLED;
                }
                session
                    .runtime
                    .push_log(format!("authgroup → {value}"));
                filled += 1;
                if !session.authgroup_set.swap(true, Ordering::SeqCst) {
                    // Ask libopenconnect to re-fetch the form for this group.
                    session.last_form_empty.store(false, Ordering::SeqCst);
                    return OC_FORM_RESULT_NEWGROUP;
                }
            }
            None => {
                session
                    .runtime
                    .push_log("authgroup present but no usable choice");
            }
        }
    }

    let mut opt = form.opts;
    while !opt.is_null() {
        let o = &mut *opt;
        if o.flags & OC_FORM_OPT_IGNORE != 0 {
            opt = o.next;
            continue;
        }

        let name = cstr_opt(o.name).unwrap_or_default().to_ascii_lowercase();
        let label = cstr_opt(o.label).unwrap_or_default().to_ascii_lowercase();
        session.runtime.push_log(format!(
            "form field type={} name='{name}' label='{label}'",
            o.type_
        ));

        match o.type_ {
            OC_FORM_OPT_SELECT => {
                if !form.authgroup_opt.is_null()
                    && std::ptr::eq(opt, form.authgroup_opt as *mut oc_form_opt)
                {
                    opt = o.next;
                    continue;
                }
                let sel = opt as *mut oc_form_opt_select;
                if let Some(value) =
                    pick_group_choice(&mut *sel, session.group.to_str().unwrap_or(""))
                {
                    if let Ok(c) = CString::new(value.as_str()) {
                        if (session.api.set_option_value)(opt, c.as_ptr()) == 0 {
                            session.runtime.push_log(format!("select {name} → {value}"));
                            filled += 1;
                        }
                    }
                }
            }
            OC_FORM_OPT_TEXT => {
                if is_username_field(&name, &label) {
                    if session.username.as_bytes().is_empty() {
                        session.runtime.push_log("username field empty");
                        session.runtime.set_auth_detail("username required");
                        return OC_FORM_RESULT_CANCELLED;
                    }
                    if (session.api.set_option_value)(opt, session.username.as_ptr()) == 0 {
                        session.runtime.push_log(format!("user → field '{name}'"));
                        filled += 1;
                    }
                } else if is_group_text_field(&name, &label) && !session.group.as_bytes().is_empty()
                {
                    if (session.api.set_option_value)(opt, session.group.as_ptr()) == 0 {
                        session.runtime.push_log(format!("group text → '{name}'"));
                        filled += 1;
                    }
                } else {
                    session
                        .runtime
                        .push_log(format!("skip text field '{name}'"));
                }
            }
            OC_FORM_OPT_PASSWORD => {
                if session.password_used.swap(true, Ordering::SeqCst) {
                    session
                        .runtime
                        .push_log(format!("skip secondary password field '{name}'"));
                } else if session.password.as_bytes().is_empty() {
                    session.runtime.set_auth_detail("password required");
                    return OC_FORM_RESULT_CANCELLED;
                } else if (session.api.set_option_value)(opt, session.password.as_ptr()) == 0 {
                    session
                        .runtime
                        .push_log(format!("password → field '{name}'"));
                    filled += 1;
                }
            }
            _ => {
                // HIDDEN / TOKEN: libopenconnect owns these.
            }
        }
        opt = o.next;
    }

    let empty = filled == 0;
    if session.last_form_empty.swap(empty, Ordering::SeqCst) && empty {
        session
            .runtime
            .push_log("empty auth form twice — cancelling (cert-only group?)");
        session
            .runtime
            .set_auth_detail("empty authentication form");
        return OC_FORM_RESULT_CANCELLED;
    }

    OC_FORM_RESULT_OK
}

fn cstr_opt(p: *mut c_char) -> Option<String> {
    if p.is_null() {
        return None;
    }
    unsafe {
        let s = CStr::from_ptr(p).to_string_lossy().trim().to_string();
        if s.is_empty() {
            None
        } else {
            Some(s)
        }
    }
}

fn is_username_field(name: &str, label: &str) -> bool {
    name.starts_with("user")
        || name == "uname"
        || name.contains("username")
        || label.contains("user name")
        || label.contains("username")
        || (label.starts_with("user") && !label.contains("group"))
}

fn is_group_text_field(name: &str, label: &str) -> bool {
    name.contains("group") || name.contains("authgroup") || label.contains("group")
}

unsafe fn log_group_choices(session: &SessionPriv, sel: &oc_form_opt_select) {
    let n = sel.nr_choices;
    if n <= 0 || sel.choices.is_null() {
        return;
    }
    let mut parts = Vec::new();
    for i in 0..n as isize {
        let ch = *sel.choices.offset(i);
        if ch.is_null() {
            continue;
        }
        let c = &*ch;
        let name = cstr_opt(c.name).unwrap_or_default();
        let label = cstr_opt(c.label).unwrap_or_default();
        if label.is_empty() || label == name {
            parts.push(name);
        } else {
            parts.push(format!("{name} ({label})"));
        }
    }
    if !parts.is_empty() {
        session
            .runtime
            .push_log(format!("authgroup choices: {}", parts.join(", ")));
    }
}

/// Resolve authgroup against server choices (name or label). Empty → first choice.
unsafe fn pick_group_choice(sel: &mut oc_form_opt_select, wanted: &str) -> Option<String> {
    let n = sel.nr_choices;
    if n <= 0 || sel.choices.is_null() {
        let wanted = wanted.trim();
        return if wanted.is_empty() {
            None
        } else {
            Some(wanted.to_string())
        };
    }
    let wanted = wanted.trim();
    if !wanted.is_empty() {
        for i in 0..n as isize {
            let ch = *sel.choices.offset(i);
            if ch.is_null() {
                continue;
            }
            let c = &*ch;
            let name = cstr_opt(c.name).unwrap_or_default();
            let label = cstr_opt(c.label).unwrap_or_default();
            if name.eq_ignore_ascii_case(wanted) || label.eq_ignore_ascii_case(wanted) {
                return Some(if name.is_empty() { label } else { name });
            }
        }
        return Some(wanted.to_string());
    }
    let ch = *sel.choices;
    if ch.is_null() {
        return None;
    }
    let c = &*ch;
    cstr_opt(c.name).or_else(|| cstr_opt(c.label))
}

#[no_mangle]
pub unsafe extern "C" fn rust_oc_progress(priv_: *mut c_void, level: c_int, msg: *const c_char) {
    if priv_.is_null() || msg.is_null() {
        return;
    }
    let session = &*(priv_ as *const SessionPriv);
    if let Ok(text) = CStr::from_ptr(msg).to_str() {
        let line = text.trim();
        if line.is_empty() {
            return;
        }
        // Capture SSL / auth failures for the UI error string.
        if level <= PRG_ERR {
            session.runtime.set_auth_detail(line.to_string());
        }
        if level <= PRG_INFO {
            session.runtime.push_log(line.to_string());
        } else if level == PRG_DEBUG {
            // Keep auth-phase debug breadcrumbs without flooding the ring forever.
            let lower = line.to_ascii_lowercase();
            if lower.contains("auth")
                || lower.contains("cookie")
                || lower.contains("form")
                || lower.contains("xml")
                || lower.contains("ssl")
                || lower.contains("cert")
                || lower.contains("password")
                || lower.contains("user")
            {
                session.runtime.push_log(format!("dbg: {line}"));
            }
        }
    }
}
