use std::env;
use std::path::{Path, PathBuf};

fn main() {
    println!("cargo:rerun-if-changed=windows/app.manifest");
    println!("cargo:rerun-if-changed=windows/app.dev.manifest");
    println!("cargo:rerun-if-changed=windows/hooks.nsh");
    println!("cargo:rerun-if-changed=native/oc_progress_shim.c");
    println!("cargo:rerun-if-env-changed=OCP_UPDATE_URL");
    println!("cargo:rerun-if-env-changed=OCP_VERSION");
    println!("cargo:rerun-if-env-changed=OCP_VERSION_CODE");

    let mut windows = tauri_build::WindowsAttributes::new();
    // Release installer / exe: always elevate (Wintun). Dev: no UAC spam.
    let manifest = if env::var("PROFILE").unwrap_or_default() == "release" {
        include_str!("windows/app.manifest")
    } else {
        include_str!("windows/app.dev.manifest")
    };
    windows = windows.app_manifest(manifest);
    let attrs = tauri_build::Attributes::new().windows_attributes(windows);
    tauri_build::try_build(attrs).expect("failed to run tauri build script");

    embed_update_url();
    embed_version();

    cc::Build::new()
        .file("native/oc_progress_shim.c")
        .compile("oc_progress_shim");

    let manifest = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let vendor_bin = manifest.join("../vendor/openconnect/bin");
    println!("cargo:rerun-if-changed={}", vendor_bin.display());

    let vendor_scripts = manifest.join("../vendor/scripts");
    println!("cargo:rerun-if-changed={}", vendor_scripts.display());

    if let Ok(profile) = env::var("PROFILE") {
        let dest = if let Ok(target) = env::var("CARGO_TARGET_DIR") {
            PathBuf::from(target).join(&profile)
        } else {
            manifest.join("target").join(&profile)
        };
        copy_vendor_bins(&vendor_bin, &dest);
        copy_vendor_bins(&vendor_scripts, &dest);
    }

    if let Ok(out_dir) = env::var("OUT_DIR") {
        let mut dir = PathBuf::from(out_dir);
        for _ in 0..8 {
            if dir
                .file_name()
                .map(|n| n == "debug" || n == "release")
                .unwrap_or(false)
            {
                copy_vendor_bins(&vendor_bin, &dir);
                copy_vendor_bins(&vendor_scripts, &dir);
                break;
            }
            if !dir.pop() {
                break;
            }
        }
    }
}

fn copy_vendor_bins(from: &Path, to: &Path) {
    if !from.is_dir() || !to.is_dir() {
        let _ = std::fs::create_dir_all(to);
    }
    if !from.is_dir() {
        return;
    }
    let Ok(entries) = std::fs::read_dir(from) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path
            .extension()
            .map(|e| e == "dll" || e == "js")
            .unwrap_or(false)
        {
            if let Some(name) = path.file_name() {
                let _ = std::fs::copy(&path, to.join(name));
            }
        }
    }
}

/// Built-in update manifest. Override at compile time with OCP_UPDATE_URL.
/// The default is not stored as a plaintext URL in the repo.
fn embed_update_url() {
    let url = env::var("OCP_UPDATE_URL").unwrap_or_else(|_| decode_default_url());
    let url = url.trim().to_string();
    if let Ok(out) = env::var("OUT_DIR") {
        let path = PathBuf::from(out).join("update_url.txt");
        let _ = std::fs::write(path, url);
    }
}

fn embed_version() {
    let name = env::var("OCP_VERSION")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| env::var("CARGO_PKG_VERSION").unwrap_or_else(|_| "0.0.0".into()));
    let name = name.trim().to_string();
    let code = env::var("OCP_VERSION_CODE")
        .ok()
        .and_then(|s| s.trim().parse::<i32>().ok())
        .unwrap_or(103);
    let Ok(out) = env::var("OUT_DIR") else {
        return;
    };
    let body = format!(
        "pub const VERSION_NAME: &str = {:?};\npub const VERSION_CODE: i32 = {code};\n",
        name
    );
    let _ = std::fs::write(PathBuf::from(out).join("app_version.rs"), body);
}

fn decode_default_url() -> String {
    const KEY: u8 = 0xA5;
    const ENC: &[u8] = &[
        205, 209, 209, 213, 214, 159, 138, 138, 192, 221, 196, 200, 213, 201, 192, 139, 198, 202,
        200, 138, 202, 198, 213, 138, 210, 204, 203, 193, 202, 210, 214, 139, 207, 214, 202, 203,
    ];
    String::from_utf8(ENC.iter().map(|b| b ^ KEY).collect()).unwrap_or_default()
}
