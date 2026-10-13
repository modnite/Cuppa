import assert from "node:assert/strict";
import { test } from "node:test";
import type { GrayImage } from "../src/thermal/raster.js";
import { ZplDriver } from "../src/thermal/drivers/zpl.js";
import { EplDriver } from "../src/thermal/drivers/epl.js";
import { EscPosDriver, Alignment } from "../src/thermal/drivers/escpos.js";
import { PclDriver } from "../src/thermal/drivers/pcl.js";

/**
 * Every remaining public driver method: clamps, escaping, polarity, framing and
 * boundary values, complementing `thermal-drivers.test.ts`.
 */

function byteRow(count: number, value: number): GrayImage {
  return { width: count, height: 1, pixels: new Uint8Array(count).fill(value) };
}

// ---------------------------------------------------------------------------
// ZPL
// ---------------------------------------------------------------------------

test("ZplDriver constructor records width, length and home", () => {
  const zpl = new ZplDriver(600, 900).buildString();
  assert.ok(zpl.startsWith("^XA\n^PW600\n^LL900\n^LH0,0\n"));
  assert.ok(zpl.endsWith("^XZ\n"));
});

test("ZplDriver clamps darkness and print speed", () => {
  assert.ok(new ZplDriver().setDarkness(-5).buildString().includes("~SD00\n"));
  assert.ok(new ZplDriver().setDarkness(99).buildString().includes("~SD30\n"));
  assert.ok(new ZplDriver().setDarkness(5).buildString().includes("~SD05\n"));
  assert.ok(new ZplDriver().setPrintSpeed(1).buildString().includes("^PR2,2\n"));
  assert.ok(new ZplDriver().setPrintSpeed(9).buildString().includes("^PR6,6\n"));
});

test("ZplDriver drawText strips ZPL control characters", () => {
  const zpl = new ZplDriver().drawText(0, 0, "a^b~c").drawText(1, 2, "x", 40, 20, "A").buildString();
  assert.ok(zpl.includes("^FO0,0^A0N,30,30^FDabc^FS\n"));
  assert.ok(zpl.includes("^FO1,2^AAN,40,20^FDx^FS\n"));
});

test("ZplDriver boxes, lines and corner radii", () => {
  const zpl = new ZplDriver()
    .drawBox(1, 2, 100, 200, 3, 5)
    .drawBox(3, 4, 10, 20)
    .drawBox(5, 6, 10, 20, 2, 99) // radius clamps to 8
    .drawHorizontalLine(7, 8, 100, 3)
    .drawVerticalLine(9, 10, 100, 3)
    .buildString();
  assert.ok(zpl.includes("^FO1,2^GB100,200,3,B,5^FS\n"));
  assert.ok(zpl.includes("^FO3,4^GB10,20,2^FS\n"));
  assert.ok(zpl.includes("^FO5,6^GB10,20,2,B,8^FS\n"));
  assert.ok(zpl.includes("^FO7,8^GB100,3,3^FS\n"));
  assert.ok(zpl.includes("^FO9,10^GB3,100,3^FS\n"));
});

test("ZplDriver barcode and QR boundary options", () => {
  const zpl = new ZplDriver()
    .drawBarcode128(1, 2, "D", 80, 2, false)
    .drawQrCode(3, 4, "D", 0, "L") // magnification clamps to 1
    .drawQrCode(5, 6, "D", 99) // clamps to 10
    .buildString();
  assert.ok(zpl.includes("^FO1,2^BY2,3,80^BCN,80,N,N,N^FDD^FS\n"));
  assert.ok(zpl.includes("^FO3,4^BQN,2,1,L^FDQA,D^FS\n"));
  assert.ok(zpl.includes("^FO5,6^BQN,2,10,M^FDQA,D^FS\n"));
});

