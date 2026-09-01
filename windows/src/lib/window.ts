import { getCurrentWindow } from "@tauri-apps/api/window";

export async function minimizeWindow() {
  await getCurrentWindow().minimize();
}

/** Hide to tray — VPN stays running (same as system ✕ before). */
export async function hideToTray() {
  await getCurrentWindow().hide();
}
