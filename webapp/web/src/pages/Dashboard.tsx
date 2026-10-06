import { useEffect, useState } from "react";
import { api } from "../api";
import type { Printer, Status } from "../types";
import { CopyButton, EmptyState } from "../components/ui";
import { InfoIcon, PrinterIcon, RefreshIcon, ShareIcon } from "../components/Icons";

function formatUptime(seconds: number): string {
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
}

export function Dashboard({
  status,
  onNavigate,
  refreshStatus,
}: {
  status: Status | null;
  onNavigate: (page: "printers" | "jobs") => void;
  refreshStatus: () => void;
}) {
  const [printers, setPrinters] = useState<Printer[]>([]);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    try {
      setPrinters(await api.printers());
    } catch {
      // A transient proxy/connection blip is shown by the app-wide banner; do not
      // interrupt with a toast just because a background refresh failed.
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
    const timer = setInterval(() => {
      void load();
      refreshStatus();
    }, 10_000);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const shared = printers.filter((printer) => printer.shared);
  const address = status ? `${status.ip}:${status.ippPort}` : "—";

  return (
    <div>
      <div className="page-head">
        <div>
          <h1 className="page-title">Dashboard</h1>
          <p className="page-subtitle">
            {status?.cupsRunning
              ? `Sharing ${status.sharedCount} printer${status.sharedCount === 1 ? "" : "s"} on your network`
              : "Waiting for the CUPS print service…"}
          </p>
        </div>
        <div className="page-actions">
          <button className="btn" onClick={() => { void load(); refreshStatus(); }}>
            <RefreshIcon size={15} />
            Refresh
          </button>
          <button className="btn btn-primary" onClick={() => onNavigate("printers")}>
            <PrinterIcon size={15} />
            Manage printers
          </button>
        </div>
      </div>

      <div className="grid cols-4">
        <div className="card stat">
          <span className="stat-label">Printers</span>
          <span className="stat-value">{status?.printerCount ?? printers.length}</span>
          <span className="stat-hint">{status?.sharedCount ?? shared.length} shared</span>
        </div>
        <div className="card stat">
          <span className="stat-label">Active jobs</span>
          <span className="stat-value">{status?.activeJobs ?? 0}</span>
          <span className="stat-hint">queued or printing</span>
        </div>
        <div className="card stat">
          <span className="stat-label">Server</span>
          <span className="stat-value" style={{ fontSize: 22 }}>
            {status?.cupsRunning ? "Online" : "Offline"}
          </span>
          <span className="stat-hint">CUPS {status?.cupsVersion ?? "—"}</span>
        </div>
        <div className="card stat">
          <span className="stat-label">Uptime</span>
          <span className="stat-value" style={{ fontSize: 22 }}>
            {status ? formatUptime(status.uptimeSeconds) : "—"}
          </span>
          <span className="stat-hint">port {status?.ippPort ?? 631}</span>
        </div>
      </div>

      <div className="section-title">Connect a device</div>
      <div className="card pad">
        {loading ? (
          <div className="center">Loading…</div>
        ) : shared.length === 0 ? (
          <EmptyState icon={<PrinterIcon />} title="Nothing is shared yet">
            Add a printer and turn on its share switch. It will then appear automatically in the
            print dialog on macOS, iOS and Android.
          </EmptyState>
        ) : (
          <div className="grid cols-2">
            {shared.map((printer) => (
              <div key={printer.queue} className="card pad" style={{ background: "var(--card-bg-2)" }}>
                <div className="row between">
                  <div className="printer-name">{printer.advertisedName}</div>
                  <span className="pill accent">
                    <ShareIcon size={12} />
                    Shared
                  </span>
                </div>
                <div className="small muted" style={{ margin: "4px 0 10px" }}>
                  {printer.makeAndModel || printer.deviceUri}
                </div>
                <CopyButton value={printer.uri} />
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="section-title">Server</div>
      <div className="card pad">
        <dl className="kv">
          <dt>Host</dt>
          <dd>{status?.host ?? "—"}</dd>
          <dt>Address</dt>
          <dd className="mono">{address}</dd>
          <dt>Bonjour / AirPrint</dt>
          <dd>{status?.advertiseEnabled ? "Advertising" : "Off"}</dd>
          <dt>Encryption</dt>
          <dd>{status?.tlsEnabled ? "IPPS available" : "Plain IPP"}</dd>
        </dl>
        <div className="row small muted mt">
          <InfoIcon size={14} />
          <span>
            Clients print straight to CUPS on port {status?.ippPort ?? 631}. The web UI is on port{" "}
            {status?.webPort ?? 8631}.
          </span>
        </div>
      </div>
    </div>
  );
}
