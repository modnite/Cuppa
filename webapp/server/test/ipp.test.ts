import assert from "node:assert/strict";
import { test } from "node:test";
import {
  IPP_OP,
  IPP_TAG,
  buildRequest,
  commonAttributes,
  decodeResponse,
  groupRecords,
  multi,
  opGroup,
  recordNumber,
  recordString,
  recordStrings,
} from "../src/ipp.js";

/** Builds a synthetic IPP response the way a CUPS scheduler would. */
function encodeResponse(
  requestId: number,
  groupTag: number,
  attributes: Array<{ tag: number; name: string; value: string | number | boolean }>
): Buffer {
  const parts: Buffer[] = [];
  const header = Buffer.alloc(8);
  header.writeUInt8(2, 0);
  header.writeUInt8(0, 1);
  header.writeUInt16BE(0, 2); // successful-ok
  header.writeUInt32BE(requestId, 4);
  parts.push(header);
  parts.push(Buffer.from([groupTag]));
  for (const attribute of attributes) {
    const name = Buffer.from(attribute.name, "utf8");
    let value: Buffer;
    if (attribute.tag === IPP_TAG.integer || attribute.tag === IPP_TAG.enum) {
      value = Buffer.alloc(4);
      value.writeInt32BE(Number(attribute.value), 0);
    } else if (attribute.tag === IPP_TAG.boolean) {
      value = Buffer.from([attribute.value ? 1 : 0]);
    } else {
      value = Buffer.from(String(attribute.value), "utf8");
    }
    const nameHeader = Buffer.alloc(2);
    nameHeader.writeUInt16BE(name.length, 0);
    const valueHeader = Buffer.alloc(2);
    valueHeader.writeUInt16BE(value.length, 0);
    parts.push(Buffer.from([attribute.tag]), nameHeader, name, valueHeader, value);
  }
  parts.push(Buffer.from([IPP_TAG.endOfAttributes]));
  return Buffer.concat(parts);
}

test("buildRequest writes the IPP header and operation attributes", () => {
  const buffer = buildRequest(
    IPP_OP.CUPS_GET_PRINTERS,
    7,
    [opGroup([...commonAttributes(), ...multi(IPP_TAG.keyword, "requested-attributes", ["printer-name", "printer-info"])])]
  );
  assert.equal(buffer.readUInt8(0), 2); // IPP/2.0
  assert.equal(buffer.readUInt8(1), 0);
  assert.equal(buffer.readUInt16BE(2), IPP_OP.CUPS_GET_PRINTERS);
  assert.equal(buffer.readUInt32BE(4), 7);
  assert.equal(buffer.readUInt8(8), IPP_TAG.operationAttributes);
  assert.equal(buffer.readUInt8(buffer.length - 1), IPP_TAG.endOfAttributes);
  assert.ok(buffer.includes(Buffer.from("attributes-charset")));
  assert.ok(buffer.includes(Buffer.from("printer-name")));
});

test("decodeResponse parses a multi-printer CUPS reply", () => {
  const response = encodeResponse(9, IPP_TAG.printerAttributes, [
    { tag: IPP_TAG.nameWithoutLanguage, name: "printer-name", value: "Office_Laser" },
    { tag: IPP_TAG.textWithoutLanguage, name: "printer-info", value: "Office Laser (Cuppa)" },
    { tag: IPP_TAG.enum, name: "printer-state", value: 3 },
    { tag: IPP_TAG.boolean, name: "printer-is-accepting-jobs", value: true },
    { tag: IPP_TAG.keyword, name: "printer-state-reasons", value: "none" },
    { tag: IPP_TAG.mimeMediaType, name: "document-format-supported", value: "application/pdf" },
    { tag: IPP_TAG.mimeMediaType, name: "document-format-supported", value: "image/pwg-raster" },
    { tag: IPP_TAG.nameWithoutLanguage, name: "printer-name", value: "Rollo" },
    { tag: IPP_TAG.enum, name: "printer-state", value: 5 },
  ]);

  const decoded = decodeResponse(response);
  assert.equal(decoded.statusCode, 0);
  assert.equal(decoded.requestId, 9);

  const group = decoded.groups.find((entry) => entry.tag === IPP_TAG.printerAttributes);
  const records = groupRecords(group, "printer-name");
  assert.equal(records.length, 2);
  assert.equal(recordString(records[0]!, "printer-name"), "Office_Laser");
  assert.equal(recordString(records[0]!, "printer-info"), "Office Laser (Cuppa)");
  assert.equal(recordNumber(records[0]!, "printer-state"), 3);
  assert.deepEqual(recordStrings(records[0]!, "document-format-supported"), [
    "application/pdf",
    "image/pwg-raster",
  ]);
  assert.equal(recordString(records[1]!, "printer-name"), "Rollo");
  assert.equal(recordNumber(records[1]!, "printer-state"), 5);
});
