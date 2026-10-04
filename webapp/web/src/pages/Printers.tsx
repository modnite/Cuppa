import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "../api";
import type { Printer } from "../types";
import { AddPrinterDialog } from "../components/AddPrinterDialog";
import { RenameDialog } from "../components/RenameDialog";
import { ThermalSettingsDialog } from "../components/ThermalSettingsDialog";
import { CopyButton, EmptyState, Modal, Spinner, StatusPill, Toggle } from "../components/ui";
import {
  FileIcon,
  PencilIcon,
  PlusIcon,
  PowerIcon,
  PrinterIcon,
  RefreshIcon,
  SettingsIcon,
  StarIcon,
  TrashIcon,
  UsbIcon,
} from "../components/Icons";

type Notify = (text: string, kind?: "info" | "success" | "error") => void;

export function Printers({ notify }: { notify: Notify }) {
  const [printers, setPrinters] = useState<Printer[]>([]);
  const [loading, setLoading] = useState(true);
  const [showAdd, setShowAdd] = useState(false);
  const [renaming, setRenaming] = useState<Printer | null>(null);
  const [removing, setRemoving] = useState<Printer | null>(null);
  const [thermalTarget, setThermalTarget] = useState<Printer | null>(null);
  const [busyQueue, setBusyQueue] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const uploadTarget = useRef<Printer | null>(null);

  const load = async () => {
    try {
      setPrinters(await api.printers());
    } catch {
      // Connection blips are reflected by the app-wide banner; loading is not
      // user-initiated here, so a toast would just be noise.
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
    const timer = setInterval(() => void load(), 8000);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const run = async (queue: string, action: () => Promise<unknown>, success: string) => {
    setBusyQueue(queue);
    try {
      await action();
      notify(success, "success");
      await load();
    } catch (error) {
      notify(error instanceof ApiError ? error.message : "Something went wrong", "error");
    } finally {
      setBusyQueue(null);
    }
  };

  const onPickFile = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    const printer = uploadTarget.current;
    event.target.value = "";
    if (!file || !printer) return;
    await run(printer.queue, () => api.uploadPrint(printer.queue, file), `Sent “${file.name}” to ${printer.displayName}`);
  };

  return (
    <div>
      <div className="page-head">
        <div>
          <h1 className="page-title">Printers</h1>
          <p className="page-subtitle">Add, rename and share the printers this server broadcasts.</p>
        </div>
        <div className="page-actions">
          <button className="btn" onClick={() => void load()}>
            <RefreshIcon size={15} />
            Refresh
          </button>
          <button className="btn btn-primary" onClick={() => setShowAdd(true)}>
            <PlusIcon size={15} />
            Add printer
          </button>
        </div>
      </div>

      {loading ? (
        <div className="center">
          <Spinner /> Loading printers…
        </div>
      ) : printers.length === 0 ? (
        <div className="card">
          <EmptyState icon={<PrinterIcon />} title="No printers yet">
            Add a printer to share it over the network. Cuppa will advertise it over Bonjour so it
            shows up on macOS, iOS and Android automatically.
            <div className="mt">
              <button className="btn btn-primary" onClick={() => setShowAdd(true)}>
                <PlusIcon size={15} />
                Add your first printer
              </button>
            </div>
          </EmptyState>
        </div>
      ) : (
        <div className="grid" style={{ gap: 12 }}>
          {printers.map((printer) => (
            <div key={printer.queue} className="card printer-card">
              <div className="printer-icon">
                {printer.deviceUri.startsWith("usb:") ? <UsbIcon size={24} /> : <PrinterIcon size={24} />}
              </div>
              <div className="printer-body">
                <div className="printer-name">
                  {printer.advertisedName}
                  {printer.thermal ? <span className="pill accent">Thermal</span> : null}
                  {printer.isDefault ? <span className="pill accent">Default</span> : null}
                </div>
                <div className="printer-meta">
                  {[printer.makeAndModel, printer.location].filter(Boolean).join(" · ")}
                </div>
                <div className="printer-origin">{printer.deviceUri}</div>
                <div className="row printer-pills" style={{ marginTop: 7, gap: 8 }}>
                  <StatusPill state={printer.state} stateLabel={printer.stateLabel} offline={!printer.enabled} />
                  {printer.driver ? <span className="pill">{printer.driver}</span> : null}
                  {printer.color ? <span className="pill">Color</span> : null}
                  {!printer.accepting ? <span className="pill warn">Not accepting jobs</span> : null}
                  <CopyButton value={printer.uri} />
                </div>
              </div>
              <div className="printer-actions">
                <div className="row" style={{ gap: 6 }}>
                  <Toggle
                    checked={printer.shared}
                    disabled={busyQueue === printer.queue}
                    label="Share on the network"
                    onChange={(value) =>
                      void run(printer.queue, () => api.updatePrinter(printer.queue, { shared: value }), value ? "Now sharing" : "Stopped sharing")
                    }
                  />
                </div>
                {!printer.isDefault ? (
                  <button
                    className="btn btn-ghost btn-icon"
                    data-tooltip="Set as default"
                    aria-label="Set as default"
                    disabled={busyQueue === printer.queue}
                    onClick={() => void run(printer.queue, () => api.setDefault(printer.queue), "Default printer updated")}
                  >
                    <StarIcon size={16} />
                  </button>
                ) : null}
                {printer.thermal ? (
                  <button
                    className="btn btn-ghost btn-icon"
                    data-tooltip="Thermal settings"
                    aria-label="Thermal settings"
                    onClick={() => setThermalTarget(printer)}
                  >
                    <SettingsIcon size={16} />
                  </button>
                ) : null}
                <button
                  className="btn btn-ghost btn-icon"
                  data-tooltip="Test print"
                  aria-label="Test print"
                  disabled={busyQueue === printer.queue}
                  onClick={() => void run(printer.queue, () => api.testPrint(printer.queue), "Test page sent")}
                >
                  <PrinterIcon size={16} />
                </button>
                <button
                  className="btn btn-ghost btn-icon"
                  data-tooltip="Print a file"
                  aria-label="Print a file"
                  disabled={busyQueue === printer.queue}
                  onClick={() => {
                    uploadTarget.current = printer;
                    fileInput.current?.click();
                  }}
                >
                  <FileIcon size={16} />
                </button>
                <button
                  className="btn btn-ghost btn-icon"
                  data-tooltip={printer.enabled ? "Pause printer" : "Resume printer"}
                  aria-label={printer.enabled ? "Pause printer" : "Resume printer"}
                  disabled={busyQueue === printer.queue}
                  onClick={() =>
                    void run(
                      printer.queue,
                      () => api.setEnabled(printer.queue, !printer.enabled),
                      printer.enabled ? "Printer paused" : "Printer resumed"
                    )
                  }
                >
                  <PowerIcon size={16} />
                </button>
                <button className="btn btn-ghost btn-icon" data-tooltip="Rename" aria-label="Rename" onClick={() => setRenaming(printer)}>
                  <PencilIcon size={16} />
                </button>
                <button className="btn btn-ghost btn-icon" data-tooltip="Remove" aria-label="Remove" onClick={() => setRemoving(printer)}>
                  <TrashIcon size={16} />
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      <input ref={fileInput} type="file" hidden onChange={onPickFile} />

      {showAdd ? (
        <AddPrinterDialog existing={printers} onClose={() => setShowAdd(false)} onAdded={() => void load()} notify={notify} />
      ) : null}

      {thermalTarget ? (
        <ThermalSettingsDialog
          printer={thermalTarget}
          onClose={() => setThermalTarget(null)}
          onSaved={() => void load()}
          notify={notify}
        />
      ) : null}

      {renaming ? (
        <RenameDialog
          printer={renaming}
          onClose={() => setRenaming(null)}
          onSaved={() => void load()}
          notify={notify}
        />
      ) : null}

      {removing ? (
        <Modal
          title={`Remove “${removing.displayName}”?`}
          onClose={() => setRemoving(null)}
          footer={
            <>
              <button className="btn" onClick={() => setRemoving(null)}>
                Keep
              </button>
              <button
                className="btn btn-danger"
                onClick={() => {
                  const printer = removing;
                  setRemoving(null);
                  void run(printer.queue, () => api.removePrinter(printer.queue), "Printer removed");
                }}
              >
                <TrashIcon size={15} />
                Remove
              </button>
            </>
          }
        >
          <p className="muted" style={{ marginTop: 0 }}>
            Other devices will stop seeing <strong>{removing.advertisedName}</strong>. The printer
            itself is untouched and can be added again later.
          </p>
        </Modal>
      ) : null}
    </div>
  );
}
