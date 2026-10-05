import { execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { labelWidthDots, type ThermalConfig } from "./config.js";
import { EplDriver } from "./drivers/epl.js";
import { EscPosDriver } from "./drivers/escpos.js";
import { PclDriver } from "./drivers/pcl.js";
import { ZplDriver } from "./drivers/zpl.js";
import { parsePgm, type GrayImage } from "./raster.js";
import { generateTsplTestLabel, tsplFromImage } from "./tspl.js";

/**
 * The Rollo X1038 (and the Xprinter/Munbyn/Phomemo rebrands, IEEE-1284 id
 * `CMD:XPP,XL`) only prints TSPL **BITMAP** jobs. The higher-level TEXT / BOX /
 * BARCODE / QRCODE commands are accepted without error and then silently print
 * nothing. So the TSPL test label is rasterized from a real page, exactly like
 * a normal job, rather than drawn with those commands.
 *
 * This mirrors the Android app's `buildTsplRasterTestLabel`.
 */

const TEST_PAGE = process.env.CUPPA_TEST_PAGE ?? "/usr/share/cups/data/default-testpage.pdf";

/** Rasterizes the CUPS test page to a grayscale image at the label width. */
function rasterizeTestPage(config: ThermalConfig): GrayImage | null {
  if (!existsSync(TEST_PAGE)) return null;
  const dir = mkdtempSync(path.join(tmpdir(), "cuppa-testpage-"));
  try {
    const width = labelWidthDots(config);
    execFileSync(
      "pdftoppm",
      [
        "-gray",
        "-r",
        String(config.dpi),
        "-scale-to-x",
        String(width),
        "-scale-to-y",
        "-1",
        TEST_PAGE,
        path.join(dir, "page"),
      ],
      { stdio: ["ignore", "ignore", "inherit"] }
    );
    const files = readdirSync(dir)
      .filter((name) => /^page.*\.pgm$/.test(name))
      .sort();
    if (files.length === 0) return null;
    return parsePgm(readFileSync(path.join(dir, files[0]!)));
  } catch {
    return null;
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/** Builds the diagnostic label for whichever command language the queue uses. */
export function generateThermalTestLabel(config: ThermalConfig, queue: string): Buffer {
  switch (config.dialect) {
    case "zpl":
      return ZplDriver.generateTestLabel(queue, "CUPS", "Cuppa Web");
    case "epl":
      return EplDriver.generateTestLabel(queue, "Cuppa Web");
    case "escpos":
      return EscPosDriver.generateTestReceipt(queue, 48);
    case "pcl":
      return PclDriver.generateTestLabel(queue, "Cuppa Web");
    case "tspl":
    default: {
      const image = rasterizeTestPage(config);
      if (image) {
        return tsplFromImage(image, {
          density: config.density,
          speed: config.speed,
          gapMm: config.gapMm,
          ditherMode: config.dither,
          invertPolarity: !config.invertPolarity,
        });
      }
      // Last resort when the test page or pdftoppm is unavailable.
      return generateTsplTestLabel(queue, "CUPS", "Cuppa Web");
    }
  }
}
