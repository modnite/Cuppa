/**
 * Runtime configuration, all overridable by environment variables so the same
 * image works on any NAS without a rebuild.
 */
function int(value: string | undefined, fallback: number): number {
  const parsed = Number.parseInt(value ?? "", 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

export const ENV = {
  /** Cuppa's own version, baked into the image at build time. */
  version: process.env.CUPPA_VERSION ?? "dev",
  /** Where Cuppa keeps its settings and printer metadata. */
  dataDir: process.env.CUPPA_DATA_DIR ?? "/data",
  /** Port the web UI/API listens on. */
  webPort: int(process.env.CUPPA_WEB_PORT, 8631),
  /** Port the CUPS IPP server listens on (advertised to clients). */
  ippPort: int(process.env.CUPPA_IPP_PORT, 631),
  /** Hostname CUPS is reachable at from the backend. */
  cupsHost: process.env.CUPS_SERVER_HOST ?? "127.0.0.1",
  /** Directory Avahi reads static service files from. */
  avahiServicesDir: process.env.CUPPA_AVAHI_DIR ?? "/etc/avahi/services",
  /** Path to the CUPS test page, used by Test Print. */
  testPage: process.env.CUPPA_TEST_PAGE ?? "/usr/share/cups/data/default-testpage.pdf",
  /** Optional admin password applied on first start, before the UI is used. */
  adminPassword: process.env.CUPPA_ADMIN_PASSWORD ?? "",
  /**
   * How mDNS is provided. "container" runs Avahi inside the image (default,
   * works on a host that does not run its own mDNS). "host" uses the host's
   * Avahi over its D-Bus, which is required on a host that already runs Avahi
   * (two responders on one host fight over port 5353).
   */
  avahiMode: process.env.CUPPA_AVAHI_MODE ?? "container",
  /** Set to "1" to log every command and IPP exchange. */
  debug: process.env.CUPPA_DEBUG === "1",
} as const;
