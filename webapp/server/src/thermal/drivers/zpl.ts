import { rasterize, type DitherMode, type GrayImage } from "../raster.js";

/**
 * ZPL II generator, ported from the Android app's `ZplDriver`.
 *
 * Tailored for direct-thermal label printers including the Rollo X1038 and
 * Zebra ZD420/GK420d at 203 DPI.
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

/** Uppercase hex of a packed 1-bit raster, for ZPL ^GFA. */
function rasterizeToHex(image: GrayImage, mode: DitherMode, invertPolarity: boolean): string {
  const bytes = rasterize(image, mode, invertPolarity);
  let hex = "";
  for (const byte of bytes) hex += byte.toString(16).padStart(2, "0").toUpperCase();
  return hex;
}

export class ZplDriver {
  private commands = "";

  constructor(
    readonly widthDots = 812,
    readonly lengthDots = 1218,
    readonly dpi = 203
  ) {
    // Start Format
    this.commands += "^XA\n";
    // Print Width & Label Length
    this.commands += `^PW${widthDots}\n`;
    this.commands += `^LL${lengthDots}\n`;
    // Label Home (0, 0)
    this.commands += "^LH0,0\n";
  }

  /** Set print darkness / density, 0 (lightest) to 30 (darkest). */
  setDarkness(darkness: number): this {
    const clamped = clamp(darkness, 0, 30);
    this.commands += `~SD${String(clamped).padStart(2, "0")}\n`;
    return this;
  }

  /** Set print speed in inches per second (IPS), 2 to 6. */
  setPrintSpeed(ips: number): this {
    const clamped = clamp(ips, 2, 6);
    this.commands += `^PR${clamped},${clamped}\n`;
    return this;
  }

  /** Draw scalable smooth-font text. */
  drawText(
    x: number,
    y: number,
    text: string,
    fontHeight = 30,
    fontWidth = fontHeight,
    font = "0"
  ): this {
    this.commands += `^FO${x},${y}^A${font}N,${fontHeight},${fontWidth}^FD${escapeText(
      text
    )}^FS\n`;
    return this;
  }

  /** Draw a graphic box / rectangle. */
  drawBox(
    x: number,
    y: number,
    width: number,
    height: number,
    thickness = 2,
    cornerRadius = 0
  ): this {
    this.commands += `^FO${x},${y}^GB${width},${height},${thickness}`;
    if (cornerRadius > 0) {
      this.commands += `,B,${clamp(cornerRadius, 0, 8)}`;
    }
    this.commands += "^FS\n";
    return this;
  }

  /** Draw a horizontal line. */
  drawHorizontalLine(x: number, y: number, length: number, thickness = 2): this {
    return this.drawBox(x, y, length, thickness, thickness);
  }

  /** Draw a vertical line. */
  drawVerticalLine(x: number, y: number, length: number, thickness = 2): this {
    return this.drawBox(x, y, thickness, length, thickness);
  }

  /** Draw a Code 128 1D barcode. */
  drawBarcode128(
    x: number,
    y: number,
    data: string,
    height = 80,
    moduleWidth = 2,
    showText = true
  ): this {
    this.commands += `^FO${x},${y}^BY${moduleWidth},3,${height}^BCN,${height},${
      showText ? "Y" : "N"
    },N,N^FD${data}^FS\n`;
    return this;
  }

  /** Draw a 2D QR code. */
  drawQrCode(
    x: number,
    y: number,
    data: string,
    magnification = 6,
    errorCorrection = "M"
  ): this {
    this.commands += `^FO${x},${y}^BQN,2,${clamp(magnification, 1, 10)},${errorCorrection}^FDQA,${data}^FS\n`;
    return this;
  }

