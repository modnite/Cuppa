import { rasterize, type DitherMode, type GrayImage } from "../raster.js";

/**
 * HP PCL 5 raster-graphics generator, ported from the Android app's `PclDriver`.
 *
 * Monochrome only: a small, self-contained byte protocol accepted as a baseline
 * compatibility mode by the overwhelming majority of laser/inkjet printers.
 */

function timestamp(): string {
  const d = new Date();
  const p = (n: number): string => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(
    d.getMinutes()
  )}:${p(d.getSeconds())}`;
}

function clamp(value: number, min: number, max: number): number {
  return value < min ? min : value > max ? max : value;
}

export class PclDriver {
  private chunks: Buffer[] = [];
  private readonly esc = 0x1b;

  constructor(readonly dpi = 300) {
    // Printer reset (Esc E) puts the printer into a known default state.
    this.chunks.push(Buffer.from([this.esc, "E".charCodeAt(0)]));
  }

  /**
   * Select a built-in PCL 5 font. 4099 is Courier, which every PCL printer
   * ships; the pitch is fixed so columns line up on the test page.
   */
  selectFont(sizePt = 12, typeface = 4099): this {
    this.writeEscCommand(`(s0p${clamp(Math.round(sizePt), 4, 72)}h0s0b${typeface}T`);
    return this;
  }

  /** Write a line of text followed by CRLF, advancing to the next line. */
  printLine(text = ""): this {
    this.chunks.push(Buffer.from(`${text}\r\n`, "ascii"));
    return this;
  }

  /** Set the page orientation: landscape (1) or portrait (0). */
  setOrientation(landscape = false): this {
    this.writeEscCommand(`&l${landscape ? 1 : 0}O`);
    return this;
  }

  /** Move the raster cursor to an absolute position in dots. */
  setCursorPosition(xDots: number, yDots: number): this {
    this.writeEscCommand(`*p${xDots}x${yDots}Y`);
    return this;
  }

  /** Render an image using PCL 5 raster graphics (monochrome, uncompressed). */
  printBitmap(image: GrayImage, ditherMode: DitherMode = "FLOYD_STEINBERG"): this {
    const rowBytes = (image.width + 7) >> 3;
    const monoBytes = rasterize(image, ditherMode, false);

    // Set raster resolution (Esc*t###R) and source raster width (Esc*r###S).
    this.writeEscCommand(`*t${this.dpi}R`);
    this.writeEscCommand(`*r${image.width}S`);
    // Compression method 0 = uncompressed.
    this.writeEscCommand("*b0M");
    // Start raster graphics at the current cursor position (Esc*r1A).
    this.writeEscCommand("*r1A");

    for (let row = 0; row < image.height; row++) {
      this.writeEscCommand(`*b${rowBytes}W`);
      this.chunks.push(Buffer.from(monoBytes.subarray(row * rowBytes, (row + 1) * rowBytes)));
    }

    // End raster graphics (Esc*rB).
    this.writeEscCommand("*rB");
    return this;
  }

  /** Eject the current page. */
  formFeed(): this {
    this.chunks.push(Buffer.from([0x0c]));
    return this;
  }

  /** Reset the printer back to its power-on default state. */
  reset(): this {
    this.writeEscCommand("E");
    return this;
  }

  /** Build the raw PCL 5 byte stream. */
  build(): Buffer {
    return Buffer.concat(this.chunks);
  }

  private writeEscCommand(command: string): void {
    this.chunks.push(Buffer.from([this.esc]));
    this.chunks.push(Buffer.from(command, "ascii"));
  }

  /** Render an image as a single-page PCL 5 raster document. */
  static fromBitmap(
    image: GrayImage,
    dpi = 300,
    ditherMode: DitherMode = "FLOYD_STEINBERG"
  ): Buffer {
    return new PclDriver(dpi)
      .setOrientation(false)
      .setCursorPosition(0, 0)
      .printBitmap(image, ditherMode)
      .formFeed()
      .reset()
      .build();
  }

  /** A readable text-only diagnostic page using PCL's built-in Courier font. */
  static generateTestLabel(printerName = "PCL Printer", transport = "Cuppa Web"): Buffer {
    const dateStr = timestamp();
    const rule = "=".repeat(56);
    const thin = "-".repeat(56);

    return new PclDriver(300)
      .selectFont(20)
      .printLine("CUPPA PRINT SERVER")
      .selectFont(11)
      .printLine("Generic PCL 5 diagnostic page")
      .printLine(rule)
      .printLine(`Printer:    ${printerName}`)
      .printLine("Driver:     PCL 5 raster / built-in Courier")
      .printLine(`Transport:  ${transport}`)
      .printLine(`Timestamp:  ${dateStr}`)
      .printLine(thin)
      .printLine("If you can read this, the printer accepts PCL 5 and")
      .printLine("is shared through Cuppa to macOS, iOS and Android.")
      .printLine(thin)
      .printLine("Cuppa Native Print Subsystem")
      .formFeed()
      .reset()
      .build();
  }
}
