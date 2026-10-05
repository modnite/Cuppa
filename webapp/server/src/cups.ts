import { existsSync, readFileSync, readdirSync, writeFileSync, unlinkSync } from "node:fs";
import { open } from "node:fs/promises";
import http from "node:http";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { ENV } from "./env.js";
import { run, runOrThrow, runWithInput } from "./exec.js";
import { log } from "./logger.js";
import {
  IPP_OP,
  IPP_TAG,
  commonAttributes,
  groupRecords,
  ippRequest,
  ippRequestTo,
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
  listThermalQueues,
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
    if (response.statusCode >= 0x0100) return "";
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

  if (response.statusCode >= 0x0100) logIppFailure("CUPS-Get-Printers", response);

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
  driver?: string;
  shared?: boolean;
  /** The printer's make and model, when known from discovery (used to pick a driver). */
  makeAndModel?: string;
  /** When set, the queue is created as a thermal label printer. */
  thermal?: Partial<ThermalConfig> | null;
  /** A base64-encoded PPD to install instead of a driver id. */
  ppdBase64?: string;
}

/** A PPD starts with *PPD-Adobe or a *FormatVersion line. */
export function looksLikePpd(buffer: Buffer): boolean {
  return /\*PPD-Adobe|\*FormatVersion|\*LanguageEncoding/.test(buffer.toString("latin1", 0, 4096));
}

function resolveDriver(deviceUri: string, requested: AddPrinterInput["driver"]): string {
  if (requested && requested !== "auto") return requested;
  return /^(ipp|ipps|dnssd):/i.test(deviceUri) ? "everywhere" : "raw";
}

/** Derives the printer's IPP endpoint from a raw socket/LPD address. */
/** IPP resource paths used by different vendors. Brother often answers on
 * `/ipp/port1` rather than the AirPrint-standard `/ipp/print`. */
const IPP_PATHS = ["/ipp/print", "/ipp/port1", "/ipp/printer", "/ipp", "/"];

/** Candidate IPP Everywhere URIs for a raw socket/LPD device. */
function candidateIppUris(deviceUri: string): string[] {
  const match = /^(?:socket|lpd):\/\/([^/:?]+)/i.exec(deviceUri);
  if (!match) return [];
  return IPP_PATHS.map((suffix) => `ipp://${match[1]}:631${suffix}`);
}

/**
 * Checks whether a printer actually speaks IPP Everywhere by asking CUPS to
 * build a driverless PPD for a throwaway queue. Tries each candidate path and
 * returns the first one that works, so a Brother that only answers on
 * `/ipp/port1` still ends up on the IPP path instead of a raw queue.
 */
