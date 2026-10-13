import dgram from "node:dgram";
import { log } from "./logger.js";
import { primaryIPv4, hostName } from "./network.js";

/**
 * A tiny mDNS (DNS-SD) advertiser.
 *
 * Cuppa publishes its printers itself instead of asking the host's Avahi to do
 * it. On some NAS firmware the container can *browse* the host's Avahi but is
 * refused permission to *publish* through it, which silently left the printers
 * undiscoverable on macOS/iOS/Android. Announcing the records directly removes
 * that dependency: the multicast socket shares port 5353 with any Avahi already
 * on the host (both set SO_REUSEADDR/SO_REUSEPORT).
 */

const MDNS_ADDRESS = "224.0.0.251";
const MDNS_PORT = 5353;
const TTL = 120;
const ANNOUNCE_INTERVAL_MS = 30_000;

const TYPE_A = 1;
const TYPE_PTR = 12;
const TYPE_TXT = 16;
const TYPE_SRV = 33;

export interface AdvertisedService {
  /** Friendly instance name, e.g. "Office (Cuppa)". */
  name: string;
  /** Service type, e.g. "_ipp._tcp". */
  type: string;
  port: number;
  txt: string[];
  /** Also publish the `_universal` AirPrint subtype. */
  airprint: boolean;
}

function encodeName(name: string): Buffer {
  const labels = name.split(".").filter((label) => label.length > 0);
  const parts: Buffer[] = [];
  for (const label of labels) {
    const bytes = Buffer.from(label, "utf8");
    parts.push(Buffer.from([Math.min(bytes.length, 63)]), bytes.subarray(0, 63));
  }
  parts.push(Buffer.from([0]));
  return Buffer.concat(parts);
}

function encodeTxt(txt: string[]): Buffer {
  const parts: Buffer[] = [];
  for (const entry of txt) {
    const bytes = Buffer.from(entry, "utf8");
    const length = Math.min(bytes.length, 255);
    parts.push(Buffer.from([length]), bytes.subarray(0, length));
  }
  if (parts.length === 0) parts.push(Buffer.from([0]));
  return Buffer.concat(parts);
}

function record(name: string, type: number, rdata: Buffer, flush: boolean): Buffer {
  const header = Buffer.alloc(10);
  header.writeUInt16BE(type, 0);
  header.writeUInt16BE(flush ? 0x8001 : 0x0001, 2);
  header.writeUInt32BE(TTL, 4);
  header.writeUInt16BE(rdata.length, 8);
  return Buffer.concat([encodeName(name), header, rdata]);
}

function srvData(port: number, target: string): Buffer {
  const head = Buffer.alloc(6);
  head.writeUInt16BE(port, 4);
  return Buffer.concat([head, encodeName(target)]);
}

function aData(ip: string): Buffer {
  const octets = ip.split(".").map((part) => Number(part) & 0xff);
  return Buffer.from(octets.length === 4 ? octets : [127, 0, 0, 1]);
}

/** Reads a (possibly compressed) DNS name, returning the name and next offset. */
function readName(buffer: Buffer, start: number): { name: string; next: number } {
  const labels: string[] = [];
  let offset = start;
  let next = start;
  let jumped = false;
  let guard = 0;
  while (offset < buffer.length && guard++ < 128) {
    const length = buffer[offset]!;
    if (length === 0) {
      offset += 1;
      if (!jumped) next = offset;
      break;
    }
    if ((length & 0xc0) === 0xc0) {
      const pointer = ((length & 0x3f) << 8) | buffer[offset + 1]!;
      if (!jumped) next = offset + 2;
      offset = pointer;
      jumped = true;
      continue;
    }
    labels.push(buffer.toString("utf8", offset + 1, offset + 1 + length));
    offset += 1 + length;
  }
  return { name: labels.join(".").toLowerCase(), next };
}

/** The question names in a query, or [] for a response/malformed packet. */
function readQuestions(message: Buffer): string[] {
  if (message.length < 12) return [];
  if (message.readUInt16BE(2) & 0x8000) return [];
  const count = message.readUInt16BE(4);
  let offset = 12;
  const names: string[] = [];
  for (let i = 0; i < count && offset < message.length; i++) {
    const { name, next } = readName(message, offset);
    names.push(name);
    offset = next + 4;
  }
  return names;
}

