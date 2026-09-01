//! Manual FFI surface for libopenconnect (API 5.x from upstream GitLab header).
//! Source of truth: https://gitlab.com/openconnect/openconnect/-/blob/master/openconnect.h

#![allow(non_camel_case_types, dead_code)]

use std::os::raw::{c_char, c_int, c_uint, c_void};

#[repr(C)]
pub struct openconnect_info {
    _private: [u8; 0],
}

#[repr(C)]
pub struct oc_form_opt {
    pub next: *mut oc_form_opt,
    pub type_: c_int,
    pub name: *mut c_char,
    pub label: *mut c_char,
    pub _value: *mut c_char,
    pub flags: c_uint,
    pub reserved: *mut c_void,
}

#[repr(C)]
pub struct oc_choice {
    pub name: *mut c_char,
    pub label: *mut c_char,
    pub auth_type: *mut c_char,
    pub override_name: *mut c_char,
    pub override_label: *mut c_char,
}

#[repr(C)]
pub struct oc_form_opt_select {
    pub form: oc_form_opt,
    pub nr_choices: c_int,
    pub choices: *mut *mut oc_choice,
}

#[repr(C)]
pub struct oc_auth_form {
    pub banner: *mut c_char,
    pub message: *mut c_char,
    pub error: *mut c_char,
    pub auth_id: *mut c_char,
    pub method: *mut c_char,
    pub action: *mut c_char,
    pub opts: *mut oc_form_opt,
    pub authgroup_opt: *mut oc_form_opt_select,
    pub authgroup_selection: c_int,
}

pub const OC_FORM_OPT_TEXT: c_int = 1;
pub const OC_FORM_OPT_PASSWORD: c_int = 2;
pub const OC_FORM_OPT_SELECT: c_int = 3;
pub const OC_FORM_OPT_HIDDEN: c_int = 4;
pub const OC_FORM_OPT_TOKEN: c_int = 5;

pub const OC_FORM_RESULT_ERR: c_int = -1;
pub const OC_FORM_RESULT_OK: c_int = 0;
pub const OC_FORM_RESULT_CANCELLED: c_int = 1;
pub const OC_FORM_RESULT_NEWGROUP: c_int = 2;

pub const OC_FORM_OPT_IGNORE: c_uint = 0x0001;
pub const OC_CMD_CANCEL: u8 = b'x';

#[repr(C)]
pub struct oc_ip_info {
    pub addr: *const c_char,
    pub netmask: *const c_char,
    pub addr6: *const c_char,
    pub netmask6: *const c_char,
    pub dns: [*const c_char; 3],
    pub nbns: [*const c_char; 3],
    pub domain: *const c_char,
    pub proxy_pac: *const c_char,
    pub mtu: c_int,
    pub split_dns: *mut c_void,
    pub split_includes: *mut c_void,
    pub split_excludes: *mut c_void,
    pub gateway_addr: *mut c_char,
}

pub type openconnect_validate_peer_cert_vfn =
    Option<unsafe extern "C" fn(*mut c_void, *const c_char) -> c_int>;
pub type openconnect_write_new_config_vfn =
    Option<unsafe extern "C" fn(*mut c_void, *const c_char, c_int) -> c_int>;
pub type openconnect_process_auth_form_vfn =
    Option<unsafe extern "C" fn(*mut c_void, *mut oc_auth_form) -> c_int>;
pub type openconnect_progress_vfn =
    Option<unsafe extern "C" fn(*mut c_void, c_int, *const c_char, ...)>;

extern "C" {
    pub fn openconnect_vpninfo_new(
        useragent: *const c_char,
        validate: openconnect_validate_peer_cert_vfn,
        write_new_config: openconnect_write_new_config_vfn,
        process_auth_form: openconnect_process_auth_form_vfn,
        progress: openconnect_progress_vfn,
        privdata: *mut c_void,
    ) -> *mut openconnect_info;

    pub fn openconnect_vpninfo_free(vpninfo: *mut openconnect_info);
    pub fn openconnect_init_ssl() -> c_int;
    pub fn openconnect_parse_url(vpninfo: *mut openconnect_info, url: *const c_char) -> c_int;
    pub fn openconnect_set_protocol(
        vpninfo: *mut openconnect_info,
        protocol: *const c_char,
    ) -> c_int;
    pub fn openconnect_obtain_cookie(vpninfo: *mut openconnect_info) -> c_int;
    pub fn openconnect_make_cstp_connection(vpninfo: *mut openconnect_info) -> c_int;
    pub fn openconnect_setup_tun_device(
        vpninfo: *mut openconnect_info,
        vpnc_script: *const c_char,
        ifname: *const c_char,
    ) -> c_int;
    pub fn openconnect_mainloop(
        vpninfo: *mut openconnect_info,
        reconnect_timeout: c_int,
        reconnect_interval: c_int,
    ) -> c_int;
    pub fn openconnect_set_option_value(opt: *mut oc_form_opt, value: *const c_char) -> c_int;
    pub fn openconnect_get_version() -> *const c_char;
    pub fn openconnect_setup_cmd_pipe(vpninfo: *mut openconnect_info) -> isize;
}

// Provided by `native/oc_progress_shim.c` (only linked with `libopenconnect`).
extern "C" {
    pub fn oc_progress_trampoline(priv_: *mut c_void, level: c_int, fmt: *const c_char, ...);
}