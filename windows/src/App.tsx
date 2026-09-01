import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import "./App.css";
import {
  backendMode,
  checkUpdate,
  clearSavedPassword,
  connectVpn,
  deleteProfile,
  disconnectVpn,
  exportProfiles,
  getAppSettings,
  getStatus,
  importProfiles,
  listProfiles,
  listSavedLogins,
  forgetSavedLogin,
  saveAppSettings,
  saveProfile,
  setProfileMode,
  startUpdateDownload,
  switchVpn,
  syncCatalog,
  flagDataUrl,
} from "./lib/vpn";
import {
  emptyProfile,
  type AppSettings,
  type NetworkMode,
  type Profile,
  type SavedLogin,
  type UpdateDownloadEvent,
  type UpdateInfo,
  type VpnStatus,
} from "./types";
import { listen } from "@tauri-apps/api/event";
import { open as pickFile, save as pickSave } from "@tauri-apps/plugin-dialog";
import { hideToTray, minimizeWindow } from "./lib/window";

type Tab = "profiles" | "status";
type View = "main" | "edit" | "settings";
type Sheet = "none" | "auth";

function shortErr(err: unknown): string {
  const t = String(err).replace(/\s+/g, " ").trim();
  return t.length > 160 ? `${t.slice(0, 157)}…` : t;
}

const idleDownload: UpdateDownloadEvent = {
  state: "idle",
  percent: 0,
  received: 0,
  total: 0,
  message: "",
};

function UpdateTag({
  info,
  dl,
  onUpdate,
}: {
  info: UpdateInfo;
  dl: UpdateDownloadEvent;
  onUpdate: () => void;
}) {
  const busy = dl.state === "progress" || dl.state === "done";
  let text = "Update";
  if (dl.state === "progress") {
    text = dl.percent > 0 ? `${dl.percent}%` : "…";
  } else if (dl.state === "done") {
    text = "…";
  } else if (dl.state === "error") {
    text = "Retry";
  }
  const canRun = /^https:\/\//i.test(info.page_url.trim());
  const ver = info.version_name.trim() || `build ${info.version_code}`;
  return (
    <button
      type="button"
      className={`update-tag ${busy ? "busy" : ""}`}
      title={
        dl.state === "error"
          ? dl.message || "Download failed"
          : canRun
            ? `Download ${ver}`
            : "No download link in the update file"
      }
      disabled={busy || !canRun}
      onClick={onUpdate}
    >
      {text}
    </button>
  );
}

const idleStatus: VpnStatus = {
  state: "disconnected",
  active_profile_id: null,
  message: "",
  bytes_in: 0,
  bytes_out: 0,
  network_mode: "tunnel",
  socks_proxy: null,
  http_proxy: null,
};

function stateTitle(state: VpnStatus["state"], message = ""): string {
  if (
    state === "disconnecting" &&
    message.toLowerCase().includes("switching")
  ) {
    return "Switching";
  }
  switch (state) {
    case "connected":
      return "Connected";
    case "connecting":
      return "Connecting";
    case "disconnecting":
      return "Disconnecting";
    case "error":
      return "Error";
    default:
      return "Disconnected";
  }
}

function canSkipAuth(p: Profile): boolean {
  const mode = p.batch_mode || "empty_only";
  if (mode === "disabled") return false;
  const userOk =
    p.disable_username_caching ||
    !!p.username.trim() ||
    (!!p.use_shared_login && !!(p.realm_username || "").trim());
  if (mode === "empty_only") return userOk && p.has_saved_password;
  return mode === "enabled";
}

function isAuthError(message: string): boolean {
  const t = message.toLowerCase();
  return (
    t.includes("authentication failed") ||
    t.includes("check username, password")
  );
}

const flagCache = new Map<string, string>();

function FlagImg({ iso, className }: { iso?: string; className?: string }) {
  const [src, setSrc] = useState("");
  useEffect(() => {
    const id = (iso || "").trim().toLowerCase();
    if (id.length !== 2) {
      setSrc("");
      return;
    }
    const hit = flagCache.get(id);
    if (hit) {
      setSrc(hit);
      return;
    }
    let live = true;
    flagDataUrl(id)
      .then((url) => {
        flagCache.set(id, url);
        if (live) setSrc(url);
      })
      .catch(() => {
        if (live) setSrc("");
      });
    return () => {
      live = false;
    };
  }, [iso]);
  if (!src) return null;
  return <img className={className ?? "flag"} src={src} alt="" />;
}

function IconPlus() {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden>
      <path
        d="M12 5v14M5 12h14"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
      />
    </svg>
  );
}

function IconGear() {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden>
      <path
        d="M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z"
        stroke="currentColor"
        strokeWidth="1.8"
      />
      <path
        d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9c.3.6.9 1 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z"
        stroke="currentColor"
        strokeWidth="1.4"
        strokeLinejoin="round"
      />
    </svg>
  );
}

