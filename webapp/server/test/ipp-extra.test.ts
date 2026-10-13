import assert from "node:assert/strict";
import { test } from "node:test";
import {
  IPP_OP,
  IPP_TAG,
  buildRequest,
  commonAttributes,
  decodeResponse,
  firstValue,
  groupRecords,
  multi,
  opGroup,
  recordBoolean,
  recordNumber,
  recordString,
  recordStrings,
  values,
  type IppGroup,
  type IppValue,
} from "../src/ipp.js";

/**
 * Extended IPP codec coverage: every value encoding path, the decoders the
 * original test does not touch (dateTime, collection, with-language, octet
 * string), and the `groupRecords` "identifier is not first" behaviour.
 */

function nameField(name: string): Buffer {
  const bytes = Buffer.from(name, "utf8");
  const header = Buffer.alloc(2);
  header.writeUInt16BE(bytes.length, 0);
  return Buffer.concat([header, bytes]);
}

function attr(tag: number, name: string, value: Buffer): Buffer {
  const valueHeader = Buffer.alloc(2);
  valueHeader.writeUInt16BE(value.length, 0);
  return Buffer.concat([Buffer.from([tag]), nameField(name), valueHeader, value]);
}

function int32(value: number): Buffer {
  const buffer = Buffer.alloc(4);
  buffer.writeInt32BE(value, 0);
  return buffer;
}

function response(requestId: number, status: number, parts: Buffer[]): Buffer {
  const header = Buffer.alloc(8);
  header.writeUInt8(2, 0);
  header.writeUInt8(0, 1);
  header.writeUInt16BE(status, 2);
  header.writeUInt32BE(requestId, 4);
  return Buffer.concat([header, ...parts, Buffer.from([IPP_TAG.endOfAttributes])]);
}

function decodeGroup(groupTag: number, attributes: Buffer[]): IppGroup {
  const decoded = decodeResponse(response(1, 0, [Buffer.concat([Buffer.from([groupTag]), ...attributes])]));
  return decoded.groups[0]!;
}

test("encodeValue round-trips every scalar tag through buildRequest/decodeResponse", () => {
  const resolution = { x: 300, y: 600, units: 3 };
  const groups: IppGroup[] = [
    opGroup([
      { tag: IPP_TAG.integer, name: "integer-value", value: -7 },
      { tag: IPP_TAG.enum, name: "enum-value", value: 3 },
      { tag: IPP_TAG.boolean, name: "boolean-true", value: true },
      { tag: IPP_TAG.boolean, name: "boolean-false", value: false },
      { tag: IPP_TAG.resolution, name: "resolution-value", value: resolution },
      { tag: IPP_TAG.rangeOfInteger, name: "range-value", value: [1, 5] },
      { tag: IPP_TAG.keyword, name: "keyword-value", value: "hello" },
    ]),
  ];
  const decoded = decodeResponse(buildRequest(IPP_OP.GET_PRINTER_ATTRIBUTES, 42, groups));

  assert.equal(decoded.requestId, 42);
  // decodeResponse reads the operation field as its status code.
  assert.equal(decoded.statusCode, IPP_OP.GET_PRINTER_ATTRIBUTES);
  const group = decoded.groups[0]!;
  assert.equal(group.tag, IPP_TAG.operationAttributes);
  assert.equal(firstValue(group, "integer-value"), -7);
  assert.equal(firstValue(group, "enum-value"), 3);
  assert.equal(firstValue(group, "boolean-true"), true);
  assert.equal(firstValue(group, "boolean-false"), false);
  assert.deepEqual(firstValue(group, "resolution-value"), resolution);
  assert.deepEqual(firstValue(group, "range-value"), [1, 5]);
  assert.equal(firstValue(group, "keyword-value"), "hello");
});

test("multi only names the first attribute of a repeated value", () => {
  const attributes = multi(IPP_TAG.keyword, "requested-attributes", ["a", "b", "c"]);
  assert.deepEqual(attributes.map((attribute) => attribute.name), [
    "requested-attributes",
    "",
    "",
  ]);
  assert.deepEqual(attributes.map((attribute) => attribute.value), ["a", "b", "c"]);
});

