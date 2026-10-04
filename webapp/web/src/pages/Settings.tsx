import { useEffect, useState } from "react";
import { api, ApiError } from "../api";
import type { Settings as SettingsModel, Status } from "../types";
import { Segmented, Spinner, Toggle } from "../components/ui";
import { ExternalLinkIcon, LockIcon, PrinterIcon, SettingsIcon, ShareIcon, ShieldIcon } from "../components/Icons";
import { ACCENTS, THEME_MODES, type ThemePrefs } from "../theme";

type Notify = (text: string, kind?: "info" | "success" | "error") => void;

const DEFAULT_SETTINGS: SettingsModel = {
  advertiseEnabled: true,
  airprintCompat: true,
  tlsEnabled: false,
  authRequired: false,
};

export function Settings({
  status,
  notify,
  refreshStatus,
  theme,
  onThemeChange,
}: {
  status: Status | null;
  notify: Notify;
  refreshStatus: () => void;
  theme: ThemePrefs;
  onThemeChange: (prefs: ThemePrefs) => void;
}) {
  const [settings, setSettings] = useState<SettingsModel>(DEFAULT_SETTINGS);
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);

  const load = async () => {
    try {
      setSettings(await api.settings());
    } catch {
      // Handled by the app-wide connection banner.
    }
  };

  // Retry until the settings actually load, so a transient failure does not
  // leave the toggles showing defaults forever.
  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const attempt = async (tries: number) => {
      try {
        const loaded = await api.settings();
        if (!cancelled) setSettings(loaded);
      } catch {
        if (!cancelled && tries < 20) timer = setTimeout(() => void attempt(tries + 1), 3000);
      }
    };
    void attempt(0);
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, []);

  const patch = async (key: "advertiseEnabled" | "airprintCompat" | "tlsEnabled", value: boolean) => {
    // Update the UI immediately so the switch always responds to a tap.
    setSettings((current) => ({ ...current, [key]: value }));
    try {
      await api.updateSettings({ [key]: value });
      refreshStatus();
      notify("Settings updated", "success");
    } catch (error) {
      notify(error instanceof ApiError ? error.message : "Could not save settings", "error");
      await load();
    }
  };

  const savePassword = async (value: string) => {
    setBusy(true);
    try {
      const result = await api.setPassword(value);
      setPassword("");
      notify(result.authRequired ? "Password updated" : "Password protection turned off", "success");
      await load();
      refreshStatus();
    } catch (error) {
      notify(error instanceof ApiError ? error.message : "Could not update the password", "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <div className="page-head">
        <div>
          <h1 className="page-title">Settings</h1>
          <p className="page-subtitle">Control how Cuppa shares printers on your network.</p>
        </div>
      </div>

      <div className="section-title">
        <SettingsIcon size={13} /> Appearance
      </div>
      <div className="card">
        <div className="setting-row">
          <div className="setting-text">
            <div className="setting-title">Theme</div>
            <div className="setting-desc">Light, dark, or follow your device.</div>
          </div>
          <Segmented
            value={theme.mode}
            onChange={(mode) => onThemeChange({ ...theme, mode })}
            options={THEME_MODES.map((m) => ({ value: m.key, label: m.label }))}
          />
        </div>
        <div className="setting-row">
          <div className="setting-text">
            <div className="setting-title">Accent colour</div>
            <div className="setting-desc">Used for buttons, links and highlights.</div>
          </div>
          <div className="swatches">
            {ACCENTS.map((a) => (
              <button
                key={a.key}
                type="button"
                className={"swatch" + (theme.accent === a.key ? " selected" : "")}
                style={{ background: a.swatch }}
                data-tooltip={a.label}
                aria-label={a.label}
                aria-pressed={theme.accent === a.key}
                onClick={() => onThemeChange({ ...theme, accent: a.key })}
              />
            ))}
          </div>
        </div>
      </div>

      <div className="section-title">
        <ShareIcon size={13} /> Sharing
      </div>
      <div className="card">
        <div className="setting-row">
          <div className="setting-text">
            <div className="setting-title">Advertise on the network</div>
            <div className="setting-desc">
              Publish shared printers over Bonjour so macOS, iOS and Android find them automatically.
              Turn off to hide all printers without deleting them.
            </div>
          </div>
          <Toggle
            checked={settings.advertiseEnabled}
            onChange={(value) => void patch("advertiseEnabled", value)}
          />
        </div>
        <div className="setting-row">
          <div className="setting-text">
            <div className="setting-title">AirPrint compatibility</div>
            <div className="setting-desc">
              Add the `_universal` Bonjour subtype Apple devices look for. Recommended while any Mac,
              iPhone or iPad is on the network.
            </div>
          </div>
          <Toggle
            checked={settings.airprintCompat}
            onChange={(value) => void patch("airprintCompat", value)}
          />
        </div>
        <div className="setting-row">
          <div className="setting-text">
            <div className="setting-title">Encrypted printing (IPPS)</div>
            <div className="setting-desc">
              Also advertise `_ipps._tcp` and accept TLS connections on the same port. Uses CUPS'
              self-signed certificate.
            </div>
          </div>
          <Toggle
            checked={settings.tlsEnabled}
            onChange={(value) => void patch("tlsEnabled", value)}
          />
        </div>
      </div>

      <div className="section-title">
        <ShieldIcon size={13} /> Security
      </div>
      <div className="card">
        <div className="setting-row">
          <div className="setting-text">
            <div className="setting-title">
              Admin password {settings.authRequired ? <span className="pill ok">On</span> : <span className="pill">Off</span>}
            </div>
            <div className="setting-desc">
              When set, the web UI asks for this password. Printing from other devices is unaffected.
              Leave empty to disable.
            </div>
          </div>
        </div>
        <div className="setting-row" style={{ alignItems: "flex-end" }}>
          <div className="field grow" style={{ marginBottom: 0 }}>
            <label>{settings.authRequired ? "Change password" : "Set a password"}</label>
            <input
              className="input"
              type="password"
              autoComplete="new-password"
              placeholder="New password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </div>
          <button className="btn btn-primary" disabled={busy || !password} onClick={() => void savePassword(password)}>
            {busy ? <Spinner /> : <LockIcon size={15} />}
            Save
          </button>
          {settings.authRequired ? (
            <button className="btn btn-danger" disabled={busy} onClick={() => void savePassword("")}>
              Turn off
            </button>
          ) : null}
        </div>
      </div>

      <div className="section-title">
        <PrinterIcon size={13} /> About
      </div>
      <div className="card pad">
        <dl className="kv">
          <dt>Product</dt>
          <dd>Cuppa Print Server (web)</dd>
          <dt>CUPS</dt>
          <dd>{status?.cupsVersion ?? "—"}</dd>
          <dt>IPP address</dt>
          <dd className="mono">
            {status ? `${status.ip}:${status.ippPort}` : "—"}
          </dd>
          <dt>Web address</dt>
          <dd className="mono">
            {status ? `${status.ip}:${status.webPort}` : "—"}
          </dd>
          <dt>Project</dt>
          <dd>
            <a href="https://github.com/modnite/Cuppa" target="_blank" rel="noreferrer">
              github.com/modnite/Cuppa <ExternalLinkIcon size={12} />
            </a>
          </dd>
        </dl>
      </div>
    </div>
  );
}
