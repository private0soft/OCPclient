use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

const LOG_CAP: usize = 200;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Disconnecting,
    Error,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VpnStatus {
    pub state: ConnectionState,
    pub active_profile_id: Option<String>,
    pub message: String,
    pub bytes_in: u64,
    pub bytes_out: u64,
    #[serde(default)]
    pub network_mode: String,
    #[serde(default)]
    pub socks_proxy: Option<String>,
    #[serde(default)]
    pub http_proxy: Option<String>,
    #[serde(default)]
    pub public_iso: String,
    #[serde(default)]
    pub public_country: String,
    #[serde(default)]
    pub public_ip4: String,
}

impl Default for VpnStatus {
    fn default() -> Self {
        Self {
            state: ConnectionState::Disconnected,
            active_profile_id: None,
            message: String::new(),
            bytes_in: 0,
            bytes_out: 0,
            network_mode: "tunnel".into(),
            socks_proxy: None,
            http_proxy: None,
            public_iso: String::new(),
            public_country: String::new(),
            public_ip4: String::new(),
        }
    }
}

/// Process-wide VPN status + ring buffer of log lines for the UI.
pub struct VpnRuntime {
    pub status: Mutex<VpnStatus>,
    pub logs: Mutex<VecDeque<String>>,
    pub proxy_shutdown: Arc<AtomicBool>,
    /// Bumped on disconnect / switch so in-flight geo lookups abort (no leak).
    pub geo_epoch: AtomicU64,
    /// Bumped to cancel an in-flight profile switch worker.
    pub switch_gen: AtomicU64,
    /// Last auth-form error string from the server (for UI).
    pub auth_detail: Mutex<String>,
}

impl VpnRuntime {
    pub fn new() -> Self {
        Self {
            status: Mutex::new(VpnStatus::default()),
            logs: Mutex::new(VecDeque::with_capacity(LOG_CAP)),
            proxy_shutdown: Arc::new(AtomicBool::new(false)),
            geo_epoch: AtomicU64::new(0),
            switch_gen: AtomicU64::new(0),
            auth_detail: Mutex::new(String::new()),
        }
    }

    pub fn set_auth_detail(&self, msg: impl Into<String>) {
        if let Ok(mut g) = self.auth_detail.lock() {
            *g = msg.into();
        }
    }

    pub fn take_auth_detail(&self) -> String {
        self.auth_detail
            .lock()
            .map(|mut g| std::mem::take(&mut *g))
            .unwrap_or_default()
    }

    pub fn bump_geo_epoch(&self) -> u64 {
        self.geo_epoch.fetch_add(1, Ordering::SeqCst) + 1
    }

    pub fn geo_epoch(&self) -> u64 {
        self.geo_epoch.load(Ordering::SeqCst)
    }

    pub fn bump_switch_gen(&self) -> u64 {
        self.switch_gen.fetch_add(1, Ordering::SeqCst) + 1
    }

    pub fn switch_gen(&self) -> u64 {
        self.switch_gen.load(Ordering::SeqCst)
    }

    pub fn push_log(&self, line: impl Into<String>) {
        if let Ok(mut logs) = self.logs.lock() {
            if logs.len() >= LOG_CAP {
                logs.pop_front();
            }
            logs.push_back(line.into());
        }
    }

    pub fn logs_snapshot(&self) -> Vec<String> {
        self.logs
            .lock()
            .map(|g| g.iter().cloned().collect())
            .unwrap_or_default()
    }

    pub fn with_status<F>(&self, f: F)
    where
        F: FnOnce(&mut VpnStatus),
    {
        if let Ok(mut status) = self.status.lock() {
            f(&mut status);
        }
    }
}
