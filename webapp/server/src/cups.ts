import { existsSync, writeFileSync, unlinkSync } from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { ENV } from "./env.js";
import { run, runOrThrow } from "./exec.js";
import { log } from "./logger.js";
import {
  IPP_OP,
  IPP_TAG,
  commonAttributes,
  groupRecords,
  ippRequest,
  logIppFailure,
  multi,
  opGroup,
  recordBoolean,
  recordNumber,
  recordString,
  recordStrings,
  values,
} from "./ipp.js";
import { advertisedName, deterministicUuid, resourceName, stripCuppaSuffix, uniqueResourceName } from "./naming.js";
import { primaryIPv4 } from "./network.js";
import { store } from "./store.js";
import { buildThermalPpd } from "./thermal/ppd.js";
import {
  defaultThermalConfig,
  loadThermalConfig,
  removeThermalConfig,
  saveThermalConfig,
  type ThermalConfig,
} from "./thermal/config.js";
import { generateThermalTestLabel } from "./thermal/testpage.js";
import type { DeviceView, JobView, PrinterView } from "./types.js";

const PRINTER_ATTRIBUTES = [
  "printer-name",
  "printer-info",
  "printer-location",
  "printer-make-and-model",
  "printer-state",
  "printer-state-reasons",
  "printer-state-message",
  "printer-is-accepting-jobs",
  "printer-is-shared",
  "printer-uri-supported",
  "device-uri",
  "printer-type",
  "color-supported",
  "document-format-supported",
  "printer-uuid",
];

const JOB_ATTRIBUTES = [
  "job-id",
  "job-name",
  "job-state",
  "job-state-reasons",
  "job-originating-user-name",
  "job-printer-uri",
  "job-printer-name",
  "job-k-octets",
  "job-media-sheets-completed",
  "time-at-creation",
  "time-at-completed",
];

export function printerStateLabel(state: number): string {
  switch (state) {
    case 3:
      return "Idle";
    case 4:
      return "Printing";
    case 5:
      return "Stopped";
    default:
      return "Unknown";
  }
}

export function jobStateLabel(state: number): string {
  switch (state) {
    case 3:
      return "Pending";
    case 4:
      return "Held";
    case 5:
      return "Printing";
    case 6:
      return "Stopped";
    case 7:
      return "Canceled";
    case 8:
      return "Aborted";
    case 9:
      return "Completed";
    default:
      return "Unknown";
  }
}

export function isActiveJobState(state: number): boolean {
  return state === 3 || state === 4 || state === 5 || state === 6;
}

interface HttpProbe {
  running: boolean;
  version: string;
}

/** Probes the local CUPS scheduler and pulls its version from the Server header. */
export async function probeCups(): Promise<HttpProbe> {
  return new Promise<HttpProbe>((resolve) => {
    const request = http.get(
      { host: ENV.cupsHost, port: ENV.ippPort, path: "/", timeout: 4000 },
      (response) => {
        const server = String(response.headers.server ?? "");
        response.resume();
        const match = /CUPS\/([\d.]+)/.exec(server);
        resolve({ running: true, version: match?.[1] ?? "unknown" });
      }
    );
    request.on("error", () => resolve({ running: false, version: "unknown" }));
    request.on("timeout", () => {
      request.destroy();
      resolve({ running: false, version: "unknown" });
    });
  });
}

async function getDefaultQueue(): Promise<string> {
  try {
    const response = await ippRequest(IPP_OP.CUPS_GET_DEFAULT, [
      opGroup([...commonAttributes(), { tag: IPP_TAG.keyword, name: "requested-attributes", value: "printer-name" }]),
    ]);
    if (response.statusCode !== 0) return "";
    for (const group of response.groups) {
      const name = values(group, "printer-name")[0];
      if (typeof name === "string") return name;
    }
  } catch {
    // CUPS may not be up yet; an empty default is fine.
  }
  return "";
}