test("ZplDriver drawBitmap polarity and hex framing", () => {
  const image = byteRow(8, 0); // all black
  const normal = new ZplDriver().drawBitmap(0, 0, image, "THRESHOLD", false).buildString();
  const inverted = new ZplDriver().drawBitmap(0, 0, image, "THRESHOLD", true).buildString();
  assert.ok(normal.includes("^FO0,0^GFA,1,1,1,FF^FS\n"));
  assert.ok(inverted.includes("^FO0,0^GFA,1,1,1,00^FS\n"));
});

test("ZplDriver build returns the ASCII of buildString", () => {
  const driver = new ZplDriver().drawText(0, 0, "X");
  assert.equal(driver.build().toString("ascii"), driver.buildString());
});

// ---------------------------------------------------------------------------
// EPL
// ---------------------------------------------------------------------------

test("EplDriver constructor sets up the label", () => {
  const text = new EplDriver(600, 900, 12).build(1).toString("ascii");
  assert.ok(text.startsWith("N\nq600\nQ900,12\n"));
  assert.ok(text.endsWith("P1\n"));
});

test("EplDriver clamps density and speed", () => {
  assert.ok(new EplDriver().setDensity(-1).build().toString("ascii").includes("D0\n"));
  assert.ok(new EplDriver().setDensity(99).build().toString("ascii").includes("D15\n"));
  assert.ok(new EplDriver().setSpeed(0).build().toString("ascii").includes("S1\n"));
  assert.ok(new EplDriver().setSpeed(9).build().toString("ascii").includes("S5\n"));
});

test("EplDriver drawText clamps, escapes and handles reverse/rotation", () => {
  const text = new EplDriver()
    .drawText(1, 2, 'a"b', 3, 1, 1, true, 90)
    .drawText(0, 0, "x", 9, 9, 9)
    .build()
    .toString("ascii");
  assert.ok(text.includes('A1,2,90,3,1,1,R,"a\\"b"\n'));
  assert.ok(text.includes('A0,0,0,5,8,8,N,"x"\n'));
});

test("EplDriver lines and boxes compute end coordinates", () => {
  const text = new EplDriver()
    .drawLine(1, 2, 3, 4)
    .drawBox(10, 20, 100, 200)
    .build()
    .toString("ascii");
  assert.ok(text.includes("LO1,2,3,4\n"));
  assert.ok(text.includes("X10,20,2,110,220\n"));
});

test("EplDriver barcode and QR options", () => {
  const text = new EplDriver()
    .drawBarcode128(1, 2, "D", 60, 2, 4, false, 90)
    .drawQrCode(3, 4, "D", 10, "H")
    .drawQrCode(5, 6, "D", 0, "l")
    .drawQrCode(7, 8, "D", 5, "Z")
    .build()
    .toString("ascii");
  assert.ok(text.includes('B1,2,90,1,2,4,60,N,"D"\n'));
  assert.ok(text.includes('b3,4,Q,m10,s3,"D"\n'));
  assert.ok(text.includes('b5,6,Q,m1,s0,"D"\n'));
  assert.ok(text.includes('b7,8,Q,m5,s1,"D"\n')); // unknown ECC falls back to M
});

test("EplDriver drawBitmap defaults to inverted (burn=0) polarity", () => {
  const black = byteRow(8, 0);
  const defaultBytes = new EplDriver().drawBitmap(0, 0, black, "THRESHOLD").build();
  const rawBytes = new EplDriver().drawBitmap(0, 0, black, "THRESHOLD", false).build();
  const marker = Buffer.from("GW0,0,1,1\n", "ascii");
  const at = defaultBytes.indexOf(marker) + marker.length;
  assert.equal(defaultBytes[at], 0x00);
  assert.equal(rawBytes[rawBytes.indexOf(marker) + marker.length], 0xff);
});

test("EplDriver build clamps the copy count to at least one", () => {
  assert.ok(new EplDriver().build(0).toString("ascii").endsWith("P1\n"));
  assert.ok(new EplDriver().build(3).toString("ascii").endsWith("P3\n"));
});

