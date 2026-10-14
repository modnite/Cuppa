import { spawn, type ChildProcess } from "node:child_process";
import { ENV } from "./env.js";
import { log } from "./logger.js";
import { mdnsAdvertiser, type AdvertisedService } from "./mdns-internal.js";
import { primaryIPv4 } from "./network.js";
import type { CuppaSettings, PrinterView } from "./types.js";

/**
 * Publishes each shared queue over mDNS/DNS-SD.
 *
 * The records mirror the Android app's `NetworkPrinterAdvertiser` so the same
 * printer shared from either front end looks identical to macOS, iOS and
 * Android clients: the service name is the user's chosen name with the
 * printer's location in parentheses (or " (Cuppa)" when no location is set),
 * and the TXT records carry the driverless formats.
 *
 * Advertising uses the host's Avahi when the container is borrowing it (the
 * path that works on the NAS), and falls back to Cuppa's own mDNS responder
 * (`mdns-internal.ts`) if publishing through the host is refused. The fallback
 * is not permanent: the host is retried a few minutes later, so a transient
 * refusal does not leave a second responder running forever.
 */

/** Driverless formats worth advertising, in preference order. */
const PREFERRED_FORMATS = [
  "application/pdf",
  "application/postscript",
  "image/pwg-raster",
  "image/jpeg",
  "image/png",
  "image/tiff",
  "application/octet-stream",
];

export function documentFormats(printer: PrinterView): string {
  const available = new Set(printer.formats);
  const chosen = PREFERRED_FORMATS.filter((format) => available.has(format));
  // Only claim what the queue can actually accept: a raw queue must not
  // advertise a PDF claim it cannot honour, or clients send a PDF that the
  // backend passes through unchanged and prints blank.
  if (chosen.length === 0) chosen.push("application/octet-stream");
  return chosen.join(",");
}

function bareUuid(printer: PrinterView): string {
  return printer.uuid.replace(/^urn:uuid:/i, "");
}

export function txtRecords(printer: PrinterView, settings: CuppaSettings): string[] {
  const model = printer.makeAndModel.trim() || "Cuppa Printer";
  const records: string[] = [
    `rp=printers/${printer.queue}`,
    `pdl=${documentFormats(printer)}`,
    `ty=${model}`,
    // Real IPP Everywhere printers publish the queue's admin URL; some clients
    // (including Android's print service) use it to open the printer's page.
    `adminurl=ipp://${primaryIPv4()}:${ENV.ippPort}/printers/${printer.queue}`,
    `Color=${printer.color ? "T" : "F"}`,
    "Duplex=F",
    "txtvers=1",
    "qtotal=1",
    "note=Cuppa Print Server",
    `product=(${model})`,
    "kind=document",
    "PaperMax=legal-A4",
    "Copies=T",
    "Transparent=T",
    "Binary=T",
    "TBCP=F",
    "Fax=F",
    "Scan=F",
    "priority=0",
    "URF=none",
    `UUID=${bareUuid(printer)}`,
  ];
  if (settings.tlsEnabled) records.push("TLS=1.2");
  return records;
}

/** The services to advertise for the current printer list. */
function desiredServices(printers: PrinterView[], settings: CuppaSettings): AdvertisedService[] {
  const services: AdvertisedService[] = [];
  if (!settings.advertiseEnabled) return services;
  for (const printer of printers) {
    if (!printer.shared) continue;
    services.push({
      name: printer.advertisedName,
      type: "_ipp._tcp",
      port: ENV.ippPort,
      txt: txtRecords(printer, settings),
      airprint: settings.airprintCompat,
    });
    if (settings.tlsEnabled) {
      services.push({
        name: printer.advertisedName,
        type: "_ipps._tcp",
        port: ENV.ippPort,
        txt: txtRecords(printer, settings),
        airprint: settings.airprintCompat,
      });
    }
  }
  return services;
}

// ---------------------------------------------------------------------------
// Publishing
// ---------------------------------------------------------------------------

/** Running `avahi-publish-service` children, keyed by "<name>\0<type>". */
const hostPublishers = new Map<string, { child: ChildProcess; signature: string }>();
/** Last failure per key, so diagnostics can show why publishing failed. */
const publisherErrors = new Map<string, string>();

/** How long before the host is retried after falling back to the built-in responder. */
const HOST_RETRY_MS = 180_000;

let usingInternal = false;
let hostRetryTimer: NodeJS.Timeout | null = null;
let lastServices: AdvertisedService[] = [];
let lastPrinters: PrinterView[] = [];
let lastSettings: CuppaSettings | null = null;
let lastSignature = "";

function publishArgs(service: AdvertisedService): string[] {
  const args: string[] = [];
  if (service.airprint) args.push(`--subtype=_universal._sub.${service.type}`);
  args.push(service.name, service.type, String(service.port), ...service.txt);
  return args;
}

function hostDesired(services: AdvertisedService[]): Map<string, string[]> {
  const desired = new Map<string, string[]>();
  for (const service of services) {
    desired.set(`${service.name}\u0000${service.type}`, publishArgs(service));
  }
  return desired;
}

/** True when every desired service has a live publisher. */
function hostHealthy(services: AdvertisedService[]): boolean {
  const desired = hostDesired(services);
  if (desired.size === 0) return true;
  for (const key of desired.keys()) {
    if (!hostPublishers.has(key)) return false;
  }
  return true;
}

function publishInternal(services: AdvertisedService[]): void {
  mdnsAdvertiser.start();
  mdnsAdvertiser.update(services);
}

