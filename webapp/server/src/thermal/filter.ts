import { execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { gunzipSync } from "node:zlib";
import { labelWidthDots, loadThermalConfig, defaultThermalConfig, type ThermalConfig } from "./config.js";
import { EplDriver } from "./drivers/epl.js";
import { EscPosDriver } from "./drivers/escpos.js";
import { PclDriver } from "./drivers/pcl.js";
import { ZplDriver } from "./drivers/zpl.js";
import { parsePgm, type GrayImage } from "./raster.js";
import { generateThermalTestLabel } from "./testpage.js";
import { tsplFromImage } from "./tspl.js";

/**
 * The `cuppa-thermal` CUPS filter.
 *
 * CUPS invokes it as: cuppa-thermal job-id user title copies options [file]
 * It reads the job (PDF or PostScript, optionally gzipped), rasterizes it to a
 * monochrome image, encodes the printer's command language, and writes the
 * bytes to stdout for the USB/socket backend to deliver.
 *
 * The queue name arrives in the PRINTER environment variable; the per-queue
 * settings live in /etc/cuppa/thermal/<queue>.json.
 */

const DEBUG = process.env.CUPPA_DEBUG === "1";

function debug(message: string): void {
  if (DEBUG) process.stderr.write(`cuppa-thermal: ${message}\n`);
}

function readInput(fileArg: string | undefined): Buffer {
  if (fileArg && fileArg !== "-" && existsSync(fileArg)) return readFileSync(fileArg);
  return readFileSync(0); // stdin
}

function maybeGunzip(data: Buffer): Buffer {
  if (data.length > 2 && data[0] === 0x1f && data[1] === 0x8b) {
    try {
      return gunzipSync(data);
    } catch {
      return data;
    }
  }
  return data;
}

function detectMime(options: string, data: Buffer): string {
  const declared = /document-format=([^\s]+)/.exec(options)?.[1];
  if (declared) return declared;
  if (data.subarray(0, 4).toString("ascii") === "%PDF") return "application/pdf";
  if (data.subarray(0, 2).toString("ascii") === "%!") return "application/postscript";
  if (data[0] === 0x50 && (data[1] === 0x35 || data[1] === 0x34)) return "image/x-portable-anymap";
  return "application/octet-stream";
}

function rasterize(data: Buffer, mime: string, config: ThermalConfig, dir: string): GrayImage[] {
  const width = labelWidthDots(config);
  const env = { ...process.env, PATH: `/usr/local/bin:/usr/bin:/bin:${process.env.PATH ?? ""}` };

  if (mime.includes("pdf")) {
    const input = path.join(dir, "input.pdf");
    const prefix = path.join(dir, "page");
    writeFileSync(input, data);
    execFileSync("pdftoppm", ["-gray", "-r", String(config.dpi), "-scale-to-x", String(width), "-scale-to-y", "-1", input, prefix], {
      env,
      stdio: ["ignore", "ignore", "inherit"],
    });
    return readPages(dir);
  }

  if (mime.includes("postscript")) {
    const input = path.join(dir, "input.ps");
    writeFileSync(input, data);
    const output = path.join(dir, "page-%d.pgm");
    const widthPt = Math.round((config.labelWidthMm / 25.4) * 72);
    const heightPt = Math.round((config.labelHeightMm / 25.4) * 72);
    execFileSync(
      "gs",
      [
        "-q",
        "-dNOPAUSE",
        "-dBATCH",
        "-dSAFER",
        "-sDEVICE=pgmraw",
        `-r${config.dpi}`,
        "-dFIXEDMEDIA",
        "-dPDFFitPage",
        `-dDEVICEWIDTHPOINTS=${widthPt}`,
        `-dDEVICEHEIGHTPOINTS=${heightPt}`,
        `-sOutputFile=${output}`,
        input,
      ],
      { env, stdio: ["ignore", "ignore", "inherit"] }
    );
    return readPages(dir);
  }

  if (data[0] === 0x50 && (data[1] === 0x35 || data[1] === 0x34)) {
    return [parsePgm(data)];
  }

  throw new Error(`Unsupported input format: ${mime}`);
}

/** Local indirection so the helper can be defined after use without a lint issue. */
function readPages(dir: string): GrayImage[] {
  const files = readdirSync(dir)
    .filter((name) => /^page-?\d*\.pgm$/.test(name) || name === "page.pgm")
    .sort((a, b) => {
      const na = Number(/(\d+)/.exec(a)?.[1] ?? 1);
      const nb = Number(/(\d+)/.exec(b)?.[1] ?? 1);
      return na - nb;
    });
  return files.map((name) => parsePgm(readFileSync(path.join(dir, name))));
}

/** Encodes one raster page in the queue's command language. */
function encodePage(page: GrayImage, config: ThermalConfig): Buffer {
  switch (config.dialect) {
    case "zpl":
      return new ZplDriver(labelWidthDots(config), page.height)
        .setDarkness(Math.min(30, config.density * 2))
        .setPrintSpeed(config.speed)
        .drawBitmap(0, 0, page, config.dither, config.invertPolarity)
        .build();
    case "epl":
      return new EplDriver(labelWidthDots(config), page.height)
        .setDensity(config.density)
        .setSpeed(config.speed)
        // EPL needs inverted bits to represent black at the protocol level.
        .drawBitmap(0, 0, page, config.dither, !config.invertPolarity)
        .build(1);
    case "escpos":
      return new EscPosDriver(48)
        .printImage(page, config.dither, config.invertPolarity)
        .feed(2)
        .cutPaper(true)
        .build();
    case "pcl":
      return PclDriver.fromBitmap(page, config.dpi, config.dither);
    case "tspl":
    default:
      return tsplFromImage(page, {
        density: config.density,
        speed: config.speed,
        gapMm: config.gapMm,
        ditherMode: config.dither,
        // TSPL BITMAP mode 1 needs inverted bits; the user toggle flips the result.
        invertPolarity: !config.invertPolarity,
      });
  }
}

function main(): void {
  // CUPS invokes: filter job-id user title copies options [file]
  // With node that is argv = [node, filter.js, job-id, user, title, copies, options, file].
  const [, , , , , copiesArg, optionsArg, fileArg] = process.argv;
  const copies = Math.max(1, Number.parseInt(copiesArg ?? "1", 10) || 1);
  const options = optionsArg ?? "";
  const queue = process.env.PRINTER ?? "";
  const config = (queue ? loadThermalConfig(queue) : null) ?? defaultThermalConfig();
  debug(`queue=${queue} copies=${copies} options=${options} dialect=${config.dialect}`);

  // A test print can ask the filter to emit the diagnostic label directly.
  if (/(^|\s)cuppa-test=1(\s|$)/.test(options)) {
    process.stdout.write(generateThermalTestLabel(config, queue || "Cuppa Thermal"));
    return;
  }

  const dir = mkdtempSync(path.join(tmpdir(), "cuppa-thermal-"));
  try {
    const data = maybeGunzip(readInput(fileArg));
    if (data.length === 0) throw new Error("Empty input document");
    const mime = detectMime(options, data);
    debug(`mime=${mime} bytes=${data.length}`);

    const pages = rasterize(data, mime, config, dir);
    if (pages.length === 0) throw new Error("Rasterization produced no pages");
    debug(`pages=${pages.length}`);

    for (const page of pages) {
      const encoded = encodePage(page, config);
      for (let copy = 0; copy < copies; copy++) process.stdout.write(encoded);
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

main();
