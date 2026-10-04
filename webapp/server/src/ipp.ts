import http from "node:http";
import { ENV } from "./env.js";
import { log } from "./logger.js";

/**
 * A deliberately small IPP/2.0 client.
 *
 * Cuppa only needs to *read* printer and job state from the local CUPS
 * scheduler; every mutation goes through the `lpadmin`/`lp`/`cancel` tools,
 * which already know how to talk to CUPS correctly. Keeping the reader here
 * avoids shelling out and parsing locale-sensitive `lpstat` output, and avoids
 * pulling in an abandoned IPP npm package.
 */

export const IPP_OP = {
  GET_JOBS: 0x000a,
  GET_PRINTER_ATTRIBUTES: 0x000b,
  CUPS_GET_DEFAULT: 0x4001,
  CUPS_GET_PRINTERS: 0x4002,
  CUPS_GET_JOBS: 0x400a,
} as const;

export const IPP_TAG = {
  operationAttributes: 0x01,
  jobAttributes: 0x02,
  endOfAttributes: 0x03,
  printerAttributes: 0x04,
  unsupportedAttributes: 0x05,
  integer: 0x21,
  boolean: 0x22,
  enum: 0x23,
  octetString: 0x30,
  dateTime: 0x31,
  resolution: 0x32,
  rangeOfInteger: 0x33,
  collection: 0x34,
  textWithLanguage: 0x35,
  nameWithLanguage: 0x36,
  endCollection: 0x37,
  textWithoutLanguage: 0x41,
  nameWithoutLanguage: 0x42,
  keyword: 0x44,
  uri: 0x45,
  uriScheme: 0x46,
  charset: 0x47,
  naturalLanguage: 0x48,
  mimeMediaType: 0x49,
  memberName: 0x4a,
} as const;

export interface IppAttribute {
  tag: number;
  name: string;
  value: IppValue;
}

export interface IppCollection {
  [key: string]: IppValue;
}

export type IppValue =
  | string
  | number
  | boolean
  | Date
  | number[]
  | { x: number; y: number; units: number }
  | IppCollection
  | Buffer;

export interface IppGroup {
  tag: number;
  attributes: IppAttribute[];
}

export interface IppResponse {
  statusCode: number;
  requestId: number;
  groups: IppGroup[];
}

let requestCounter = 0;

// ---------------------------------------------------------------------------
// Encoding
// ---------------------------------------------------------------------------

function encodeValue(tag: number, value: IppValue): Buffer {
  switch (tag) {
    case IPP_TAG.integer:
    case IPP_TAG.enum: {
      const buffer = Buffer.alloc(4);
      buffer.writeInt32BE(Number(value), 0);
      return buffer;
    }
    case IPP_TAG.boolean:
      return Buffer.from([value ? 1 : 0]);
    case IPP_TAG.resolution: {
      const res = value as { x: number; y: number; units: number };
      const buffer = Buffer.alloc(9);
      buffer.writeInt32BE(res.x, 0);
      buffer.writeInt32BE(res.y, 4);
      buffer.writeUInt8(res.units, 8);
      return buffer;
    }
    case IPP_TAG.rangeOfInteger: {
      const range = value as number[];
      const buffer = Buffer.alloc(8);
      buffer.writeInt32BE(range[0] ?? 0, 0);
      buffer.writeInt32BE(range[1] ?? 0, 4);
      return buffer;
    }
    default:
      return Buffer.from(String(value), "utf8");
  }
}

function encodeAttribute(attribute: IppAttribute): Buffer {
  const value = encodeValue(attribute.tag, attribute.value);
  const name = Buffer.from(attribute.name, "utf8");
  const header = Buffer.alloc(2 + name.length + 2);
  header.writeUInt16BE(name.length, 0);
  name.copy(header, 2);
  header.writeUInt16BE(value.length, 2 + name.length);
  return Buffer.concat([Buffer.from([attribute.tag]), header, value]);
}

export function buildRequest(operation: number, requestId: number, groups: IppGroup[]): Buffer {
  const header = Buffer.alloc(8);
  header.writeUInt8(2, 0); // IPP/2.0
  header.writeUInt8(0, 1);
  header.writeUInt16BE(operation, 2);
  header.writeUInt32BE(requestId, 4);

  const parts: Buffer[] = [header];
  for (const group of groups) {
    parts.push(Buffer.from([group.tag]));
    for (const attribute of group.attributes) parts.push(encodeAttribute(attribute));
  }
  parts.push(Buffer.from([IPP_TAG.endOfAttributes]));
  return Buffer.concat(parts);
}