  /** Draw a monochrome image using ZPL ^GFA (Graphic Field ASCII). */
  drawBitmap(
    x: number,
    y: number,
    image: GrayImage,
    ditherMode: DitherMode = "FLOYD_STEINBERG",
    invertPolarity = false
  ): this {
    const rowBytes = (image.width + 7) >> 3;
    const totalBytes = rowBytes * image.height;
    const hex = rasterizeToHex(image, ditherMode, invertPolarity);

    this.commands += `^FO${x},${y}^GFA,${totalBytes},${totalBytes},${rowBytes},${hex}^FS\n`;
    return this;
  }

  /** Build the raw ZPL command stream. */
  build(): Buffer {
    return Buffer.from(this.buildString(), "ascii");
  }

  /** Build as a plain ZPL string. */
  buildString(): string {
    return this.commands + "^XZ\n";
  }

  /** Generate a comprehensive 4" x 6" test label. */
  static generateTestLabel(
    printerName = "Rollo X1038",
    cupsVersion = "CUPS v2.2.9",
    transport = "USB Direct"
  ): Buffer {
    const dateStr = timestamp();

    const driver = new ZplDriver(812, 1218).setDarkness(18).setPrintSpeed(4);

    // Outer border box (4x6 boundary)
    driver.drawBox(20, 20, 772, 1178, 4);

    // Header Banner
    driver.drawBox(24, 24, 764, 110, 110);
    driver.drawText(60, 50, "CUPPA PRINT SERVER", 50, 45);
    driver.drawText(60, 105, "Android Local CUPS & Thermal Subsystem", 24, 22);

    // Divider
    driver.drawHorizontalLine(40, 160, 732, 3);

    // Printer & Diagnostics Section
    driver.drawText(50, 185, "TARGET PRINTER:", 26, 24);
    driver.drawText(270, 185, printerName, 28, 26);

    driver.drawText(50, 230, "DRIVER ENGINE:", 26, 24);
    driver.drawText(270, 230, "ZPL II Direct Thermal (203 DPI)", 26, 24);

    driver.drawText(50, 275, "TRANSPORT:", 26, 24);
    driver.drawText(270, 275, transport, 26, 24);

    driver.drawText(50, 320, "CUPS CORE:", 26, 24);
    driver.drawText(270, 320, cupsVersion, 26, 24);

    driver.drawText(50, 365, "TIMESTAMP:", 26, 24);
    driver.drawText(270, 365, dateStr, 24, 22);

    // Divider
    driver.drawHorizontalLine(40, 420, 732, 2);

    // 1D Barcode section
    driver.drawText(50, 445, "CODE 128 TEST BARCODE:", 24, 22);
    driver.drawBarcode128(90, 485, "CUPPA-TEST-2026", 90, 3);

    // Divider
    driver.drawHorizontalLine(40, 630, 732, 2);

    // 2D QR Code & Label Calibration Box
    driver.drawText(50, 660, "2D QR CODE (DEVICE VERIFICATION):", 24, 22);
    driver.drawQrCode(70, 710, `cuppa://test?printer=${printerName}&time=${dateStr}`, 7);

    // Calibration & Density Patterns on the right
    driver.drawBox(400, 710, 350, 200, 2);
    driver.drawText(420, 730, "DENSITY & ALIGNMENT", 22, 20);
    driver.drawHorizontalLine(420, 765, 310, 1);
    driver.drawHorizontalLine(420, 790, 310, 2);
    driver.drawHorizontalLine(420, 815, 310, 4);
    driver.drawHorizontalLine(420, 845, 310, 8);
    driver.drawText(420, 875, "203 DPI - 8 DOTS/MM", 20, 18);

    // Footer
    driver.drawHorizontalLine(40, 960, 732, 3);
    driver.drawText(120, 1000, "PASS - HARDWARE PRINT ENGINE VERIFIED", 32, 30);
    driver.drawText(200, 1050, "Cuppa Native Thermal Subsystem", 22, 20);

    return driver.build();
  }
}

/** Escape special ZPL control characters. */
function escapeText(text: string): string {
  return text.replace(/\^/g, "").replace(/~/g, "");
}
