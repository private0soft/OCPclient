//! Runtime loader for libopenconnect (MSVC-friendly dynload).

use std::ffi::CString;
use std::os::raw::{c_char, c_int, c_uint, c_void};
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use libloading::Library;

use super::ffi::{
    oc_form_opt, oc_ip_info, openconnect_info, openconnect_process_auth_form_vfn,
    openconnect_progress_vfn, openconnect_validate_peer_cert_vfn,
    openconnect_write_new_config_vfn,
};

type FnInitSsl = unsafe extern "C" fn() -> c_int;
type FnVpninfoNew = unsafe extern "C" fn(
    *const c_char,
    openconnect_validate_peer_cert_vfn,
    openconnect_write_new_config_vfn,
    openconnect_process_auth_form_vfn,
    openconnect_progress_vfn,
    *mut c_void,
) -> *mut openconnect_info;
type FnVpninfoFree = unsafe extern "C" fn(*mut openconnect_info);
type FnParseUrl = unsafe extern "C" fn(*mut openconnect_info, *const c_char) -> c_int;
type FnSetProtocol = unsafe extern "C" fn(*mut openconnect_info, *const c_char) -> c_int;
type FnObtainCookie = unsafe extern "C" fn(*mut openconnect_info) -> c_int;
type FnMakeCstp = unsafe extern "C" fn(*mut openconnect_info) -> c_int;
type FnSetupTun =
    unsafe extern "C" fn(*mut openconnect_info, *const c_char, *const c_char) -> c_int;
type FnMainloop = unsafe extern "C" fn(*mut openconnect_info, c_int, c_int) -> c_int;
type FnSetupCmdPipe = unsafe extern "C" fn(*mut openconnect_info) -> isize;
type FnSetOptionValue = unsafe extern "C" fn(*mut oc_form_opt, *const c_char) -> c_int;
type FnGetVersion = unsafe extern "C" fn() -> *const c_char;
type FnSetReportedOs = unsafe extern "C" fn(*mut openconnect_info, *const c_char) -> c_int;
type FnGetIpInfo = unsafe extern "C" fn(
    *mut openconnect_info,
    *mut *const oc_ip_info,
    *mut *const c_void,
    *mut *const c_void,
) -> c_int;
type FnDisableDtls = unsafe extern "C" fn(*mut openconnect_info) -> c_int;
type FnDisableIpv6 = unsafe extern "C" fn(*mut openconnect_info) -> c_int;
type FnSetSni = unsafe extern "C" fn(*mut openconnect_info, *const c_char) -> c_int;
type FnSetCafile = unsafe extern "C" fn(*mut openconnect_info, *const c_char) -> c_int;
type FnSetSystemTrust = unsafe extern "C" fn(*mut openconnect_info, c_uint);
type FnSetUseragent = unsafe extern "C" fn(*mut openconnect_info, *const c_char) -> c_int;
type FnSetVersionString = unsafe extern "C" fn(*mut openconnect_info, *const c_char) -> c_int;
type FnSetXmlpost = unsafe extern "C" fn(*mut openconnect_info, c_int);
type FnSetLoglevel = unsafe extern "C" fn(*mut openconnect_info, c_int);
type FnSetAllowInsecureCrypto = unsafe extern "C" fn(*mut openconnect_info, c_uint) -> c_int;
type FnGetCookie = unsafe extern "C" fn(*mut openconnect_info) -> *const c_char;

pub struct OpenConnectApi {
    _lib: Library,
    pub init_ssl: FnInitSsl,
    pub vpninfo_new: FnVpninfoNew,
    pub vpninfo_free: FnVpninfoFree,
    pub parse_url: FnParseUrl,
    pub set_protocol: FnSetProtocol,
    pub obtain_cookie: FnObtainCookie,
    pub make_cstp: FnMakeCstp,
    pub setup_tun: FnSetupTun,
    pub mainloop: FnMainloop,
    pub setup_cmd_pipe: FnSetupCmdPipe,
    pub set_option_value: FnSetOptionValue,
    pub get_version: FnGetVersion,
    pub set_reported_os: FnSetReportedOs,
    pub get_ip_info: FnGetIpInfo,
    pub disable_dtls: Option<FnDisableDtls>,
    pub disable_ipv6: Option<FnDisableIpv6>,
    pub set_sni: Option<FnSetSni>,
    pub set_cafile: Option<FnSetCafile>,
    pub set_system_trust: Option<FnSetSystemTrust>,
    pub set_useragent: Option<FnSetUseragent>,
    pub set_version_string: Option<FnSetVersionString>,
    pub set_xmlpost: Option<FnSetXmlpost>,
    pub set_loglevel: Option<FnSetLoglevel>,
    pub set_allow_insecure_crypto: Option<FnSetAllowInsecureCrypto>,
    pub get_cookie: Option<FnGetCookie>,
}

static API: OnceLock<Result<OpenConnectApi, String>> = OnceLock::new();

