import { useEffect, useState } from "react";
import { api, ApiError } from "../api";
import type { Job } from "../types";
import { EmptyState, Segmented, Spinner } from "../components/ui";
import { ClockIcon, FileIcon, RefreshIcon, XIcon } from "../components/Icons";

type Scope = "active" | "history" | "all";
type Notify = (text: string, kind?: "info" | "success" | "error") => void;

function formatBytes(bytes: number): string {
  if (!bytes) return "—";
  const units = ["B", "KB", "MB", "GB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value >= 10 || unit === 0 ? 0 : 1)} ${units[unit]}`;
}

function formatTime(ms: number): string {
  if (!ms) return "—";
  return new Date(ms).toLocaleString(undefined, {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function jobPill(job: Job): string {
  if (job.state === 9) return "ok";
  if (job.state === 7 || job.state === 8) return "bad";
  if (job.state === 5) return "info";
  if (job.state === 3 || job.state === 4) return "warn";
  return "";
}

export function Jobs({ notify }: { notify: Notify }) {
  const [scope, setScope] = useState<Scope>("active");
  const [jobs, setJobs] = useState<Job[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<number | null>(null);

  const load = async () => {
    try {
      setJobs(await api.jobs(scope));
    } catch {
      // Handled by the app-wide connection banner; avoid toast noise on polls.
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setLoading(true);
    void load();
    const timer = setInterval(() => void load(), 5000);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scope]);

  const cancel = async (job: Job) => {
    setBusy(job.id);
    try {
      await api.cancelJob(job.id);
      notify(`Canceled “${job.name}”`, "success");
      await load();
    } catch (error) {
      notify(error instanceof ApiError ? error.message : "Could not cancel the job", "error");
    } finally {
      setBusy(null);
    }
  };

  return (
    <div>
      <div className="page-head">
        <div>
          <h1 className="page-title">Jobs</h1>
          <p className="page-subtitle">Print jobs sent to and from this server.</p>
        </div>
        <div className="page-actions">
          <button className="btn" onClick={() => void load()}>
            <RefreshIcon size={15} />
            Refresh
          </button>
        </div>
      </div>

      <div className="mb">
        <Segmented<Scope>
          value={scope}
          onChange={setScope}
          options={[
            { value: "active", label: "Active" },
            { value: "history", label: "History" },
            { value: "all", label: "All" },
          ]}
        />
      </div>

      <div className="card" style={{ overflowX: "auto" }}>
        {loading ? (
          <div className="center">
            <Spinner /> Loading jobs…
          </div>
        ) : jobs.length === 0 ? (
          <EmptyState icon={<FileIcon />} title={scope === "active" ? "No active jobs" : "No jobs yet"}>
            {scope === "active"
              ? "Jobs appear here while they are queued or printing."
              : "Print something from a connected device and it will show up here."}
          </EmptyState>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Job</th>
                <th>Printer</th>
                <th>User</th>
                <th>Status</th>
                <th className="right">Size</th>
                <th>Submitted</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {jobs.map((job) => (
                <tr key={job.id}>
                  <td>
                    <div style={{ fontWeight: 600 }}>{job.name}</div>
                    <div className="muted small mono">#{job.id}</div>
                  </td>
                  <td className="muted">{job.printer}</td>
                  <td className="muted">{job.user || "—"}</td>
                  <td>
                    <span className={`pill ${jobPill(job)}`}>{job.stateLabel}</span>
                    {job.stateReasons.length > 0 ? (
                      <div className="muted small">{job.stateReasons.join(", ")}</div>
                    ) : null}
                  </td>
                  <td className="right muted">{formatBytes(job.sizeBytes)}</td>
                  <td className="muted small">{formatTime(job.createdAt)}</td>
                  <td className="right">
                    {job.active ? (
                      <button
                        className="btn btn-sm btn-danger"
                        disabled={busy === job.id}
                        onClick={() => void cancel(job)}
                      >
                        {busy === job.id ? <Spinner /> : <XIcon size={13} />}
                        Cancel
                      </button>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="row small muted mt">
        <ClockIcon size={14} />
        <span>Active jobs refresh automatically every few seconds.</span>
      </div>
    </div>
  );
}