/** Lists every CUPS queue, merged with Cuppa's display-name metadata. */
export async function listPrinters(): Promise<PrinterView[]> {
  const response = await ippRequest(IPP_OP.CUPS_GET_PRINTERS, [
    opGroup([
      ...commonAttributes(),
      ...multi(IPP_TAG.keyword, "requested-attributes", PRINTER_ATTRIBUTES),
    ]),
  ]);

  if (response.statusCode !== 0) logIppFailure("CUPS-Get-Printers", response);

  const printerGroupRecords = response.groups
    .filter((group) => group.tag === IPP_TAG.printerAttributes)
    .flatMap((group) => groupRecords(group, "printer-name"));
  const records = printerGroupRecords;
  const defaultQueue = await getDefaultQueue();
  const ip = primaryIPv4();

  const printers: PrinterView[] = [];
  for (const record of records) {
    const queue = recordString(record, "printer-name");
    if (!queue) continue;
    const meta = store.metaFor(queue);
    const info = recordString(record, "printer-info");
    const displayName = meta?.displayName?.trim() || stripCuppaSuffix(info) || queue;
    const state = recordNumber(record, "printer-state", 3);
    const shared = meta?.shared ?? recordBoolean(record, "printer-is-shared", true);
    const deviceUri = recordString(record, "device-uri");
    const thermal = loadThermalConfig(queue);
    const driver = thermal
      ? `Thermal (${thermal.dialect.toUpperCase()})`
      : meta?.driver ?? (/^(ipp|ipps|dnssd):/i.test(deviceUri) ? "IPP Everywhere" : deviceUri.startsWith("usb:") ? "USB (raw)" : "Raw");

    printers.push({
      queue,
      displayName,
      advertisedName: advertisedName(displayName),
      location: meta?.location || recordString(record, "printer-location"),
      makeAndModel: recordString(record, "printer-make-and-model"),
      state,
      stateLabel: printerStateLabel(state),
      stateReasons: recordStrings(record, "printer-state-reasons"),
      accepting: recordBoolean(record, "printer-is-accepting-jobs", true),
      enabled: state !== 5,
      shared,
      isDefault: queue === defaultQueue,
      color: recordBoolean(record, "color-supported"),
      formats: recordStrings(record, "document-format-supported"),
      uri: `ipp://${ip}:${ENV.ippPort}/printers/${queue}`,
      deviceUri,
      uuid: recordString(record, "printer-uuid") || `urn:uuid:${deterministicUuid(queue)}`,
      driver,
      thermal,
    });
  }

  printers.sort((a, b) => a.displayName.localeCompare(b.displayName));
  return printers;
}

async function existingQueues(): Promise<string[]> {
  const printers = await listPrinters();
  return printers.map((printer) => printer.queue);
}

export interface AddPrinterInput {
  deviceUri: string;
  displayName: string;
  location?: string;
  driver?: "auto" | "everywhere" | "raw";
  shared?: boolean;
  /** When set, the queue is created as a thermal label printer. */
  thermal?: Partial<ThermalConfig> | null;
}

function resolveDriver(deviceUri: string, requested: AddPrinterInput["driver"]): string {
  if (requested && requested !== "auto") return requested;
  return /^(ipp|ipps|dnssd):/i.test(deviceUri) ? "everywhere" : "raw";
}

/** Derives the printer's IPP endpoint from a raw socket/LPD address. */
function deriveIppUri(deviceUri: string): string | null {
  const match = /^(?:socket|lpd):\/\/([^/:?]+)/i.exec(deviceUri);
  return match ? `ipp://${match[1]}:631/ipp/print` : null;
}

/**
 * Checks whether a printer actually speaks IPP Everywhere at [ippUri] by asking
 * CUPS to build a driverless PPD for a throwaway queue. This is how a printer
 * that only advertises its raw port (a Brother, typically) still ends up on the
 * IPP path, where CUPS converts PDF to raster instead of sending raw bytes the
 * printer cannot read.
 */
async function probeIppEverywhere(ippUri: string): Promise<boolean> {
  const probe = `cuppa_probe_${Date.now()}`;
  try {
    const result = await run("lpadmin", ["-p", probe, "-E", "-v", ippUri, "-m", "everywhere"], { timeoutMs: 30_000 });
    return result.code === 0 && existsSync(`/etc/cups/ppd/${probe}.ppd`);
  } finally {
    await run("lpadmin", ["-x", probe]);
  }
}

