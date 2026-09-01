//! VPN domain: profiles + connection lifecycle.
//! Uses libopenconnect via runtime DLL load when available; otherwise mock.

pub mod cred;
pub mod ffi;
mod loader;
mod mode;
mod openconnect;
pub mod paths;
mod profile;
mod proxy;
mod request;
mod backup;
mod geo;
mod ipv4_http;
mod settings;
mod update;
pub mod session;
mod persist;
mod state;

pub use openconnect::{
    connect, disconnect, switch_profile, version_label, vpn_logs, vpn_status,
};
pub use profile::{
    add_profile, clear_saved_password, delete_profile, get_profile, list_profiles, set_profile_mode,
    update_profile, Profile, ProfileStore,
};
pub use request::ConnectRequest;
pub use settings::AppSettings;
pub use state::{ConnectionState, VpnRuntime, VpnStatus};
pub use update::{
    check as check_update, download_update, launch_update, snooze as snooze_update, UpdateInfo,
};
pub use backup::{
    export_json as export_profiles_json, import_json as import_profiles_json,
    sync_from_url as sync_catalog_from_url, ImportResult,
};
pub use geo::flag_data_url;