function IconList() {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden>
      <path
        d="M8 7h12M8 12h12M8 17h12M4 7h.01M4 12h.01M4 17h.01"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
      />
    </svg>
  );
}

function IconStatus() {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden>
      <circle cx="12" cy="12" r="7" stroke="currentColor" strokeWidth="2" />
      <circle cx="12" cy="12" r="3" fill="currentColor" />
    </svg>
  );
}

function IconBack() {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden>
      <path
        d="M15 6 9 12l6 6"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

function IconTrash() {
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden>
      <path
        d="M5 7h14M10 7V5h4v2M8 7v12h8V7"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

function SavedPasswordsPanel({
  items,
  busy,
  onForget,
}: {
  items: SavedLogin[];
  busy: boolean;
  onForget: (login: SavedLogin) => void;
}) {
  const profiles = items.filter((x) => x.kind === "profile");
  const shared = items.filter((x) => x.kind !== "profile");

  if (items.length === 0) {
    return <p className="saved-empty">Nothing saved yet</p>;
  }

  const renderCard = (login: SavedLogin) => (
    <div className="saved-card" key={`${login.kind}:${login.key}`}>
      <div className="row-text">
        <p className="row-title">{login.title}</p>
        <p className="row-sub">
          {login.username || (login.kind === "domain" ? "Shared login" : "Saved login")}
        </p>
      </div>
      <button
        type="button"
        className="row-edit saved-forget"
        title="Forget"
        aria-label={`Forget ${login.title}`}
        disabled={busy}
        onClick={() => onForget(login)}
      >
        <IconTrash />
      </button>
    </div>
  );

  return (
    <div className="saved-stack">
      {profiles.length > 0 && (
        <div className="saved-group">
          <p className="saved-label">Profiles</p>
          {profiles.map(renderCard)}
        </div>
      )}
      {shared.length > 0 && (
        <div className="saved-group">
          <p className="saved-label">Shared on domain</p>
          {shared.map(renderCard)}
        </div>
      )}
    </div>
  );
}

function WindowControls() {
  return (
    <div className="win-controls">
      <button
        type="button"
        className="win-btn"
        title="Minimize"
        onClick={() => void minimizeWindow()}
      >
        <svg viewBox="0 0 12 12" aria-hidden>
          <path d="M2 6h8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
        </svg>
      </button>
      <button
        type="button"
        className="win-btn win-close"
        title="Close to tray"
        onClick={() => void hideToTray()}
      >
        <svg viewBox="0 0 12 12" aria-hidden>
          <path
            d="M3 3l6 6M9 3L3 9"
            stroke="currentColor"
            strokeWidth="1.4"
            strokeLinecap="round"
          />
        </svg>
      </button>
    </div>
  );
}

function IconTheme({ light }: { light: boolean }) {
  if (light) {
    return (
      <svg viewBox="0 0 24 24" fill="none" aria-hidden>
        <path
          d="M12 3v2M12 19v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M3 12h2M19 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
        />
        <circle cx="12" cy="12" r="4" stroke="currentColor" strokeWidth="1.8" />
      </svg>
    );
  }
  return (
    <svg viewBox="0 0 24 24" fill="none" aria-hidden>
      <path
        d="M20 13.5A7.5 7.5 0 1 1 10.5 4 6 6 0 0 0 20 13.5Z"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinejoin="round"
      />
    </svg>
  );
}

function applyTheme(theme: string) {
  document.documentElement.dataset.theme = theme === "light" ? "light" : "dark";
}

function isCatalog(p: Profile) {
  return p.source === "catalog";
}

function App() {
  const [tab, setTab] = useState<Tab>("profiles");
  const [view, setView] = useState<View>("main");
  const [sheet, setSheet] = useState<Sheet>("none");
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [status, setStatus] = useState<VpnStatus>(idleStatus);
  const [backend, setBackend] = useState("…");
  const [appSettings, setAppSettings] = useState<AppSettings | null>(null);
  const [update, setUpdate] = useState<UpdateInfo | null>(null);
  const [dl, setDl] = useState<UpdateDownloadEvent>(idleDownload);
  const [checkHint, setCheckHint] = useState<string | null>(null);
  const [draft, setDraft] = useState<Profile>(emptyProfile());
  const [authUser, setAuthUser] = useState("");
  const [authPass, setAuthPass] = useState("");
  const [authSave, setAuthSave] = useState(true);
  const [authSaveDomain, setAuthSaveDomain] = useState(false);
  const [savedLogins, setSavedLogins] = useState<SavedLogin[]>([]);
  const lastAuthError = useRef("");
  const updateRetry = useRef(true);
  const updateVpnTried = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const selected = useMemo(
    () => profiles.find((p) => p.id === selectedId) ?? null,
    [profiles, selectedId],
  );

  const netMode: NetworkMode =
    selected?.network_mode === "proxy" ? "proxy" : "tunnel";

  const refresh = useCallback(async () => {
    const [nextProfiles, nextStatus, nextMode, nextSettings, nextLogins] =
      await Promise.all([
        listProfiles(),
        getStatus(),
        backendMode(),
        getAppSettings(),
        listSavedLogins().catch(() => [] as SavedLogin[]),
      ]);
    setProfiles(nextProfiles);
    setStatus(nextStatus);
    setBackend(nextMode);
    setAppSettings(nextSettings);
    setSavedLogins(nextLogins);
    applyTheme(nextSettings.theme || "dark");
    setSelectedId((prev) => {
      if (prev && nextProfiles.some((p) => p.id === prev)) return prev;
      if (nextStatus.active_profile_id) return nextStatus.active_profile_id;
      return nextProfiles[0]?.id ?? null;
    });
  }, []);

  useEffect(() => {
    refresh().catch((e) => setError(String(e)));
    checkUpdate(false)
      .then((info) => {
        if (info.available) {
          updateRetry.current = false;
          setUpdate(info);
        } else if (info.checked) {
          updateRetry.current = false;
        }
      })
      .catch(() => undefined);
  }, [refresh]);

  useEffect(() => {
    if (!error) return;
    const t = window.setTimeout(() => setError(null), 3500);
    return () => window.clearTimeout(t);
  }, [error]);

  useEffect(() => {
    let unlisten: (() => void) | undefined;
    void listen<UpdateDownloadEvent>("update-download", (ev) => {
      setDl(ev.payload);
    }).then((fn) => {
      unlisten = fn;
    });
    return () => {
      unlisten?.();
    };
  }, []);

  useEffect(() => {
    if (status.state !== "connected") return;
    if (update?.available) return;
    if (!updateRetry.current || updateVpnTried.current) return;
    updateVpnTried.current = true;
    const viaProxy = status.network_mode === "proxy";
    const wait = viaProxy ? 1400 : 2500;
    const timer = window.setTimeout(() => {
      checkUpdate(false, viaProxy)
        .then((info) => {
          if (info.available) {
            updateRetry.current = false;
            setUpdate(info);
          } else if (info.checked) {
            updateRetry.current = false;
          }
        })
        .catch(() => undefined);
    }, wait);
    return () => window.clearTimeout(timer);
  }, [status.state, status.network_mode, update]);

  useEffect(() => {
    const id = window.setInterval(() => {
      getStatus()
        .then((next) => {
          setStatus(next);
          // Tunnel/proxy geo writes profiles.json; sync list so flags show up.
          if (
            next.state === "connected" &&
            next.public_iso &&
            next.active_profile_id
          ) {
            const pid = next.active_profile_id;
            const iso = next.public_iso;
            const country = next.public_country;
            const ip4 = next.public_ip4;
            setProfiles((prev) =>
              prev.map((p) =>
                p.id === pid
                  ? {
                      ...p,
                      geo_iso: iso || p.geo_iso,
                      geo_country: country || p.geo_country,
                      geo_ip4: ip4 || p.geo_ip4,
                    }
                  : p,
              ),
            );
          }
        })
        .catch(() => undefined);
    }, 1000);
    return () => window.clearInterval(id);
  }, []);

  useEffect(() => {
    if (status.state === "connected") {
      lastAuthError.current = "";
      return;
    }
    if (status.state !== "error" || !isAuthError(status.message || "")) {
      return;
    }
    if (lastAuthError.current === status.message) {
      return;
    }
    lastAuthError.current = status.message;
    void listProfiles()
      .then((next) => {
        setProfiles(next);
        const pid = status.active_profile_id;
        const p =
          next.find((x) => x.id === pid) ??
          next.find((x) => x.id === selectedId) ??
          null;
        if (!p) {
          setSheet("auth");
          return;
        }
        setSelectedId(p.id);
        setAuthUser(p.username || p.realm_username || "");
        setAuthPass("");
        setAuthSave(true);
        setAuthSaveDomain(!!p.use_shared_login);
        setSheet("auth");
      })
      .catch(() => setSheet("auth"));
  }, [status.state, status.message, status.active_profile_id, selectedId]);

  function openNew() {
    setDraft(emptyProfile());
    setShowAdvanced(false);
    setView("edit");
  }

  function openEdit(p: Profile) {
    setDraft({ ...p });
    setShowAdvanced(false);
    setView("edit");
  }

  function closeEdit() {
    setView("main");
    setShowAdvanced(false);
  }

  async function onToggleTheme() {
    const current = appSettings ?? (await getAppSettings());
    const theme = current.theme === "light" ? "dark" : "light";
    applyTheme(theme);
    try {
      const saved = await saveAppSettings({ ...current, theme });
      setAppSettings(saved);
    } catch (err) {
      setError(String(err));
    }
  }

  async function onSaveSettings(e: FormEvent) {
    e.preventDefault();
    if (!appSettings) return;
    setBusy(true);
    setError(null);
    try {
      setAppSettings(await saveAppSettings(appSettings));
      applyTheme(appSettings.theme || "dark");
      setView("main");
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  }

  async function onExportProfiles() {
    setError(null);
    try {
      const picked = await pickSave({
        title: "Export profiles",
        defaultPath: "myoc-profiles.json",
        filters: [{ name: "MyOC profiles", extensions: ["json"] }],
      });
      if (!picked) return;
      setBusy(true);
      const result = await exportProfiles(picked);
      if (result.cancelled) return;
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  }

  async function onImportProfiles() {
    setError(null);
    try {
      const picked = await pickFile({
        title: "Import profiles",
        multiple: false,
        filters: [{ name: "MyOC profiles", extensions: ["json"] }],
      });
      if (!picked || Array.isArray(picked)) return;
      setBusy(true);
      const result = await importProfiles(picked);
      if (!result.cancelled) await refresh();
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  }

  async function onSyncCatalog() {
    const url = (appSettings?.catalog_url ?? "").trim();
    setError(null);
    setBusy(true);
    try {
      await syncCatalog(url);
      if (appSettings) {
        setAppSettings({ ...appSettings, catalog_url: url });
      }
      await refresh();
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  }

  async function onCheckNow() {
    setBusy(true);
    setError(null);
    setCheckHint(null);
    try {
      const info = await checkUpdate(true);
      if (info.available) {
        updateRetry.current = false;
        setUpdate(info);
        setCheckHint(null);
      } else {
        setUpdate(null);
        setCheckHint(info.message || "You are up to date.");
        if (info.checked) updateRetry.current = false;
      }
    } catch (err) {
      setError(shortErr(err));
    } finally {
      setBusy(false);
    }
  }

  async function onInstallUpdate() {
    const href = (update?.page_url ?? "").trim();
    if (!/^https:\/\//i.test(href)) return;
    setDl({ ...idleDownload, state: "progress", message: "Downloading" });
    try {
      await startUpdateDownload(href);
    } catch (err) {
      setDl({
        ...idleDownload,
        state: "error",
        message: String(err),
      });
    }
  }

  function patchDraft<K extends keyof Profile>(key: K, value: Profile[K]) {
    setDraft((d) => ({ ...d, [key]: value }));
  }

  async function onSave(e: FormEvent) {
    e.preventDefault();
    if (!draft.name.trim() || !draft.server.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const saved = await saveProfile({
        ...draft,
        name: draft.name.trim(),
        server: draft.server.trim(),
        protocol: "anyconnect",
        batch_mode: draft.batch_mode || "empty_only",
      });
      closeEdit();
      await refresh();
      setSelectedId(saved.id);
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  }

  async function onDeleteFromEditor() {
    if (!draft.id) {
      closeEdit();
      return;
    }
    if (!window.confirm(`Delete profile “${draft.name}”?`)) return;
    setBusy(true);
    setError(null);
    try {
      await deleteProfile(draft.id);
      closeEdit();
      await refresh();
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  }

  async function onClearPassword() {
    if (!draft.id) return;
    setBusy(true);
    setError(null);
    try {
      const next = await clearSavedPassword(draft.id);
      setDraft(next);
      await refresh();
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  }

  async function startConnect(
    profile: Profile,
    password?: string,
    savePassword?: boolean,
    saveForDomain?: boolean,
    username?: string,
  ) {
    setBusy(true);
    setError(null);
    try {
      setSheet("none");
      setSelectedId(profile.id);
      setTab("status");
      const live =
        status.state === "connected" ||
        status.state === "connecting" ||
        status.state === "disconnecting";
      const switching =
        live && status.active_profile_id !== profile.id;
      setStatus(
        switching
          ? await switchVpn(
              profile.id,
              password,
              savePassword,
              saveForDomain,
              username,
            )
          : await connectVpn(
              profile.id,
              password,
              savePassword,
              saveForDomain,
              username,
            ),
      );
      await refresh();
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  }

  function onConnectClick() {
    if (!selected) return;
    if (canSkipAuth(selected)) {
      void startConnect(selected);
      return;
    }
    setAuthUser(selected.username || selected.realm_username || "");
    setAuthPass("");
    setAuthSave(selected.save_password || !!selected.use_shared_login);
    setAuthSaveDomain(!!selected.use_shared_login);
    setSheet("auth");
  }

  async function onSelectProfile(p: Profile) {
    setSelectedId(p.id);
    setTab("status");
    const switchingNow =
      status.state === "disconnecting" &&
      status.message.toLowerCase().includes("switching");
    const live =
      status.state === "connected" ||
      status.state === "connecting" ||
      switchingNow;
    if (!live) return;
    if (status.active_profile_id === p.id && !switchingNow) return;
    if (canSkipAuth(p)) {
      void startConnect(p);
      return;
    }
    setAuthUser(p.username || p.realm_username || "");
    setAuthPass("");
    setAuthSave(p.save_password || !!p.use_shared_login);
    setAuthSaveDomain(!!p.use_shared_login);
    setSheet("auth");
  }

  async function onAuthSubmit(e: FormEvent) {
    e.preventDefault();
    const target =
      profiles.find((p) => p.id === selectedId) ?? selected;
    if (!target) return;
    await startConnect(
      target,
      authPass,
      authSave || authSaveDomain,
      authSaveDomain,
      authUser,
    );
  }

  async function onDisconnect() {
    setBusy(true);
    setError(null);
    try {
      setStatus(await disconnectVpn());
    } catch (err) {
      setError(String(err));
    } finally {
      setBusy(false);
    }
  }

  const connected = status.state === "connected";
  const connecting =
    status.state === "connecting" || status.state === "disconnecting";

  async function onMode(next: NetworkMode) {
    if (!selected || connected || connecting) return;
    try {
      await setProfileMode(selected.id, next);
      await refresh();
    } catch (err) {
      setError(String(err));
    }
  }

  function copyText(value: string) {
    void navigator.clipboard.writeText(value);
  }

  if (view === "settings") {
    return (
      <div className="app editing">
        <header className="topbar">
          <button
            type="button"
            className="icon-flat"
            title="Back"
            onClick={() => setView("main")}
          >
            <IconBack />
          </button>
          <div className="topbar-titles" data-tauri-drag-region>
            <p className="topbar-title">Settings</p>
            <p className="topbar-sub">App</p>
          </div>
          <WindowControls />
        </header>
        {error && <p className="error">{error}</p>}
        <form id="settings-form" className="page page-edit" onSubmit={onSaveSettings}>
          <p className="pref-cat">Updates</p>
          <label className="check">
            <input
              type="checkbox"
              checked={!!appSettings?.custom_update}
              onChange={(e) =>
                setAppSettings((s) =>
                  s ? { ...s, custom_update: e.target.checked } : s,
                )
              }
            />
            Custom update link
          </label>
          {appSettings?.custom_update ? (
            <>
              <label className="pref-label">Update file URL</label>
              <input
                className="field"
                placeholder="https://"
                value={appSettings?.update_manifest_url ?? ""}
                onChange={(e) =>
                  setAppSettings((s) =>
                    s ? { ...s, update_manifest_url: e.target.value } : s,
                  )
                }
              />
            </>
          ) : (
            <p className="hint">The app checks for updates automatically.</p>
          )}
          <button
            type="button"
            className="btn btn-ghost btn-compact"
            style={{ marginTop: 8 }}
            disabled={busy}
            onClick={() => void onCheckNow()}
          >
            Check now
          </button>
          {checkHint && <p className="update-hint">{checkHint}</p>}
          {update?.available && (
            <div style={{ marginTop: 8 }}>
              <UpdateTag info={update} dl={dl} onUpdate={() => void onInstallUpdate()} />
            </div>
          )}

          <p className="pref-cat">Location</p>
          <label className="check">
            <input
              type="checkbox"
              checked={appSettings?.lookup_public_ip ?? true}
              onChange={(e) =>
                setAppSettings((s) =>
                  s ? { ...s, lookup_public_ip: e.target.checked } : s,
                )
              }
            />
            Look up public IP and country after connect
          </label>

          <p className="pref-cat">Profiles</p>
          <p className="hint">
            Export writes only name and server. Passwords stay on this PC.
          </p>
          <div style={{ display: "flex", gap: 8, marginTop: 8, flexWrap: "wrap" }}>
            <button
              type="button"
              className="btn btn-ghost btn-compact"
              disabled={busy}
              onClick={() => void onExportProfiles()}
            >
              Export…
            </button>
            <button
              type="button"
              className="btn btn-ghost btn-compact"
              disabled={busy}
              onClick={() => void onImportProfiles()}
            >
              Import…
            </button>
          </div>
          <label className="pref-label">Online list</label>
          <input
            className="field"
            placeholder="https://raw.githubusercontent.com/USER/REPO/main/profiles.json"
            value={appSettings?.catalog_url ?? ""}
            onChange={(e) =>
              setAppSettings((s) =>
                s ? { ...s, catalog_url: e.target.value } : s,
              )
            }
          />
          <pre className="hint-code">{`{
  "profiles": [
    { "name": "Germany", "server": "de.example.com" }
  ]
}`}</pre>
          <p className="hint">
            Host that JSON (not the app-update file with versionCode). Paste
            the Raw https:// URL. Sync adds, renames, or removes list
            profiles. Profiles you created stay.
          </p>
          <button
            type="button"
            className="btn btn-ghost btn-compact"
            style={{ marginTop: 8 }}
            disabled={busy}
            onClick={() => void onSyncCatalog()}
          >
            Sync now
          </button>

          <p className="pref-cat">Saved passwords</p>
          <SavedPasswordsPanel
            items={savedLogins}
            busy={busy}
            onForget={(login) => {
              void (async () => {
                try {
                  await forgetSavedLogin(login.kind, login.key);
                  await refresh();
                } catch (err) {
                  setError(String(err));
                }
              })();
            }}
          />

          <p className="pref-cat">Appearance</p>
          <div className="mode-switch" style={{ marginTop: 0 }}>
            <button
              type="button"
              className={appSettings?.theme !== "light" ? "on" : ""}
              onClick={() => {
                applyTheme("dark");
                setAppSettings((s) => (s ? { ...s, theme: "dark" } : s));
              }}
            >
              Dark
            </button>
            <button
              type="button"
              className={appSettings?.theme === "light" ? "on" : ""}
              onClick={() => {
                applyTheme("light");
                setAppSettings((s) => (s ? { ...s, theme: "light" } : s));
              }}
            >
              Light
            </button>
          </div>

          <p className="pref-cat">Tray</p>
          <p className="hint">
            Closing the window hides to the notification area (next to the
            clock). VPN stays up. Quit from the tray menu to exit.
          </p>
        </form>
        <div className="edit-actions">
          <button
            type="submit"
            form="settings-form"
            className="btn btn-primary"
            disabled={busy}
          >
            Save
          </button>
        </div>
      </div>
    );
  }

  if (view === "edit") {
    return (
      <div className="app editing">
        <header className="topbar">
          <button
            type="button"
            className="icon-flat"
            title="Back"
            onClick={closeEdit}
          >
            <IconBack />
          </button>
          <div className="topbar-titles" data-tauri-drag-region>
            <p className="topbar-title">
              {draft.id ? draft.name || "Profile" : "Add profile"}
            </p>
            <p className="topbar-sub">ocserv / AnyConnect</p>
          </div>
          <WindowControls />
        </header>

        {error && <p className="error">{error}</p>}

        <form id="edit-form" className="page page-edit" onSubmit={onSave}>
          <p className="pref-cat">Server</p>
          <label className="pref-label">Profile name</label>
          <input
            className="field"
            value={draft.name}
            onChange={(e) => patchDraft("name", e.target.value)}
            autoFocus
          />
          <label className="pref-label">Server address</label>
          <input
            className="field"
            placeholder="vpn.example.com:443"
            value={draft.server}
            onChange={(e) => patchDraft("server", e.target.value)}
          />
          {isCatalog(draft) && (
            <p className="hint">
              From the online list. A sync can rename or remove this profile.
            </p>
          )}
          <label className="check" style={{ marginTop: 10 }}>
            <input
              type="checkbox"
              checked={draft.accept_insecure_cert}
              onChange={(e) =>
                patchDraft("accept_insecure_cert", e.target.checked)
              }
            />
            Accept invalid certificate
          </label>

          <p className="pref-cat">Authentication</p>
          <label className="pref-label">Username</label>
          <input
            className="field"
            value={draft.username}
            onChange={(e) => patchDraft("username", e.target.value)}
            disabled={draft.disable_username_caching}
          />
          <label className="pref-label">Group</label>
          <input
            className="field"
            placeholder="optional"
            value={draft.group}
            onChange={(e) => patchDraft("group", e.target.value)}
          />
          <label className="pref-label">Batch mode</label>
          <select
            className="field"
            value={draft.batch_mode || "empty_only"}
            onChange={(e) => patchDraft("batch_mode", e.target.value)}
          >
            <option value="disabled">Always prompt</option>
            <option value="empty_only">Prompt for empty fields only</option>
            <option value="enabled">Never prompt</option>
          </select>
          <label className="check" style={{ marginTop: 10 }}>
            <input
              type="checkbox"
              checked={draft.save_password}
              onChange={(e) => patchDraft("save_password", e.target.checked)}
            />
            Remember password for this profile
          </label>
          {draft.login_domain ? (
            <label className="check">
              <input
                type="checkbox"
                checked={!!draft.use_shared_login}
                onChange={(e) =>
                  patchDraft("use_shared_login", e.target.checked)
                }
              />
              Use shared login for {draft.login_domain}
            </label>
          ) : null}
          {(draft.has_own_password || draft.has_saved_password) && (
            <button
              type="button"
              className="btn btn-ghost btn-compact"
              style={{ marginTop: 8 }}
              onClick={onClearPassword}
              disabled={busy}
            >
              Forget saved password
            </button>
          )}

          <p className="pref-cat">Connection</p>
          <div className="mode-switch" style={{ marginTop: 0 }}>
            <button
              type="button"
              className={draft.network_mode !== "proxy" ? "on" : ""}
              onClick={() => patchDraft("network_mode", "tunnel")}
            >
              Tunnel
            </button>
            <button
              type="button"
              className={draft.network_mode === "proxy" ? "on" : ""}
              onClick={() => patchDraft("network_mode", "proxy")}
            >
              Proxy
            </button>
          </div>
          <p className="hint">
            {draft.network_mode === "proxy"
              ? "System routing stays. Apps use SOCKS5 :1080 / HTTP :8118."
              : "Full tunnel — default route goes through the VPN."}
          </p>

          <button
            type="button"
            className="advanced-toggle"
            onClick={() => setShowAdvanced((v) => !v)}
          >
            {showAdvanced ? "Hide advanced" : "Advanced"}
          </button>

          {showAdvanced && (
            <>
              <label className="pref-label">CA file</label>
              <input
                className="field"
                placeholder="optional path"
                value={draft.ca_file}
                onChange={(e) => patchDraft("ca_file", e.target.value)}
              />
              <label className="pref-label">SNI</label>
              <input
                className="field"
                placeholder="optional"
                value={draft.sni}
                onChange={(e) => patchDraft("sni", e.target.value)}
              />
              <label className="check" style={{ marginTop: 10 }}>
                <input
                  type="checkbox"
                  checked={draft.use_dtls}
                  onChange={(e) => patchDraft("use_dtls", e.target.checked)}
                />
                DTLS
              </label>
              <label className="check">
                <input
                  type="checkbox"
                  checked={draft.disable_ipv6}
                  onChange={(e) =>
                    patchDraft("disable_ipv6", e.target.checked)
                  }
                />
                Disable IPv6
              </label>
              <label className="check">
                <input
                  type="checkbox"
                  checked={draft.disable_username_caching}
                  onChange={(e) =>
                    patchDraft("disable_username_caching", e.target.checked)
                  }
                />
                Do not cache username
              </label>
            </>
          )}
        </form>

        <div className="edit-actions">
          {draft.id ? (
            <button
              type="button"
              className="btn btn-danger"
              onClick={onDeleteFromEditor}
              disabled={busy}
            >
              Delete profile
            </button>
          ) : null}
          <button
            type="submit"
            form="edit-form"
            className="btn btn-primary"
            disabled={busy}
          >
            Save
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="app">
      <header className="topbar">
        <div className="topbar-titles" data-tauri-drag-region>
          <p className="topbar-title">OpenConnect +P</p>
          <p className="topbar-sub">{backend}</p>
        </div>
        <div className="topbar-actions">
          {update?.available && (
            <UpdateTag info={update} dl={dl} onUpdate={() => void onInstallUpdate()} />
          )}
          {tab === "profiles" && (
            <button
              type="button"
              className="icon-flat"
              title="Add profile"
              onClick={openNew}
            >
              <IconPlus />
            </button>
          )}
          <button
            type="button"
            className="icon-flat"
            title={
              appSettings?.theme === "light"
                ? "Switch to dark"
                : "Switch to light"
            }
            onClick={() => void onToggleTheme()}
          >
            <IconTheme light={appSettings?.theme !== "light"} />
          </button>
          <button
            type="button"
            className="icon-flat"
            title="Settings"
            onClick={() => setView("settings")}
          >
            <IconGear />
          </button>
          <WindowControls />
        </div>
      </header>

      {error && <p className="error">{error}</p>}

      {tab === "profiles" ? (
        <main className="page">
          {profiles.length === 0 ? (
            <div className="empty">
              <h2>No VPN profiles</h2>
              <p>Tap + to add an ocserv server</p>
            </div>
          ) : (
            <ul className="list">
              {profiles.map((p) => {
                const isActive =
                  status.active_profile_id === p.id && connected;
                const isSelected = selectedId === p.id;
                return (
                  <li key={p.id}>
                    <div
                      className={`row ${isSelected ? "active" : ""}`}
                      role="button"
                      tabIndex={0}
                      onClick={() => {
                        void onSelectProfile(p);
                      }}
                      onKeyDown={(ev) => {
                        if (ev.key === "Enter") {
                          void onSelectProfile(p);
                        }
                      }}
                    >
                      <div className="row-avatar">
                        <span className={`row-dot ${isActive ? "" : "off"}`} />
                        <FlagImg iso={p.geo_iso} />
                      </div>
                      <div className="row-text">
                        <p className="row-title">{p.name}</p>
                        <p className="row-sub">
                          {p.geo_country || p.server}
                          {p.network_mode === "proxy" ? " · Proxy" : ""}
                          {isCatalog(p) ? " · Online" : ""}
                        </p>
                      </div>
                      <button
                        type="button"
                        className="row-edit"
                        title="Configure"
                        onClick={(ev) => {
                          ev.stopPropagation();
                          openEdit(p);
                        }}
                      >
                        <IconGear />
                      </button>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </main>
      ) : (
        <main
          className={`page page-status ${
            connected ? "is-on" : connecting ? "is-busy" : "is-off"
          }`}
        >
          <div className="status-scroll">
            <h1 className="page-heading">Status</h1>
            <div className="orb-block">
              <div
                className={`orb ${
                  connecting
                    ? "busy"
                    : connected
                      ? "on"
                      : status.state === "error"
                        ? "err"
                        : "off"
                }`}
                aria-hidden
              >
                <span className="orb-glow" />
                <span className="orb-outer" />
                <span className="orb-inner">
                  <FlagImg
                    iso={status.public_iso || selected?.geo_iso}
                    className="flag-orb"
                  />
                  {!(status.public_iso || selected?.geo_iso) && (
                    <span className="orb-fallback">
                      {connected ? "ON" : connecting ? "…" : "OFF"}
                    </span>
                  )}
                </span>
              </div>
              <p
                className={`state-label ${connected ? "on" : connecting ? "busy" : "off"}`}
              >
                {stateTitle(status.state, status.message)}
              </p>
              <p className="state-hint">
                {status.public_country ||
                  status.message ||
                  (selected ? selected.name : "Select a profile")}
              </p>
            </div>

            <div className="mode-switch">
              <button
                type="button"
                className={netMode === "tunnel" ? "on" : ""}
                disabled={busy || connecting || connected}
                onClick={() => onMode("tunnel")}
              >
                Tunnel
              </button>
              <button
                type="button"
                className={netMode === "proxy" ? "on" : ""}
                disabled={busy || connecting || connected}
                onClick={() => onMode("proxy")}
              >
                Proxy
              </button>
            </div>

            {selected && (
              <div className="info-card">
                <div className="info-row">
                  <span>Server</span>
                  <span>{selected.server}</span>
                </div>
                {selected.group ? (
                  <div className="info-row">
                    <span>Group</span>
                    <span>{selected.group}</span>
                  </div>
                ) : null}
                {(status.public_ip4 || selected.geo_ip4) && (
                  <div className="info-row">
                    <span>Public IP</span>
                    <span>{status.public_ip4 || selected.geo_ip4}</span>
                  </div>
                )}
                {connected ? (
                  <p className="hint">
                    Close the window to keep running next to the clock. Quit
                    from the tray menu.
                  </p>
                ) : null}
                {(status.socks_proxy || status.http_proxy) && (
                  <>
                    {status.socks_proxy && (
                      <div className="info-row">
                        <span>SOCKS5</span>
                        <button
                          type="button"
                          className="copy"
                          onClick={() => copyText(status.socks_proxy || "")}
                        >
                          {status.socks_proxy}
                        </button>
                      </div>
                    )}
                    {status.http_proxy && (
                      <div className="info-row">
                        <span>HTTP</span>
                        <button
                          type="button"
                          className="copy"
                          onClick={() => copyText(status.http_proxy || "")}
                        >
                          {status.http_proxy}
                        </button>
                      </div>
                    )}
                    <p className="hint">
                      Set these in the browser — system traffic is not redirected.
                    </p>
                  </>
                )}
              </div>
            )}
          </div>

          <div className="status-actions">
            {connected || connecting ? (
              <button
                type="button"
                className="btn btn-danger"
                disabled={busy || status.state === "disconnecting"}
                onClick={onDisconnect}
              >
                Disconnect
              </button>
            ) : (
              <button
                type="button"
                className="btn btn-primary"
                disabled={busy || !selected}
                onClick={onConnectClick}
              >
                Connect
              </button>
            )}
          </div>
        </main>
      )}

      <nav className="nav">
        <button
          type="button"
          className={tab === "profiles" ? "active" : ""}
          onClick={() => setTab("profiles")}
        >
          <IconList />
          Profiles
        </button>
        <button
          type="button"
          className={tab === "status" ? "active" : ""}
          onClick={() => setTab("status")}
        >
          <IconStatus />
          Status
        </button>
      </nav>

      {sheet === "auth" && (
        <div className="modal-backdrop" onClick={() => setSheet("none")}>
          <form
            className="sheet"
            onClick={(e) => e.stopPropagation()}
            onSubmit={onAuthSubmit}
          >
            <div className="sheet-handle" />
            <h3>Sign in</h3>
            <input
              className="field"
              placeholder="Username"
              value={authUser}
              onChange={(e) => setAuthUser(e.target.value)}
              autoComplete="username"
              autoFocus
            />
            <input
              className="field"
              type="password"
              placeholder="Password"
              value={authPass}
              onChange={(e) => setAuthPass(e.target.value)}
              autoComplete="current-password"
            />
            <label className="check">
              <input
                type="checkbox"
                checked={authSave}
                onChange={(e) => setAuthSave(e.target.checked)}
              />
              Save password for this profile
            </label>
            {(
              (profiles.find((p) => p.id === selectedId) ?? selected)
                ?.login_domain || ""
            ) ? (
              <label className="check">
                <input
                  type="checkbox"
                  checked={authSaveDomain}
                  onChange={(e) => {
                    setAuthSaveDomain(e.target.checked);
                    if (e.target.checked) setAuthSave(true);
                  }}
                />
                Also use for other profiles on{" "}
                {
                  (profiles.find((p) => p.id === selectedId) ?? selected)
                    ?.login_domain
                }
              </label>
            ) : null}
            <p className="hint">
              Sharing is optional. Each profile can keep its own password.
            </p>
            <div className="sheet-actions">
              <button
                type="button"
                className="btn btn-ghost"
                onClick={() => setSheet("none")}
              >
                Cancel
              </button>
              <button type="submit" className="btn btn-primary" disabled={busy}>
                Connect
              </button>
            </div>
      </form>
        </div>
      )}
    </div>
  );
}

export default App;
