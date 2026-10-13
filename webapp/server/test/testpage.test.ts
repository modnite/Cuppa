import assert from "node:assert/strict";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";

/**
 * `generateThermalTestLabel` dispatches to the right driver per dialect. The
 * CUPS test page is pointed at a path that cannot exist, so the TSPL branch
 * deterministically falls back to the pre-drawn diagnostic label.
 */

process.env.CUPPA_TEST_PAGE = path.join(os.tmpdir(), "cuppa-no-such-testpage.pdf");
const { generateThermalTestLabel } = await import("../src/thermal/testpage.js");
const { defaultThermalConfig } = await import("../src/thermal/config.js");

const queue = "Office_Laser";

test("generateThermalTestLabel uses the ZPL driver for a zpl queue", () => {
  const bytes = generateThermalTestLabel(defaultThermalConfig({ dialect: "zpl" }), queue);
  const text = bytes.toString("ascii");
  assert.ok(text.startsWith("^XA\n"));
  assert.ok(text.includes("CUPPA PRINT SERVER"));
  assert.ok(text.includes(queue));
  assert.ok(text.endsWith("^XZ\n"));
});

test("generateThermalTestLabel uses the EPL driver for an epl queue", () => {
  const bytes = generateThermalTestLabel(defaultThermalConfig({ dialect: "epl" }), queue);
  const text = bytes.toString("ascii");
  assert.ok(text.startsWith("N\n"));
  assert.ok(text.includes(queue));
  assert.ok(text.endsWith("P1\n"));
});

test("generateThermalTestLabel uses the ESC/POS driver for an escpos queue", () => {
  const bytes = generateThermalTestLabel(defaultThermalConfig({ dialect: "escpos" }), queue);
  assert.deepEqual([...bytes.subarray(0, 2)], [0x1b, 0x40]);
  const text = bytes.toString("ascii");
  assert.ok(text.includes("CUPPA PRINT SERVER"));
  assert.ok(text.includes(queue));
});

test("generateThermalTestLabel uses the PCL driver for a pcl queue", () => {
  const bytes = generateThermalTestLabel(defaultThermalConfig({ dialect: "pcl" }), queue);
  assert.deepEqual([...bytes.subarray(0, 2)], [0x1b, 0x45]);
  const text = bytes.toString("ascii");
  assert.ok(text.includes("CUPPA PRINT SERVER"));
  assert.ok(text.includes(queue));
});

test("generateThermalTestLabel falls back to the TSPL diagnostic label for a tspl queue", () => {
  const bytes = generateThermalTestLabel(defaultThermalConfig({ dialect: "tspl" }), queue);
  assert.deepEqual([...bytes.subarray(0, 4)], [0x7e, 0x40, 0x0d, 0x0a]);
  assert.ok(bytes.toString("latin1").endsWith("PRINT 1,1\n"));
});
