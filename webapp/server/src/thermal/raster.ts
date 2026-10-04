/**
 * Monochrome raster handling for thermal print heads.
 *
 * The dithering algorithms are a direct port of the Android app's
 * `ThermalRasterizer`, so a label rendered by the webapp is bit-for-bit the
 * same as one rendered by the phone.
 */

export type DitherMode = "THRESHOLD" | "FLOYD_STEINBERG" | "ATKINSON";

export const DITHER_MODES: DitherMode[] = ["THRESHOLD", "FLOYD_STEINBERG", "ATKINSON"];

export interface GrayImage {
  width: number;
  height: number;
  /** One luminance byte per pixel, 0 (black) .. 255 (white). */
  pixels: Uint8Array;
}

function clamp(value: number, min: number, max: number): number {
  return value < min ? min : value > max ? max : value;
}

/**
 * Turns a grayscale image into a packed 1-bit raster, MSB first, rows padded to
 * a byte boundary. `true` bits mean "burn" in the standard polarity; pass
 * `invertPolarity` to flip, exactly like the Android toggle.
 */
export function rasterize(
  image: GrayImage,
  mode: DitherMode = "FLOYD_STEINBERG",
  invertPolarity = false,
  threshold = 128
): Uint8Array {
  const { width, height } = image;
  const rowBytes = (width + 7) >> 3;
  const out = new Uint8Array(rowBytes * height);

  const setBurn = (rowOffset: number, x: number, burn: boolean) => {
    if (burn) out[rowOffset + (x >> 3)]! |= 1 << (7 - (x & 7));
  };

  if (mode === "THRESHOLD") {
    for (let y = 0; y < height; y++) {
      const rowOffset = y * rowBytes;
      for (let x = 0; x < width; x++) {
        const lum = image.pixels[y * width + x]!;
        let burn = lum <= threshold;
        if (invertPolarity) burn = !burn;
        setBurn(rowOffset, x, burn);
      }
    }
    return out;
  }

  // Error-diffusion modes operate on a mutable copy so the error can spread.
  const gray = new Float32Array(image.pixels.length);
  for (let i = 0; i < image.pixels.length; i++) gray[i] = image.pixels[i]!;

  for (let y = 0; y < height; y++) {
    const rowOffset = y * rowBytes;
    for (let x = 0; x < width; x++) {
      const idx = y * width + x;
      const oldValue = clamp(gray[idx]!, 0, 255);
      const newValue = oldValue < 128 ? 0 : 255;
      const error = oldValue - newValue;

      let burn = newValue === 0;
      if (invertPolarity) burn = !burn;
      setBurn(rowOffset, x, burn);

      if (mode === "FLOYD_STEINBERG") {
        if (x + 1 < width) gray[idx + 1]! += error * (7 / 16);
        if (y + 1 < height) {
          if (x - 1 >= 0) gray[(y + 1) * width + (x - 1)]! += error * (3 / 16);
          gray[(y + 1) * width + x]! += error * (5 / 16);
          if (x + 1 < width) gray[(y + 1) * width + (x + 1)]! += error * (1 / 16);
        }
      } else {
        // Atkinson: 1/8 of the error to six neighbours.
        const e8 = error / 8;
        if (x + 1 < width) gray[y * width + (x + 1)]! += e8;
        if (x + 2 < width) gray[y * width + (x + 2)]! += e8;
        if (y + 1 < height) {
          if (x - 1 >= 0) gray[(y + 1) * width + (x - 1)]! += e8;
          gray[(y + 1) * width + x]! += e8;
          if (x + 1 < width) gray[(y + 1) * width + (x + 1)]! += e8;
        }
        if (y + 2 < height) gray[(y + 2) * width + x]! += e8;
      }
    }
  }
  return out;
}

/** Pads an image with white to the print head width, centring the content. */
export function centerOnHead(image: GrayImage, headDots: number): GrayImage {
  if (image.width >= headDots) return image;
  const pixels = new Uint8Array(headDots * image.height).fill(255);
  const left = (headDots - image.width) >> 1;
  for (let y = 0; y < image.height; y++) {
    const src = y * image.width;
    const dst = y * headDots + left;
    pixels.set(image.pixels.subarray(src, src + image.width), dst);
  }
  return { width: headDots, height: image.height, pixels };
}

/** Parses a binary PGM (P5) or PBM (P4) image, as produced by pdftoppm. */
export function parsePgm(buffer: Buffer): GrayImage {
  if (buffer.length < 2) throw new Error("Empty PGM");
  const magic = buffer.toString("ascii", 0, 2);
  if (magic !== "P5" && magic !== "P4") throw new Error(`Unsupported image magic ${magic}`);

  let offset = 2;
  const readToken = (): string => {
    while (offset < buffer.length) {
      const byte = buffer[offset]!;
      if (byte === 0x23) {
        // comment to end of line
        while (offset < buffer.length && buffer[offset] !== 0x0a) offset++;
      } else if (byte === 0x20 || byte === 0x09 || byte === 0x0a || byte === 0x0d) {
        offset++;
      } else {
        break;
      }
    }
    const start = offset;
    while (offset < buffer.length) {
      const byte = buffer[offset]!;
      if (byte === 0x20 || byte === 0x09 || byte === 0x0a || byte === 0x0d) break;
      offset++;
    }
    return buffer.toString("ascii", start, offset);
  };

  const width = Number.parseInt(readToken(), 10);
  const height = Number.parseInt(readToken(), 10);
  const maxValue = magic === "P5" ? Number.parseInt(readToken(), 10) : 1;
  offset++; // single whitespace after the max value

  const pixels = new Uint8Array(width * height);
  if (magic === "P5") {
    const bytesPerSample = maxValue > 255 ? 2 : 1;
    for (let i = 0; i < pixels.length; i++) {
      if (bytesPerSample === 1) {
        pixels[i] = buffer[offset + i] ?? 0;
      } else {
        pixels[i] = buffer[offset + i * 2] ?? 0;
      }
    }
  } else {
    const rowBytes = (width + 7) >> 3;
    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) {
        const bit = (buffer[offset + y * rowBytes + (x >> 3)]! >> (7 - (x & 7))) & 1;
        pixels[y * width + x] = bit ? 0 : 255; // PBM: 1 = black
      }
    }
  }
  return { width, height, pixels };
}
