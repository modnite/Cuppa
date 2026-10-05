import { useEffect, useState } from "react";
import { api, ApiError } from "../api";
import type { Diagnostics as DiagnosticsData, Printer, UsbSelfTestResult } from "../types";
import { EmptyState, Spinner, copyText } from "../components/ui";
import {
  CheckIcon,
  CopyIcon,
  InfoIcon,
  PrinterIcon,
  RefreshIcon,
  TerminalIcon,
  UsbIcon,
  WarningIcon,
} from "../components/Icons";

type Notify = (text: string, kind?: "info" | "success" | "error") => void;

const SECTIONS: Array<{ key: keyof DiagnosticsData | string; label: string; hint: string }> = [
  { key: "queues", label: "Queues", hint: "lpstat -v — the device URI each queue actually uses." },
  { key: "printers", label: "Printers", hint: "lpstat -p -d — state and default queue." },
  { key: "jobs", label: "Jobs", hint: "lpstat -W all -o — every queued, held and completed job." },
  { key: "devices", label: "Discovered devices", hint: "lpinfo -v — what CUPS can see right now." },
  { key: "backends", label: "Backends", hint: "Installed CUPS backends, including cuppa-usb." },
  { key: "thermal", label: "Thermal queues", hint: "Queues with a saved thermal configuration." },
  { key: "printerOptions", label: "Printer options", hint: "lpoptions -l — the PPD options each queue exposes." },
  { key: "usbDevices", label: "USB devices", hint: "lsusb — everything on the USB bus." },
  { key: "usbTree", label: "USB tree", hint: "lsusb -t — which driver owns each interface." },
  { key: "usbNodes", label: "USB device nodes", hint: "Kernel /dev/bus/usb and /dev/usb nodes." },
  { key: "cupsdConf", label: "cupsd.conf", hint: "The active CUPS scheduler configuration." },
  { key: "errorLog", label: "CUPS error log", hint: "The last 200 lines of /var/log/cups/error.log." },
];

function CopyIconButton({ value, tooltip = "Copy" }: { value: string; tooltip?: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      type="button"
      className="btn btn-ghost btn-icon"
      data-tooltip={copied ? "Copied" : tooltip}
      aria-label={tooltip}
      onClick={async () => {
        const ok = await copyText(value);
        setCopied(ok);
        setTimeout(() => setCopied(false), 1600);
      }}
    >
      {copied ? <CheckIcon size={15} /> : <CopyIcon size={15} />}
    </button>
  );
}

function formatAll(data: DiagnosticsData): string {
  return SECTIONS.map((section) => {
    const value = data[section.key] ?? "";
    return `# ${section.label}\n${value}`;
  }).join("\n\n");
}

export function Diagnostics({ notify }: { notify: Notify }) {
  const [data, setData] = useState<DiagnosticsData | null>(null);
  const [printers, setPrinters] = useState<Printer[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const [testing, setTesting] = useState<string | null>(null);
  const [results, setResults] = useState<Record<string, UsbSelfTestResult>>({});

  const load = async () => {
    setLoading(true);
    setError("");
    try {
      const [diagnostics, list] = await Promise.all([api.diagnostics(), api.printers()]);
      setData(diagnostics);
      setPrinters(list);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not load diagnostics");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const runSelfTest = async (printer: Printer) => {
    setTesting(printer.queue);
    try {
      const result = await api.usbSelfTest(printer.queue);
      setResults((current) => ({ ...current, [printer.queue]: result }));
      const ok = result.steps.filter((step) => step.ok).length;
      notify(
        `${printer.displayName}: ${ok}/${result.steps.length} USB path${result.steps.length === 1 ? "" : "s"} succeeded`,
        ok > 0 ? "success" : "error"
      );
    } catch (err) {
      notify(err instanceof ApiError ? err.message : "USB self-test failed", "error");
    } finally {
      setTesting(null);
    }
  };

  const usbPrinters = printers.filter((printer) => /^(cuppa-)?usb:/i.test(printer.deviceUri));

  return (
    <div>
      <div className="page-head">
        <div>
          <h1 className="page-title">Diagnostics</h1>
          <p className="page-subtitle">
            What CUPS sees, what the USB bus looks like, and a raw self-test that bypasses the queue.
          </p>
        </div>
        <div className="page-actions">
          <button className="btn" onClick={() => void load()} disabled={loading}>
            {loading ? <Spinner /> : <RefreshIcon size={15} />}
            Refresh
          </button>
          {data ? <CopyIconButton value={formatAll(data)} tooltip="Copy all diagnostics" /> : null}
        </div>
      </div>

      {error ? <div className="pill bad mb">{error}</div> : null}

      <div className="section-title">
        <UsbIcon size={13} /> USB self-test
      </div>
      <div className="card pad">
        <div className="row small muted mb">
          <InfoIcon size={14} />
          <span>
            Sends a diagnostic label straight to the printer over USB, without the queue or scheduler.
            Use it to tell a broken queue apart from a printer that cannot be driven over USB.
          </span>
        </div>

        {usbPrinters.length === 0 ? (
          <EmptyState icon={<UsbIcon />} title="No USB printers">
            Add a USB printer and it will show up here with a one-click self-test.
          </EmptyState>
        ) : (
          usbPrinters.map((printer) => {
            const result = results[printer.queue];
            return (
              <div key={printer.queue} className="usb-test">
                <div className="row between">
                  <div className="printer-body">
                    <div className="printer-name">{printer.displayName}</div>
                    <div className="printer-origin">{printer.deviceUri}</div>
                  </div>
                  <button
                    className="btn btn-primary"
                    disabled={testing === printer.queue}
                    onClick={() => void runSelfTest(printer)}
                  >
                    {testing === printer.queue ? <Spinner /> : <UsbIcon size={15} />}
                    Run self-test
                  </button>
                </div>

                {result ? (
                  <div className="mt">
                    <div className="small muted mb">
                      Sent {result.bytes.toLocaleString()} bytes to <span className="mono">{result.usbUri}</span>
                    </div>
                    {result.steps.map((step) => (
                      <div key={step.name} className="usb-step">
                        <div className="row between">
                          <div className="row">
                            <span className={`pill ${step.ok ? "ok" : "bad"}`}>
                              {step.ok ? <CheckIcon size={12} /> : <WarningIcon size={12} />}
                              {step.name}
                            </span>
                            <span className="small muted">
                              exit {step.code} · {step.durationMs} ms
                            </span>
                          </div>
                        </div>
                        <div className="small muted" style={{ marginTop: 4 }}>
                          {step.detail}
                        </div>
                        <pre className="diag-pre">{step.output || "(no output)"}</pre>
                      </div>
                    ))}
                  </div>
                ) : null}
              </div>
            );
          })
        )}
      </div>

      <div className="section-title">
        <TerminalIcon size={13} /> System
      </div>

      {loading && !data ? (
        <div className="center">
          <Spinner /> Collecting diagnostics…
        </div>
      ) : data ? (
        SECTIONS.map((section) => {
          const value = data[section.key] ?? "";
          return (
            <div key={String(section.key)} className="card pad diag-section">
              <div className="row between">
                <div>
                  <div className="setting-title">{section.label}</div>
                  <div className="small muted">{section.hint}</div>
                </div>
                <CopyIconButton value={value} tooltip={`Copy ${section.label}`} />
              </div>
              <pre className="diag-pre">{value || "(empty)"}</pre>
            </div>
          );
        })
      ) : (
        <div className="card">
          <EmptyState icon={<PrinterIcon />} title="No diagnostics">
            {error || "Refresh to try again."}
          </EmptyState>
        </div>
      )}
    </div>
  );
}
