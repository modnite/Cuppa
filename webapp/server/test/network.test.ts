import assert from "node:assert/strict";
import { test } from "node:test";
import { looksLikePpd, parseAvahiBrowse, parseDeviceUri } from "../src/cups.js";

test("parseDeviceUri splits an IPP URI into host, port and path", () => {
  assert.deepEqual(parseDeviceUri("ipp://192.168.1.50:631/ipp/print"), {
    scheme: "ipp",
    host: "192.168.1.50",
    port: 631,
    path: "/ipp/print",
    secure: false,
  });
});

test("parseDeviceUri applies the default port for each scheme", () => {
  assert.equal(parseDeviceUri("socket://192.168.1.50")?.port, 9100);
  assert.equal(parseDeviceUri("lpd://BRW38D57A15425E/BINARY_P1")?.port, 515);
  assert.equal(parseDeviceUri("lpd://BRW38D57A15425E/BINARY_P1")?.path, "/BINARY_P1");
  assert.equal(parseDeviceUri("ipp://printer.local")?.path, "/");
  assert.equal(parseDeviceUri("ipps://printer.local")?.secure, true);
  assert.equal(parseDeviceUri("https://printer.local")?.secure, true);
});

test("parseDeviceUri handles bracketed IPv6 literals", () => {
  const parsed = parseDeviceUri("ipp://[fe80::1]:631/ipp/print");
  assert.equal(parsed?.host, "[fe80::1]");
  assert.equal(parsed?.port, 631);
});

test("parseDeviceUri rejects a string that is not a URI", () => {
  assert.equal(parseDeviceUri("not a uri"), null);
  assert.equal(parseDeviceUri(""), null);
});

test("parseAvahiBrowse turns a resolved _ipp._tcp service into a direct IPP URI", () => {
  const line =
    '=;eth0;IPv4;Brother MFC-L2717DW;_ipp._tcp;local;BRW38D57A15425E.local;192.168.1.136;631;' +
    '"rp=ipp/print" "ty=Brother MFC-L2717DW" "usb_MDL=MFC-L2717DW" "note="';
  const devices = parseAvahiBrowse(line + "\n", false);

  assert.equal(devices.length, 1);
  assert.equal(devices[0]?.uri, "ipp://192.168.1.136:631/ipp/print");
  assert.equal(devices[0]?.makeAndModel, "Brother MFC-L2717DW");
  assert.equal(devices[0]?.kind, "network");
});

test("parseAvahiBrowse ignores unresolved and malformed lines", () => {
  assert.deepEqual(parseAvahiBrowse("+;eth0;IPv4;Name;_ipp._tcp;local\n", false), []);
  assert.deepEqual(parseAvahiBrowse("garbage\n", false), []);
});

test("parseAvahiBrowse defaults the resource path and honours ipps", () => {
  const line = '=;eth0;IPv4;Printer;_ipps._tcp;local;host.local;10.0.0.5;631;"ty=Laser"';
  const devices = parseAvahiBrowse(line, true);
  assert.equal(devices[0]?.uri, "ipps://10.0.0.5:631/ipp/print");
});

test("looksLikePpd accepts a PPD header and rejects other files", () => {
  assert.equal(looksLikePpd(Buffer.from('*PPD-Adobe: "4.3"\n*FormatVersion: "4.3"\n')), true);
  assert.equal(looksLikePpd(Buffer.from("hello world")), false);
});
