# OpenConnect +P (Windows)

Tauri + React + **libopenconnect** (runtime DLL load) + Wintun.

## Run

```bat
.\dev.cmd
```

If PowerShell blocks `npm`, `dev.cmd` uses `npm.cmd`.

First time (or after a clean checkout):

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\fetch-native.ps1
```

## Data location

The installer is per-machine and the exe always runs elevated (Wintun).
On domain PCs that often means the **local Administrator** token — whose
`AppData` profile may not exist or may not be writable.

All app data therefore lives in a machine-wide folder:

```
%ProgramData%\OpenConnect-P\
  profiles.json
  settings.json
  secrets\     encrypted passwords (Windows DPAPI)
  flags\
  scripts\
  webview\     WebView2 cache
```

Fallback if ProgramData cannot be created: `%LOCALAPPDATA%\OpenConnect-P`, then `%TEMP%\OpenConnect-P`.
Existing profiles from the old Tauri AppData path are copied once on first launch.

## Modes

- **Tunnel** — default route through VPN (whole PC)
- **Proxy** — no default route; local **SOCKS5 `127.0.0.1:1080`** and **HTTP CONNECT `127.0.0.1:8118`**. Point the app (browser, Telegram, …) at those proxies.

| Toolbar badge | Meaning |
|---------------|---------|
| `libopenconnect` | Real VPN (DLL found) |
| `mock` | UI-only simulation |

Real connect requires **Administrator** for Wintun.

See `BUILD.md`.
