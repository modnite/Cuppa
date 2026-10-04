import { listPrinters } from "./cups.js";
import { syncAdvertisements } from "./mdns.js";
import { store } from "./store.js";
import { log } from "./logger.js";

/**
 * Re-reads CUPS and republishes Bonjour records. Called after every mutation
 * and on a timer, so queues changed directly in CUPS are still reflected.
 * Never throws: a CUPS hiccup must not take down the web app.
 */
export async function refreshAdvertisements(): Promise<boolean> {
  try {
    const printers = await listPrinters();
    syncAdvertisements(printers, store.settings);
    return true;
  } catch (error) {
    log.debug(`Advertisement refresh skipped: ${String(error)}`);
    return false;
  }
}