/** Falls back to Cuppa's own responder, then schedules a retry of the host. */
function switchToInternal(reason: string): void {
  if (usingInternal) return;
  usingInternal = true;
  log.warn(`Host Avahi publishing failed (${reason}); falling back to the built-in mDNS responder`);
  for (const entry of hostPublishers.values()) entry.child.kill("SIGTERM");
  hostPublishers.clear();
  publishInternal(lastServices);

  // Do not latch forever: retry the host so a transient refusal does not leave
  // a second responder running indefinitely.
  if (!hostRetryTimer) {
    hostRetryTimer = setTimeout(() => {
      hostRetryTimer = null;
      if (!usingInternal) return;
      log.info("Retrying host Avahi publishing");
      usingInternal = false;
      mdnsAdvertiser.stop();
      lastSignature = "";
      if (lastSettings) syncAdvertisements(lastPrinters, lastSettings);
    }, HOST_RETRY_MS);
    hostRetryTimer.unref();
  }
}

function syncHostAvahi(services: AdvertisedService[]): void {
  const desired = hostDesired(services);

  for (const [key, entry] of hostPublishers) {
    if (!desired.has(key)) {
      entry.child.kill("SIGTERM");
      hostPublishers.delete(key);
      publisherErrors.delete(key);
    }
  }

  for (const [key, args] of desired) {
    const signature = args.join("\u0000");
    const existing = hostPublishers.get(key);
    if (existing?.signature === signature) continue;
    if (existing) existing.child.kill("SIGTERM");
    hostPublishers.delete(key);

    // Capture stderr: when the host Avahi refuses publishing this is the only
    // place the reason appears.
    const child = spawn("avahi-publish-service", args, { stdio: ["ignore", "ignore", "pipe"] });
    let stderr = "";
    child.stderr?.on("data", (chunk: Buffer) => {
      stderr = (stderr + chunk.toString()).slice(-400);
    });
    child.on("error", (error) => {
      publisherErrors.set(key, `spawn failed: ${error.message}`);
      switchToInternal(`spawn failed: ${error.message}`);
    });
    child.on("exit", (code) => {
      if (hostPublishers.get(key)?.child !== child) return;
      hostPublishers.delete(key);
      const message = stderr.trim() || `exit ${code}`;
      publisherErrors.set(key, message);
      log.warn(`Avahi publisher for ${key} exited: ${message}`);
      switchToInternal(message);
    });
    hostPublishers.set(key, { child, signature });
    publisherErrors.delete(key);
  }
}

/**
 * Rebuilds the advertised records from the current printer list. Safe to call
 * after any change and on a timer; an unchanged, healthy set is skipped, but a
 * set that failed to publish is retried.
 */
export function syncAdvertisements(printers: PrinterView[], settings: CuppaSettings): void {
  lastPrinters = printers;
  lastSettings = settings;
  const services = desiredServices(printers, settings);
  lastServices = services;
  const signature = JSON.stringify(services);

  const forceInternal = ENV.avahiMode !== "host" || ENV.mdnsMode === "internal";
  if (forceInternal || usingInternal) {
    if (signature === lastSignature && mdnsAdvertiser.isRunning) return;
    lastSignature = signature;
    publishInternal(services);
    log.info(`Bonjour/AirPrint advertisement updated (${services.length} service(s), built-in)`);
    return;
  }

  if (signature === lastSignature && hostHealthy(services)) return;
  lastSignature = signature;
  syncHostAvahi(services);
  log.info(`Bonjour/AirPrint advertisement updated (${services.length} service(s), host Avahi)`);
}

/** Human-readable advertiser state for the diagnostics page. */
export function advertisementStatus(): string {
  const lines: string[] = [];
  if (usingInternal || ENV.avahiMode !== "host" || ENV.mdnsMode === "internal") {
    lines.push(`mode: built-in responder`);
    // Why we are not using the host's Avahi is the whole question when a
    // printer is not discoverable, so it is shown even in this mode.
    for (const [key, error] of publisherErrors) {
      lines.push(`  host publish FAILED  ${key.replace("\u0000", "  ")}  ${error}`);
    }
    lines.push(mdnsAdvertiser.status());
  } else {
    lines.push(`mode: host Avahi (${hostPublishers.size} running)`);
    for (const key of hostPublishers.keys()) {
      lines.push(`  RUNNING  ${key.replace("\u0000", "  ")}`);
    }
    for (const [key, error] of publisherErrors) {
      if (!hostPublishers.has(key)) lines.push(`  FAILED   ${key.replace("\u0000", "  ")}  ${error}`);
    }
    if (hostPublishers.size === 0 && publisherErrors.size === 0) {
      lines.push("  (nothing to publish — check that printers are shared and advertising is on)");
    }
  }

  // The exact records clients see, so the advertised TXT can be compared with a
  // real IPP Everywhere printer's without a packet capture.
  lines.push("", `published records (${lastServices.length} service(s)):`);
  if (lastServices.length === 0) lines.push("  (none)");
  for (const service of lastServices) {
    lines.push(`  ${service.name}  ${service.type}${service.airprint ? "  [_universal]" : ""}`);
    for (const entry of service.txt) lines.push(`    ${entry}`);
  }
  return lines.join("\n");
}

/** Stops advertising (called on shutdown). */
export function stopAdvertisements(): void {
  if (hostRetryTimer) {
    clearTimeout(hostRetryTimer);
    hostRetryTimer = null;
  }
  for (const entry of hostPublishers.values()) entry.child.kill("SIGTERM");
  hostPublishers.clear();
  mdnsAdvertiser.stop();
}
