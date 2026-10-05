import type { ThermalDialect } from "./types";

/** Command languages a thermal/label queue can speak, with the hardware each targets. */
export const THERMAL_DIALECTS: Array<{ value: ThermalDialect; label: string }> = [
  { value: "tspl", label: "TSPL — Rollo / TSC" },
  { value: "zpl", label: "ZPL II — Zebra" },
  { value: "epl", label: "EPL2 — Eltron / Zebra" },
  { value: "escpos", label: "ESC/POS — receipt" },
  { value: "pcl", label: "PCL 5 — laser / inkjet" },
];
