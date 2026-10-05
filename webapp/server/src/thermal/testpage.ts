import type { ThermalConfig } from "./config.js";
import { EplDriver } from "./drivers/epl.js";
import { EscPosDriver } from "./drivers/escpos.js";
import { PclDriver } from "./drivers/pcl.js";
import { ZplDriver } from "./drivers/zpl.js";
import { generateTsplTestLabel } from "./tspl.js";

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
    default:
      return generateTsplTestLabel(queue, "CUPS", "Cuppa Web");
  }
}
