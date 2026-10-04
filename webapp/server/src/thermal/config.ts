import { existsSync, mkdirSync, readFileSync, unlinkSync, writeFileSync } from "node:fs";
import path from "node:path";
import type { DitherMode } from "./raster.js";

/**
 * Per-queue thermal configuration. The CUPS filter reads this by queue name, so
 * the web UI can change darkness/speed/dither without touching the PPD.
 */

export type ThermalDialect = "tspl" | "zpl" | "epl" | "escpos" | "pcl";

export const THERMAL_DIALECTS: ThermalDialect[] = ["tspl", "zpl", "epl", "escpos", "pcl"];

export interface ThermalConfig {
  dialect: ThermalDialect;
  labelWidthMm: number;
  labelHeightMm: number;
  dpi: number;
  /** 0..15 for TSPL. */
  density: number;
  /** 2..6 inches per second. */
  speed: number;
  gapMm: number;
  dither: DitherMode;
  invertPolarity: boolean;
  headDots: number;
}

export const THERMAL_CONFIG_DIR = process.env.CUPPA_THERMAL_DIR ?? "/data/thermal";

export function defaultThermalConfig(patch: Partial<ThermalConfig> = {}): ThermalConfig {
  return {
    dialect: "tspl",
    labelWidthMm: 101.6,
    labelHeightMm: 152.4,
    dpi: 203,
    density: 8,
    speed: 5,
    gapMm: 3,
    dither: "FLOYD_STEINBERG",
    invertPolarity: false,
    headDots: 832,
    ...patch,
  };
}

export function thermalConfigPath(queue: string): string {
  return path.join(THERMAL_CONFIG_DIR, `${queue}.json`);
}

export function loadThermalConfig(queue: string): ThermalConfig | null {
  try {
    const file = thermalConfigPath(queue);
    if (!existsSync(file)) return null;
    return defaultThermalConfig(JSON.parse(readFileSync(file, "utf8")) as Partial<ThermalConfig>);
  } catch {
    return null;
  }
}

export function saveThermalConfig(queue: string, config: ThermalConfig): void {
  mkdirSync(THERMAL_CONFIG_DIR, { recursive: true });
  writeFileSync(thermalConfigPath(queue), JSON.stringify(config, null, 2), "utf8");
}

export function removeThermalConfig(queue: string): void {
  try {
    const file = thermalConfigPath(queue);
    if (existsSync(file)) unlinkSync(file);
  } catch {
    // best effort
  }
}

/** Dots across the label at the configured resolution. */
export function labelWidthDots(config: ThermalConfig): number {
  return Math.round((config.labelWidthMm / 25.4) * config.dpi);
}
