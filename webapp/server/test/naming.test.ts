import assert from "node:assert/strict";
import { test } from "node:test";
import {
  advertisedName,
  deterministicUuid,
  resourceName,
  stripCuppaSuffix,
  uniqueResourceName,
} from "../src/naming.js";

test("resourceName matches the Android sanitizer", () => {
  assert.equal(resourceName("Brother MFC-L2717DW"), "Brother_MFC-L2717DW");
  assert.equal(resourceName("USB Printer (0x09C5:0x0588)"), "USB_Printer_0x09C5_0x0588");
  assert.equal(resourceName("a.b-c_d"), "a.b-c_d");
  assert.equal(resourceName("  (x)  "), "x");
  assert.equal(resourceName("()"), "printer");
  assert.equal(resourceName(""), "printer");
});

test("advertisedName appends (Cuppa) exactly once", () => {
  assert.equal(advertisedName("Office Laser"), "Office Laser (Cuppa)");
  assert.equal(advertisedName("Office Laser (Cuppa)"), "Office Laser (Cuppa)");
  assert.equal(advertisedName(""), "Cuppa Printer (Cuppa)");
});

test("stripCuppaSuffix removes only the Cuppa decoration", () => {
  assert.equal(stripCuppaSuffix("Office Laser (Cuppa)"), "Office Laser");
  assert.equal(stripCuppaSuffix("Office Laser"), "Office Laser");
  assert.equal(stripCuppaSuffix("Cuppa (Cuppa)"), "Cuppa");
});

test("uniqueResourceName avoids collisions", () => {
  assert.equal(uniqueResourceName("Office Laser", []), "Office_Laser");
  assert.equal(uniqueResourceName("Office Laser", ["Office_Laser"]), "Office_Laser_2");
  assert.equal(uniqueResourceName("Office Laser", ["Office_Laser", "Office_Laser_2"]), "Office_Laser_3");
});

test("deterministicUuid is stable and RFC 4122 shaped", () => {
  const first = deterministicUuid("Office_Laser");
  assert.equal(first, deterministicUuid("Office_Laser"));
  assert.match(first, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.notEqual(first, deterministicUuid("Other"));
});
