import { useEffect, useMemo, useState } from "react";
import { api, ApiError } from "../api";
import type { Device, Printer, ThermalConfig } from "../types";
import { InfoIcon, PlusIcon, PrinterIcon, RefreshIcon, SearchIcon, UsbIcon, WifiIcon } from "./Icons";
import { Modal, Segmented, Spinner, Toggle } from "./ui";
import { THERMAL_DIALECTS } from "../thermal";

type Tab = "discovered" | "manual";
type Driver = string;
type LabelSize = "4x6" | "4x4";

const LABEL_SIZES: Record<LabelSize, { labelWidthMm: number; labelHeightMm: number }> = {
  "4x6": { labelWidthMm: 101.6, labelHeightMm: 152.4 },
  "4x4": { labelWidthMm: 101.6, labelHeightMm: 101.6 },
};

function guessName(device: Device): string {
  const model = device.makeAndModel.trim() || device.info.trim();
  if (model) return model;
  try {
    const url = new URL(device.uri.replace(/^dnssd:/, "http:"));
    return url.hostname || "Cuppa Printer";
  } catch {
    return "Cuppa Printer";
  }
}

function deviceIcon(uri: string) {
  if (uri.startsWith("usb:")) return <UsbIcon size={18} />;
  return <WifiIcon size={18} />;
}

function recommendedDriver(uri: string): string {
  if (uri.startsWith("ipp://") || uri.startsWith("ipps://") || uri.startsWith("dnssd://")) {
    return "IPP Everywhere";
  }
  if (uri.startsWith("usb://")) return "USB (raw)";
  return "Raw (no conversion)";
}

