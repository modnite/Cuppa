import type { Page, Status } from "../types";
import { GaugeIcon, JobsIcon, PrinterIcon, SettingsIcon, TerminalIcon } from "./Icons";

const NAV: Array<{ page: Page; label: string; icon: typeof GaugeIcon }> = [
  { page: "dashboard", label: "Dashboard", icon: GaugeIcon },
  { page: "printers", label: "Printers", icon: PrinterIcon },
  { page: "jobs", label: "Jobs", icon: JobsIcon },
  { page: "diagnostics", label: "Diagnostics", icon: TerminalIcon },
  { page: "settings", label: "Settings", icon: SettingsIcon },
];

export function Sidebar({
  page,
  onNavigate,
  status,
}: {
  page: Page;
  onNavigate: (page: Page) => void;
  status: Status | null;
}) {
  const online = status?.cupsRunning ?? false;
  return (
    <aside className="sidebar">
      <div className="brand">
        <img src="/icon.svg" alt="Cuppa" />
        <div>
          <div className="brand-name">Cuppa</div>
          <div className="brand-sub">Print server</div>
        </div>
      </div>

      <nav className="nav">
        {NAV.map((item) => {
          const Icon = item.icon;
          return (
            <button
              key={item.page}
              className={`nav-item${page === item.page ? " active" : ""}`}
              onClick={() => onNavigate(item.page)}
            >
              <Icon />
              <span>{item.label}</span>
              {item.page === "jobs" && status && status.activeJobs > 0 ? (
                <span className="nav-badge">{status.activeJobs}</span>
              ) : null}
            </button>
          );
        })}
      </nav>

      <div className="sidebar-footer">
        <div className="status-line">
          <span className={`dot ${online ? "ok" : "bad"}`} />
          <span>{online ? "CUPS running" : "CUPS unavailable"}</span>
        </div>
        <div className="sidebar-version">
          {status ? `Cuppa ${status.cuppaVersion} · CUPS ${status.cupsVersion}` : "Connecting…"}
        </div>
      </div>
    </aside>
  );
}
