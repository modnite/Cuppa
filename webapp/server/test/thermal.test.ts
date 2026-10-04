import assert from "node:assert/strict";
import { test } from "node:test";
import { centerOnHead, parsePgm, rasterize, type GrayImage } from "../src/thermal/raster.js";
import { generateTsplTestLabel, tsplFromImage } from "../src/thermal/tspl.js";

test("parsePgm reads a binary P5 image", () => {
  const header = Buffer.from("P5\n# a comment\n2 2\n255\n", "ascii");
  const body = Buffer.from([0, 255, 128, 64]);
  const image = parsePgm(Buffer.concat([header, body]));
  assert.equal(image.width, 2);
  assert.equal(image.height, 2);
  assert.deepEqual([...image.pixels], [0, 255, 128, 64]);
});

test("rasterize threshold packs MSB-first bits", () => {
  const image: GrayImage = { width: 2, height: 2, pixels: Uint8Array.from([0, 255, 255, 0]) };
  const packed = rasterize(image, "THRESHOLD", false, 128);
  // Row 0: black, white -> 0b10000000. Row 1: white, black -> 0b01000000.
  assert.deepEqual([...packed], [0b1000_0000, 0b0100_0000]);
});

test("rasterize inverts polarity", () => {
  const image: GrayImage = { width: 2, height: 2, pixels: Uint8Array.from([0, 255, 255, 0]) };
  const packed = rasterize(image, "THRESHOLD", true, 128);
  // Only the two used bits flip; the padding bits stay 0.
  assert.deepEqual([...packed], [0b0100_0000, 0b1000_0000]);
});

test("centerOnHead pads with white and centres the content", () => {
  const image: GrayImage = { width: 2, height: 1, pixels: Uint8Array.from([0, 255]) };
  const centred = centerOnHead(image, 8);
  assert.equal(centred.width, 8);
  assert.deepEqual([...centred.pixels], [255, 255, 255, 0, 255, 255, 255, 255]);
});

test("tsplFromImage emits a Rollo job centred on the 832-dot head", () => {
  const image: GrayImage = { width: 4, height: 4, pixels: new Uint8Array(16).fill(255) };
  const job = tsplFromImage(image, { density: 8, speed: 5 });
  // Reset preamble.
  assert.deepEqual([...job.subarray(0, 4)], [0x7e, 0x40, 0x0d, 0x0a]);
  const text = job.toString("latin1");
  assert.ok(text.includes("SIZE 104 mm ,1 mm\n"), "sized to the centred 832-dot width");
  assert.ok(text.includes("DENSITY 8\n"));
  assert.ok(text.includes("SPEED 5\n"));
  assert.ok(text.includes("BITMAP 0,0,104,4,1,"));
  assert.ok(text.endsWith("\nPRINT 1,1\n"));
});

test("generateTsplTestLabel produces the diagnostic label", () => {
  const label = generateTsplTestLabel("Rollo X1038", "CUPS v2.2.9", "USB Direct");
  const text = label.toString("latin1");
  assert.ok(text.includes("CUPPA PRINT SERVER"));
  assert.ok(text.includes("Rollo X1038"));
  assert.ok(text.includes("CUPPA-ROLLO-X1038"));
  assert.ok(text.includes("PASS - ROLLO X1038 PRINT ENGINE VERIFIED"));
  assert.ok(text.endsWith("PRINT 1,1\n"));
});