export function AddPrinterDialog({
  existing,
  onClose,
  onAdded,
  notify,
}: {
  existing: Printer[];
  onClose: () => void;
  onAdded: () => void;
  notify: (text: string, kind?: "info" | "success" | "error") => void;
}) {
  const [tab, setTab] = useState<Tab>("discovered");
  const [devices, setDevices] = useState<Device[]>([]);
  const [scanning, setScanning] = useState(false);
  const [selected, setSelected] = useState<Device | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const [manualUri, setManualUri] = useState("");
  const [name, setName] = useState("");
  const [location, setLocation] = useState("");
  const [driver, setDriver] = useState<Driver>("auto");
  const [pickMode, setPickMode] = useState(false);
  const [selectedModel, setSelectedModel] = useState<{ id: string; name: string } | null>(null);
  const [models, setModels] = useState<Array<{ id: string; name: string }>>([]);
  const [modelsLoading, setModelsLoading] = useState(false);
  const [modelsLoaded, setModelsLoaded] = useState(false);
  const [modelFilter, setModelFilter] = useState("");

  const [thermal, setThermal] = useState(false);
  const [dialect, setDialect] = useState<ThermalConfig["dialect"]>("tspl");
  const [labelSize, setLabelSize] = useState<LabelSize>("4x6");
  const [density, setDensity] = useState(8);
  const [speed, setSpeed] = useState(5);
  const [dither, setDither] = useState<ThermalConfig["dither"]>("FLOYD_STEINBERG");
  const [invertPolarity, setInvertPolarity] = useState(false);

  const scan = async () => {
    setScanning(true);
    setError("");
    try {
      setDevices(await api.discover());
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Discovery failed");
    } finally {
      setScanning(false);
    }
  };

  useEffect(() => {
    void scan();
  }, []);

  const loadModels = async () => {
    if (modelsLoaded || modelsLoading) return;
    setModelsLoading(true);
    try {
      setModels(await api.drivers());
      setModelsLoaded(true);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not load drivers");
    } finally {
      setModelsLoading(false);
    }
  };

  const filteredModels = useMemo(() => {
    const query = modelFilter.trim().toLowerCase();
    const list = query
      ? models.filter((model) => model.name.toLowerCase().includes(query) || model.id.toLowerCase().includes(query))
      : models;
    return list.slice(0, 50);
  }, [models, modelFilter]);

  const activeUri = tab === "manual" ? manualUri.trim() : selected?.uri ?? "";
  const activeName = tab === "manual" ? name : name || (selected ? guessName(selected) : "");

  const add = async () => {
    if (!activeUri) {
      setError("Choose a device or enter a URI first.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      await api.addPrinter({
        deviceUri: activeUri,
        displayName: activeName.trim() || "Cuppa Printer",
        location: location.trim(),
        driver: pickMode ? selectedModel?.id ?? "auto" : driver,
        makeAndModel: tab === "discovered" ? selected?.makeAndModel : undefined,
        shared: true,
        thermal: thermal
          ? {
              dialect,
              ...LABEL_SIZES[labelSize],
              density,
              speed,
              dither,
              invertPolarity,
            }
          : null,
      });
      notify(`Added “${activeName.trim() || "Cuppa Printer"}”`, "success");
      onAdded();
      onClose();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not add the printer");
    } finally {
      setBusy(false);
    }
  };

  const preview = useMemo(() => {
    const trimmed = activeName.trim() || "Cuppa Printer";
    return /\(cuppa\)$/i.test(trimmed) ? trimmed : `${trimmed} (Cuppa)`;
  }, [activeName]);

  return (
    <Modal
      title="Add a printer"
      onClose={onClose}
      width={620}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" onClick={add} disabled={busy || !activeUri}>
            {busy ? <Spinner /> : <PlusIcon size={15} />}
            Add printer
          </button>
        </>
      }
    >
      <div className="row between mb">
        <Segmented<Tab>
          value={tab}
          onChange={(value) => {
            setTab(value);
            setSelected(null);
            setPickMode(false);
            setSelectedModel(null);
            setError("");
          }}
          options={[
            { value: "discovered", label: "Discovered" },
            { value: "manual", label: "Manual" },
          ]}
        />
        {tab === "discovered" ? (
          <button className="btn btn-sm" onClick={scan} disabled={scanning}>
            {scanning ? <Spinner /> : <RefreshIcon size={14} />}
            Scan
          </button>
        ) : null}
      </div>

      {error ? <div className="pill bad mb">{error}</div> : null}

      {tab === "discovered" ? (
        <div>
          {scanning && devices.length === 0 ? (
            <div className="center">
              <Spinner />
              Looking for printers on the network…
            </div>
          ) : devices.length === 0 ? (
            <div className="center">
              <SearchIcon size={30} />
              <div>No printers found yet.</div>
              <div className="small muted">
                Make sure the printer is on and on the same network, then scan again.
              </div>
            </div>
          ) : (
            devices.map((device) => {
              const isSelected = selected?.uri === device.uri;
              const added = existing.some((p) => p.deviceUri === device.uri);
              const recommended = recommendedDriver(device.uri);
              return (
                <button
                  key={device.uri}
                  className="device-row"
                  disabled={added}
                  style={{
                    width: "100%",
                    textAlign: "left",
                    cursor: added ? "default" : "pointer",
                    opacity: added ? 0.6 : undefined,
                    borderColor: isSelected ? "var(--accent)" : undefined,
                    boxShadow: isSelected ? "0 0 0 3px var(--accent-soft)" : undefined,
                  }}
                  onClick={() => {
                    if (added) return;
                    setSelected(device);
                    setName(guessName(device));
                    setDriver("auto");
                    setPickMode(false);
                    setSelectedModel(null);
                    setError("");
                  }}
                >
                  <span className="printer-icon" style={{ width: 36, height: 36, borderRadius: 10 }}>
                    {deviceIcon(device.uri)}
                  </span>
                  <span className="device-info">
                    <span className="device-name">{device.makeAndModel || device.info || device.uri}</span>
                    <span className="device-uri">{device.uri}</span>
                    <span className="small muted" style={{ display: "block" }}>
                      Recommended driver: {recommended}
                    </span>
                    {recommended === "Raw (no conversion)" ? (
                      <span className="small" style={{ display: "block", color: "var(--warning)" }}>
                        Raw queues pass the file through unchanged — pick the printer's IPP/AirPrint entry
                        if it has one, or documents may not print. Monochrome Brother laser? Choose{" "}
                        <strong>Specific driver…</strong> and pick a brlaser/Brother entry.
                      </span>
                    ) : null}
                  </span>
                  {added ? (
                    <span className="pill ok">Added</span>
                  ) : isSelected ? (
                    <span className="pill accent">Selected</span>
                  ) : (
                    <PlusIcon size={16} />
                  )}
                </button>
              );
            })
          )}
        </div>
      ) : (
        <div>
          <div className="field">
            <label>Printer address (IPP URI)</label>
            <input
              className="input"
              placeholder="ipp://192.168.1.50:631/ipp/print"
              value={manualUri}
              onChange={(event) => setManualUri(event.target.value)}
            />
            <span className="hint">
              Use the printer's IPP/AirPrint address, or a discovered address you copied earlier.
            </span>
          </div>
        </div>
      )}

      {activeUri ? (
        <div className="mt">
          <div className="section-title">Details</div>
          <div className="field">
            <label>Name shown on the network</label>
            <input
              className="input"
              placeholder="Office Laser"
              value={activeName}
              onChange={(event) => setName(event.target.value)}
            />
            <span className="hint">
              Other devices will see it as <strong>{preview}</strong>.
            </span>
          </div>
          <div className="grid cols-2">
            <div className="field">
              <label>Location (optional)</label>
              <input
                className="input"
                placeholder="Office"
                value={location}
                onChange={(event) => setLocation(event.target.value)}
              />
            </div>
            <div className="field">
              <label>Driver</label>
              <select
                className="select"
                value={pickMode ? "__pick__" : driver}
                onChange={(event) => {
                  const value = event.target.value;
                  if (value === "__pick__") {
                    setPickMode(true);
                    void loadModels();
                  } else {
                    setPickMode(false);
                    setSelectedModel(null);
                    setDriver(value);
                  }
                }}
              >
                <option value="auto">Automatic (recommended)</option>
                <option value="everywhere">IPP Everywhere / AirPrint</option>
                <option value="raw">Raw (pass-through)</option>
                <option value="__pick__">Specific driver…</option>
              </select>
            </div>
          </div>
          {pickMode ? (
            <div className="field mt">
              <label>Specific driver</label>
              <input
                className="input"
                placeholder="Filter drivers, e.g. Brother or brlaser"
                value={modelFilter}
                onChange={(event) => setModelFilter(event.target.value)}
              />
              {modelsLoading ? (
                <span className="hint">Loading available drivers…</span>
              ) : (
                <select
                  className="select"
                  size={Math.min(6, Math.max(2, filteredModels.length || 2))}
                  value={selectedModel?.id ?? ""}
                  onChange={(event) => {
                    const model = models.find((entry) => entry.id === event.target.value);
                    if (model) {
                      setSelectedModel(model);
                      setDriver(model.id);
                    }
                  }}
                >
                  {filteredModels.length === 0 ? <option value="">No matching drivers</option> : null}
                  {filteredModels.map((model) => (
                    <option key={model.id} value={model.id}>
                      {model.name}
                    </option>
                  ))}
                </select>
              )}
              {selectedModel ? (
                <span className="hint">
                  Selected: <strong>{selectedModel.name}</strong>
                </span>
              ) : (
                <span className="hint">
                  Pick the driver that matches your printer. For monochrome Brother lasers, look for a
                  brlaser/Brother entry.
                </span>
              )}
            </div>
          ) : null}
          <div className="row small muted">
            <InfoIcon size={14} />
            <span>
              Automatic uses IPP Everywhere for network printers, which is what macOS, iOS and Android expect.
            </span>
          </div>

          <div className="setting-row mt" style={{ padding: 0, border: 0 }}>
            <div className="setting-text">
              <div className="setting-title">Thermal label printer</div>
              <div className="setting-desc">
                Send TSPL commands directly to a Rollo or compatible thermal label printer.
              </div>
            </div>
            <Toggle checked={thermal} onChange={setThermal} label="Thermal label printer" />
          </div>

          {thermal ? (
            <div className="mt">
              <div className="grid cols-2">
                <div className="field">
                  <label>Dialect</label>
                  <select
                    className="select"
                    value={dialect}
                    onChange={(event) => setDialect(event.target.value as ThermalConfig["dialect"])}
                  >
                    {THERMAL_DIALECTS.map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="field">
                  <label>Label size</label>
                  <select
                    className="select"
                    value={labelSize}
                    onChange={(event) => setLabelSize(event.target.value as LabelSize)}
                  >
                    <option value="4x6">4 × 6 in (101.6 × 152.4 mm)</option>
                    <option value="4x4">4 × 4 in (101.6 × 101.6 mm)</option>
                  </select>
                </div>
                <div className="field">
                  <label>Density</label>
                  <input
                    className="input"
                    type="number"
                    min={0}
                    max={15}
                    value={density}
                    onChange={(event) => setDensity(Number(event.target.value))}
                  />
                  <span className="hint">0–15, higher is darker.</span>
                </div>
                <div className="field">
                  <label>Speed</label>
                  <input
                    className="input"
                    type="number"
                    min={2}
                    max={6}
                    value={speed}
                    onChange={(event) => setSpeed(Number(event.target.value))}
                  />
                  <span className="hint">2–6 inches per second.</span>
                </div>
                <div className="field">
                  <label>Dither</label>
                  <select
                    className="select"
                    value={dither}
                    onChange={(event) => setDither(event.target.value as ThermalConfig["dither"])}
                  >
                    <option value="FLOYD_STEINBERG">Floyd-Steinberg</option>
                    <option value="THRESHOLD">Threshold</option>
                    <option value="ATKINSON">Atkinson</option>
                  </select>
                </div>
              </div>
              <div className="setting-row" style={{ padding: 0, border: 0 }}>
                <div className="setting-text">
                  <div className="setting-title">Invert polarity</div>
                  <div className="setting-desc">
                    Swap black and white for printers that render labels inverted.
                  </div>
                </div>
                <Toggle checked={invertPolarity} onChange={setInvertPolarity} label="Invert polarity" />
              </div>
            </div>
          ) : null}
        </div>
      ) : (
        <div className="mt row small muted">
          <PrinterIcon size={14} />
          <span>Select a printer above, or switch to Manual to type an address.</span>
        </div>
      )}
    </Modal>
  );
}
