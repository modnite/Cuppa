/**
 * Printer naming rules, deliberately identical to the Android app's
 * `PrinterNaming.resourceName` so a queue created by either front end behaves
 * the same on the wire.
 *
 * The queue name is the URI-safe form of the user's chosen name. The name other
 * devices see over Bonjour/AirPrint is the friendly name with " (Cuppa)"
 * appended, which is what makes a Cuppa-shared printer recognisable in the
 * macOS/iOS/Android print dialog.
 */

/** Turns a display name into an IPP/Bonjour-safe resource name. */
export function resourceName(name: string): string {
  let out = "";
  for (const char of name) {
    const plain =
      (char >= "A" && char <= "Z") ||
      (char >= "a" && char <= "z") ||
      (char >= "0" && char <= "9") ||
      char === "." ||
      char === "-" ||
      char === "_";
    if (plain) {
      out += char;
    } else if (out.length > 0 && !out.endsWith("_")) {
      out += "_";
    }
  }
  while (out.endsWith("_")) out = out.slice(0, -1);
  return out.length > 0 ? out : "printer";
}

export const CUPPA_SUFFIX = " (Cuppa)";

/** The Bonjour/AirPrint service name for a printer. */
export function advertisedName(displayName: string): string {
  const trimmed = displayName.trim() || "Cuppa Printer";
  return trimmed.toLowerCase().endsWith(CUPPA_SUFFIX.trim().toLowerCase())
    ? trimmed
    : `${trimmed}${CUPPA_SUFFIX}`;
}

/** Strips a trailing " (Cuppa)" that may have been stored in CUPS' info field. */
export function stripCuppaSuffix(info: string): string {
  const trimmed = info.trim();
  return trimmed.toLowerCase().endsWith(CUPPA_SUFFIX.trim().toLowerCase())
    ? trimmed.slice(0, -CUPPA_SUFFIX.length).trim()
    : trimmed;
}

/**
 * Picks a queue name that does not collide with an existing one. Two printers
 * of the same model would otherwise fight over a single queue.
 */
export function uniqueResourceName(base: string, existing: Iterable<string>): string {
  const taken = new Set([...existing].map((name) => name.toLowerCase()));
  const stem = resourceName(base);
  if (!taken.has(stem.toLowerCase())) return stem;
  let index = 2;
  while (taken.has(`${stem}_${index}`.toLowerCase())) index += 1;
  return `${stem}_${index}`;
}

/**
 * Deterministic UUID for a queue, matching the Android app's FNV-1a scheme.
 * Used only as a fallback when CUPS does not report a printer-uuid.
 */
export function deterministicUuid(seed: string): string {
  const fnv1a = (input: string, offset: bigint): bigint => {
    let hash = offset;
    for (const byte of Buffer.from(input, "utf8")) {
      hash ^= BigInt(byte);
      hash = BigInt.asUintN(64, hash * 1099511628211n);
    }
    return hash;
  };

  let hi = fnv1a(`cuppa-printer-uuid:${seed}`, 14695981039346656037n);
  let lo = fnv1a(`${seed}:cuppa-printer-uuid`, 14695981039346656037n);

  hi = (hi & 0xffffffffffff0fffn) | 0x0000000000004000n; // version 4
  lo = (lo & 0x3fffffffffffffffn) | 0x8000000000000000n; // RFC 4122 variant

  const hex = (value: bigint, width: number) => value.toString(16).padStart(width, "0");
  return [
    hex((hi >> 32n) & 0xffffffffn, 8),
    hex((hi >> 16n) & 0xffffn, 4),
    hex(hi & 0xffffn, 4),
    hex((lo >> 48n) & 0xffffn, 4),
    hex(lo & 0xffffffffffffn, 12),
  ].join("-");
}