async function probeIppEverywhere(candidates: string[]): Promise<string | null> {
  for (const ippUri of candidates) {
    const probe = `cuppa_probe_${Date.now()}`;
    try {
      const result = await run("lpadmin", ["-p", probe, "-E", "-v", ippUri, "-m", "everywhere"], { timeoutMs: 20_000 });
      if (result.code === 0 && existsSync(`/etc/cups/ppd/${probe}.ppd`)) return ippUri;
    } finally {
      await run("lpadmin", ["-x", probe]);
    }
  }
  return null;
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
    const ippUri = await probeIppEverywhere(candidateIppUris(deviceUri));
    if (ippUri) {
      log.info(`Using IPP Everywhere for ${queue} at ${ippUri} (was ${deviceUri})`);
      deviceUri = ippUri;
      driver = "everywhere";
    }
  }

  // A monochrome Brother laser that only exposes its raw port needs the brlaser
  // driver; otherwise a PDF is passed through and the printer ejects blank pages.
  if (!thermalConfig && driver === "raw" && /brother/i.test(input.makeAndModel ?? "")) {
    const brlaser = (await listDrivers()).find((candidate) => /brlaser/i.test(candidate.id));
    if (brlaser) {
      log.info(`Using ${brlaser.id} for ${queue} (Brother)`);
      driver = brlaser.id;
    }
  }

  // A thermal printer on USB goes through the paced cuppa-usb backend: the
  // stock USB backend sends the whole label in large unpaced writes, which the
  // Rollo X1038's firmware ACKs and then silently drops without firing the head.
  if (thermalConfig && /^usb:\/\//i.test(deviceUri)) {
    deviceUri = deviceUri.replace(/^usb:/i, "cuppa-usb:");
  }

  const uploadedPpd = input.ppdBase64 ? Buffer.from(input.ppdBase64, "base64") : null;
  if (uploadedPpd && !looksLikePpd(uploadedPpd)) {
    throw new Error("That file does not look like a PPD (no *PPD-Adobe or *FormatVersion header).");
  }

  const args = ["-p", queue, "-E", "-v", deviceUri];
  let ppdPath: string | null = null;
  if (thermalConfig) {
    // A generated PPD routes the job through the cuppa-thermal filter.
    ppdPath = path.join(os.tmpdir(), `cuppa-${queue}-${Date.now()}.ppd`);
    writeFileSync(ppdPath, buildThermalPpd(thermalConfig, displayName), "utf8");
    args.push("-P", ppdPath);
  } else if (uploadedPpd) {
    // A vendor or compatible PPD supplied by the user.
    ppdPath = path.join(os.tmpdir(), `cuppa-upload-${queue}-${Date.now()}.ppd`);
    writeFileSync(ppdPath, uploadedPpd);
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
    driver: thermalConfig
      ? `Thermal (${thermalConfig.dialect.toUpperCase()})`
      : uploadedPpd
        ? "Uploaded PPD"
        : driver,
  });
  log.info(
    `Added printer ${queue} (${displayName}) -> ${deviceUri} [${thermalConfig ? `thermal:${thermalConfig.dialect}` : driver}]`
  );
  return queue;
}