/** Builds the attribute entries for one name carrying several values. */
export function multi(tag: number, name: string, values: IppValue[]): IppAttribute[] {
  return values.map((value, index) => ({ tag, name: index === 0 ? name : "", value }));
}

export function opGroup(attributes: IppAttribute[]): IppGroup {
  return { tag: IPP_TAG.operationAttributes, attributes };
}

export function commonAttributes(): IppAttribute[] {
  return [
    { tag: IPP_TAG.charset, name: "attributes-charset", value: "utf-8" },
    { tag: IPP_TAG.naturalLanguage, name: "attributes-natural-language", value: "en" },
  ];
}

// ---------------------------------------------------------------------------
// Decoding
// ---------------------------------------------------------------------------

function decodeDateTime(raw: Buffer): Date {
  const year = raw.readUInt16BE(0);
  const month = raw.readUInt8(2);
  const day = raw.readUInt8(3);
  const hour = raw.readUInt8(4);
  const minute = raw.readUInt8(5);
  const second = raw.readUInt8(6);
  const direction = raw.readUInt8(8); // '+' | '-'
  const offsetHours = raw.readUInt8(9);
  const offsetMinutes = raw.readUInt8(10);
  const offset = (offsetHours * 60 + offsetMinutes) * (direction === 0x2b ? 1 : -1);
  const utc = Date.UTC(year, month - 1, day, hour, minute, second);
  return new Date(utc - offset * 60_000);
}

function decodeCollection(raw: Buffer): IppCollection {
  const result: IppCollection = {};
  let offset = 0;
  let lastName = "";
  while (offset < raw.length) {
    const tag = raw.readUInt8(offset++);
    if (tag === IPP_TAG.endCollection) break;
    const nameLength = raw.readUInt16BE(offset);
    offset += 2;
    if (nameLength > 0) {
      lastName = raw.toString("utf8", offset, offset + nameLength);
      offset += nameLength;
    }
    const valueLength = raw.readUInt16BE(offset);
    offset += 2;
    const value = raw.subarray(offset, offset + valueLength);
    offset += valueLength;
    result[lastName] = decodeValue(tag, value);
  }
  return result;
}

function decodeValue(tag: number, raw: Buffer): IppValue {
  switch (tag) {
    case IPP_TAG.integer:
    case IPP_TAG.enum:
      return raw.readInt32BE(0);
    case IPP_TAG.boolean:
      return raw.readUInt8(0) !== 0;
    case IPP_TAG.dateTime:
      return raw.length >= 11 ? decodeDateTime(raw) : raw.toString("utf8");
    case IPP_TAG.resolution:
      return { x: raw.readInt32BE(0), y: raw.readInt32BE(4), units: raw.readUInt8(8) };
    case IPP_TAG.rangeOfInteger:
      return [raw.readInt32BE(0), raw.readInt32BE(4)];
    case IPP_TAG.collection:
      return decodeCollection(raw);
    case IPP_TAG.textWithLanguage:
    case IPP_TAG.nameWithLanguage: {
      const languageLength = raw.readUInt16BE(0);
      const textLength = raw.readUInt16BE(2 + languageLength);
      return raw.toString("utf8", 4 + languageLength, 4 + languageLength + textLength);
    }
    case IPP_TAG.octetString:
      return Buffer.from(raw);
    default:
      return raw.toString("utf8");
  }
}

