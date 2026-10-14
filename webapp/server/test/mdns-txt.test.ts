import assert from "node:assert/strict";
import { test } from "node:test";
import { documentFormats, txtRecords } from "../src/mdns.js";
import type { CuppaSettings, PrinterView } from "../src/types.js";

/**
 * The Bonjour TXT records are what decides whether a client shows the printer
 * at all. These pin the two rules that matter: never claim a format the queue
 * cannot accept, and always publish the standard keys a real IPP Everywhere
 * printer carries.
 */

function printer(overrides: Partial<PrinterView> = {}): PrinterView {
  return {
    queue: "Office",
    displayName: "Printer",
    advertisedName: "Printer (Office)",
    location: "Office",
    makeAndModel: "Brother MFC-L2717DW",
    state: 3,
    stateLabel: "Idle",
    stateReasons: [],
    accepting: true,
    enabled: true,
    shared: true,
    isDefault: false,
    color: false,
    formats: [],
    uri: "ipp://192.168.1.180/ipp/print",
    deviceUri: "ipp://192.168.1.180/ipp/print",
    uuid: "urn:uuid:11111111-2222-3333-4444-555555555555",
    driver: "everywhere",
    thermal: null,
    ...overrides,
  };
}

const settings: CuppaSettings = {
  advertiseEnabled: true,
  airprintCompat: true,
  tlsEnabled: false,
  adminPasswordHash: null,
  adminPasswordSalt: null,
};

test("documentFormats keeps the queue's own formats in preference order", () => {
  const formats = documentFormats(
    printer({ formats: ["image/tiff", "application/pdf", "image/pwg-raster"] })
  );
  assert.equal(formats, "application/pdf,image/pwg-raster,image/tiff");
});

test("documentFormats never claims PDF for a raw queue", () => {
  // A raw queue passes the document through unchanged, so advertising PDF makes
  // clients send a PDF that comes out blank.
  assert.equal(documentFormats(printer({ formats: [] })), "application/octet-stream");
});

test("txtRecords publishes the standard IPP Everywhere keys", () => {
  const records = txtRecords(printer({ formats: ["application/pdf", "image/pwg-raster"] }), settings);
  const map = new Map(records.map((entry) => [entry.split("=")[0], entry.slice(entry.indexOf("=") + 1)]));
  assert.equal(map.get("rp"), "printers/Office");
  assert.equal(map.get("pdl"), "application/pdf,image/pwg-raster");
  assert.equal(map.get("ty"), "Brother MFC-L2717DW");
  assert.equal(map.get("UUID"), "11111111-2222-3333-4444-555555555555");
  assert.ok(map.get("adminurl")?.endsWith("/printers/Office"), "adminurl points at the queue");
  assert.equal(map.get("txtvers"), "1");
  assert.equal(map.get("qtotal"), "1");
});

test("txtRecords adds TLS only when encryption is enabled", () => {
  const plain = txtRecords(printer(), settings);
  assert.ok(!plain.includes("TLS=1.2"));
  const secure = txtRecords(printer(), { ...settings, tlsEnabled: true });
  assert.ok(secure.includes("TLS=1.2"));
});