/** Installs a user-supplied PPD on an existing queue. */
export async function installPpd(queue: string, ppd: Buffer): Promise<void> {
  if (!looksLikePpd(ppd)) {
    throw new Error("That file does not look like a PPD (no *PPD-Adobe or *FormatVersion header).");
  }
  const file = path.join(os.tmpdir(), `cuppa-${queue}-${Date.now()}.ppd`);
  writeFileSync(file, ppd);
  try {
    await runOrThrow("lpadmin", ["-p", queue, "-P", file], { timeoutMs: 30_000 });
  } finally {
    try {
      unlinkSync(file);
    } catch {
      // best effort
    }
  }
  store.patchMeta(queue, { driver: "Uploaded PPD" });
  log.info(`Installed an uploaded PPD for ${queue}`);
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
  const output = await runOrThrow("lp", ["-d", queue, "-t", title, filePath], { timeoutMs: 60_000 });
  log.info(`Submitted "${title}" to ${queue}: ${output.trim()}`);
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
      const output = await runOrThrow("lp", ["-d", queue, "-o", "raw", "-t", "Cuppa Thermal Test", file], { timeoutMs: 60_000 });
      log.info(`Submitted thermal test label to ${queue}: ${output.trim()}`);
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

/**
 * Points existing thermal USB queues at the paced cuppa-usb backend. Queues
 * created before that backend existed use a plain `usb://` URI, which makes the
 * Rollo X1038 drop the whole label.
 */
export async function migrateThermalUsbQueues(): Promise<void> {
  const thermal = new Set(listThermalQueues());
  if (thermal.size === 0) return;
  let printers: PrinterView[];
  try {
    printers = await listPrinters();
  } catch {
    return;
  }
  for (const printer of printers) {
    if (!thermal.has(printer.queue)) continue;
    if (!/^usb:\/\//i.test(printer.deviceUri)) continue;
    const next = printer.deviceUri.replace(/^usb:/i, "cuppa-usb:");
    const result = await run("lpadmin", ["-p", printer.queue, "-v", next]);
    if (result.code === 0) log.info(`Migrated ${printer.queue} to the paced USB backend`);
  }
}

/** Active or completed jobs across every queue. */
export async function listJobs(scope: "active" | "history" | "all"): Promise<JobView[]> {
  const which = scope === "active" ? "not-completed" : scope === "history" ? "completed" : "all";
  const response = await ippRequest(IPP_OP.GET_JOBS, [
    opGroup([
      ...commonAttributes(),
      // Get-Jobs requires the server URI and a user name. (CUPS-Get-Jobs is a
      // different operation and returns 400 here, which is why the Jobs page
      // was always empty.)
      { tag: IPP_TAG.uri, name: "printer-uri", value: `ipp://${ENV.cupsHost}:${ENV.ippPort}/` },
      { tag: IPP_TAG.nameWithoutLanguage, name: "requesting-user-name", value: "root" },
      { tag: IPP_TAG.keyword, name: "which-jobs", value: which },
      ...multi(IPP_TAG.keyword, "requested-attributes", JOB_ATTRIBUTES),
    ]),
  ]);

  if (response.statusCode >= 0x0100) logIppFailure("Get-Jobs", response);

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
      stateReasons: recordStrings(record, "job-state-reasons").filter((reason) => reason !== "none"),
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

/** Parses `avahi-browse -rtp <type>` lines into direct IPP device URIs. */
export function parseAvahiBrowse(stdout: string, secure: boolean): DeviceView[] {
  const devices: DeviceView[] = [];
  for (const line of stdout.split(/\r?\n/)) {
    if (!line.startsWith("=")) continue;
    const parts = line.split(";");
    if (parts.length < 10) continue;
    const name = parts[3] ?? "";
    const address = parts[7] ?? "";
    const port = parts[8] ?? "631";
    const txt = parts.slice(9).join(";");
    if (!address) continue;

    const attrs = new Map<string, string>();
    // TXT entries arrive as space-separated quoted pairs, e.g.
    //   "rp=ipp/print" "ty=Brother MFC-L2717DW"
    // so split on the quote boundaries to keep spaces inside values.
    const quoted = txt.match(/"[^"]*"/g) ?? [];
    if (quoted.length > 0) {
      for (const raw of quoted) {
        const entry = raw.slice(1, -1);
        const equals = entry.indexOf("=");
        if (equals > 0) attrs.set(entry.slice(0, equals), entry.slice(equals + 1));
      }
    } else {
      for (const entry of txt.split(/[,\s]+/)) {
        const equals = entry.indexOf("=");
        if (equals > 0) attrs.set(entry.slice(0, equals), entry.slice(equals + 1));
      }
    }
    const resource = (attrs.get("rp") ?? "ipp/print").replace(/^\//, "");
    const scheme = secure ? "ipps" : "ipp";
    devices.push({
      uri: `${scheme}://${address}:${port}/${resource}`,
      kind: "network",
      makeAndModel: attrs.get("ty") ?? attrs.get("usb_MDL") ?? name,
      info: name,
    });
  }
  return devices;
}

/**
 * CUPS' own dnssd/driverless discovery is unreliable when the container uses
 * the host's Avahi (the compat DNS-SD layer wants a socket the container does
 * not have), but `avahi-browse` over the host D-Bus works. So browse mDNS
 * ourselves and add the IPP entries directly.
 */
async function avahiDevices(): Promise<DeviceView[]> {
  const [ipp, ipps] = await Promise.all([
    run("avahi-browse", ["-rtp", "_ipp._tcp"], { timeoutMs: 8000 }),
    run("avahi-browse", ["-rtp", "_ipps._tcp"], { timeoutMs: 8000 }),
  ]);
  return [...parseAvahiBrowse(ipp.stdout, false), ...parseAvahiBrowse(ipps.stdout, true)];
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

  // Add mDNS-discovered IPP printers, which CUPS' own dnssd backend misses when
  // the container borrows the host's Avahi.
  for (const device of await avahiDevices()) {
    if (!device.uri || seen.has(device.uri)) continue;
    seen.add(device.uri);
    devices.push(device);
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

/** Best-effort snapshot of CUPS and the backends, for troubleshooting. */
export async function collectDiagnostics(): Promise<Record<string, string>> {
  const text = async (command: string, args: string[]): Promise<string> => {
    const result = await run(command, args, { timeoutMs: 15_000 });
    return (result.stdout || result.stderr || `(exit ${result.code})`).trim();
  };
  const errorLog = (() => {
    try {
      return readFileSync("/var/log/cups/error_log", "utf8").split(/\r?\n/).slice(-200).join("\n");
    } catch {
      return "(no /var/log/cups/error_log)";
    }
  })();
  const [
    devices,
    queues,
    printers,
    jobs,
    backends,
    thermal,
    usbDevices,
    usbTree,
    usbNodes,
    printerOptions,
    config,
    services,
    mdns,
  ] = await Promise.all([
    // `lpinfo -v` returns non-zero when any discovery backend hiccups (a broken
    // driverless/ippfind does this), so keep the output it did produce.
    text("sh", ["-c", "lpinfo -v 2>&1 || true"]),
    text("lpstat", ["-v"]),
    text("lpstat", ["-p", "-d"]),
    text("lpstat", ["-W", "all", "-o"]),
    text("ls", ["-l", "/usr/lib/cups/backend"]),
    Promise.resolve(listThermalQueues().join("\n")),
    text("lsusb", []),
    text("lsusb", ["-t"]),
    text("sh", ["-c", "ls -l /dev/usb/lp* /dev/bus/usb/*/* 2>/dev/null || true"]),
    text("sh", [
      "-c",
      "for p in $(lpstat -v 2>/dev/null | sed -n 's/^device for \\(.*\\): .*/\\1/p'); do echo \"# $p\"; lpoptions -p \"$p\" -l 2>/dev/null | head -80; echo; done",
    ]),
    text("sh", ["-c", "grep -vE '^\\s*#|^\\s*$' /etc/cups/cupsd.conf | head -80"]),
    text("sh", [
      "-c",
      "ps -eo pid,comm,args 2>/dev/null | grep -E 'cupsd|avahi|ipp-usb|dbus-daemon|node ' | grep -v grep || true",
    ]),
    text("sh", [
      "-c",
      "echo '-- sockets --'; ls -l /run/dbus/system_bus_socket /host-dbus/system_bus_socket /run/avahi-daemon/socket 2>&1; echo; echo '-- _ipp._tcp --'; timeout 6 avahi-browse -rt _ipp._tcp 2>&1 | head -40 || true",
    ]),
  ]);
  return {
    devices,
    queues,
    printers,
    jobs,
    backends,
    thermal,
    usbDevices,
    usbTree,
    usbNodes,
    printerOptions,
    cupsdConf: config,
    services,
    mdns,
    errorLog,
  };
}

const PACED_USB_BACKEND = process.env.CUPPA_PACED_USB_BACKEND || "/usr/lib/cups/backend/cuppa-usb";

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

/** The plain `usb://` URI behind a queue, or "" when the queue is not USB. */
function usbDeviceUri(deviceUri: string): string {
  if (/^usb:\/\//i.test(deviceUri)) return deviceUri;
  if (/^cuppa-usb:\/\//i.test(deviceUri)) return deviceUri.replace(/^cuppa-usb:/i, "usb:");
  return "";
}

/** First kernel usblp character device, if the printer is bound to one. */
function findUsbPrintDevice(): string | null {
  try {
    const entries = readdirSync("/dev/usb")
      .filter((name) => /^lp\d+$/.test(name))
      .sort();
    return entries.length > 0 ? `/dev/usb/${entries[0]}` : null;
  } catch {
    return null;
  }
}

/**
 * Sends a diagnostic label straight to a USB printer, bypassing cupsd.
 *
 * This is the fastest way to tell apart a broken queue from a printer that
 * cannot be driven over USB at all: the stock backend, the paced Cuppa backend
 * and a raw kernel-device write are each tried and reported separately.
 */
export async function usbSelfTest(queue: string): Promise<UsbSelfTestResult> {
  const printer = (await listPrinters()).find((candidate) => candidate.queue === queue);
  if (!printer) throw new Error(`No printer named ${queue}`);
  const usbUri = usbDeviceUri(printer.deviceUri);
  if (!usbUri) {
    throw new Error("This printer is not on a USB device URI, so a USB self-test does not apply.");
  }

  const config = loadThermalConfig(queue) ?? defaultThermalConfig();
  const payload = generateThermalTestLabel(config, queue);
  const steps: UsbSelfTestStep[] = [];

  const runBackend = async (
    name: string,
    detail: string,
    command: string,
    uri: string
  ): Promise<UsbSelfTestStep> => {
    const started = Date.now();
    // CUPS calls a backend as: backend job-id user title copies options [file],
    // with the device URI in DEVICE_URI. The stock USB backend prints its usage
    // and exits 1 if the URI is passed as an argument instead.
    const result = await runWithInput(
      command,
      ["cuppa-selftest", "root", "Cuppa USB self-test", "1", "cuppa-test=1"],
      payload,
      { timeoutMs: 30_000, env: { DEVICE_URI: uri } }
    );
    return {
      name,
      detail,
      command: `${command} (DEVICE_URI=${uri})`,
      code: result.code,
      durationMs: Date.now() - started,
      ok: result.code === 0,
      output: (result.stdout + result.stderr).trim(),
    };
  };

  const writeDevice = async (device: string, paced: boolean): Promise<UsbSelfTestStep> => {
    const started = Date.now();
    const label = paced ? "Kernel device write (paced)" : "Kernel device write";
    const detail = `Writes straight to ${device}, bypassing libusb and CUPS entirely${
      paced ? ", 1 KiB at a time" : ""
    }.`;
    let handle: Awaited<ReturnType<typeof open>> | undefined;
    try {
      handle = await open(device, "w");
      if (paced) {
        for (let offset = 0; offset < payload.length; offset += 1024) {
          await handle.write(payload.subarray(offset, offset + 1024));
          await new Promise((resolve) => setTimeout(resolve, 10));
        }
      } else {
        await handle.write(payload);
      }
      return {
        name: label,
        detail,
        command: `write ${payload.length} bytes -> ${device}`,
        code: 0,
        durationMs: Date.now() - started,
        ok: true,
        output: `Wrote ${payload.length} bytes to ${device}`,
      };
    } catch (error) {
      return {
        name: label,
        detail,
        command: `write ${payload.length} bytes -> ${device}`,
        code: 1,
        durationMs: Date.now() - started,
        ok: false,
        output: error instanceof Error ? error.message : String(error),
      };
    } finally {
      if (handle) await handle.close().catch(() => undefined);
    }
  };

  // Kernel writes first: the stock libusb backend detaches usblp and can wedge
  // these printers, so it must never run before the path that actually prints.
  const device = findUsbPrintDevice();
  if (device) {
    steps.push(await writeDevice(device, true));
    steps.push(await writeDevice(device, false));
  }

  steps.push(
    await runBackend(
      "Cuppa USB backend",
      "Runs the queue's real backend end to end (kernel usblp path, 1 KiB / 10 ms).",
      PACED_USB_BACKEND,
      `cuppa-usb://${usbUri.replace(/^usb:\/\//i, "")}`
    )
  );

  log.info(`USB self-test for ${queue}: ${steps.map((step) => `${step.name}=${step.ok ? "ok" : "fail"}`).join(", ")}`);
  return { queue, deviceUri: printer.deviceUri, usbUri, bytes: payload.length, steps };
}

// ---------------------------------------------------------------------------
// Network printer diagnostics
// ---------------------------------------------------------------------------

export interface SupplyLevel {
  name: string;
  color: string;
  type: string;
  /** 0-100, or a negative IPP sentinel (-1 unknown, -2 unknown, -3 unavailable). */
  level: number;
}

export interface NetworkProbe {
  label: string;
  deviceUri: string;
  scheme: string;
  host: string;
  port: number;
  tcp: { ok: boolean; ms: number; error: string };
  ipp: {
    attempted: boolean;
    ok: boolean;
    statusCode: number;
    path: string;
    makeAndModel: string;
    state: number;
    stateLabel: string;
    stateReasons: string[];
    stateMessage: string;
    supplies: SupplyLevel[];
    error: string;
  } | null;
}

interface ParsedDeviceUri {
  scheme: string;
  host: string;
  port: number;
  path: string;
  secure: boolean;
}

const DEFAULT_PORTS: Record<string, number> = {
  ipp: 631,
  ipps: 631,
  http: 80,
  https: 443,
  socket: 9100,
  lpd: 515,
};

/** Splits a device URI into the pieces a network probe needs. */
export function parseDeviceUri(uri: string): ParsedDeviceUri | null {
  const match = /^([a-z0-9+.-]+):\/\/([^/?#]+)(\/[^?#]*)?/i.exec(uri.trim());
  if (!match) return null;
  const scheme = match[1]!.toLowerCase();
  const authority = match[2]!;
  const path = match[3] && match[3] !== "" ? match[3] : "/";

  // Host may be an IPv6 literal in brackets; take the last colon as the port
  // separator only when what follows is all digits.
  let host = authority;
  let portText = "";
  if (authority.startsWith("[")) {
    const close = authority.indexOf("]");
    if (close === -1) return null;
    host = authority.slice(0, close + 1);
    portText = authority.slice(close + 2);
  } else {
    const colon = authority.lastIndexOf(":");
    if (colon !== -1 && /^\d+$/.test(authority.slice(colon + 1))) {
      host = authority.slice(0, colon);
      portText = authority.slice(colon + 1);
    }
  }

  if (!host) return null;
  return {
    scheme,
    host,
    port: portText ? Number(portText) : DEFAULT_PORTS[scheme] ?? 0,
    path,
    secure: scheme === "ipps" || scheme === "https",
  };
}

/** Opens a TCP connection and reports how long it took. */
function tcpProbe(host: string, port: number, timeoutMs = 4000): Promise<{ ok: boolean; ms: number; error: string }> {
  return new Promise((resolve) => {
    if (!port) {
      resolve({ ok: false, ms: 0, error: "No port in the device URI" });
      return;
    }
    const started = Date.now();
    const socket = net.connect({ host, port });
    let settled = false;
    const done = (ok: boolean, error = ""): void => {
      if (settled) return;
      settled = true;
      socket.destroy();
      resolve({ ok, ms: Date.now() - started, error });
    };
    socket.setTimeout(timeoutMs);
    socket.once("connect", () => done(true));
    socket.once("timeout", () => done(false, "timed out"));
    socket.once("error", (error) => done(false, error.message));
  });
}

/** Asks a printer for its own attributes over IPP, trying each path in turn. */
async function ippProbe(
  host: string,
  port: number,
  secure: boolean,
  paths: string[]
): Promise<NonNullable<NetworkProbe["ipp"]>> {
  let lastError = "no IPP response";
  let lastStatus = -1;

  for (const path of paths) {
    try {
      const printerUri = `${secure ? "ipps" : "ipp"}://${host}:${port}${path}`;
      const response = await ippRequestTo(
        { host, port, path, secure },
        IPP_OP.GET_PRINTER_ATTRIBUTES,
        [
          opGroup([
            ...commonAttributes(),
            { tag: IPP_TAG.uri, name: "printer-uri", value: printerUri },
            ...multi(IPP_TAG.keyword, "requested-attributes", [
              "printer-make-and-model",
              "printer-info",
              "printer-state",
              "printer-state-reasons",
              "printer-state-message",
              "marker-levels",
              "marker-colors",
              "marker-names",
              "marker-types",
              "printer-alert",
              "printer-alert-description",
            ]),
          ]),
        ],
        6000
      );
      lastStatus = response.statusCode;
      const group = response.groups.find((candidate) => candidate.tag === IPP_TAG.printerAttributes);
      // Any status below 0x0100 is a success. 0x0001 ("ok-ignored-or-substituted-
      // attributes") is common on printers that don't accept every attribute we
      // asked for, and must not be treated as a failure.
      if (response.statusCode >= 0x0100 || !group) {
        lastError = `IPP status 0x${response.statusCode.toString(16)}`;
        continue;
      }

      const state = Number(values(group, "printer-state")[0] ?? 0);
      const levels = values(group, "marker-levels").filter((value): value is number => typeof value === "number");
      const colors = values(group, "marker-colors").filter((value): value is string => typeof value === "string");
      const names = values(group, "marker-names").filter((value): value is string => typeof value === "string");
      const types = values(group, "marker-types").filter((value): value is string => typeof value === "string");
      const supplies: SupplyLevel[] = levels.map((level, index) => ({
        name: names[index] ?? `Supply ${index + 1}`,
        color: colors[index] ?? "",
        type: types[index] ?? "",
        level,
      }));

      return {
        attempted: true,
        ok: true,
        statusCode: response.statusCode,
        path,
        makeAndModel: String(values(group, "printer-make-and-model")[0] ?? ""),
        state,
        stateLabel: printerStateLabel(state),
        stateReasons: values(group, "printer-state-reasons").filter(
          (value): value is string => typeof value === "string" && value !== "none"
        ),
        stateMessage: String(values(group, "printer-state-message")[0] ?? ""),
        supplies,
        error: "",
      };
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error);
    }
  }

  return {
    attempted: true,
    ok: false,
    statusCode: lastStatus,
    path: "",
    makeAndModel: "",
    state: 0,
    stateLabel: "",
    stateReasons: [],
    stateMessage: "",
    supplies: [],
    error: lastError,
  };
}

/** Reachability and live IPP state for one network device URI. */
export async function probeNetworkTarget(label: string, uri: string): Promise<NetworkProbe> {
  const parsed = parseDeviceUri(uri);
  if (!parsed) {
    return {
      label,
      deviceUri: uri,
      scheme: "?",
      host: "",
      port: 0,
      tcp: { ok: false, ms: 0, error: "Unrecognised device URI" },
      ipp: null,
    };
  }

  // A dnssd:// URI names a Bonjour service, not an address; there is nothing to
  // connect to until mDNS resolves it, which CUPS does when it prints.
  if (parsed.scheme === "dnssd") {
    return {
      label,
      deviceUri: uri,
      scheme: parsed.scheme,
      host: parsed.host,
      port: parsed.port,
      tcp: { ok: false, ms: 0, error: "Bonjour service name; resolved by CUPS when printing" },
      ipp: null,
    };
  }

  const tcp = await tcpProbe(parsed.host, parsed.port);

  const isIpp = ["ipp", "ipps", "http", "https"].includes(parsed.scheme);
  const maybeIpp = isIpp || ["socket", "lpd"].includes(parsed.scheme) || parsed.port === 631;
  let ipp: NetworkProbe["ipp"] = null;
  if (maybeIpp) {
    ipp = await ippProbe(parsed.host, isIpp ? parsed.port : 631, parsed.secure, isIpp ? [parsed.path] : IPP_PATHS);
  }

  return {
    label,
    deviceUri: uri,
    scheme: parsed.scheme,
    host: parsed.host,
    port: parsed.port,
    tcp,
    ipp,
  };
}

/** Probes every non-USB queue, so network printers are diagnosed too. */
export async function networkDiagnostics(): Promise<NetworkProbe[]> {
  const printers = await listPrinters();
  const targets = printers.filter((printer) => !/^(cuppa-)?usb:/i.test(printer.deviceUri));
  return Promise.all(targets.map((printer) => probeNetworkTarget(printer.displayName, printer.deviceUri)));
}

/** Probes an arbitrary address, e.g. one a discovery scan surfaced. */
export async function probeUri(uri: string, label?: string): Promise<NetworkProbe> {
  const trimmed = uri.trim();
  if (!trimmed) throw new Error("A device URI is required");
  return probeNetworkTarget(label?.trim() || trimmed, trimmed);
}

export function localQueueName(displayName: string): string {
  return resourceName(displayName);
}
