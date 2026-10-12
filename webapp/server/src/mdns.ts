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
 * Advertising is done by Cuppa's own advertiser (see `mdns-internal.ts`) rather
 * than the host's Avahi. On some NAS firmware a container is allowed to *browse*
 * the host's Avahi but refused permission to *publish* through it, which
 * silently left the printers undiscoverable. Announcing the records directly
 * removes that dependency.
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

/** Content last advertised, so a periodic refresh does not re-announce needlessly. */
let lastSignature = "";

/**
 * Rebuilds the advertised records from the current printer list. Safe to call
 * after any change and on a timer; only an actual change re-announces.
 */
export function syncAdvertisements(printers: PrinterView[], settings: CuppaSettings): void {
  const services = desiredServices(printers, settings);
  const signature = JSON.stringify(services);

  mdnsAdvertiser.start();
  if (signature === lastSignature) return;
  lastSignature = signature;
  mdnsAdvertiser.update(services);
  log.info(`Bonjour/AirPrint advertisement updated (${services.length} service(s))`);
}

/** Human-readable advertiser state for the diagnostics page. */
export function advertisementStatus(): string {
  return mdnsAdvertiser.status();
}

/** Stops advertising (called on shutdown). */
export function stopAdvertisements(): void {
  mdnsAdvertiser.stop();
}
