import { useMemo, useState } from "react";
import { api, ApiError } from "../api";
import type { Printer } from "../types";
import { Modal, Spinner } from "./ui";

export function RenameDialog({
  printer,
  onClose,
  onSaved,
  notify,
}: {
  printer: Printer;
  onClose: () => void;
  onSaved: () => void;
  notify: (text: string, kind?: "info" | "success" | "error") => void;
}) {
  const [name, setName] = useState(printer.displayName);
  const [location, setLocation] = useState(printer.location);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const preview = useMemo(() => {
    const trimmed = name.trim() || "Cuppa Printer";
    return /\(cuppa\)$/i.test(trimmed) ? trimmed : `${trimmed} (Cuppa)`;
  }, [name]);

  const save = async () => {
    setBusy(true);
    setError("");
    try {
      await api.updatePrinter(printer.queue, { displayName: name.trim(), location: location.trim() });
      notify(`Renamed to “${name.trim()}”`, "success");
      onSaved();
      onClose();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not rename the printer");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title="Rename printer"
      onClose={onClose}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" onClick={save} disabled={busy || !name.trim()}>
            {busy ? <Spinner /> : null}
            Save
          </button>
        </>
      }
    >
      {error ? <div className="pill bad mb">{error}</div> : null}
      <div className="field">
        <label>Printer name</label>
        <input
          className="input"
          autoFocus
          value={name}
          onChange={(event) => setName(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") void save();
          }}
        />
        <span className="hint">
          Broadcast on the network as <strong>{preview}</strong>. The “(Cuppa)” suffix is added
          automatically so this server stays recognisable in the print dialog.
        </span>
      </div>
      <div className="field">
        <label>Location (optional)</label>
        <input
          className="input"
          placeholder="Office"
          value={location}
          onChange={(event) => setLocation(event.target.value)}
        />
      </div>
      <div className="card pad" style={{ background: "var(--card-bg-2)" }}>
        <div className="small muted">Current queue name</div>
        <div className="mono">{printer.queue}</div>
        <div className="small muted mt">Source</div>
        <div className="mono">{printer.deviceUri}</div>
        <div className="small muted mt">Internal CUPS queue stays the same, so existing jobs and settings are preserved.</div>
      </div>
    </Modal>
  );
}
