//! Persist VPN secrets only after the server accepted them.

use super::cred;
use super::profile::ProfileStore;
use super::request::ConnectRequest;

pub fn after_auth_ok(req: &ConnectRequest) -> Result<(), String> {
    if !req.save_password && !req.save_for_domain {
        return Ok(());
    }

    let mut shared = false;
    if req.save_for_domain {
        if let Some(domain) = cred::login_domain(&req.server) {
            if !req.password.is_empty() {
                match cred::write_realm(&domain, &req.username, &req.password) {
                    Ok(()) => {
                        cred::delete(&req.profile_id);
                        shared = true;
                        if let Ok(store) = ProfileStore::open(req.app_data.clone()) {
                            let _ = store.set_shared_for_domain(&domain, true);
                        }
                    }
                    Err(e) => {
                        eprintln!("realm save: {e}");
                    }
                }
            }
        }
    }

    if !shared && !req.password.is_empty() {
        cred::write(&req.profile_id, &req.username, &req.password)?;
    }

    let store = ProfileStore::open(req.app_data.clone())?;
    store.remember_login(&req.profile_id, &req.username, shared)?;
    Ok(())
}

pub fn after_auth_fail(req: &ConnectRequest) {
    cred::delete(&req.profile_id);
    if req.save_for_domain || req.used_shared {
        if let Some(domain) = cred::login_domain(&req.server) {
            cred::delete_realm(&domain);
        }
    }
}
