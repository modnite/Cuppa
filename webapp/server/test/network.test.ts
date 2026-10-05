import assert from "node:assert/strict";
import { test } from "node:test";
import { parseDeviceUri } from "../src/cups.js";

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
