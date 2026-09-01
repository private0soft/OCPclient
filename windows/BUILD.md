# Building / running real OpenConnect on Windows

The app **dynamically loads** `libopenconnect-5.dll` at runtime (works with MSVC Rust).
If the DLL is missing, it falls back to mock mode.

## 1. Fetch native libs

```powershell
cd windows
powershell -ExecutionPolicy Bypass -File .\scripts\fetch-native.ps1
```

This copies LGPL OpenConnect DLLs + `wintun.dll` + `vpnc-script.js` from an installed
[OpenConnect-GUI](https://github.com/openconnect/openconnect-gui/releases) into `vendor/openconnect/bin`.

## 2. Run

```bat
dev.cmd
```

Toolbar should show `libopenconnect` (not `mock`).

## 3. Connect for real

1. The release exe elevates automatically (Wintun). Dev builds do not — run as Administrator if you need a real tunnel.
2. Add a profile, open Status, Connect, enter credentials.
3. Disconnect sends `OC_CMD_CANCEL` on the OpenConnect command pipe.

## Layout

```
vendor/openconnect/bin/   # libopenconnect-5.dll + MinGW deps + wintun + vpnc-script.js
vendor/wintun/            # optional mirror of wintun.dll
vendor/scripts/           # vpnc-script.js
```

`build.rs` copies these next to the debug/release exe during compile.

## Bundle

`tauri.conf.json` includes `vendor/openconnect/bin/*` as resources so packaged installs keep the DLLs.

## Release installer

Edit the three values at the top of `build.cmd`, then run it:

```bat
build.cmd
```

| Variable | Meaning |
|----------|---------|
| `VERSION` | Installer / app version, e.g. `1.0.4` |
| `VERSION_CODE` | Integer for update checks (must increase each release), e.g. `104` |
| `UPDATE_URL` | HTTPS JSON the app checks by default (baked into the exe, not shown in Settings) |

A successful build also:

1. **Moves** the NSIS installer to `windows/OpenConnect +P_latest_x64-setup.exe` (replaces the old file; nothing left in `target\release\bundle\nsis\`)
2. Rewrites `windows/ex.json` (and `ex.json` at the repo root if it exists) from `VERSION` / `VERSION_CODE`

Upload both the installer and `ex.json` to GitHub after each release. Bump `versionCode` only in `build.cmd` — the JSON files follow automatically.

