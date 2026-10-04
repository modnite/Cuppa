import { rasterize, type DitherMode, type GrayImage } from "../raster.js";

/**
 * Epson ESC/POS receipt-protocol generator, ported from the Android app's
 * `EscPosDriver`. Supports text styling, alignment, Code 128, QR codes,
 * GS v 0 raster graphics, cash-drawer pulse and auto-cut.
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

export enum Alignment {
  LEFT = 0,
  CENTER = 1,
  RIGHT = 2,
}

export class EscPosDriver {
  private chunks: Buffer[] = [];

  constructor(readonly characterWidth = 48) {
    // Initialize printer (ESC @)
    this.chunks.push(Buffer.from([0x1b, 0x40]));
  }

  /** Set text horizontal alignment. */
  setAlignment(align: Alignment): this {
    this.chunks.push(Buffer.from([0x1b, 0x61, align]));
    return this;
  }

  /** Enable or disable bold emphasis. */
  setBold(enabled: boolean): this {
    this.chunks.push(Buffer.from([0x1b, 0x45, enabled ? 1 : 0]));
    return this;
  }

  /** Enable or disable underline. */
  setUnderline(enabled: boolean): this {
    this.chunks.push(Buffer.from([0x1b, 0x2d, enabled ? 1 : 0]));
    return this;
  }

  /** Set character size scaling, 1 to 8. */
  setTextSize(widthScale = 1, heightScale = 1): this {
    const w = clamp(widthScale - 1, 0, 7);
    const h = clamp(heightScale - 1, 0, 7);
    this.chunks.push(Buffer.from([0x1d, 0x21, (w << 4) | h]));
    return this;
  }

  /** Write raw text. */
  print(text: string): this {
    this.chunks.push(Buffer.from(text, "ascii"));
    return this;
  }

  /** Write a line of text followed by a line feed. */
  printLine(text = ""): this {
    this.print(text);
    this.chunks.push(Buffer.from([0x0a]));
    return this;
  }

  /** Print a two-column line (e.g. item on the left, price on the right). */
  printTwoColumnLine(left: string, right: string): this {
    const spaces = this.characterWidth - left.length - right.length;
    const line = spaces > 0 ? left + " ".repeat(spaces) + right : `${left} ${right}`;
    return this.printLine(line);
  }

  /** Print a divider line of repeating characters. */
  printDivider(char = "-"): this {
    return this.printLine(char.repeat(this.characterWidth));
  }

  /** Feed `lines` empty lines. */
  feed(lines = 1): this {
    this.chunks.push(Buffer.from([0x1b, 0x64, clamp(lines, 1, 255)]));
    return this;
  }

  /** Print a Code 128 barcode (GS k 73 len {data}). */
  printBarcode128(data: string, height = 64, widthMultiplier = 2): this {
    // Set barcode height (GS h n)
    this.chunks.push(Buffer.from([0x1d, 0x68, clamp(height, 1, 255)]));
    // Set barcode width (GS w n)
    this.chunks.push(Buffer.from([0x1d, 0x77, clamp(widthMultiplier, 2, 6)]));
    // Set HRI characters below barcode (GS H 2)
    this.chunks.push(Buffer.from([0x1d, 0x48, 2]));

    const dataBytes = Buffer.from(data, "ascii");
    // GS k 73 (Code 128)
    this.chunks.push(Buffer.from([0x1d, 0x6b, 73, dataBytes.length & 0xff]));
    this.chunks.push(dataBytes);
    return this;
  }

  /** Print a 2D QR code (GS ( k sequence). */
  printQrCode(data: string, moduleSize = 6): this {
    const dataBytes = Buffer.from(data, "ascii");
    const pL = (dataBytes.length + 3) & 0xff;
    const pH = ((dataBytes.length + 3) >> 8) & 0xff;

    // 1. Model: GS ( k 4 0 49 65 50 0 (Model 2)
    this.chunks.push(Buffer.from([0x1d, 0x28, 0x6b, 4, 0, 49, 65, 50, 0]));

    // 2. Module size: GS ( k 3 0 49 67 n
    this.chunks.push(Buffer.from([0x1d, 0x28, 0x6b, 3, 0, 49, 67, clamp(moduleSize, 1, 16)]));

    // 3. Error correction level M (15%): GS ( k 3 0 49 69 49
    this.chunks.push(Buffer.from([0x1d, 0x28, 0x6b, 3, 0, 49, 69, 49]));

    // 4. Store data: GS ( k pL pH 49 80 48 {data}
    this.chunks.push(Buffer.from([0x1d, 0x28, 0x6b, pL, pH, 49, 80, 48]));
    this.chunks.push(dataBytes);

    // 5. Print QR Code: GS ( k 3 0 49 81 48
    this.chunks.push(Buffer.from([0x1d, 0x28, 0x6b, 3, 0, 49, 81, 48]));
    return this;
  }

  /** Print a 1-bit raster bit image using GS v 0. */
  printImage(
    image: GrayImage,
    ditherMode: DitherMode = "FLOYD_STEINBERG",
    invertPolarity = false
  ): this {
    const rowBytes = (image.width + 7) >> 3;
    const monoBytes = rasterize(image, ditherMode, invertPolarity);

    const xL = rowBytes & 0xff;
    const xH = (rowBytes >> 8) & 0xff;
    const yL = image.height & 0xff;
    const yH = (image.height >> 8) & 0xff;

    // GS v 0 0 xL xH yL yH {data}
    this.chunks.push(Buffer.from([0x1d, 0x76, 0x30, 0, xL, xH, yL, yH]));
    this.chunks.push(Buffer.from(monoBytes));
    return this;
  }

  /** Pulse cash-drawer pin 2 (ESC p 0 25 250). */
  openCashDrawer(): this {
    this.chunks.push(Buffer.from([0x1b, 0x70, 0, 25, 250]));
    return this;
  }

  /** Feed paper and perform a full or partial cut. */
  cutPaper(partial = true): this {
    this.feed(3);
    // GS V m: 65 = full cut, 66 = partial cut
    const m = partial ? 66 : 65;
    this.chunks.push(Buffer.from([0x1d, 0x56, m, 0]));
    return this;
  }

  /** Build the raw ESC/POS byte sequence. */
  build(): Buffer {
    return Buffer.concat(this.chunks);
  }

  /** Generate a standard diagnostic test receipt. */
  static generateTestReceipt(printerName = "ESC/POS Thermal Receipt", characterWidth = 48): Buffer {
    const dateStr = timestamp();

    const driver = new EscPosDriver(characterWidth)
      .setAlignment(Alignment.CENTER)
      .setTextSize(2, 2)
      .setBold(true)
      .printLine("CUPPA PRINT SERVER")
      .setTextSize(1, 1)
      .setBold(false)
      .printLine("Android Local CUPS Subsystem")
      .printDivider("=")
      .setAlignment(Alignment.LEFT)
      .setBold(true)
      .printLine("PRINTER DIAGNOSTICS")
      .setBold(false)
      .printTwoColumnLine("Printer:", printerName)
      .printTwoColumnLine("Driver:", "ESC/POS Direct Receipt")
      .printTwoColumnLine("Timestamp:", dateStr)
      .printTwoColumnLine("Width:", `${characterWidth} chars (80mm)`)
      .printDivider("-")
      .setBold(true)
      .printLine("DIAGNOSTIC TEST ITEMS")
      .setBold(false)
      .printTwoColumnLine("1x Thermal Test Print", "$0.00")
      .printTwoColumnLine("1x ZPL/ESC Emulation", "$0.00")
      .printTwoColumnLine("1x CUPS Core Spooler", "$0.00")
      .printDivider("-")
      .setBold(true)
      .printTwoColumnLine("TOTAL:", "$0.00")
      .setBold(false)
      .printDivider("=")
      .setAlignment(Alignment.CENTER)
      .printLine("CODE 128 TEST BARCODE:")
      .printBarcode128("CUPPA-POS-TEST")
      .feed(1)
      .printLine("QR VERIFICATION CODE:")
      .printQrCode(`cuppa://test/receipt?printer=${printerName}&time=${dateStr}`)
      .feed(1)
      .printLine("*** TEST PRINT SUCCESSFUL ***")
      .printLine("Hardware Printhead Operational")
      .cutPaper(true);

    return driver.build();
  }
}