test("opGroup and commonAttributes carry the expected tags and values", () => {
  assert.equal(opGroup([]).tag, IPP_TAG.operationAttributes);
  assert.deepEqual(commonAttributes(), [
    { tag: IPP_TAG.charset, name: "attributes-charset", value: "utf-8" },
    { tag: IPP_TAG.naturalLanguage, name: "attributes-natural-language", value: "en" },
  ]);
});

test("values and firstValue collect repeated attributes and handle missing groups", () => {
  const group = decodeGroup(IPP_TAG.printerAttributes, [
    attr(IPP_TAG.keyword, "printer-state-reasons", Buffer.from("none")),
    attr(IPP_TAG.keyword, "printer-state-reasons", Buffer.from("media-empty")),
    attr(IPP_TAG.integer, "printer-state", int32(4)),
  ]);
  assert.deepEqual(values(group, "printer-state-reasons"), ["none", "media-empty"]);
  assert.deepEqual(values(group, "printer-state"), [4]);
  assert.deepEqual(values(group, "absent"), []);
  assert.equal(firstValue(group, "printer-state"), 4);
  assert.equal(firstValue(group, "absent"), undefined);
  assert.deepEqual(values(undefined, "anything"), []);
  assert.equal(firstValue(undefined, "anything"), undefined);
});

test("decodeResponse synthesises an operation-attributes group when none is present", () => {
  const buffer = response(3, 0, [
    attr(IPP_TAG.charset, "attributes-charset", Buffer.from("utf-8")),
  ]);
  const decoded = decodeResponse(buffer);
  assert.equal(decoded.groups.length, 1);
  assert.equal(decoded.groups[0]!.tag, IPP_TAG.operationAttributes);
  assert.equal(decoded.groups[0]!.attributes[0]!.name, "attributes-charset");
});

test("groupRecords attributes seen before the identifier belong to the first record", () => {
  const group = decodeGroup(IPP_TAG.jobAttributes, [
    attr(IPP_TAG.integer, "job-state", int32(5)),
    attr(IPP_TAG.nameWithoutLanguage, "job-name", Buffer.from("first")),
    attr(IPP_TAG.integer, "job-id", int32(101)),
    attr(IPP_TAG.integer, "job-id", int32(102)),
    attr(IPP_TAG.nameWithoutLanguage, "job-name", Buffer.from("second")),
  ]);

  const records = groupRecords(group, "job-id");
  assert.equal(records.length, 2);
  // The pre-identifier attributes are attached to the first record.
  assert.equal(recordNumber(records[0]!, "job-state"), 5);
  assert.equal(recordString(records[0]!, "job-name"), "first");
  assert.equal(recordNumber(records[0]!, "job-id"), 101);
  // Attributes after the second identifier belong to the second record.
  assert.equal(recordString(records[1]!, "job-name"), "second");
  assert.equal(recordNumber(records[1]!, "job-id"), 102);
  assert.equal(recordNumber(records[1]!, "job-state"), 0); // not shared
});

test("groupRecords returns nothing for a missing group or a key that never appears", () => {
  assert.deepEqual(groupRecords(undefined, "printer-name"), []);
  const group = decodeGroup(IPP_TAG.printerAttributes, [
    attr(IPP_TAG.keyword, "printer-info", Buffer.from("x")),
  ]);
  assert.deepEqual(groupRecords(group, "printer-name"), []);
});

test("record accessors fall back on missing, null and mistyped values", () => {
  const record = {
    name: ["Office"],
    count: [5],
    flag: [true],
    mixed: ["a", 7, "b"],
    nothing: [] as IppValue[],
    nullish: [null as unknown as IppValue],
  };

  assert.equal(recordString(record, "name"), "Office");
  assert.equal(recordString(record, "count"), "5"); // numbers are stringified
  assert.equal(recordString(record, "missing", "fallback"), "fallback");
  assert.equal(recordString(record, "nullish", "fallback"), "fallback");
  assert.equal(recordString(record, "nothing", "fallback"), "fallback");

  assert.equal(recordNumber(record, "count"), 5);
  assert.equal(recordNumber(record, "name", 9), 9);
  assert.equal(recordNumber(record, "missing"), 0);

  assert.equal(recordBoolean(record, "flag"), true);
  assert.equal(recordBoolean(record, "count", true), true);
  assert.equal(recordBoolean(record, "missing"), false);

  assert.deepEqual(recordStrings(record, "mixed"), ["a", "b"]);
  assert.deepEqual(recordStrings(record, "missing"), []);
});

