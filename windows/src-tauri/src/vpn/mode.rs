//! Tunnel (full route) vs Proxy (local SOCKS/HTTP, no default route).

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum NetworkMode {
    #[default]
    Tunnel,
    Proxy,
}

impl NetworkMode {
    pub fn parse(s: &str) -> Self {
        match s.trim().to_ascii_lowercase().as_str() {
            "proxy" => Self::Proxy,
            _ => Self::Tunnel,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Tunnel => "tunnel",
            Self::Proxy => "proxy",
        }
    }
}