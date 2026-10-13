import assert from "node:assert/strict";
import { test } from "node:test";
import {
  DITHER_MODES,
  centerOnHead,
  parsePgm,
  rasterize,
  type GrayImage,
} from "../src/thermal/raster.js";

/**
 * Raster edge cases the original `thermal.test.ts` does not cover: the two
 * error-diffusion modes, thresholds, row padding, already-wide images, and the
 * 16-bit / PBM / malformed parse paths.
 */

test("DITHER_MODES lists exactly the supported algorithms", () => {
  assert.deepEqual(DITHER_MODES, ["THRESHOLD", "FLOYD_STEINBERG", "ATKINSON"]);
});

test("THRESHOLD honours a custom threshold and defaults to black-on-white", () => {
  const image: GrayImage = { width: 2, height: 1, pixels: Uint8Array.from([100, 100]) };
  assert.deepEqual([...rasterize(image, "THRESHOLD", false, 128)], [0b1100_0000]);
  assert.deepEqual([...rasterize(image, "THRESHOLD", false, 50)], [0b0000_0000]);
  // Exactly at the threshold burns (<=).
  assert.deepEqual([...rasterize(image, "THRESHOLD", false, 100)], [0b1100_0000]);
});

test("FLOYD_STEINBERG diffuses error into later pixels", () => {
  const mid: GrayImage = { width: 2, height: 1, pixels: Uint8Array.from([100, 100]) };
  // Threshold alone would burn both; diffusion pushes the second over 128.
  assert.deepEqual([...rasterize(mid, "FLOYD_STEINBERG")], [0b1000_0000]);

  const bright: GrayImage = { width: 2, height: 1, pixels: Uint8Array.from([160, 160]) };
  assert.deepEqual([...rasterize(bright, "FLOYD_STEINBERG")], [0b0100_0000]);
});

test("ATKINSON discards error differently from Floyd-Steinberg", () => {
  const bright: GrayImage = { width: 2, height: 1, pixels: Uint8Array.from([160, 160]) };
  // Atkinson only passes 1/8 of the error, so the second pixel stays white.
  assert.deepEqual([...rasterize(bright, "ATKINSON")], [0b0000_0000]);
});

test("rasterize pads each row to a byte boundary, MSB first", () => {
  const image: GrayImage = { width: 9, height: 1, pixels: new Uint8Array(9) }; // all black
  const packed = rasterize(image, "THRESHOLD");
  assert.deepEqual([...packed], [0xff, 0b1000_0000]);
});

test("inverted polarity flips only the used bits and leaves padding clear", () => {
  const image: GrayImage = { width: 9, height: 1, pixels: new Uint8Array(9).fill(255) };
  const packed = rasterize(image, "THRESHOLD", true);
  assert.deepEqual([...packed], [0xff, 0b1000_0000]);
});

test("centerOnHead returns the same image when it is already head-wide", () => {
  const image: GrayImage = { width: 8, height: 1, pixels: Uint8Array.from([0, 1, 2, 3, 4, 5, 6, 7]) };
  assert.equal(centerOnHead(image, 8), image);
  assert.equal(centerOnHead(image, 4), image);
});

test("centerOnHead pads odd gaps asymmetrically and per row", () => {
  const image: GrayImage = {
    width: 3,
    height: 2,
    pixels: Uint8Array.from([0, 0, 0, 255, 255, 255]),
  };
  const centred = centerOnHead(image, 8);
  assert.equal(centred.width, 8);
  assert.deepEqual([...centred.pixels], [
    255, 255, 0, 0, 0, 255, 255, 255, // left = (8-3)>>1 = 2
    255, 255, 255, 255, 255, 255, 255, 255, // row 1 content is white anyway
  ]);
});

test("parsePgm reads a 16-bit P5 image using the high byte", () => {
  const header = Buffer.from("P5\n2 1\n65535\n", "ascii");
  const body = Buffer.from([0x00, 0x10, 0xab, 0xcd]);
  const image = parsePgm(Buffer.concat([header, body]));
  assert.equal(image.width, 2);
  assert.equal(image.height, 1);
  assert.deepEqual([...image.pixels], [0x00, 0xab]);
});

test("parsePgm handles whitespace and comments around the header tokens", () => {
  const header = Buffer.from("P5 # comment here\n 2\t2\n255\n", "ascii");
  const image = parsePgm(Buffer.concat([header, Buffer.from([1, 2, 3, 4])]));
  assert.deepEqual([...image.pixels], [1, 2, 3, 4]);
});

test("parsePgm reads a packed PBM (P4) image, one bit per pixel", () => {
  const image = parsePgm(Buffer.concat([Buffer.from("P4\n8 1\n", "ascii"), Buffer.from([0b1010_0000])]));
  assert.deepEqual([...image.pixels], [0, 255, 0, 255, 255, 255, 255, 255]);
});

test("parsePgm reads a PBM whose rows are byte-padded", () => {
  const image = parsePgm(
    Buffer.concat([Buffer.from("P4\n10 1\n", "ascii"), Buffer.from([0xff, 0b1100_0000])])
  );
  assert.deepEqual([...image.pixels], new Array(10).fill(0));
});

test("parsePgm rejects an empty buffer and an unsupported magic", () => {
  assert.throws(() => parsePgm(Buffer.alloc(0)), /Empty PGM/);
  assert.throws(() => parsePgm(Buffer.from("P6\n1 1\n255\n")), /Unsupported image magic P6/);
});

test("parsePgm tolerates a truncated P5 body by filling zeros", () => {
  const image = parsePgm(Buffer.from("P5\n2 2\n255\n", "ascii"));
  assert.deepEqual([...image.pixels], [0, 0, 0, 0]);
});
