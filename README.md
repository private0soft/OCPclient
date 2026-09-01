# OpenConnect +P

Personal multi-platform OpenConnect client.

## Layout

```
android/   Fork of ics-openconnect (branch: myocapp)
windows/   Tauri + React + libopenconnect
```

## Windows (Tauri)

- Stack: Tauri 2, React, TypeScript, Rust
- VPN: **libopenconnect** via runtime DLL load + Wintun (falls back to mock if DLLs missing)
- Data: `%ProgramData%\OpenConnect-P` (not per-user AppData — required on domain PCs)
- Fetch natives: `windows\scripts\fetch-native.ps1`
- Run: `windows\dev.cmd`

```powershell
cd windows
powershell -ExecutionPolicy Bypass -File .\scripts\fetch-native.ps1
.\dev.cmd
```

## Android per-app VPN

- **Global rules:** Settings → Global per-app rules  
  Mode: all apps / allowlist / denylist + app picker
- **Per profile:** Edit profile → Advanced  
  Policy: *Use global rules* or *Custom for this profile*
- Applied in `VpnService.Builder` via `PerAppVpn` before `establish()`
- Reconnect after changing these settings
