import { rasterize, type DitherMode, type GrayImage } from "../raster.js";

/**
 * HP PCL 5 raster-graphics generator, ported from the Android app's `PclDriver`.
 *
 * Monochrome only: a small, self-contained byte protocol accepted as a baseline
 * compatibility mode by the overwhelming majority of laser/inkjet printers.
 */

export class PclDriver {
  private chunks: Buffer[] = [];
  private readonly esc = 0x1b;

  constructor(readonly dpi = 300) {
    // Printer reset (Esc E) puts the printer into a known default state.
    this.chunks.push(Buffer.from([this.esc, "E".charCodeAt(0)]));
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
}
