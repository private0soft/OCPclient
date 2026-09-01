import { invoke } from "@tauri-apps/api/core";
import type {
  AppSettings,
  BackupResult,
  Profile,
  SavedLogin,
  StorageInfo,
  UpdateInfo,
  VpnStatus,
} from "../types";

export const getStatus = () => invoke<VpnStatus>("get_status");
export const getLogs = () => invoke<string[]>("get_logs");
export const storageInfo = () => invoke<StorageInfo>("storage_info");
export const listProfiles = () => invoke<Profile[]>("list_profiles");
export const saveProfile = (profile: Profile) =>
  invoke<Profile>("save_profile", { profile });
export const setProfileMode = (id: string, networkMode: string) =>
  invoke<void>("set_profile_mode", { id, networkMode });
export const deleteProfile = (id: string) =>
  invoke<void>("delete_profile", { id });
export const clearSavedPassword = (id: string) =>
  invoke<Profile>("clear_saved_password", { id });
export const connectVpn = (
  profileId: string,
  password?: string,
  savePassword?: boolean,
  saveForDomain?: boolean,
  username?: string,
) =>
  invoke<VpnStatus>("connect_vpn", {
    profileId,
    password: password ?? null,
    savePassword: savePassword ?? null,
    saveForDomain: saveForDomain ?? null,
    username: username ?? null,
  });
export const switchVpn = (
  profileId: string,
  password?: string,
  savePassword?: boolean,
  saveForDomain?: boolean,
  username?: string,
) =>
  invoke<VpnStatus>("switch_vpn", {
    profileId,
    password: password ?? null,
    savePassword: savePassword ?? null,
    saveForDomain: saveForDomain ?? null,
    username: username ?? null,
  });
export const disconnectVpn = () => invoke<VpnStatus>("disconnect_vpn");
export const backendMode = () => invoke<string>("backend_mode");
export const getAppSettings = () => invoke<AppSettings>("get_app_settings");
export const saveAppSettings = (settings: AppSettings) =>
  invoke<AppSettings>("save_app_settings", { settings });
export const checkUpdate = (manual: boolean, viaProxy = false) =>
  invoke<UpdateInfo>("check_update", { manual, viaProxy });
export const startUpdateDownload = (url: string) =>
  invoke<void>("start_update_download", { url });
export const snoozeUpdate = (versionCode: number) =>
  invoke<void>("snooze_update", { versionCode });
export const flagDataUrl = (iso: string) =>
  invoke<string>("flag_data_url", { iso });
export const exportProfiles = (path: string) =>
  invoke<BackupResult>("export_profiles", { path });
export const importProfiles = (path: string) =>
  invoke<BackupResult>("import_profiles", { path });
export const syncCatalog = (url: string) =>
  invoke<BackupResult>("sync_catalog", { url });
export const listSavedLogins = () => invoke<SavedLogin[]>("list_saved_logins");
export const forgetSavedLogin = (kind: string, key: string) =>
  invoke<void>("forget_saved_login", { kind, key });
