export type ConnectionState =
  | "disconnected"
  | "connecting"
  | "connected"
  | "disconnecting"
  | "error";

export type NetworkMode = "tunnel" | "proxy";

export type BatchMode = "disabled" | "empty_only" | "enabled";

export interface VpnStatus {
  state: ConnectionState;
  active_profile_id: string | null;
  message: string;
  bytes_in: number;
  bytes_out: number;
  network_mode?: string;
  socks_proxy?: string | null;
  http_proxy?: string | null;
  public_iso?: string;
  public_country?: string;
  public_ip4?: string;
}

export interface StorageInfo {
  data_dir: string;
  profiles_file: string;
  secrets_dir: string;
}

export interface Profile {
  id: string;
  name: string;
  server: string;
  username: string;
  group: string;
  protocol: string;
  network_mode: string;
  use_dtls: boolean;
  disable_ipv6: boolean;
  sni: string;
  ca_file: string;
  accept_insecure_cert: boolean;
  batch_mode: BatchMode | string;
  save_password: boolean;
  use_shared_login?: boolean;
  disable_username_caching: boolean;
  has_saved_password: boolean;
  password_target: string;
  login_domain?: string;
  has_domain_password?: boolean;
  has_own_password?: boolean;
  realm_username?: string;
  geo_iso: string;
  geo_country: string;
  geo_ip4: string;
  source: "user" | "catalog" | string;
}

export interface SavedLogin {
  kind: "profile" | "domain" | string;
  key: string;
  title: string;
  username: string;
}

export interface AppSettings {
  update_manifest_url: string;
  custom_update?: boolean;
  lookup_public_ip: boolean;
  update_last_ms: number;
  update_snooze_code: number;
  theme: "dark" | "light" | string;
  catalog_url?: string;
}

export interface UpdateInfo {
  available: boolean;
  checked?: boolean;
  version_code: number;
  version_name: string;
  notes: string;
  page_url: string;
  installed_name: string;
  installed_code: number;
  message: string;
}

export interface UpdateDownloadEvent {
  state: string;
  percent: number;
  received: number;
  total: number;
  message: string;
}

export interface BackupResult {
  cancelled: boolean;
  message: string;
  imported: number;
  skipped: number;
}

export function emptyProfile(): Profile {
  return {
    id: "",
    name: "",
    server: "",
    username: "",
    group: "",
    protocol: "anyconnect",
    network_mode: "tunnel",
    use_dtls: true,
    disable_ipv6: true,
    sni: "",
    ca_file: "",
    accept_insecure_cert: true,
    batch_mode: "empty_only",
    save_password: true,
    use_shared_login: false,
    disable_username_caching: false,
    has_saved_password: false,
    password_target: "",
    login_domain: "",
    has_domain_password: false,
    has_own_password: false,
    realm_username: "",
    geo_iso: "",
    geo_country: "",
    geo_ip4: "",
    source: "user",
  };
}
