import { ENV } from "./env.js";

type Level = "debug" | "info" | "warn" | "error";

/**
 * The most recent log lines, kept in memory so Diagnostics can export them.
 * Without this, Cuppa's own log only ever reaches the container's stdout, which
 * is exactly what is missing when something fails on someone else's NAS.
 */
const BUFFER_LIMIT = 2000;
const buffer: string[] = [];

export function recentLogLines(): string[] {
  return [...buffer];
}

function stringifyMeta(meta: unknown): string {
  if (typeof meta === "string") return meta;
  try {
    return JSON.stringify(meta);
  } catch {
    return String(meta);
  }
}

function emit(level: Level, message: string, meta?: unknown): void {
  if (level === "debug" && !ENV.debug) return;
  const stamp = new Date().toISOString();
  const line = `[${stamp}] [${level.toUpperCase()}] ${message}`;
  buffer.push(meta === undefined ? line : `${line} ${stringifyMeta(meta)}`);
  if (buffer.length > BUFFER_LIMIT) buffer.splice(0, buffer.length - BUFFER_LIMIT);
  const sink = level === "error" ? console.error : level === "warn" ? console.warn : console.log;
  if (meta === undefined) sink(line);
  else sink(line, meta);
}

export const log = {
  debug: (message: string, meta?: unknown) => emit("debug", message, meta),
  info: (message: string, meta?: unknown) => emit("info", message, meta),
  warn: (message: string, meta?: unknown) => emit("warn", message, meta),
  error: (message: string, meta?: unknown) => emit("error", message, meta),
};
