import assert from "node:assert/strict";
import { test } from "node:test";
import type { GrayImage } from "../src/thermal/raster.js";
import { TsplBuilder, tsplFromImage } from "../src/thermal/tspl.js";

/**
 * TSPL coverage beyond the two smoke tests: every `TsplBuilder` command, its
 * clamping, and the `tsplFromImage` option paths.
 */

const RESET_PREAMBLE = [0x7e, 0x40, 0x0d, 0x0a];

test("TsplBuilder constructor writes the size preamble and defaults to the Rollo", () => {
  const text = new TsplBuilder().buildString();
  assert.ok(text.startsWith("SIZE 101 mm ,152 mm\n"));
  assert.ok(text.includes("REFERENCE 0,0\n"));
  assert.ok(text.includes("DIRECTION 0,0\n"));
  assert.ok(text.includes("GAP 3 mm,0 mm\n"));
  assert.ok(text.includes("OFFSET 0 mm\n"));

  const truncated = new TsplBuilder(50.9, 100.1).buildString();
  assert.ok(truncated.startsWith("SIZE 50 mm ,100 mm\n"));
});

test("TsplBuilder clamps density and speed to the printer's range", () => {
  assert.ok(new TsplBuilder().setDensity(-5).buildString().includes("DENSITY 0\n"));
  assert.ok(new TsplBuilder().setDensity(99).buildString().includes("DENSITY 15\n"));
  assert.ok(new TsplBuilder().setDensity(8.9).buildString().includes("DENSITY 8\n"));
  assert.ok(new TsplBuilder().setSpeed(1).buildString().includes("SPEED 2\n"));
  assert.ok(new TsplBuilder().setSpeed(99).buildString().includes("SPEED 6\n"));
});

test("TsplBuilder setGap truncates and defaults the offset", () => {
  assert.ok(new TsplBuilder().setGap().buildString().includes("GAP 3 mm,0 mm\n"));
  assert.ok(new TsplBuilder().setGap(4.9, 2.2).buildString().includes("GAP 4 mm,2 mm\n"));
});

test("TsplBuilder setDirection and setReference emit their commands", () => {
  const text = new TsplBuilder().setDirection(1).setReference(12, 34).buildString();
  assert.ok(text.includes("DIRECTION 1,0\n"));
  assert.ok(text.includes("REFERENCE 12,34\n"));
});

test("TsplBuilder clearBuffer emits the four setup commands", () => {
  const text = new TsplBuilder().clearBuffer().buildString();
  assert.ok(text.endsWith("SETC AUTODOTTED OFF\nSETC PAUSEKEY ON\nSETC WATERMARK OFF\nCLS\n"));
});

test("TsplBuilder drawText strips quotes and passes styling through", () => {
  const text = new TsplBuilder()
    .drawText(1, 2, 'he said "hi"')
    .drawText(5, 6, "styled", "4", 90, 2, 3)
    .buildString();
  assert.ok(text.includes('TEXT 1,2,"3",0,1,1,"he said hi"\n'));
  assert.ok(text.includes('TEXT 5,6,"4",90,2,3,"styled"\n'));
});

test("TsplBuilder draws bars, boxes and lines", () => {
  const text = new TsplBuilder()
    .drawBar(1, 2, 30, 40)
    .drawBox(5, 6, 50, 60)
    .drawBox(7, 8, 70, 80, 4)
    .drawHorizontalLine(9, 10, 100, 3)
    .buildString();
  assert.ok(text.includes("BAR 1,2,30,40\n"));
  assert.ok(text.includes("BOX 5,6,50,60,2\n"));
  assert.ok(text.includes("BOX 7,8,70,80,4\n"));
  // A horizontal line is a bar with the thickness as its height.
  assert.ok(text.includes("BAR 9,10,100,3\n"));
});

test("TsplBuilder drawBarcode128 strips quotes and honours its options", () => {
  const text = new TsplBuilder()
    .drawBarcode128(1, 2, 'A"B')
    .drawBarcode128(3, 4, "D", 50, 0, 180, 1, 3)
    .buildString();
  assert.ok(text.includes('BARCODE 1,2,"128",80,1,0,2,4,"AB"\n'));
  assert.ok(text.includes('BARCODE 3,4,"128",50,0,180,1,3,"D"\n'));
});

test("TsplBuilder drawQrCode strips quotes and honours its options", () => {
  const text = new TsplBuilder()
    .drawQrCode(1, 2, 'c"d')
    .drawQrCode(3, 4, "DATA", "H", 10, "M", 90)
    .buildString();
  assert.ok(text.includes('QRCODE 1,2,M,6,A,0,"cd"\n'));
  assert.ok(text.includes('QRCODE 3,4,H,10,M,90,"DATA"\n'));
});

test("TsplBuilder print defaults to one copy and build prepends the reset", () => {
  assert.ok(new TsplBuilder().print().buildString().endsWith("PRINT 1,1\n"));
  assert.ok(new TsplBuilder().print(3).buildString().endsWith("PRINT 3,1\n"));

  const built = new TsplBuilder().print().build();
  assert.deepEqual([...built.subarray(0, 4)], RESET_PREAMBLE);
});

test("tsplFromImage clamps density, speed and gap", () => {
  const image: GrayImage = { width: 4, height: 4, pixels: new Uint8Array(16).fill(255) };
  const text = tsplFromImage(image, { density: 99, speed: 1, gapMm: 0 }).toString("latin1");
  assert.ok(text.includes("DENSITY 15\n"));
  assert.ok(text.includes("SPEED 2\n"));
  assert.ok(text.includes("GAP 0 mm,0 mm\n"));
});

test("tsplFromImage inverts the raster when asked", () => {
  // Exactly head-wide, all black: every raster byte burns, or none when inverted.
  const black: GrayImage = { width: 832, height: 1, pixels: new Uint8Array(832) };
  const marker = Buffer.from("\nBITMAP 0,0,104,1,1,\n", "ascii");

  const normal = tsplFromImage(black, { ditherMode: "THRESHOLD" });
  const inverted = tsplFromImage(black, { ditherMode: "THRESHOLD", invertPolarity: true });

  const normalStart = normal.indexOf(marker) + marker.length;
  const invertedStart = inverted.indexOf(marker) + marker.length;
  assert.ok(normal.indexOf(marker) !== -1);
  assert.ok(inverted.indexOf(marker) !== -1);

  assert.deepEqual([...normal.subarray(normalStart, normalStart + 104)], new Array(104).fill(0xff));
  assert.deepEqual([...inverted.subarray(invertedStart, invertedStart + 104)], new Array(104).fill(0x00));
});

test("tsplFromImage sizes a head-wide image without re-centring it", () => {
  const image: GrayImage = { width: 832, height: 8, pixels: new Uint8Array(832 * 8).fill(255) };
  const text = tsplFromImage(image, { ditherMode: "THRESHOLD" }).toString("latin1");
  assert.ok(text.includes("SIZE 104 mm ,1 mm\n"));
  assert.ok(text.includes("BITMAP 0,0,104,8,1,"));
  assert.ok(text.endsWith("\nPRINT 1,1\n"));
});