// ---------------------------------------------------------------------------
// ESC/POS
// ---------------------------------------------------------------------------

test("EscPosDriver style commands emit the right bytes", () => {
  const bytes = new EscPosDriver()
    .setAlignment(Alignment.RIGHT)
    .setBold(true)
    .setBold(false)
    .setUnderline(true)
    .build();
  assert.deepEqual([...bytes.subarray(0, 2)], [0x1b, 0x40]);
  assert.deepEqual([...bytes.subarray(2, 5)], [0x1b, 0x61, 2]);
  assert.deepEqual([...bytes.subarray(5, 8)], [0x1b, 0x45, 1]);
  assert.deepEqual([...bytes.subarray(8, 11)], [0x1b, 0x45, 0]);
  assert.deepEqual([...bytes.subarray(11, 14)], [0x1b, 0x2d, 1]);
});

test("EscPosDriver setTextSize clamps the scale to 1..8", () => {
  const size = (w: number, h: number): number[] => {
    const bytes = new EscPosDriver().setTextSize(w, h).build();
    return [...bytes.subarray(bytes.length - 3)];
  };
  assert.deepEqual(size(1, 1), [0x1d, 0x21, 0x00]);
  assert.deepEqual(size(8, 8), [0x1d, 0x21, 0x77]);
  assert.deepEqual(size(0, 0), [0x1d, 0x21, 0x00]);
  assert.deepEqual(size(99, 99), [0x1d, 0x21, 0x77]);
});

test("EscPosDriver text helpers pad, overflow and divide", () => {
  const padded = new EscPosDriver(10).printTwoColumnLine("ab", "cd").build().toString("ascii");
  assert.ok(padded.includes("ab      cd\n")); // 10 - 2 - 2 spaces

  // When the columns do not fit, they are joined by a single space instead.
  const tight = new EscPosDriver(8).printTwoColumnLine("abcdefg", "gh").build().toString("ascii");
  assert.ok(tight.includes("abcdefg gh\n"));

  const dividers = new EscPosDriver(10).printDivider().printDivider("=").printLine().build().toString("ascii");
  assert.ok(dividers.includes("----------\n"));
  assert.ok(dividers.includes("==========\n"));
});

test("EscPosDriver feed clamps the line count", () => {
  assert.deepEqual([...new EscPosDriver().feed().build().subarray(-3)], [0x1b, 0x64, 1]);
  assert.deepEqual([...new EscPosDriver().feed(0).build().subarray(-3)], [0x1b, 0x64, 1]);
  assert.deepEqual([...new EscPosDriver().feed(300).build().subarray(-3)], [0x1b, 0x64, 255]);
});

test("EscPosDriver barcode framing clamps height and width", () => {
  const bytes = new EscPosDriver().printBarcode128("12345678", 300, 0).build();
  assert.notEqual(bytes.indexOf(Buffer.from([0x1d, 0x68, 255])), -1); // GS h, height clamped
  assert.notEqual(bytes.indexOf(Buffer.from([0x1d, 0x77, 2])), -1); // GS w, width clamped
  assert.notEqual(bytes.indexOf(Buffer.from([0x1d, 0x48, 2])), -1); // GS H
  assert.notEqual(bytes.indexOf(Buffer.from([0x1d, 0x6b, 73, 8])), -1); // GS k 73 len
  assert.ok(bytes.toString("ascii").includes("12345678"));
});

test("EscPosDriver QR framing clamps the module size and length fields", () => {
  const small = new EscPosDriver().printQrCode("D", 0).build();
  // Module size chunk: GS ( k 3 0 49 67 n
  const moduleAt = small.indexOf(Buffer.from([0x1d, 0x28, 0x6b, 3, 0, 49, 67]));
  assert.equal(small[moduleAt + 7], 1);

  const large = new EscPosDriver().printQrCode("D", 99).build();
  const largeAt = large.indexOf(Buffer.from([0x1d, 0x28, 0x6b, 3, 0, 49, 67]));
  assert.equal(large[largeAt + 7], 16);

  const long = new EscPosDriver().printQrCode("x".repeat(300)).build();
  // Store-data chunk carries pL = 303 & 0xff and pH = 303 >> 8.
  assert.notEqual(long.indexOf(Buffer.from([0x1d, 0x28, 0x6b, 47, 1, 49, 80, 48])), -1);
});