/** Creates a queue and records Cuppa's display metadata for it. */
export async function addPrinter(input: AddPrinterInput): Promise<string> {
  let deviceUri = input.deviceUri.trim();
  if (!deviceUri) throw new Error("A device URI is required");

  const displayName = input.displayName.trim() || "Cuppa Printer";
  const queue = uniqueResourceName(displayName, await existingQueues());
  let driver = resolveDriver(deviceUri, input.driver);
  const shared = input.shared ?? true;
  const location = (input.location ?? "").trim();
  const thermalConfig = input.thermal ? defaultThermalConfig(input.thermal) : null;

  // A raw socket/LPD printer may still speak IPP on 631. Probe for it so CUPS
  // uses IPP Everywhere and converts PDFs to raster rather than passing a PDF
  // through to a printer that cannot read it.
  if (!thermalConfig && driver === "raw") {
    const ippUri = deriveIppUri(deviceUri);
    if (ippUri && (await probeIppEverywhere(ippUri))) {
      log.info(`Using IPP Everywhere for ${queue} at ${ippUri} (was ${deviceUri})`);
      deviceUri = ippUri;
      driver = "everywhere";
    }
  }

  const args = ["-p", queue, "-E", "-v", deviceUri];
  let ppdPath: string | null = null;
  if (thermalConfig) {
    // A generated PPD routes the job through the cuppa-thermal filter.
    ppdPath = path.join(os.tmpdir(), `cuppa-${queue}-${Date.now()}.ppd`);
    writeFileSync(ppdPath, buildThermalPpd(thermalConfig, displayName), "utf8");
    args.push("-P", ppdPath);
  } else {
    args.push("-m", driver);
  }
  args.push(
    "-D",
    advertisedName(displayName),
    "-o",
    `printer-is-shared=${shared}`,
    // Stop the queue on the first error instead of retrying a bad job forever.
    "-o",
    "printer-error-policy=stop-printer"
  );
  if (location) args.push("-L", location);

  try {
    await runOrThrow("lpadmin", args, { timeoutMs: 60_000 });
  } finally {
    if (ppdPath) {
      try {
        unlinkSync(ppdPath);
      } catch {
        // best effort
      }
    }
  }

  if (thermalConfig) saveThermalConfig(queue, thermalConfig);

  // A driverless queue that CUPS could not build a PPD for accepts jobs but
  // prints nothing. Fail loudly instead of leaving a broken queue behind.
  if (!thermalConfig && driver === "everywhere") {
    const ppdPath = `/etc/cups/ppd/${queue}.ppd`;
    if (!existsSync(ppdPath)) {
      await run("lpadmin", ["-x", queue]);
      throw new Error(
        "Could not build a driverless PPD for this printer. Make sure it is reachable and supports IPP/AirPrint, then add it by its IPP address (not its socket/raw address)."
      );
    }
  }
  if (!thermalConfig && driver === "raw") {
    log.warn(`Queue ${queue} is raw: documents will be passed through unchanged and may not print`);
  }

  store.setMeta(queue, {
    displayName,
    location,
    shared,
    createdAt: Date.now(),
    driver: thermalConfig ? `Thermal (${thermalConfig.dialect.toUpperCase()})` : driver,
  });
  log.info(
    `Added printer ${queue} (${displayName}) -> ${deviceUri} [${thermalConfig ? `thermal:${thermalConfig.dialect}` : driver}]`
  );
  return queue;
}

/** Updates a thermal queue's settings and reinstalls its PPD. */
export async function updateThermalSettings(queue: string, patch: Partial<ThermalConfig>): Promise<void> {
  const existing = loadThermalConfig(queue) ?? defaultThermalConfig();
  const next = defaultThermalConfig({ ...existing, ...patch });
  saveThermalConfig(queue, next);

  const ppdPath = path.join(os.tmpdir(), `cuppa-${queue}-${Date.now()}.ppd`);
  writeFileSync(ppdPath, buildThermalPpd(next, queue), "utf8");
  try {
    await runOrThrow("lpadmin", ["-p", queue, "-P", ppdPath], { timeoutMs: 30_000 });
  } finally {
    try {
      unlinkSync(ppdPath);
    } catch {
      // best effort
    }
  }
  log.info(`Updated thermal settings for ${queue}`);
}