test("decodeResponse decodes dateTime with positive and negative offsets", () => {
  function dateTime(
    year: number,
    month: number,
    day: number,
    hour: number,
    minute: number,
    second: number,
    direction: number,
    offsetHours: number,
    offsetMinutes: number
  ): Buffer {
    const raw = Buffer.alloc(11);
    raw.writeUInt16BE(year, 0);
    raw.writeUInt8(month, 2);
    raw.writeUInt8(day, 3);
    raw.writeUInt8(hour, 4);
    raw.writeUInt8(minute, 5);
    raw.writeUInt8(second, 6);
    raw.writeUInt8(0, 7); // deciseconds, ignored
    raw.writeUInt8(direction, 8);
    raw.writeUInt8(offsetHours, 9);
    raw.writeUInt8(offsetMinutes, 10);
    return raw;
  }

  const group = decodeGroup(IPP_TAG.jobAttributes, [
    attr(IPP_TAG.dateTime, "east", dateTime(2026, 10, 13, 12, 0, 0, 0x2b, 2, 0)),
    attr(IPP_TAG.dateTime, "west", dateTime(2026, 10, 13, 12, 0, 0, 0x2d, 5, 30)),
  ]);
  assert.equal((firstValue(group, "east") as Date).toISOString(), "2026-10-13T10:00:00.000Z");
  assert.equal((firstValue(group, "west") as Date).toISOString(), "2026-10-13T17:30:00.000Z");
});

test("decodeResponse decodes a collection into a key/value map", () => {
  function member(tag: number, name: string, value: Buffer): Buffer {
    return attr(tag, name, value);
  }
  const collection = Buffer.concat([
    member(IPP_TAG.memberName, "color", Buffer.from("black")),
    member(IPP_TAG.integer, "level", int32(50)),
    Buffer.from([IPP_TAG.endCollection]),
  ]);
  const group = decodeGroup(IPP_TAG.printerAttributes, [
    attr(IPP_TAG.collection, "marker", collection),
  ]);
  assert.deepEqual(firstValue(group, "marker"), { color: "black", level: 50 });
});

test("decodeResponse decodes with-language and octetString values", () => {
  function withLanguage(tag: number, language: string, text: string): Buffer {
    const languageBytes = Buffer.from(language, "utf8");
    const textBytes = Buffer.from(text, "utf8");
    const raw = Buffer.alloc(4 + languageBytes.length + textBytes.length);
    raw.writeUInt16BE(languageBytes.length, 0);
    languageBytes.copy(raw, 2);
    raw.writeUInt16BE(textBytes.length, 2 + languageBytes.length);
    textBytes.copy(raw, 4 + languageBytes.length);
    return raw;
  }

  const group = decodeGroup(IPP_TAG.printerAttributes, [
    attr(IPP_TAG.textWithLanguage, "text", withLanguage(IPP_TAG.textWithLanguage, "en", "hello")),
    attr(IPP_TAG.nameWithLanguage, "name", withLanguage(IPP_TAG.nameWithLanguage, "fr", "bonjour")),
    attr(IPP_TAG.octetString, "blob", Buffer.from([0xde, 0xad, 0xbe, 0xef])),
  ]);
  assert.equal(firstValue(group, "text"), "hello");
  assert.equal(firstValue(group, "name"), "bonjour");
  const blob = firstValue<Buffer>(group, "blob")!;
  assert.ok(Buffer.isBuffer(blob));
  assert.deepEqual([...blob], [0xde, 0xad, 0xbe, 0xef]);
});

test("decodeResponse reads a short dateTime as a plain string", () => {
  const group = decodeGroup(IPP_TAG.jobAttributes, [
    attr(IPP_TAG.dateTime, "when", Buffer.from("short")),
  ]);
  assert.equal(firstValue(group, "when"), "short");
});