test("EscPosDriver printImage frames a non-byte-aligned width", () => {
  const image: GrayImage = { width: 9, height: 2, pixels: new Uint8Array(18) };
  const bytes = new EscPosDriver().printImage(image, "THRESHOLD").build();
  const header = Buffer.from([0x1d, 0x76, 0x30, 0x00, 0x02, 0x00, 0x02, 0x00]);
  assert.notEqual(bytes.indexOf(header), -1);
});

test("EscPosDriver drawer pulse and cut commands", () => {
  assert.deepEqual([...new EscPosDriver().openCashDrawer().build().subarray(-5)], [
    0x1b, 0x70, 0, 25, 250,
  ]);
  const partial = new EscPosDriver().cutPaper(true).build();
  assert.deepEqual([...partial.subarray(-7, -4)], [0x1b, 0x64, 3]); // feed(3)
  assert.deepEqual([...partial.subarray(-4)], [0x1d, 0x56, 66, 0]);
  const full = new EscPosDriver().cutPaper(false).build();
  assert.deepEqual([...full.subarray(-4)], [0x1d, 0x56, 65, 0]);
});

// ---------------------------------------------------------------------------
// PCL
// ---------------------------------------------------------------------------

test("PclDriver constructor resets the printer", () => {
  const bytes = new PclDriver().build();
  assert.deepEqual([...bytes], [0x1b, 0x45]);
});

test("PclDriver selectFont clamps the point size and keeps the typeface", () => {
  assert.ok(new PclDriver().selectFont(2).build().toString("ascii").includes("(s0p4h0s0b4099T"));
  assert.ok(new PclDriver().selectFont(99).build().toString("ascii").includes("(s0p72h0s0b4099T"));
  assert.ok(new PclDriver().selectFont(12, 4102).build().toString("ascii").includes("(s0p12h0s0b4102T"));
});

test("PclDriver text and positioning commands", () => {
  const text = new PclDriver()
    .printLine("hi")
    .setOrientation(true)
    .setOrientation(false)
    .setCursorPosition(10, 20)
    .build()
    .toString("ascii");
  assert.ok(text.includes("hi\r\n"));
  assert.ok(text.includes("&l1O"));
  assert.ok(text.includes("&l0O"));
  assert.ok(text.includes("*p10x20Y"));
});

test("PclDriver printBitmap frames each row and ends the raster", () => {
  const image: GrayImage = { width: 9, height: 2, pixels: new Uint8Array(18) };
  const text = new PclDriver(150).printBitmap(image, "THRESHOLD").build().toString("ascii");
  assert.ok(text.includes("*t150R"));
  assert.ok(text.includes("*r9S"));
  assert.ok(text.includes("*b0M"));
  assert.ok(text.includes("*r1A"));
  assert.equal(text.split("*b2W").length - 1, 2); // one width command per row
  assert.ok(text.includes("*rB"));
});

test("PclDriver formFeed and reset emit the expected control bytes", () => {
  const bytes = new PclDriver().formFeed().reset().build();
  assert.deepEqual([...bytes], [0x1b, 0x45, 0x0c, 0x1b, 0x45]);
});

test("PclDriver fromBitmap honours the requested resolution", () => {
  const bytes = PclDriver.fromBitmap(byteRow(8, 255), 150, "THRESHOLD");
  assert.ok(bytes.toString("ascii").includes("*t150R"));
  assert.equal(bytes[0], 0x1b);
  assert.equal(bytes[1], 0x45);
});