export async function renamePrinter(queue: string, displayName: string): Promise<void> {
  const clean = displayName.trim();
  if (!clean) throw new Error("A printer name is required");
  await runOrThrow("lpadmin", ["-p", queue, "-D", advertisedName(clean)]);
  store.patchMeta(queue, { displayName: clean });
  log.info(`Renamed ${queue} to "${clean}"`);
}

export async function setLocation(queue: string, location: string): Promise<void> {
  await runOrThrow("lpadmin", ["-p", queue, "-L", location.trim()]);
  store.patchMeta(queue, { location: location.trim() });
}

export async function setShared(queue: string, shared: boolean): Promise<void> {
  await runOrThrow("lpadmin", ["-p", queue, "-o", `printer-is-shared=${shared}`]);
  store.patchMeta(queue, { shared });
}

export async function removePrinter(queue: string): Promise<void> {
  await runOrThrow("lpadmin", ["-x", queue]);
  store.removeMeta(queue);
  removeThermalConfig(queue);
  log.info(`Removed printer ${queue}`);
}

export async function setDefaultPrinter(queue: string): Promise<void> {
  await runOrThrow("lpadmin", ["-d", queue]);
}

export async function setEnabled(queue: string, enabled: boolean): Promise<void> {
  await runOrThrow(enabled ? "cupsenable" : "cupsdisable", [queue]);
}

export async function setAccepting(queue: string, accepting: boolean): Promise<void> {
  await runOrThrow(accepting ? "cupsaccept" : "cupsreject", [queue]);
}

/** Sends a file to a queue through CUPS' own `lp` client. */
export async function printFile(queue: string, filePath: string, title: string): Promise<void> {
  await runOrThrow("lp", ["-d", queue, "-t", title, filePath], { timeoutMs: 60_000 });
}

/** Prints CUPS' standard test page, falling back to a generated text page. */
export async function testPrint(queue: string): Promise<void> {
  // Thermal queues get a pre-encoded diagnostic label sent raw, bypassing the
  // filter (the bytes are already the printer's command language).
  const thermalConfig = loadThermalConfig(queue);
  if (thermalConfig) {
    const file = path.join(os.tmpdir(), `cuppa-thermal-test-${Date.now()}.tspl`);
    writeFileSync(file, generateThermalTestLabel(thermalConfig, queue));
    try {
      await runOrThrow("lp", ["-d", queue, "-o", "raw", "-t", "Cuppa Thermal Test", file], { timeoutMs: 60_000 });
    } finally {
      try {
        unlinkSync(file);
      } catch {
        // best effort
      }
    }
    return;
  }

  const testPages = [ENV.testPage, "/usr/share/cups/data/default-testpage.pdf", "/usr/share/cups/data/testprint"];
  const testPage = testPages.find((candidate) => existsSync(candidate));
  if (testPage) {
    await printFile(queue, testPage, "Cuppa Test Page");
    return;
  }
  const temporary = path.join(os.tmpdir(), `cuppa-test-${Date.now()}.txt`);
  writeFileSync(
    temporary,
    [
      "Cuppa test page",
      "===============",
      "",
      "If you can read this, the printer is shared through Cuppa",
      "and reachable from macOS, iOS and Android over the network.",
      "",
      `Printed: ${new Date().toISOString()}`,
      "",
    ].join("\n"),
    "utf8"
  );
  try {
    await printFile(queue, temporary, "Cuppa Test Page");
  } finally {
    try {
      unlinkSync(temporary);
    } catch {
      // best effort
    }
  }
}

