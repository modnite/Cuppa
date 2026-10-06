import { useEffect, useState } from "react";
import { api, ApiError } from "../api";
import type {
  Device,
  Diagnostics as DiagnosticsData,
  NetworkProbe,
  Printer,
  SupplyLevel,
  UsbSelfTestResult,
} from "../types";
import { EmptyState, Spinner, copyText } from "../components/ui";
import {
  CheckIcon,
  CopyIcon,
  InfoIcon,
  PrinterIcon,
  RefreshIcon,
  SearchIcon,
  TerminalIcon,
  UsbIcon,
  WarningIcon,
  WifiIcon,
} from "../components/Icons";

type Notify = (text: string, kind?: "info" | "success" | "error") => void;

const SECTIONS: Array<{ key: string; label: string; hint: string }> = [
  { key: "queues", label: "Queues", hint: "lpstat -v — the device URI each queue actually uses." },
  { key: "printers", label: "Printers", hint: "lpstat -p -d — state and default queue." },
  { key: "jobs", label: "Jobs", hint: "lpstat -W all -o — every queued, held and completed job." },
  { key: "devices", label: "Discovered devices", hint: "lpinfo -v — what CUPS can see right now." },
  { key: "discovered", label: "Discovery (resolved)", hint: "Devices Cuppa found and resolved, including mDNS and the subnet sweep." },
  { key: "backends", label: "Backends", hint: "Installed CUPS backends, including cuppa-usb." },
  { key: "thermal", label: "Thermal queues", hint: "Queues with a saved thermal configuration." },
  { key: "printerOptions", label: "Printer options", hint: "lpoptions -l — the PPD options each queue exposes." },
  { key: "usbDevices", label: "USB devices", hint: "lsusb — everything on the USB bus." },
  { key: "usbTree", label: "USB tree", hint: "lsusb -t — which driver owns each interface." },
  { key: "usbNodes", label: "USB device nodes", hint: "Kernel /dev/bus/usb and /dev/usb nodes." },
  { key: "services", label: "Services", hint: "Processes running in the container (cupsd, Avahi, ipp-usb, the backend)." },
  { key: "mdns", label: "mDNS / discovery", hint: "D-Bus and Avahi sockets, and the _ipp._tcp services Avahi can see." },
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
  return SECTIONS.map((section) => `# ${section.label}\n${data[section.key] ?? ""}`).join("\n\n");
}

function SupplyBar({ supply }: { supply: SupplyLevel }) {
  const known = supply.level >= 0;
  const pct = Math.max(0, Math.min(100, supply.level));
  const tone = !known ? "muted" : pct <= 10 ? "bad" : pct <= 25 ? "warn" : "ok";
  return (
    <div className="supply">
      <div className="row between">
        <span className="small">
          {supply.name}
          {supply.color && supply.color !== "none" ? <span className="muted"> · {supply.color}</span> : null}
        </span>
        <span className="small muted">{known ? `${supply.level}%` : "unknown"}</span>
      </div>
      <div className="supply-bar">
        <div className={`supply-fill ${tone}`} style={{ width: `${known ? pct : 0}%` }} />
      </div>
    </div>
  );
}

function ProbeCard({ probe }: { probe: NetworkProbe }) {
  return (
    <div className="usb-step">
      <div className="row between">
        <div className="printer-body">
          <div className="printer-name">{probe.label}</div>
          <div className="printer-origin">{probe.deviceUri}</div>
        </div>
        <div className="row" style={{ gap: 6, flexWrap: "wrap", justifyContent: "flex-end" }}>
          <span className={`pill ${probe.tcp.ok ? "ok" : "bad"}`}>
            {probe.tcp.ok ? <CheckIcon size={12} /> : <WarningIcon size={12} />}
            {probe.tcp.ok ? `TCP ${probe.tcp.ms} ms` : "TCP failed"}
          </span>
          {probe.ipp ? (
            <span className={`pill ${probe.ipp.ok ? "ok" : "warn"}`}>
              {probe.ipp.ok ? `IPP · ${probe.ipp.stateLabel}` : "IPP unavailable"}
            </span>
          ) : null}
        </div>
      </div>

      {!probe.tcp.ok && probe.tcp.error ? (
        <div className="small muted" style={{ marginTop: 4 }}>
          {probe.tcp.error}
        </div>
      ) : null}

      {probe.ipp?.ok ? (
        <div style={{ marginTop: 8 }}>
          {probe.ipp.makeAndModel ? <div className="small">{probe.ipp.makeAndModel}</div> : null}
          {probe.ipp.stateReasons.length > 0 ? (
            <div className="small muted">{probe.ipp.stateReasons.join(", ")}</div>
          ) : null}
          {probe.ipp.stateMessage ? <div className="small muted">{probe.ipp.stateMessage}</div> : null}
          {probe.ipp.supplies.length > 0 ? (
            <div className="supplies">
              {probe.ipp.supplies.map((supply, index) => (
                <SupplyBar key={index} supply={supply} />
              ))}
            </div>
          ) : null}
        </div>
      ) : probe.ipp ? (
        <div className="small muted" style={{ marginTop: 4 }}>
          IPP: {probe.ipp.error}
          {probe.ipp.statusCode >= 0 ? ` (status 0x${probe.ipp.statusCode.toString(16)})` : ""}
        </div>
      ) : null}
    </div>
  );
}

function isRawDevice(uri: string): boolean {
  return /^(socket|lpd|http):/i.test(uri);
}

