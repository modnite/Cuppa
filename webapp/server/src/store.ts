import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import path from "node:path";
import { ENV } from "./env.js";
import { log } from "./logger.js";
import type { CuppaSettings, PrinterMeta, StoreData } from "./types.js";

const DEFAULTS: StoreData = {
  settings: {
    advertiseEnabled: true,
    airprintCompat: true,
    tlsEnabled: false,
    adminPasswordHash: null,
    adminPasswordSalt: null,
  },
  printers: {},
};

/**
 * Tiny JSON-file store for the things CUPS does not own: the user's chosen
 * display names, the advertisement toggle per printer, and app settings. Kept
 * out of CUPS' own config so a `lpadmin` change can never corrupt Cuppa state.
 */
class Store {
  private file: string;
  private data: StoreData;
  private saveTimer: NodeJS.Timeout | null = null;

  constructor() {
    this.file = path.join(ENV.dataDir, "cuppa.json");
    this.data = this.load();
  }

  private load(): StoreData {
    try {
      if (!existsSync(ENV.dataDir)) mkdirSync(ENV.dataDir, { recursive: true });
      if (!existsSync(this.file)) return structuredClone(DEFAULTS);
      const raw = JSON.parse(readFileSync(this.file, "utf8")) as Partial<StoreData>;
      return {
        settings: { ...DEFAULTS.settings, ...(raw.settings ?? {}) },
        printers: raw.printers ?? {},
      };
    } catch (error) {
      log.error("Could not read the Cuppa store; starting from defaults", error);
      return structuredClone(DEFAULTS);
    }
  }

  private persist(): void {
    if (this.saveTimer) clearTimeout(this.saveTimer);
    this.saveTimer = setTimeout(() => {
      try {
        if (!existsSync(ENV.dataDir)) mkdirSync(ENV.dataDir, { recursive: true });
        const temporary = `${this.file}.tmp`;
        writeFileSync(temporary, JSON.stringify(this.data, null, 2), "utf8");
        renameSync(temporary, this.file);
      } catch (error) {
        log.error("Could not write the Cuppa store", error);
      }
    }, 150);
  }

  get settings(): CuppaSettings {
    return this.data.settings;
  }

  updateSettings(patch: Partial<CuppaSettings>): CuppaSettings {
    this.data.settings = { ...this.data.settings, ...patch };
    this.persist();
    return this.data.settings;
  }

  get printerMeta(): Record<string, PrinterMeta> {
    return this.data.printers;
  }

  metaFor(queue: string): PrinterMeta | undefined {
    return this.data.printers[queue];
  }

  setMeta(queue: string, meta: PrinterMeta): void {
    this.data.printers[queue] = meta;
    this.persist();
  }

  patchMeta(queue: string, patch: Partial<PrinterMeta>): PrinterMeta | undefined {
    const existing = this.data.printers[queue];
    if (!existing) return undefined;
    const next = { ...existing, ...patch };
    this.data.printers[queue] = next;
    this.persist();
    return next;
  }

  removeMeta(queue: string): void {
    delete this.data.printers[queue];
    this.persist();
  }
}

export const store = new Store();
