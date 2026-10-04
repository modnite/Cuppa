import os from "node:os";

/** First non-internal IPv4 address, used for the share URIs shown in the UI. */
export function primaryIPv4(): string {
  const interfaces = os.networkInterfaces();
  const candidates: string[] = [];
  for (const addresses of Object.values(interfaces)) {
    for (const address of addresses ?? []) {
      if (address.family === "IPv4" && !address.internal) {
        candidates.push(address.address);
      }
    }
  }
  const siteLocal = candidates.find(
    (ip) => ip.startsWith("192.168.") || ip.startsWith("10.") || /^172\.(1[6-9]|2\d|3[01])\./.test(ip)
  );
  return siteLocal ?? candidates[0] ?? "127.0.0.1";
}

export function hostName(): string {
  return os.hostname();
}

export function systemUptimeSeconds(): number {
  return Math.round(os.uptime());
}
