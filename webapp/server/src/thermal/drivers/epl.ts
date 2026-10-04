import { rasterize, type DitherMode, type GrayImage } from "../raster.js";

/**
 * EPL2 generator, ported from the Android app's `EplDriver`.
 *
 * Important EPL2 bit-polarity convention: in the GW command 0 = black/burn and
 * 1 = white/skip, the inverse of ZPL and ESC/POS. `drawBitmap` defaults
 * `invertPolarity` to true to compensate.
 */

function clamp(value: number, min: number, max: number): number {
  return value < min ? min : value > max ? max : value;
}

/** Local-time timestamp matching Kotlin's `yyyy-MM-dd HH:mm:ss`. */
function timestamp(): string {
  const d = new Date();
  const p = (n: number): string => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(
    d.getMinutes()
  )}:${p(d.getSeconds())}`;
}

export class EplDriver {
  private chunks: Buffer[] = [];

  constructor(
    readonly widthDots = 812,
    readonly lengthDots = 1218,
    readonly gapDots = 24
  ) {
    // N = Clear image buffer
    this.writeString("N\n");
    // q = Set label width
    this.writeString(`q${widthDots}\n`);
    // Q = Set label length with gap
    this.writeString(`Q${lengthDots},${gapDots}\n`);
  }

  private writeString(s: string): void {
    this.chunks.push(Buffer.from(s, "ascii"));
  }

  /** Set print density, 0 (lightest) to 15 (darkest). */
  setDensity(density: number): this {
    this.writeString(`D${clamp(density, 0, 15)}\n`);
    return this;
  }

  /** Set print speed in inches per second, 1 to 5. */
  setSpeed(speedIps: number): this {
    this.writeString(`S${clamp(speedIps, 1, 5)}\n`);
    return this;
  }

  /** Draw ASCII text (A command). */
  drawText(
    x: number,
    y: number,
    text: string,
    font = 3,
    hMult = 1,
    vMult = 1,
    reverse = false,
    rotation = 0
  ): this {
    const revChar = reverse ? "R" : "N";
    const escaped = text.replace(/"/g, '\\"');
    this.writeString(
      `A${x},${y},${rotation},${clamp(font, 1, 5)},${clamp(hMult, 1, 8)},${clamp(
        vMult,
        1,
        8
      )},${revChar},"${escaped}"\n`
    );
    return this;
  }

  /** Draw a line or solid rectangle (LO command). */
  drawLine(x: number, y: number, length: number, thickness: number): this {
    this.writeString(`LO${x},${y},${length},${thickness}\n`);
    return this;
  }

  /** Draw a box border (X command). */
  drawBox(x: number, y: number, width: number, height: number, thickness = 2): this {
    const endX = x + width;
    const endY = y + height;
    this.writeString(`X${x},${y},${thickness},${endX},${endY}\n`);
    return this;
  }

  /** Draw a Code 128 1D barcode (B command). */
  drawBarcode128(
    x: number,
    y: number,
    data: string,
    height = 80,
    narrowBar = 2,
    wideBar = 4,
    showText = true,
    rotation = 0
  ): this {
    const hrChar = showText ? "B" : "N";
    this.writeString(
      `B${x},${y},${rotation},1,${narrowBar},${wideBar},${height},${hrChar},"${data}"\n`
    );
    return this;
  }

  /** Draw a 2D QR code (b command). */
  drawQrCode(
    x: number,
    y: number,
    data: string,
    magnification = 5,
    errorCorrection = "M"
  ): this {
    const ec = ((): number => {
      switch (errorCorrection.toUpperCase()) {
        case "L":
          return 0;
        case "M":
          return 1;
        case "Q":
          return 2;
        case "H":
          return 3;
        default:
          return 1;
      }
    })();
    this.writeString(`b${x},${y},Q,m${clamp(magnification, 1, 10)},s${ec},"${data}"\n`);
    return this;
  }

  /** Draw a monochrome graphic image (GW command). */
  drawBitmap(
    x: number,
    y: number,
    image: GrayImage,
    ditherMode: DitherMode = "FLOYD_STEINBERG",
    invertPolarity = true
  ): this {
    const rowBytes = (image.width + 7) >> 3;
    const monoBytes = rasterize(image, ditherMode, invertPolarity);

    this.writeString(`GW${x},${y},${rowBytes},${image.height}\n`);
    this.chunks.push(Buffer.from(monoBytes));
    this.chunks.push(Buffer.from([0x0a])); // LF
    return this;
  }

  /** Issue the print command (P{copies}). */
  build(copies = 1): Buffer {
    const result = [...this.chunks];
    result.push(Buffer.from(`P${Math.max(copies, 1)}\n`, "ascii"));
    return Buffer.concat(result);
  }

  /** Generate a comprehensive EPL2 test label. */
  static generateTestLabel(printerName = "EPL2 Thermal Printer", transport = "USB Direct"): Buffer {
    const dateStr = timestamp();

    const driver = new EplDriver(812, 1218).setDensity(10).setSpeed(4);

    // Outer border
    driver.drawBox(20, 20, 772, 1178, 4);

    // Header
    driver.drawText(60, 50, "CUPPA PRINT SERVER", 4, 2, 2);
    driver.drawText(60, 120, "Android Local CUPS & Thermal Subsystem", 2);
    driver.drawLine(40, 160, 732, 3);

    // Info rows
    driver.drawText(50, 190, `TARGET PRINTER: ${printerName}`, 3);
    driver.drawText(50, 240, "PROTOCOL:       EPL2 Direct Thermal (203 DPI)", 3);
    driver.drawText(50, 290, `TRANSPORT:      ${transport}`, 3);
    driver.drawText(50, 340, `TIMESTAMP:      ${dateStr}`, 2);
    driver.drawLine(40, 390, 732, 2);

    // Code 128
    driver.drawText(50, 420, "CODE 128 TEST BARCODE:", 2);
    driver.drawBarcode128(90, 460, "CUPPA-EPL-2026", 90, 3, 6);
    driver.drawLine(40, 610, 732, 2);

    // QR Code
    driver.drawText(50, 640, "2D QR CODE (EPL DIAGNOSTIC):", 2);
    driver.drawQrCode(70, 690, `cuppa://test/epl?printer=${printerName}&time=${dateStr}`, 6);

    // Alignment pattern
    driver.drawBox(400, 690, 350, 200, 2);
    driver.drawText(420, 710, "EPL2 ALIGNMENT TEST", 2);
    driver.drawLine(420, 745, 310, 1);
    driver.drawLine(420, 775, 310, 2);
    driver.drawLine(420, 805, 310, 4);
    driver.drawText(420, 845, "203 DPI - 8 DOTS/MM", 2);

    // Footer
    driver.drawLine(40, 960, 732, 3);
    driver.drawText(100, 1000, "PASS - HARDWARE PRINT ENGINE VERIFIED", 3, 1, 2);
    driver.drawText(180, 1060, "Cuppa Native Thermal Subsystem", 2);

    return driver.build(1);
  }
}