/** Active or completed jobs across every queue. */
export async function listJobs(scope: "active" | "history" | "all"): Promise<JobView[]> {
  const which = scope === "active" ? "not-completed" : scope === "history" ? "completed" : "all";
  const response = await ippRequest(IPP_OP.CUPS_GET_JOBS, [
    opGroup([
      ...commonAttributes(),
      { tag: IPP_TAG.keyword, name: "which-jobs", value: which },
      { tag: IPP_TAG.boolean, name: "my-jobs", value: false },
      ...multi(IPP_TAG.keyword, "requested-attributes", JOB_ATTRIBUTES),
    ]),
  ]);

  if (response.statusCode !== 0) logIppFailure("CUPS-Get-Jobs", response);

  const jobGroupRecords = response.groups
    .filter((group) => group.tag === IPP_TAG.jobAttributes)
    .flatMap((group) => groupRecords(group, "job-id"));

  const jobs: JobView[] = jobGroupRecords.map((record) => {
    const id = recordNumber(record, "job-id");
    const state = recordNumber(record, "job-state", 3);
    const printerUri = recordString(record, "job-printer-uri");
    const printer =
      recordString(record, "job-printer-name") ||
      printerUri.split("/").filter(Boolean).pop() ||
      "unknown";
    const completedAt = recordNumber(record, "time-at-completed", 0);
    return {
      id,
      name: recordString(record, "job-name", `Job ${id}`),
      printer,
      user: recordString(record, "job-originating-user-name"),
      state,
      stateLabel: jobStateLabel(state),
      sizeBytes: recordNumber(record, "job-k-octets") * 1024,
      createdAt: recordNumber(record, "time-at-creation") * 1000,
      completedAt: completedAt > 0 ? completedAt * 1000 : null,
      active: isActiveJobState(state),
    };
  });

  jobs.sort((a, b) => (b.createdAt || b.id) - (a.createdAt || a.id));
  return jobs;
}

export async function cancelJob(id: number): Promise<void> {
  await runOrThrow("cancel", [String(id)]);
}

/** Parses `lpinfo -l -v` long device listings. */
export async function discoverDevices(): Promise<DeviceView[]> {
  const result = await run("lpinfo", ["-l", "-v"], { timeoutMs: 45_000 });
  if (result.code !== 0 && !result.stdout) return [];

  const devices: DeviceView[] = [];
  const seen = new Set<string>();
  let current: Partial<DeviceView> | null = null;

  const flush = () => {
    if (current?.uri && !seen.has(current.uri)) {
      seen.add(current.uri);
      devices.push({
        uri: current.uri,
        kind: current.kind ?? "network",
        makeAndModel: current.makeAndModel ?? "",
        info: current.info ?? "",
      });
    }
    current = null;
  };

  for (const rawLine of result.stdout.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line) continue;
    if (line.startsWith("Device:")) {
      flush();
      const uri = /uri\s*=\s*(.+)$/.exec(line)?.[1]?.trim();
      current = { uri };
      continue;
    }
    if (!current) continue;
    const field = /^(\S+)\s*=\s*(.*)$/.exec(line);
    if (!field) continue;
    const key = field[1] ?? "";
    const value = field[2] ?? "";
    if (key === "class") current.kind = value.trim();
    else if (key === "info") current.info = value.trim();
    else if (key === "make-and-model") current.makeAndModel = value.trim();
  }
  flush();

  // Fall back to the compact `lpinfo -v` form ("network ipp://...") if the long
  // listing is not what this CUPS build produced.
  if (devices.length === 0) {
    for (const line of result.stdout.split(/\r?\n/)) {
      const match = /^(\S+)\s+(\S+:\/\/\S+)$/.exec(line.trim());
      if (match && !seen.has(match[2]!)) {
        seen.add(match[2]!);
        devices.push({ uri: match[2]!, kind: match[1]!, makeAndModel: "", info: "" });
      }
    }
  }

  // Only surface real, usable device URIs.
  return devices.filter((device) => device.uri.includes("://"));
}

export interface DriverView {
  id: string;
  name: string;
}

/** Lists the PPD/driver identifiers CUPS knows about (for the advanced add flow). */
export async function listDrivers(): Promise<DriverView[]> {
  const result = await run("lpinfo", ["-m"], { timeoutMs: 30_000 });
  const drivers: DriverView[] = [];
  for (const line of result.stdout.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    const match = /^(\S+)\s+(.*)$/.exec(trimmed);
    if (!match) continue;
    drivers.push({ id: match[1]!, name: match[2]! });
  }
  return drivers;
}

export function localQueueName(displayName: string): string {
  return resourceName(displayName);
}
