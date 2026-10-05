import assert from "node:assert/strict";
import { test } from "node:test";
import type { GrayImage } from "../src/thermal/raster.js";
import { ZplDriver } from "../src/thermal/drivers/zpl.js";
import { EplDriver } from "../src/thermal/drivers/epl.js";
import { EscPosDriver, Alignment } from "../src/thermal/drivers/escpos.js";
import { PclDriver } from "../src/thermal/drivers/pcl.js";
import { TsplBuilder, generateTsplTestLabel } from "../src/thermal/tspl.js";

/** Small synthetic checkerboard used to exercise the bitmap code paths. */
function checkerboard(width = 64, height = 32): GrayImage {
  const pixels = new Uint8Array(width * height);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      pixels[y * width + x] = ((x >> 3) + (y >> 3)) % 2 === 0 ? 0 : 255;
    }
  }
  return { width, height, pixels };
}

test("ZplDriver basic label", () => {
  const driver = new ZplDriver(812, 1218)
    .setDarkness(20)
    .setPrintSpeed(4)
    .drawText(50, 50, "HELLO CUPPA")
    .drawBox(10, 10, 800, 1200, 2);

  const zpl = driver.buildString();
  assert.ok(zpl.startsWith("^XA\n"));
  assert.ok(zpl.includes("^PW812\n"));
  assert.ok(zpl.includes("^LL1218\n"));
  assert.ok(zpl.includes("~SD20\n"));
  assert.ok(zpl.includes("^PR4,4\n"));
  assert.ok(zpl.includes("^FO50,50^A0N,30,30^FDHELLO CUPPA^FS\n"));
  assert.ok(zpl.includes("^FO10,10^GB800,1200,2^FS\n"));
  assert.ok(zpl.endsWith("^XZ\n"));
});

test("ZplDriver barcode and QR", () => {
  const driver = new ZplDriver()
    .drawBarcode128(100, 100, "TEST12345", 80, 3)
    .drawQrCode(100, 300, "https://cuppa.local", 8, "H");

  const zpl = driver.buildString();
  assert.ok(zpl.includes("^BY3,3,80"));
  assert.ok(zpl.includes("^BCN,80,Y,N,N^FDTEST12345^FS"));
  assert.ok(zpl.includes("^BQN,2,8,H^FDQA,https://cuppa.local^FS"));
});

test("ZplDriver generateTestLabel", () => {
  const bytes = ZplDriver.generateTestLabel("Rollo X1038", "CUPS 2.2.9", "USB Direct");
  assert.ok(bytes.length > 0);

  const content = bytes.toString("ascii");
  assert.ok(content.startsWith("^XA\n"));
  assert.ok(content.includes("CUPPA PRINT SERVER"));
  assert.ok(content.includes("Rollo X1038"));
  assert.ok(content.includes("CUPPA-TEST-2026"));
  assert.ok(content.includes("PASS - HARDWARE PRINT ENGINE VERIFIED"));
  assert.ok(content.endsWith("^XZ\n"));
});

test("ZplDriver drawBitmap emits ^GFA framing and hex data", () => {
  const image = checkerboard();
  const zpl = new ZplDriver().drawBitmap(0, 0, image).buildString();

  assert.ok(zpl.includes("^GFA,256,256,8,"));
  assert.match(zpl, /\^GFA,256,256,8,[0-9A-F]{512}\^FS\n/);
  assert.ok(zpl.endsWith("^XZ\n"));
});

test("EscPosDriver initialization and formatting", () => {
  const driver = new EscPosDriver(48)
    .setAlignment(Alignment.CENTER)
    .setBold(true)
    .printLine("TEST RECEIPT")
    .setBold(false)
    .setAlignment(Alignment.LEFT)
    .printTwoColumnLine("Item 1", "$10.00")
    .printBarcode128("12345678")
    .cutPaper(true);

  const bytes = driver.build();
  assert.ok(bytes.length > 20);

  // Initializer ESC @ (0x1B, 0x40)
  assert.equal(bytes[0], 0x1b);
  assert.equal(bytes[1], 0x40);

  const str = bytes.toString("ascii");
  assert.ok(str.includes("TEST RECEIPT"));
  assert.ok(str.includes("Item 1"));
  assert.ok(str.includes("$10.00"));
  assert.ok(str.includes("12345678"));

  // Auto cut at end: GS V 66 0 (0x1D, 0x56, 0x42, 0x00)
  const lastBytes = bytes.subarray(bytes.length - 4);
  assert.equal(lastBytes[0], 0x1d);
  assert.equal(lastBytes[1], 0x56);
  assert.equal(lastBytes[2], 66);
  assert.equal(lastBytes[3], 0);
});

