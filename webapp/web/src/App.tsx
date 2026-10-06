import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError } from "./api";
import { Login } from "./components/Login";
import { CuppaIcon } from "./components/CuppaIcon";
import { Sidebar } from "./components/Sidebar";
import { Toasts, type ToastMessage } from "./components/ui";
import { Dashboard } from "./pages/Dashboard";
import { Diagnostics } from "./pages/Diagnostics";
import { Jobs } from "./pages/Jobs";
import { Printers } from "./pages/Printers";
import { Settings } from "./pages/Settings";
import { applyTheme, loadThemePrefs, saveThemePrefs, type ThemePrefs } from "./theme";
import type { Page, Status } from "./types";
import "./styles.css";

export default function App() {
  const [booting, setBooting] = useState(true);
  const [authRequired, setAuthRequired] = useState(false);
  const [authenticated, setAuthenticated] = useState(true);
  const [page, setPage] = useState<Page>("dashboard");
  const [status, setStatus] = useState<Status | null>(null);
  const [online, setOnline] = useState(true);
  const [toasts, setToasts] = useState<ToastMessage[]>([]);
  const [themePrefs, setThemePrefs] = useState<ThemePrefs>(() => loadThemePrefs());
  const toastId = useRef(0);

  const notify = useCallback((text: string, kind: "info" | "success" | "error" = "info") => {
    const id = ++toastId.current;
    setToasts((current) => [...current, { id, text, kind }]);
    setTimeout(() => setToasts((current) => current.filter((toast) => toast.id !== id)), 3800);
  }, []);

  const refreshStatus = useCallback(async () => {
    try {
      setStatus(await api.status());
      setOnline(true);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        setAuthenticated(false);
        return;
      }
      // A 5xx or dropped connection is almost always a proxy/backend blip.
      setOnline(false);
    }
  }, []);

  useEffect(() => {
    (async () => {
      try {
        const auth = await api.authStatus();
        setAuthRequired(auth.required);
        setAuthenticated(auth.authenticated);
        if (auth.authenticated) await refreshStatus();
      } catch {
        setOnline(false);
      } finally {
        setBooting(false);
      }
    })();
  }, [refreshStatus]);

  useEffect(() => {
    if (!authenticated) return;
    const timer = setInterval(() => void refreshStatus(), 10_000);
    return () => clearInterval(timer);
  }, [authenticated, refreshStatus]);

  // While disconnected, retry quickly so the UI heals on its own.
  useEffect(() => {
    if (!authenticated || online) return;
    const timer = setInterval(() => void refreshStatus(), 3000);
    return () => clearInterval(timer);
  }, [authenticated, online, refreshStatus]);

  // Theme: apply and persist, and follow the OS while in "system" mode.
  useEffect(() => {
    applyTheme(themePrefs);
    saveThemePrefs(themePrefs);
  }, [themePrefs]);

  useEffect(() => {
    if (themePrefs.mode !== "system") return;
    const query = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => applyTheme(themePrefs);
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, [themePrefs]);

  if (booting) {
    return (
      <div className="center" style={{ height: "100%" }}>
        <CuppaIcon size={60} radius={14} />
        <div className="muted">Starting Cuppa…</div>
      </div>
    );
  }

  if (authRequired && !authenticated) {
    return (
      <Login
        onSuccess={async () => {
          setAuthenticated(true);
          await refreshStatus();
        }}
      />
    );
  }

  return (
    <div className="app">
      <Sidebar page={page} onNavigate={setPage} status={status} />
      <main className="main">
        <div className="main-inner">
          {!online ? (
            <div className="conn-banner">
              <span className="dot warn" />
              Reconnecting to Cuppa…
            </div>
          ) : null}
          {page === "dashboard" ? (
            <Dashboard
              status={status}
              onNavigate={(target) => setPage(target)}
              refreshStatus={() => void refreshStatus()}
            />
          ) : null}
          {page === "printers" ? <Printers notify={notify} /> : null}
          {page === "jobs" ? <Jobs notify={notify} /> : null}
          {page === "diagnostics" ? <Diagnostics notify={notify} /> : null}
          {page === "settings" ? (
            <Settings
              status={status}
              notify={notify}
              refreshStatus={() => void refreshStatus()}
              theme={themePrefs}
              onThemeChange={setThemePrefs}
            />
          ) : null}
        </div>
      </main>
      <Toasts items={toasts} />
    </div>
  );
}
