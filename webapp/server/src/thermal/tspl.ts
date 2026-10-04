import { centerOnHead, rasterize, type DitherMode, type GrayImage } from "./raster.js";

/**
 * TSPL (TSC Programming Language) generator, ported from the Android app's
 * `TsplDriver`. Calibrated against the Rollo X1038.
 */

/** `~@` reset followed by CRLF. The Rollo accepts a whole TSPL stream without
 * this preamble but never fires the print head. */
const RESET_PREAMBLE = Buffer.from([0x7e, 0x40, 0x0d, 0x0a]);

/** The X1038 head is 832 dots wide; a 4" label at 203 DPI is 812. */
export const ROLLO_HEAD_DOTS = 832;
export const ROLLO_WIDTH_MM = 101.6;
export const ROLLO_HEIGHT_MM = 152.4;

function clamp(value: number, min: number, max: number): number {
  return value < min ? min : value > max ? max : value;
}

export interface TsplOptions {
  /** 0 (lightest) .. 15 (darkest). */
  density?: number;
  /** 2 .. 6 inches per second. */
  speed?: number;
  gapMm?: number;
  ditherMode?: DitherMode;
  /** Final polarity handed to the rasterizer (see the dispatcher's `true xor`). */
  invertPolarity?: boolean;
}

/** Encodes a grayscale page as a complete TSPL job. */
export function tsplFromImage(image: GrayImage, options: TsplOptions = {}): Buffer {
  const density = clamp(Math.trunc(options.density ?? 8), 0, 15);
  const speed = clamp(Math.trunc(options.speed ?? 5), 2, 6);
  const gap = Math.trunc(options.gapMm ?? 3);
  const dither = options.ditherMode ?? "FLOYD_STEINBERG";
  const invert = options.invertPolarity ?? false;

  const centered = centerOnHead(image, ROLLO_HEAD_DOTS);
  const rowBytes = (centered.width + 7) >> 3;
  const widthMm = rowBytes; // 8 dots = 1 mm at 203 DPI
  const heightMm = (centered.height + 7) >> 3;
  const raster = rasterize(centered, dither, invert);

  const header =
    [
      `SIZE ${widthMm} mm ,${heightMm} mm`,
      "REFERENCE 0,0",
      "DIRECTION 0,0",
      `GAP ${gap} mm,0 mm`,
      "OFFSET 0 mm",
      `DENSITY ${density}`,
      `SPEED ${speed}`,
      "SETC AUTODOTTED OFF",
      "SETC PAUSEKEY ON",
      "SETC WATERMARK OFF",
      "CLS",
      `BITMAP 0,0,${rowBytes},${centered.height},1,`,
    ].join("\n") + "\n";

  return Buffer.concat([
    RESET_PREAMBLE,
    Buffer.from(header, "ascii"),
    Buffer.from(raster),
    Buffer.from("\nPRINT 1,1\n", "ascii"),
  ]);
}

/** String builder used for the text/barcode test label (no binary bitmap). */
export class TsplBuilder {
  private commands = "";

  constructor(widthMm = ROLLO_WIDTH_MM, heightMm = ROLLO_HEIGHT_MM) {
    const w = Math.trunc(widthMm);
    const h = Math.trunc(heightMm);
    this.commands += `SIZE ${w} mm ,${h} mm\n`;
    this.commands += "REFERENCE 0,0\n";
    this.commands += "DIRECTION 0,0\n";
    this.commands += "GAP 3 mm,0 mm\n";
    this.commands += "OFFSET 0 mm\n";
  }

  setDensity(density: number): this {
    this.commands += `DENSITY ${clamp(Math.trunc(density), 0, 15)}\n`;
    return this;
  }

  setSpeed(speed: number): this {
    this.commands += `SPEED ${clamp(Math.trunc(speed), 2, 6)}\n`;
    return this;
  }

  setGap(gapMm = 3, offsetMm = 0): this {
    this.commands += `GAP ${Math.trunc(gapMm)} mm,${Math.trunc(offsetMm)} mm\n`;
    return this;
  }

  setDirection(direction: number): this {
    this.commands += `DIRECTION ${direction},0\n`;
    return this;
  }

  setReference(x: number, y: number): this {
    this.commands += `REFERENCE ${x},${y}\n`;
    return this;
  }

  clearBuffer(): this {
    this.commands += "SETC AUTODOTTED OFF\n";
    this.commands += "SETC PAUSEKEY ON\n";
    this.commands += "SETC WATERMARK OFF\n";
    this.commands += "CLS\n";
    return this;
  }