export function decodeResponse(buffer: Buffer): IppResponse {
  let offset = 0;
  offset += 2; // version
  const statusCode = buffer.readUInt16BE(offset);
  offset += 2;
  const requestId = buffer.readUInt32BE(offset);
  offset += 4;

  const groups: IppGroup[] = [];
  let current: IppGroup | null = null;
  let lastName = "";

  while (offset < buffer.length) {
    const tag = buffer.readUInt8(offset++);
    if (tag === IPP_TAG.endOfAttributes) break;

    if (tag <= 0x0f) {
      current = { tag, attributes: [] };
      groups.push(current);
      lastName = "";
      continue;
    }

    const nameLength = buffer.readUInt16BE(offset);
    offset += 2;
    if (nameLength > 0) {
      lastName = buffer.toString("utf8", offset, offset + nameLength);
      offset += nameLength;
    }
    const valueLength = buffer.readUInt16BE(offset);
    offset += 2;
    const raw = buffer.subarray(offset, offset + valueLength);
    offset += valueLength;

    if (!current) {
      current = { tag: IPP_TAG.operationAttributes, attributes: [] };
      groups.push(current);
    }
    current.attributes.push({ tag, name: lastName, value: decodeValue(tag, raw) });
  }

  return { statusCode, requestId, groups };
}

// ---------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------

export async function ippRequest(
  operation: number,
  groups: IppGroup[],
  path = "/",
  timeoutMs = 15_000
): Promise<IppResponse> {
  requestCounter = (requestCounter + 1) & 0x7fffffff;
  const requestId = requestCounter;
  const body = buildRequest(operation, requestId, groups);

  return new Promise<IppResponse>((resolve, reject) => {
    const request = http.request(
      {
        host: ENV.cupsHost,
        port: ENV.ippPort,
        path,
        method: "POST",
        headers: { "Content-Type": "application/ipp", "Content-Length": body.length },
      },
      (response) => {
        const chunks: Buffer[] = [];
        response.on("data", (chunk: Buffer) => chunks.push(chunk));
        response.on("end", () => {
          try {
            resolve(decodeResponse(Buffer.concat(chunks)));
          } catch (error) {
            reject(error);
          }
        });
      }
    );
    request.on("error", reject);
    request.setTimeout(timeoutMs, () => request.destroy(new Error("IPP request timed out")));
    request.end(body);
  });
}

// ---------------------------------------------------------------------------
// Response helpers
// ---------------------------------------------------------------------------

export function values(group: IppGroup | undefined, name: string): IppValue[] {
  if (!group) return [];
  return group.attributes.filter((attribute) => attribute.name === name).map((attribute) => attribute.value);
}

export function firstValue<T = IppValue>(group: IppGroup | undefined, name: string): T | undefined {
  const all = values(group, name);
  return all.length > 0 ? (all[0] as T) : undefined;
}

/**
 * CUPS repeats the identifying attribute once per object (printer-name for
 * printers, job-id for jobs). This splits a response group back into one
 * record per object. Each field holds every value seen for that name.
 */
export interface IppRecord {
  [name: string]: IppValue[];
}

export function groupRecords(group: IppGroup | undefined, key: string): IppRecord[] {
  if (!group) return [];
  const records: IppRecord[] = [];
  let current: IppRecord | null = null;
  let pending: Array<[string, IppValue]> = [];

  for (const attribute of group.attributes) {
    if (attribute.name === key) {
      if (current === null) {
        // CUPS does not guarantee the identifying attribute comes first (for
        // jobs it sits in the middle), so attributes seen before it belong to
        // the first record.
        current = { [key]: [attribute.value] };
        for (const [name, value] of pending) (current[name] ??= []).push(value);
        pending = [];
        records.push(current);
      } else {
        current = { [key]: [attribute.value] };
        records.push(current);
      }
      continue;
    }
    if (!current) {
      pending.push([attribute.name, attribute.value]);
      continue;
    }
    (current[attribute.name] ??= []).push(attribute.value);
  }
  return records;
}

export function recordString(record: IppRecord, name: string, fallback = ""): string {
  const value = record[name]?.[0];
  if (value === undefined || value === null) return fallback;
  return typeof value === "string" ? value : String(value);
}

export function recordNumber(record: IppRecord, name: string, fallback = 0): number {
  const value = record[name]?.[0];
  return typeof value === "number" ? value : fallback;
}

export function recordBoolean(record: IppRecord, name: string, fallback = false): boolean {
  const value = record[name]?.[0];
  return typeof value === "boolean" ? value : fallback;
}

export function recordStrings(record: IppRecord, name: string): string[] {
  return (record[name] ?? []).filter((value): value is string => typeof value === "string");
}

export function logIppFailure(operation: string, response: IppResponse): void {
  log.warn(`${operation} returned IPP status 0x${response.statusCode.toString(16)}`);
}