export class MdnsAdvertiser {
  private socket: dgram.Socket | null = null;
  private timer: NodeJS.Timeout | null = null;
  private services: AdvertisedService[] = [];
  private ip = "";
  private host = "";
  private bindError = "";

  /** Records every service Cuppa advertises, so queries can be answered. */
  private targets(): Set<string> {
    const set = new Set<string>(["_services._dns-sd._udp.local", `${this.host}.local`]);
    for (const service of this.services) {
      set.add(`${service.type}.local`);
      set.add(`${service.name}.${service.type}.local`.toLowerCase());
      if (service.airprint) set.add(`_universal._sub.${service.type}.local`);
    }
    return set;
  }

  private buildPacket(): Buffer {
    const answers: Buffer[] = [];
    const additionals: Buffer[] = [];

    answers.push(record("_services._dns-sd._udp.local", TYPE_PTR, encodeName("_ipp._tcp.local"), false));
    for (const service of this.services) {
      const instance = `${service.name}.${service.type}.local`;
      answers.push(record(`${service.type}.local`, TYPE_PTR, encodeName(instance), false));
      if (service.airprint) {
        answers.push(
          record(`_universal._sub.${service.type}.local`, TYPE_PTR, encodeName(instance), false)
        );
      }
      answers.push(record(instance, TYPE_SRV, srvData(service.port, `${this.host}.local`), true));
      answers.push(record(instance, TYPE_TXT, encodeTxt(service.txt), true));
    }
    additionals.push(record(`${this.host}.local`, TYPE_A, aData(this.ip), true));

    const header = Buffer.alloc(12);
    header.writeUInt16BE(0x8400, 2); // response, authoritative
    header.writeUInt16BE(answers.length, 6);
    header.writeUInt16BE(additionals.length, 10);
    return Buffer.concat([header, ...answers, ...additionals]);
  }

  private announce(): void {
    if (!this.socket || this.services.length === 0) return;
    const packet = this.buildPacket();
    this.socket.send(packet, MDNS_PORT, MDNS_ADDRESS, (error) => {
      if (error) log.debug(`mDNS announce failed: ${error.message}`);
    });
  }

  /** Replaces the advertised set and announces it immediately. */
  update(services: AdvertisedService[]): void {
    this.services = services;
    this.announce();
  }

  start(): void {
    if (this.socket) return;
    this.ip = primaryIPv4();
    // A hostname that is unlikely to collide with the host's own records.
    this.host = `${hostName().toLowerCase().replace(/[^a-z0-9-]/g, "-")}-cuppa`;

    const socket = dgram.createSocket({ type: "udp4", reuseAddr: true });
    socket.on("error", (error) => {
      this.bindError = error.message;
      log.warn(`mDNS socket error: ${error.message}`);
    });
    socket.on("message", (message: Buffer) => {
      try {
        const questions = readQuestions(message);
        if (questions.length === 0) return;
        const targets = this.targets();
        if (questions.some((name) => targets.has(name))) this.announce();
      } catch {
        // Ignore malformed packets.
      }
    });
    socket.bind(MDNS_PORT, () => {
      try {
        socket.addMembership(MDNS_ADDRESS);
      } catch (error) {
        log.warn(`mDNS could not join ${MDNS_ADDRESS}: ${(error as Error).message}`);
      }
      this.announce();
    });
    this.socket = socket;
    this.timer = setInterval(() => this.announce(), ANNOUNCE_INTERVAL_MS);
    this.timer.unref();
    log.info(`mDNS advertiser started on ${this.ip} (${this.host}.local)`);
  }

  stop(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    if (this.socket) {
      try {
        this.socket.close();
      } catch {
        // already closed
      }
      this.socket = null;
    }
  }

  status(): string {
    if (!this.socket) return "(mDNS advertiser not started)";
    const lines: string[] = [];
    if (this.bindError) lines.push(`socket error: ${this.bindError}`);
    lines.push(`advertising ${this.services.length} service(s) as ${this.host}.local (${this.ip})`);
    for (const service of this.services) {
      lines.push(`  ${service.name}  ${service.type}  port ${service.port}`);
    }
    if (this.services.length === 0) lines.push("  (nothing to advertise)");
    return lines.join("\n");
  }

  /** True once the socket is open and records are being announced. */
  get isRunning(): boolean {
    return this.socket !== null;
  }
}

export const mdnsAdvertiser = new MdnsAdvertiser();