test("EscPosDriver generateTestReceipt", () => {
  const bytes = EscPosDriver.generateTestReceipt("Epson TM-T88VI", 48);
  assert.ok(bytes.length > 0);

  const text = bytes.toString("ascii");
  assert.ok(text.includes("CUPPA PRINT SERVER"));
  assert.ok(text.includes("Epson TM-T88VI"));
  assert.ok(text.includes("PRINTER DIAGNOSTICS"));
  assert.ok(text.includes("TOTAL:"));
  assert.ok(text.includes("*** TEST PRINT SUCCESSFUL ***"));
});

test("EscPosDriver printImage emits GS v 0 framing", () => {
  const image = checkerboard();
  const bytes = new EscPosDriver().printImage(image).build();

  assert.equal(bytes[0], 0x1b);
  assert.equal(bytes[1], 0x40);

  // GS v 0 0 xL xH yL yH, with rowBytes = 8 and height = 32.
  const header = Buffer.from([0x1d, 0x76, 0x30, 0x00, 0x08, 0x00, 0x20, 0x00]);
  assert.ok(bytes.indexOf(header) !== -1);
  assert.ok(bytes.length > 8 + 8 * 32);
});

test("EplDriver basic commands", () => {
  const driver = new EplDriver(812, 1218)
    .setDensity(12)
    .setSpeed(3)
    .drawText(50, 50, "EPL TEST", 3)
    .drawLine(10, 10, 500, 2)
    .drawBox(20, 20, 200, 100, 2);

  const bytes = driver.build(1);
  const text = bytes.toString("ascii");

  assert.ok(text.startsWith("N\nq812\nQ1218,24\n"));
  assert.ok(text.includes("D12\n"));
  assert.ok(text.includes("S3\n"));
  assert.ok(text.includes('A50,50,0,3,1,1,N,"EPL TEST"\n'));
  assert.ok(text.includes("LO10,10,500,2\n"));
  assert.ok(text.includes("X20,20,2,220,120\n"));
  assert.ok(text.endsWith("P1\n"));
});

test("EplDriver barcode and QR", () => {
  const driver = new EplDriver()
    .drawBarcode128(50, 100, "EPL128TEST", 60)
    .drawQrCode(50, 250, "cuppa://verify", 5, "M");

  const bytes = driver.build(1);
  const text = bytes.toString("ascii");

  assert.ok(text.includes('B50,100,0,1,2,4,60,B,"EPL128TEST"\n'));
  assert.ok(text.includes('b50,250,Q,m5,s1,"cuppa://verify"\n'));
});

test("EplDriver generateTestLabel", () => {
  const bytes = EplDriver.generateTestLabel("Zebra LP 2844", "USB Direct");
  assert.ok(bytes.length > 0);

  const text = bytes.toString("ascii");
  assert.ok(text.startsWith("N\n"));
  assert.ok(text.includes("CUPPA PRINT SERVER"));
  assert.ok(text.includes("Zebra LP 2844"));
  assert.ok(text.includes("CUPPA-EPL-2026"));
  assert.ok(text.includes("PASS - HARDWARE PRINT ENGINE VERIFIED"));
  assert.ok(text.endsWith("P1\n"));
});

test("EplDriver drawBitmap emits GW framing and raster data", () => {
  const image = checkerboard();
  const bytes = new EplDriver().drawBitmap(0, 0, image).build(1);
  const text = bytes.toString("ascii");

  assert.ok(text.includes("GW0,0,8,32\n"));
  assert.ok(text.endsWith("P1\n"));
  // GW header (12 bytes) + one row byte per row (32) + LF.
  assert.ok(bytes.length > 12 + 32);
});

test("PclDriver escape sequence structure", () => {
  const driver = new PclDriver(300)
    .setOrientation(false)
    .setCursorPosition(0, 0)
    .formFeed()
    .reset();

  const bytes = driver.build();
  const text = bytes.toString("ascii");

  // Reset (Esc E) is emitted on construction.
  assert.equal(bytes[0], 0x1b);
  assert.equal(bytes[1], "E".charCodeAt(0));

  assert.ok(text.includes("&l0O")); // portrait orientation
  assert.ok(text.includes("*p0x0Y")); // cursor position
  assert.ok(text.includes("\f")); // form feed
  // Ends with a second reset (Esc E) after the form feed.
  assert.ok(text.endsWith("E"));
});

