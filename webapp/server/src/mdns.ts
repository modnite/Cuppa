import { spawn, type ChildProcess } from "node:child_process";
import { ENV } from "./env.js";
import { log } from "./logger.js";
import { mdnsAdvertiser, type AdvertisedService } from "./mdns-internal.js";
import type { CuppaSettings, PrinterView } from "./types.js";

/**
 * Publishes each shared queue over mDNS/DNS-SD.
 *
 * The records mirror the Android app's `NetworkPrinterAdvertiser` so the same
 * printer shared from either front end looks identical to macOS, iOS and
 * Android clients: the service name is exactly the user's chosen name with
 * " (Cuppa)" on the end, and the TXT records carry the driverless formats.
 *
 * Advertising uses the host's Avahi when the container is borrowing it (that is
 * the path that works on the NAS), and falls back to Cuppa's own mDNS responder
 * (`mdns-internal.ts`) if publishing through the host is refused. The fallback
 * is what makes a firmware that blocks publishing still discoverable.
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

function documentFormats(printer: PrinterView): string {
  const available = new Set(printer.formats);
  const chosen = PREFERRED_FORMATS.filter((format) => available.has(format));
  // A queue that accepts nothing recognizable should still claim PDF and raw.
  if (!chosen.includes("application/pdf")) chosen.unshift("application/pdf");
  if (chosen.length === 0) chosen.push("application/octet-stream");
  return chosen.join(",");
}

function bareUuid(printer: PrinterView): string {
  return printer.uuid.replace(/^urn:uuid:/i, "");
}

function txtRecords(printer: PrinterView, settings: CuppaSettings): string[] {
  const model = printer.makeAndModel.trim() || "Cuppa Printer";
  const records: string[] = [
    `rp=printers/${printer.queue}`,
    `pdl=${documentFormats(printer)}`,
    `ty=${model}`,
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
// Host-Avahi publishing
// ---------------------------------------------------------------------------

/** Running `avahi-publish-service` children, keyed by "<name>\0<type>". */
const hostPublishers = new Map<string, { child: ChildProcess; signature: string }>();
/** Last failure per key, so diagnostics can show why publishing failed. */
const publisherErrors = new Map<string, string>();

/** Once the host refuses publishing we stay on the built-in responder. */
let usingInternal = false;
let lastServices: AdvertisedService[] = [];
let lastSignature = "";

function publishArgs(service: AdvertisedService): string[] {
  const args: string[] = [];
  if (service.airprint) args.push(`--subtype=_universal._sub.${service.type}`);
  args.push(service.name, service.type, String(service.port), ...service.txt);
  return args;
}

/** Switches to Cuppa's own responder, once. */
function useInternalResponder(reason: string): void {
  if (usingInternal) return;
  usingInternal = true;
  log.warn(`Host Avahi publishing failed (${reason}); using the built-in mDNS responder`);
  for (const entry of hostPublishers.values()) entry.child.kill("SIGTERM");
  hostPublishers.clear();
  mdnsAdvertiser.start();
  mdnsAdvertiser.update(lastServices);
}

function syncHostAvahi(services: AdvertisedService[]): void {
  const desired = new Map<string, string[]>();
  for (const service of services) {
    desired.set(`${service.name}\u0000${service.type}`, publishArgs(service));
  }

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
      useInternalResponder(`spawn failed: ${error.message}`);
    });
    child.on("exit", (code) => {
      if (hostPublishers.get(key)?.child !== child) return;
      hostPublishers.delete(key);
      const message = stderr.trim() || `exit ${code}`;
      publisherErrors.set(key, message);
      log.warn(`Avahi publisher for ${key} exited: ${message}`);
      useInternalResponder(message);
    });
    hostPublishers.set(key, { child, signature });
    publisherErrors.delete(key);
  }
}

/** Content last advertised, so a periodic refresh does not re-announce needlessly. */
export function syncAdvertisements(printers: PrinterView[], settings: CuppaSettings): void {
  const services = desiredServices(printers, settings);
  lastServices = services;

  const signature = JSON.stringify(services);
  if (signature === lastSignature) return;
  lastSignature = signature;

  const preferInternal = usingInternal || ENV.avahiMode !== "host" || ENV.mdnsMode === "internal";
  if (preferInternal) {
    mdnsAdvertiser.start();
    mdnsAdvertiser.update(services);
  } else {
    syncHostAvahi(services);
  }
  log.info(`Bonjour/AirPrint advertisement updated (${services.length} service(s))`);
}

/** Human-readable advertiser state for the diagnostics page. */
export function advertisementStatus(): string {
  if (usingInternal || ENV.avahiMode !== "host" || ENV.mdnsMode === "internal") {
    return `mode: built-in responder\n${mdnsAdvertiser.status()}`;
  }
  const lines = [`mode: host Avahi (${hostPublishers.size} running)`];
  for (const key of hostPublishers.keys()) {
    lines.push(`  RUNNING  ${key.replace("\u0000", "  ")}`);
  }
  for (const [key, error] of publisherErrors) {
    if (!hostPublishers.has(key)) lines.push(`  FAILED   ${key.replace("\u0000", "  ")}  ${error}`);
  }
  if (hostPublishers.size === 0 && publisherErrors.size === 0) {
    lines.push("  (nothing to publish — check that printers are shared and advertising is on)");
  }
  return lines.join("\n");
}

/** Stops advertising (called on shutdown). */
export function stopAdvertisements(): void {
  for (const entry of hostPublishers.values()) entry.child.kill("SIGTERM");
  hostPublishers.clear();
  mdnsAdvertiser.stop();
}