fn candidate_dirs() -> Vec<PathBuf> {
    let mut dirs = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            // Prefer files next to the EXE (flattened NSIS resources).
            dirs.push(dir.to_path_buf());
            dirs.push(dir.join("resources"));
            // Legacy Tauri layout: ../vendor → _up_/vendor/...
            dirs.push(dir.join("_up_").join("vendor").join("openconnect").join("bin"));
            dirs.push(dir.join("vendor").join("openconnect").join("bin"));
        }
    }
    if let Ok(manifest) = std::env::var("CARGO_MANIFEST_DIR") {
        dirs.push(
            PathBuf::from(manifest)
                .join("..")
                .join("vendor")
                .join("openconnect")
                .join("bin"),
        );
    }
    if let Ok(cwd) = std::env::current_dir() {
        dirs.push(cwd.join("vendor").join("openconnect").join("bin"));
        dirs.push(cwd.join("..").join("vendor").join("openconnect").join("bin"));
    }
    if let Ok(extra) = std::env::var("OPENCONNECT_BIN_DIR") {
        dirs.push(PathBuf::from(extra));
    }
    dirs.push(PathBuf::from(r"C:\Program Files\OpenConnect-GUI"));
    dirs
}

fn dll_names() -> &'static [&'static str] {
    &[
        "libopenconnect-5.dll",
        "libopenconnect.dll",
        "openconnect.dll",
    ]
}

pub fn probe_dll_path() -> Option<PathBuf> {
    for dir in candidate_dirs() {
        for name in dll_names() {
            let p = dir.join(name);
            if p.is_file() {
                return Some(p);
            }
        }
    }
    None
}

pub fn is_available() -> bool {
    probe_dll_path().is_some()
}

pub fn api() -> Result<&'static OpenConnectApi, String> {
    match API.get_or_init(load) {
        Ok(api) => Ok(api),
        Err(e) => Err(e.clone()),
    }
}

fn load() -> Result<OpenConnectApi, String> {
    let path = probe_dll_path().ok_or_else(|| {
        "libopenconnect DLL not found. Run scripts/fetch-native.ps1 — see BUILD.md".to_string()
    })?;

    if let Some(dir) = path.parent() {
        prepend_dll_directory(dir);
    }

    let lib =
        unsafe { Library::new(&path) }.map_err(|e| format!("failed to load {}: {e}", path.display()))?;

    unsafe fn sym<T>(lib: &Library, name: &[u8]) -> Result<T, String>
    where
        T: Copy,
    {
        let s = lib.get::<T>(name).map_err(|e| format!("{}: {e}", String::from_utf8_lossy(name)))?;
        Ok(*s)
    }

    unsafe fn optsym<T>(lib: &Library, name: &[u8]) -> Option<T>
    where
        T: Copy,
    {
        lib.get::<T>(name).ok().map(|s| *s)
    }

    unsafe {
        Ok(OpenConnectApi {
            init_ssl: sym(&lib, b"openconnect_init_ssl\0")?,
            vpninfo_new: sym(&lib, b"openconnect_vpninfo_new\0")?,
            vpninfo_free: sym(&lib, b"openconnect_vpninfo_free\0")?,
            parse_url: sym(&lib, b"openconnect_parse_url\0")?,
            set_protocol: sym(&lib, b"openconnect_set_protocol\0")?,
            obtain_cookie: sym(&lib, b"openconnect_obtain_cookie\0")?,
            make_cstp: sym(&lib, b"openconnect_make_cstp_connection\0")?,
            setup_tun: sym(&lib, b"openconnect_setup_tun_device\0")?,
            mainloop: sym(&lib, b"openconnect_mainloop\0")?,
            setup_cmd_pipe: sym(&lib, b"openconnect_setup_cmd_pipe\0")?,
            set_option_value: sym(&lib, b"openconnect_set_option_value\0")?,
            get_version: sym(&lib, b"openconnect_get_version\0")?,
            set_reported_os: sym(&lib, b"openconnect_set_reported_os\0")?,
            get_ip_info: sym(&lib, b"openconnect_get_ip_info\0")?,
            disable_dtls: optsym(&lib, b"openconnect_disable_dtls\0"),
            disable_ipv6: optsym(&lib, b"openconnect_disable_ipv6\0"),
            set_sni: optsym(&lib, b"openconnect_set_sni\0"),
            set_cafile: optsym(&lib, b"openconnect_set_cafile\0"),
            set_system_trust: optsym(&lib, b"openconnect_set_system_trust\0"),
            set_useragent: optsym(&lib, b"openconnect_set_useragent\0"),
            set_version_string: optsym(&lib, b"openconnect_set_version_string\0"),
            set_xmlpost: optsym(&lib, b"openconnect_set_xmlpost\0"),
            set_loglevel: optsym(&lib, b"openconnect_set_loglevel\0"),
            set_allow_insecure_crypto: optsym(&lib, b"openconnect_set_allow_insecure_crypto\0"),
            get_cookie: optsym(&lib, b"openconnect_get_cookie\0"),
            _lib: lib,
        })
    }
}

fn prepend_dll_directory(dir: &Path) {
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        #[link(name = "kernel32")]
        extern "system" {
            fn SetDllDirectoryW(path: *const u16) -> i32;
        }
        let mut wide: Vec<u16> = dir.as_os_str().encode_wide().collect();
        wide.push(0);
        unsafe {
            let _ = SetDllDirectoryW(wide.as_ptr());
        }
        if let Ok(path) = std::env::var("PATH") {
            let prefix = dir.display().to_string();
            if !path.split(';').any(|p| p.eq_ignore_ascii_case(&prefix)) {
                std::env::set_var("PATH", format!("{prefix};{path}"));
            }
        } else {
            std::env::set_var("PATH", dir.display().to_string());
        }
    }
    let _ = dir;
}

pub fn c_string(s: &str) -> Result<CString, String> {
    CString::new(s).map_err(|e| e.to_string())
}