export function Diagnostics({ notify }: { notify: Notify }) {
  const [data, setData] = useState<DiagnosticsData | null>(null);
  const [printers, setPrinters] = useState<Printer[]>([]);
  const [probes, setProbes] = useState<NetworkProbe[]>([]);
  const [devices, setDevices] = useState<Device[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [showRaw, setShowRaw] = useState(false);

  const [testing, setTesting] = useState<string | null>(null);
  const [results, setResults] = useState<Record<string, UsbSelfTestResult>>({});

  const [probing, setProbing] = useState<string | null>(null);
  const [probeResults, setProbeResults] = useState<Record<string, NetworkProbe>>({});
  const [manualUri, setManualUri] = useState("");

  const load = async () => {
    setLoading(true);
    setError("");
    try {
      const [diagnostics, list, network, discovered] = await Promise.all([
        api.diagnostics(),
        api.printers(),
        api.networkDiagnostics().catch(() => [] as NetworkProbe[]),
        api.discover().catch(() => [] as Device[]),
      ]);
      setData(diagnostics);
      setPrinters(list);
      setProbes(network);
      setDevices(discovered);
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

  const probe = async (uri: string, label?: string) => {
    const key = uri.trim();
    if (!key) return;
    setProbing(key);
    try {
      const result = await api.probeUri(key, label);
      setProbeResults((current) => ({ ...current, [key]: result }));
    } catch (err) {
      notify(err instanceof ApiError ? err.message : "Could not probe the address", "error");
    } finally {
      setProbing(null);
    }
  };

  const usbPrinters = printers.filter((printer) => /^(cuppa-)?usb:/i.test(printer.deviceUri));
  const manualResult = probeResults[manualUri.trim()];
  const visibleDevices = showRaw ? devices : devices.filter((device) => !isRawDevice(device.uri));

  return (
    <div>
      <div className="page-head">
        <div>
          <h1 className="page-title">Diagnostics</h1>
          <p className="page-subtitle">
            What CUPS sees, what the USB bus and the network look like, and self-tests that bypass the queue.
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

      {/* ---- Network printers ---- */}
      <div className="section-title">
        <WifiIcon size={13} /> Network printers
      </div>
      <div className="card pad">
        <div className="row small muted mb">
          <InfoIcon size={14} />
          <span>
            Each network queue is contacted directly: a TCP reachability check, then a live IPP query for its
            state and supply levels. This works whether or not Cuppa can print to it.
          </span>
        </div>

        {loading && probes.length === 0 ? (
          <div className="center">
            <Spinner /> Probing network printers…
          </div>
        ) : probes.length === 0 ? (
          <EmptyState icon={<WifiIcon />} title="No network printers">
            Add a network printer and it will show up here with its state and supplies.
          </EmptyState>
        ) : (
          probes.map((probe) => <ProbeCard key={probe.deviceUri} probe={probe} />)
        )}
      </div>

      {/* ---- USB self-test ---- */}
      <div className="section-title">
        <UsbIcon size={13} /> USB self-test
      </div>
      <div className="card pad">
        <div className="row small muted mb">
          <InfoIcon size={14} />
          <span>
            Sends a diagnostic label straight to the printer over USB, without the queue or scheduler. Use it to
            tell a broken queue apart from a printer that cannot be driven over USB.
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
                          <div className="row" style={{ flexWrap: "wrap" }}>
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

      {/* ---- Discovered devices + manual probe ---- */}
      <div className="section-title">
        <SearchIcon size={13} /> Discovered on the network
      </div>
      <div className="card pad">
        <div className="row small muted mb">
          <InfoIcon size={14} />
          <span>
            Everything CUPS discovery can see, plus any address you want to probe. Probing does not add or print
            anything.
          </span>
        </div>

        <div className="row" style={{ gap: 8, alignItems: "flex-end", flexWrap: "wrap" }}>
          <div className="field grow" style={{ marginBottom: 0 }}>
            <label>Probe an address</label>
            <input
              className="input"
              placeholder="ipp://192.168.1.50:631/ipp/print"
              value={manualUri}
              onChange={(event) => setManualUri(event.target.value)}
            />
          </div>
          <button className="btn btn-primary" disabled={!manualUri.trim() || probing === manualUri.trim()} onClick={() => void probe(manualUri)}>
            {probing === manualUri.trim() ? <Spinner /> : <SearchIcon size={15} />}
            Probe
          </button>
        </div>

        {manualResult ? (
          <div className="mt">
            <ProbeCard probe={manualResult} />
          </div>
        ) : null}

        <div className="row between mt">
          <div className="small muted">Discovered devices</div>
          <label className="row small muted" style={{ gap: 6, cursor: "pointer" }}>
            <input type="checkbox" checked={showRaw} onChange={(event) => setShowRaw(event.target.checked)} />
            Show raw
          </label>
        </div>
        <div className="mt">
          {visibleDevices.length === 0 ? (
            <div className="small muted">
              {devices.length === 0
                ? "No devices discovered right now."
                : "Only raw devices found; tick “Show raw”."}
            </div>
          ) : (
            visibleDevices.map((device) => {
              const result = probeResults[device.uri];
              return (
                <div key={device.uri} className="usb-test">
                  <div className="row between">
                    <div className="printer-body">
                      <div className="printer-name">{device.makeAndModel || device.info || device.uri}</div>
                      <div className="printer-origin">{device.uri}</div>
                    </div>
                    <button
                      className="btn"
                      disabled={probing === device.uri}
                      onClick={() => void probe(device.uri, device.makeAndModel || device.info || device.uri)}
                    >
                      {probing === device.uri ? <Spinner /> : <SearchIcon size={15} />}
                      Probe
                    </button>
                  </div>
                  {result ? (
                    <div className="mt">
                      <ProbeCard probe={result} />
                    </div>
                  ) : null}
                </div>
              );
            })
          )}
        </div>
      </div>

      {/* ---- System ---- */}
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
            <div key={section.key} className="card pad diag-section">
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
