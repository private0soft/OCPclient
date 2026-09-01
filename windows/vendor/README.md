# Vendor binaries (not committed as large DLLs by default)

Place upstream artifacts here when enabling `--features libopenconnect`:

```
vendor/openconnect/include/openconnect.h   # from GitLab openconnect master
vendor/openconnect/lib/libopenconnect.dll
vendor/openconnect/lib/libopenconnect.dll.a  # or .lib import library
vendor/wintun/wintun.dll
```

Upstream header:
https://gitlab.com/openconnect/openconnect/-/raw/master/openconnect.h

Rust FFI in `src-tauri/src/vpn/ffi.rs` mirrors API 5.x from that header.