test("PclDriver raster command framing is deterministic", () => {
  const first = new PclDriver(300).setCursorPosition(10, 20).build();
  const second = new PclDriver(300).setCursorPosition(10, 20).build();
  assert.equal(first.toString("ascii"), second.toString("ascii"));
});

test("PclDriver fromBitmap emits raster framing and resets", () => {
  const image = checkerboard();
  const bytes = PclDriver.fromBitmap(image);
  const text = bytes.toString("ascii");

  assert.equal(bytes[0], 0x1b);
  assert.equal(bytes[1], "E".charCodeAt(0));
  assert.ok(text.includes("*t300R"));
  assert.ok(text.includes("*r64S"));
  assert.ok(text.includes("*b0M"));
  assert.ok(text.includes("*r1A"));
  assert.ok(text.includes("*b8W"));
  assert.ok(text.includes("*rB"));
  assert.ok(text.includes("\f"));
  // Ends with a reset (Esc E).
  assert.equal(bytes[bytes.length - 2], 0x1b);
  assert.equal(bytes[bytes.length - 1], "E".charCodeAt(0));
});

test("PclDriver generateTestLabel renders a real text page", () => {
  const bytes = PclDriver.generateTestLabel("HP LaserJet", "Cuppa Web");
  assert.ok(bytes.length > 0);

  const text = bytes.toString("ascii");
  assert.ok(text.includes("CUPPA PRINT SERVER"));
  assert.ok(text.includes("HP LaserJet"));
  assert.ok(text.includes("PCL 5 raster"));
  assert.ok(text.includes("Cuppa Web"));
  // Selects the built-in Courier typeface and ejects a page.
  assert.ok(text.includes("b4099T"));
  assert.ok(text.includes("\f"));
  assert.equal(bytes[0], 0x1b);
  assert.equal(bytes[1], "E".charCodeAt(0));
});

test("TsplDriver basic commands", () => {
  const driver = new TsplBuilder(101.6, 152.4)
    .setDensity(8)
    .setSpeed(5)
    .setGap(3.0, 0.0)
    .setDirection(0)
    .setReference(0, 0)
    .clearBuffer()
    .drawText(50, 50, "ROLLO TEST", "3")
    .drawBox(20, 20, 792, 1198, 4)
    .drawBar(24, 24, 768, 110)
    .drawBarcode128(70, 485, "CUPPA-ROLLO-X1038", 90)
    .drawQrCode(70, 715, "cuppa://test?rollo", "M", 7)
    .print(1);

  const bytes = driver.build();
  const text = bytes.toString("ascii");

  assert.ok(text.includes("SIZE 101 mm ,152 mm\n"));
  assert.ok(text.includes("REFERENCE 0,0\n"));
  assert.ok(text.includes("DIRECTION 0,0\n"));
  assert.ok(text.includes("GAP 3 mm,0 mm\n"));
  assert.ok(text.includes("DENSITY 8\n"));
  assert.ok(text.includes("SPEED 5\n"));
  assert.ok(text.includes("SETC AUTODOTTED OFF\n"));
  assert.ok(text.includes("SETC PAUSEKEY ON\n"));
  assert.ok(text.includes("SETC WATERMARK OFF\n"));
  assert.ok(text.includes("CLS\n"));
  assert.ok(text.includes('TEXT 50,50,"3",0,1,1,"ROLLO TEST"\n'));
  assert.ok(text.includes("BOX 20,20,792,1198,4\n"));
  assert.ok(text.includes("BAR 24,24,768,110\n"));
  assert.ok(text.includes('BARCODE 70,485,"128",90,1,0,2,4,"CUPPA-ROLLO-X1038"\n'));
  assert.ok(text.includes('QRCODE 70,715,M,7,A,0,"cuppa://test?rollo"\n'));
  assert.ok(text.endsWith("PRINT 1,1\n"));
});

test("TsplDriver generateTestLabel", () => {
  const bytes = generateTsplTestLabel("Rollo X1038", "CUPS v2.2.9", "USB Direct");
  assert.ok(bytes.length > 0);

  const text = bytes.toString("ascii");
  assert.ok(text.includes("SIZE 101 mm ,152 mm\n"));
  assert.ok(text.includes("CUPPA PRINT SERVER"));
  assert.ok(text.includes("Rollo X1038"));
  assert.ok(text.includes("TSPL / Rollo X1038 Native"));
  assert.ok(text.includes("CUPPA-ROLLO-X1038"));
  assert.ok(text.includes("PASS - ROLLO X1038 PRINT ENGINE VERIFIED"));
  assert.ok(text.endsWith("PRINT 1,1\n"));
});
