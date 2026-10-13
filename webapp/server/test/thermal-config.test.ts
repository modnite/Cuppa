import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, test } from "node:test";

/**
 * Thermal config: the pure default/merge/rounding logic, plus the file-backed
 * load/save/list/remove against a temporary CUPPA_THERMAL_DIR. The directory is
 * set before the dynamic import so the module captures it.
 */

const tempDir = mkdtempSync(path.join(os.tmpdir(), "cuppa-thermal-cfg-"));
process.env.CUPPA_THERMAL_DIR = tempDir;
const config = await import("../src/thermal/config.js");

after(() => rmSync(tempDir, { recursive: true, force: true }));

test("defaultThermalConfig returns the documented defaults", () => {
  assert.deepEqual(config.defaultThermalConfig(), {
    dialect: "tspl",
    labelWidthMm: 101.6,
    labelHeightMm: 152.4,
    dpi: 203,
    density: 8,
    speed: 5,
    gapMm: 3,
    dither: "FLOYD_STEINBERG",
    invertPolarity: false,
    headDots: 832,
  });
});

test("defaultThermalConfig merges a partial patch over the defaults", () => {
  const merged = config.defaultThermalConfig({ dialect: "zpl", density: 12, invertPolarity: true });
  assert.equal(merged.dialect, "zpl");
  assert.equal(merged.density, 12);
  assert.equal(merged.invertPolarity, true);
  assert.equal(merged.speed, 5);
  assert.equal(merged.dpi, 203);
});

test("THERMAL_DIALECTS lists every supported dialect", () => {
  assert.deepEqual(config.THERMAL_DIALECTS, ["tspl", "zpl", "epl", "escpos", "pcl"]);
});

test("labelWidthDots converts mm at the configured dpi and rounds", () => {
  const at = (labelWidthMm: number, dpi: number): number =>
    config.labelWidthDots(config.defaultThermalConfig({ labelWidthMm, dpi }));
  assert.equal(at(101.6, 203), 812); // 4" head exactly
  assert.equal(at(25.4, 72), 72); // 1" = 72pt
  assert.equal(at(50, 300), 591); // 590.55 rounds up
  assert.equal(at(12.7, 203), 102); // 101.5 rounds up
  assert.equal(at(0, 203), 0);
});

test("thermalConfigPath lives under the configured directory", () => {
  assert.equal(config.thermalConfigPath("Office_Laser"), path.join(tempDir, "Office_Laser.json"));
});

test("saveThermalConfig then loadThermalConfig round-trips a config", () => {
  const queue = "round-trip";
  const saved = config.defaultThermalConfig({ dialect: "escpos", density: 3 });
  config.saveThermalConfig(queue, saved);
  assert.deepEqual(config.loadThermalConfig(queue), saved);
});

test("loadThermalConfig returns null for a missing queue", () => {
  assert.equal(config.loadThermalConfig("does-not-exist"), null);
});

test("loadThermalConfig merges a partial file over the defaults", () => {
  const queue = "partial";
  writeFileSync(config.thermalConfigPath(queue), JSON.stringify({ dialect: "epl" }), "utf8");
  const loaded = config.loadThermalConfig(queue);
  assert.equal(loaded?.dialect, "epl");
  assert.equal(loaded?.density, 8);
  assert.equal(loaded?.headDots, 832);
});

test("loadThermalConfig returns null for malformed JSON", () => {
  const queue = "malformed";
  writeFileSync(config.thermalConfigPath(queue), "{not valid json", "utf8");
  assert.equal(config.loadThermalConfig(queue), null);
});

test("listThermalQueues reports every saved queue", () => {
  const queues = config.listThermalQueues();
  assert.ok(queues.includes("round-trip"));
  assert.ok(queues.includes("partial"));
  assert.ok(queues.includes("malformed"));
  assert.ok(!queues.includes("does-not-exist"));
});

test("removeThermalConfig deletes the file and is best-effort for missing queues", () => {
  const queue = "round-trip";
  config.removeThermalConfig(queue);
  assert.equal(config.loadThermalConfig(queue), null);
  config.removeThermalConfig("never-existed"); // must not throw
});

test("saveThermalConfig writes pretty JSON", () => {
  const queue = "pretty";
  config.saveThermalConfig(queue, config.defaultThermalConfig());
  const raw = readFileSync(config.thermalConfigPath(queue), "utf8");
  assert.ok(raw.includes('\n  "dialect": "tspl"'));
});
