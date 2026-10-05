import { useState } from "react";
import { api, ApiError } from "../api";
import type { Printer, ThermalConfig } from "../types";
import { Modal, Spinner, Toggle } from "./ui";
import { THERMAL_DIALECTS } from "../thermal";

type LabelSize = "4x6" | "4x4";

const LABEL_SIZES: Record<LabelSize, { labelWidthMm: number; labelHeightMm: number }> = {
  "4x6": { labelWidthMm: 101.6, labelHeightMm: 152.4 },
  "4x4": { labelWidthMm: 101.6, labelHeightMm: 101.6 },
};

function labelSizeFrom(config: ThermalConfig): LabelSize {
  return Math.abs(config.labelHeightMm - 152.4) < 1 ? "4x6" : "4x4";
}

export function ThermalSettingsDialog({
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
  const thermal = printer.thermal;
  const [dialect, setDialect] = useState<ThermalConfig["dialect"]>(thermal?.dialect ?? "tspl");
  const [labelSize, setLabelSize] = useState<LabelSize>(() => (thermal ? labelSizeFrom(thermal) : "4x6"));
  const [density, setDensity] = useState(thermal?.density ?? 8);
  const [speed, setSpeed] = useState(thermal?.speed ?? 5);
  const [dither, setDither] = useState<ThermalConfig["dither"]>(thermal?.dither ?? "FLOYD_STEINBERG");
  const [invertPolarity, setInvertPolarity] = useState(thermal?.invertPolarity ?? false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  if (!thermal) return null;

  const save = async () => {
    setBusy(true);
    setError("");
    try {
      await api.updateThermal(printer.queue, {
        dialect,
        ...LABEL_SIZES[labelSize],
        density,
        speed,
        dither,
        invertPolarity,
      });
      notify(`Thermal settings saved for “${printer.displayName}”`, "success");
      onSaved();
      onClose();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not save thermal settings");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title="Thermal settings"
      onClose={onClose}
      width={620}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" onClick={save} disabled={busy}>
            {busy ? <Spinner /> : null}
            Save
          </button>
        </>
      }
    >
      {error ? <div className="pill bad mb">{error}</div> : null}

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
          <div className="setting-desc">Swap black and white for printers that render labels inverted.</div>
        </div>
        <Toggle checked={invertPolarity} onChange={setInvertPolarity} label="Invert polarity" />
      </div>
    </Modal>
  );
}
