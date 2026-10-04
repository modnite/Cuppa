import { existsSync, mkdirSync, readFileSync, readdirSync, unlinkSync, writeFileSync } from "node:fs";
import path from "node:path";
import { ENV } from "./env.js";
import { log } from "./logger.js";
import type { CuppaSettings, PrinterView } from "./types.js";

/**
 * Publishes each shared queue to Bonjour/DNS-SD through Avahi static service
 * files.
 *
 * We do this ourselves rather than letting cupsd advertise, because the whole
 * point is the exact service name the user chose with " (Cuppa)" on the end.
 * cupsd's own naming derives from `printer-info` and may decorate it further
 * depending on the host, which would break that promise. cupsd's built-in
 * DNSSD is disabled in `cupsd.conf` so there are never duplicate records.
 *
 * The TXT records mirror the Android app's `NetworkPrinterAdvertiser` so the
 * same printer shared from either front end looks identical to macOS, iOS and
 * Android clients.
 */

const PREFIX = "cuppa-";

function escapeXml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

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

function serviceGroup(printer: PrinterView, settings: CuppaSettings): string {
  const name = escapeXml(printer.advertisedName);
  const txt = txtRecords(printer, settings)
    .map((record) => `      <txt-record>${escapeXml(record)}</txt-record>`)
    .join("\n");
  const airprintSubtype = settings.airprintCompat
    ? "      <subtype>_universal._sub._ipp._tcp</subtype>\n"
    : "";
  const ippsSubtype = settings.airprintCompat
    ? "      <subtype>_universal._sub._ipps._tcp</subtype>\n"
    : "";

  const ippService = [
    "    <service>",
    "      <type>_ipp._tcp</type>",
    airprintSubtype + `      <port>${ENV.ippPort}</port>`,
    txt,
    "    </service>",
  ]
    .filter((line) => line.length > 0)
    .join("\n");

  const ippsService = settings.tlsEnabled
    ? [
        "    <service>",
        "      <type>_ipps._tcp</type>",
        ippsSubtype + `      <port>${ENV.ippPort}</port>`,
        txt,
        "    </service>",
      ]
        .filter((line) => line.length > 0)
        .join("\n")
    : "";

  return [
    '<?xml version="1.0" standalone=\'no\'?>',
    '<!DOCTYPE service-group SYSTEM "avahi-service.dtd">',
    "<service-group>",
    `  <name>${name}</name>`,
    ippService,
    ippsService,
    "</service-group>",
    "",
  ]
    .filter((line) => line !== "")
    .join("\n");
}

function reloadAvahi(): void {
  const pidFiles = ["/run/avahi-daemon/pid", "/var/run/avahi-daemon/pid"];
  for (const pidFile of pidFiles) {
    try {
      if (!existsSync(pidFile)) continue;
      const pid = Number.parseInt(readFileSync(pidFile, "utf8"), 10);
      if (Number.isFinite(pid) && pid > 0) {
        process.kill(pid, "SIGHUP");
        log.debug(`Signalled Avahi (pid ${pid}) to reload service definitions`);
        return;
      }
    } catch (error) {
      log.debug(`Avahi reload via ${pidFile} failed: ${String(error)}`);
    }
  }
  log.debug("Avahi is not running; service files were written but not reloaded");
}

/** Content last written per queue, so a periodic refresh does not churn Avahi. */
const published = new Map<string, string>();

/**
 * Rewrites every Cuppa Avahi record from the current printer list. Safe to call
 * after any change and on a timer: files are only touched when their content
 * actually changed, and Avahi is only signalled when something did.
 */
export function syncAdvertisements(printers: PrinterView[], settings: CuppaSettings): void {
  try {
    if (!existsSync(ENV.avahiServicesDir)) mkdirSync(ENV.avahiServicesDir, { recursive: true });
  } catch (error) {
    log.warn(`Cannot create Avahi services directory ${ENV.avahiServicesDir}`, error);
    return;
  }

  const desired = new Map<string, string>();
  if (settings.advertiseEnabled) {
    for (const printer of printers) {
      if (!printer.shared) continue;
      desired.set(printer.queue, serviceGroup(printer, settings));
    }
  }

  let changed = false;

  // Remove files for queues that are gone or no longer shared.
  try {
    if (existsSync(ENV.avahiServicesDir)) {
      for (const entry of readdirSync(ENV.avahiServicesDir)) {
        if (!entry.startsWith(PREFIX) || !entry.endsWith(".service")) continue;
        const queue = entry.slice(PREFIX.length, -".service".length);
        if (!desired.has(queue)) {
          unlinkSync(path.join(ENV.avahiServicesDir, entry));
          published.delete(queue);
          changed = true;
        }
      }
    }
  } catch (error) {
    log.warn("Could not clear old Avahi service files", error);
  }

  // Write only records whose content changed.
  for (const [queue, content] of desired) {
    if (published.get(queue) === content) continue;
    try {
      writeFileSync(path.join(ENV.avahiServicesDir, `${PREFIX}${queue}.service`), content, "utf8");
      published.set(queue, content);
      changed = true;
    } catch (error) {
      log.warn(`Could not write Avahi record for ${queue}`, error);
    }
  }

  if (changed) {
    log.info(`Bonjour/AirPrint advertisement updated (${desired.size} printer(s))`);
    reloadAvahi();
  }
}
