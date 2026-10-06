import type { ThermalConfig } from "./thermal/config.js";

/** Shapes shared between the backend and (mirrored in) the web front end. */

export interface CuppaSettings {
  /** Master switch for Bonjour/AirPrint advertisement. */
  advertiseEnabled: boolean;
  /** Adds the `_universal` subtype Apple's AirPrint discovery looks for. */
  airprintCompat: boolean;
  /** Also advertise `_ipps._tcp` and offer encrypted printing. */
  tlsEnabled: boolean;
  /** scrypt hash of the optional admin password, or null when auth is off. */
  adminPasswordHash: string | null;
  adminPasswordSalt: string | null;
}

export interface PrinterMeta {
  displayName: string;
  location: string;
  /** Whether this queue is published on the network. */
  shared: boolean;
  createdAt: number;
  /** The driver actually used when the queue was created (for display). */
  driver?: string;
}

export interface StoreData {
  settings: CuppaSettings;
  printers: Record<string, PrinterMeta>;
}

export interface PrinterView {
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
  uuid: string;
  /** The driver CUPS is using for this queue (IPP Everywhere, Raw, or a thermal dialect). */
  driver: string;
  /** Present when this queue is a thermal label printer. */
  thermal: ThermalConfig | null;
}

export interface DeviceView {
  uri: string;
  kind: string;
  makeAndModel: string;
  info: string;
}

export interface JobView {
  id: number;
  name: string;
  printer: string;
  user: string;
  state: number;
  stateLabel: string;
  /** CUPS `job-state-reasons` (e.g. `job-completed-with-errors`). */
  stateReasons: string[];
  sizeBytes: number;
  createdAt: number;
  completedAt: number | null;
  active: boolean;
}

export interface StatusView {
  ok: boolean;
  cupsVersion: string;
  /** Cuppa's own version (the webapp image build). */
  cuppaVersion: string;
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

export interface AppInfo {
  name: string;
  version: string;
  repository: string;
}