  drawText(x: number, y: number, text: string, font = "3", rotation = 0, xMulti = 1, yMulti = 1): this {
    const escaped = text.replace(/"/g, "");
    this.commands += `TEXT ${x},${y},"${font}",${rotation},${xMulti},${yMulti},"${escaped}"\n`;
    return this;
  }

  drawBar(x: number, y: number, width: number, height: number): this {
    this.commands += `BAR ${x},${y},${width},${height}\n`;
    return this;
  }

  drawBox(x: number, y: number, endX: number, endY: number, thickness = 2): this {
    this.commands += `BOX ${x},${y},${endX},${endY},${thickness}\n`;
    return this;
  }

  drawHorizontalLine(x: number, y: number, length: number, thickness = 2): this {
    return this.drawBar(x, y, length, thickness);
  }

  drawBarcode128(x: number, y: number, text: string, height = 80, humanReadable = 1, rotation = 0, narrow = 2, wide = 4): this {
    const escaped = text.replace(/"/g, "");
    this.commands += `BARCODE ${x},${y},"128",${height},${humanReadable},${rotation},${narrow},${wide},"${escaped}"\n`;
    return this;
  }

  drawQrCode(x: number, y: number, text: string, eccLevel = "M", cellWidth = 6, mode = "A", rotation = 0): this {
    const escaped = text.replace(/"/g, "");
    this.commands += `QRCODE ${x},${y},${eccLevel},${cellWidth},${mode},${rotation},"${escaped}"\n`;
    return this;
  }

  print(copies = 1): this {
    this.commands += `PRINT ${copies},1\n`;
    return this;
  }

  buildString(): string {
    return this.commands;
  }

  build(): Buffer {
    return Buffer.concat([RESET_PREAMBLE, Buffer.from(this.commands, "ascii")]);
  }
}

/** A full 4x6 diagnostic label, matching the Android generator. */
export function generateTsplTestLabel(
  printerName = "Rollo X1038",
  cupsVersion = "CUPS v2.2.9",
  transport = "USB Direct"
): Buffer {
  const dateStr = new Date().toISOString().slice(0, 19).replace("T", " ");
  const driver = new TsplBuilder()
    .setDensity(8)
    .setSpeed(5)
    .setGap(3, 0)
    .setDirection(0)
    .setReference(0, 0)
    .clearBuffer();

  driver.drawBox(20, 20, 792, 1198, 4);
  driver.drawBar(24, 24, 768, 110);
  driver.drawText(50, 45, "CUPPA PRINT SERVER", "4");
  driver.drawText(50, 95, "Android Local CUPS & Thermal Subsystem", "2");
  driver.drawHorizontalLine(40, 160, 732, 3);
  driver.drawText(50, 185, `TARGET PRINTER:  ${printerName}`, "3");
  driver.drawText(50, 230, "DRIVER ENGINE:   TSPL / Rollo X1038 Native", "3");
  driver.drawText(50, 275, `TRANSPORT:       ${transport}`, "3");
  driver.drawText(50, 320, `CUPS CORE:       ${cupsVersion}`, "3");
  driver.drawText(50, 365, `TIMESTAMP:       ${dateStr}`, "3");
  driver.drawHorizontalLine(40, 420, 732, 2);
  driver.drawText(50, 445, "CODE 128 TEST BARCODE:", "2");
  driver.drawBarcode128(70, 485, "CUPPA-ROLLO-X1038", 90, 1, 0, 3, 6);
  driver.drawHorizontalLine(40, 640, 732, 2);
  driver.drawText(50, 665, "2D QR CODE (DEVICE VERIFICATION):", "2");
  driver.drawQrCode(70, 715, `cuppa://test?printer=${printerName}&proto=tspl&time=${dateStr}`, "M", 7);
  driver.drawBox(400, 715, 750, 920, 2);
  driver.drawText(420, 735, "DENSITY & ALIGNMENT", "2");
  driver.drawHorizontalLine(420, 770, 310, 1);
  driver.drawHorizontalLine(420, 795, 310, 2);
  driver.drawHorizontalLine(420, 820, 310, 4);
  driver.drawHorizontalLine(420, 850, 310, 8);
  driver.drawText(420, 885, "203 DPI - 8 DOTS/MM", "2");
  driver.drawHorizontalLine(40, 970, 732, 3);
  driver.drawText(100, 1010, "PASS - ROLLO X1038 PRINT ENGINE VERIFIED", "3");
  driver.drawText(180, 1060, "Cuppa Native Thermal Subsystem (TSPL)", "2");
  driver.print(1);

  return driver.build();
}
