export type Page = "dashboard" | "printers" | "jobs" | "diagnostics" | "settings";

export interface Status {
  ok: boolean;
  cupsVersion: string;
  cupsRunning: boolean;
  host: string;
  ip: string;
  ippPort: number;
  webPort: number;
  advertiseEnabled: boolean;
  airprintCompat: boolean;
  tlsEnabled: boolean;
  authRequired: boolean;
  printerCount: number;
  sharedCount: number;
  activeJobs: number;
  uptimeSeconds: number;
}

export type ThermalDialect = "tspl" | "zpl" | "epl" | "escpos" | "pcl";

export interface ThermalConfig {
  dialect: ThermalDialect;
  labelWidthMm: number;
  labelHeightMm: number;
  dpi: number;
  density: number;
  speed: number;
  gapMm: number;
  dither: "THRESHOLD" | "FLOYD_STEINBERG" | "ATKINSON";
  invertPolarity: boolean;
  headDots: number;
}

export interface Printer {
  queue: string;
  displayName: string;
  advertisedName: string;
  location: string;
  makeAndModel: string;
  state: number;
  stateLabel: string;
  stateReasons: string[];
  accepting: boolean;
  enabled: boolean;
  shared: boolean;
  isDefault: boolean;
  color: boolean;
  formats: string[];
  uri: string;
  deviceUri: string;
  driver: string;
  uuid: string;
  thermal: ThermalConfig | null;
}

export interface Device {
  uri: string;
  kind: string;
  makeAndModel: string;
  info: string;
}

export interface Job {
  id: number;
  name: string;
  printer: string;
  user: string;
  state: number;
  stateLabel: string;
  stateReasons: string[];
  sizeBytes: number;
  createdAt: number;
  completedAt: number | null;
  active: boolean;
}

export interface Settings {
  advertiseEnabled: boolean;
  airprintCompat: boolean;
  tlsEnabled: boolean;
  authRequired: boolean;
}

export interface AuthStatus {
  required: boolean;
  authenticated: boolean;
}

export interface UsbSelfTestStep {
  name: string;
  detail: string;
  command: string;
  code: number;
  durationMs: number;
  ok: boolean;
  output: string;
}

export interface UsbSelfTestResult {
  queue: string;
  deviceUri: string;
  usbUri: string;
  bytes: number;
  steps: UsbSelfTestStep[];
}

export type Diagnostics = Record<string, string>;
