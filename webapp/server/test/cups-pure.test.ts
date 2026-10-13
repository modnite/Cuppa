import assert from "node:assert/strict";
import { test } from "node:test";
import {
  isActiveJobState,
  jobStateLabel,
  localQueueName,
  looksLikePpd,
  parseAvahiLine,
  parseDeviceUri,
  printerStateLabel,
} from "../src/cups.js";

/**
 * The pure helpers in `cups.ts`. Nothing here shells out or talks to CUPS.
 */

test("printerStateLabel maps IPP printer states", () => {
  assert.equal(printerStateLabel(3), "Idle");
  assert.equal(printerStateLabel(4), "Printing");
  assert.equal(printerStateLabel(5), "Stopped");
  assert.equal(printerStateLabel(0), "Unknown");
  assert.equal(printerStateLabel(99), "Unknown");
});

test("jobStateLabel maps IPP job states", () => {
  assert.equal(jobStateLabel(3), "Pending");
  assert.equal(jobStateLabel(4), "Held");
  assert.equal(jobStateLabel(5), "Printing");
  assert.equal(jobStateLabel(6), "Stopped");
  assert.equal(jobStateLabel(7), "Canceled");
  assert.equal(jobStateLabel(8), "Aborted");
  assert.equal(jobStateLabel(9), "Completed");
  assert.equal(jobStateLabel(1), "Unknown");
});

test("isActiveJobState treats pending through stopped as active", () => {
  for (const state of [3, 4, 5, 6]) assert.equal(isActiveJobState(state), true, `state ${state}`);
  for (const state of [0, 7, 8, 9, 10]) assert.equal(isActiveJobState(state), false, `state ${state}`);
});

test("localQueueName is the resource-name sanitizer", () => {
  assert.equal(localQueueName("Brother MFC-L2717DW"), "Brother_MFC-L2717DW");
  assert.equal(localQueueName("()"), "printer");
});

test("parseDeviceUri defaults unknown schemes to port 0 and keeps the path", () => {
  assert.deepEqual(parseDeviceUri("foo://host"), {
    scheme: "foo",
    host: "host",
    port: 0,
    path: "/",
    secure: false,
  });
  assert.deepEqual(parseDeviceUri("dnssd://Name._ipp._tcp.local"), {
    scheme: "dnssd",
    host: "Name._ipp._tcp.local",
    port: 0,
    path: "/",
    secure: false,
  });
});

test("parseDeviceUri handles IPv6 literals with and without a port", () => {
  assert.deepEqual(parseDeviceUri("ipp://[fe80::1]"), {
    scheme: "ipp",
    host: "[fe80::1]",
    port: 631,
    path: "/",
    secure: false,
  });
  assert.equal(parseDeviceUri("ipp://[fe80::1"), null);
});

test("parseDeviceUri keeps a non-numeric colon in the host", () => {
  const parsed = parseDeviceUri("ipp://host:abc");
  assert.equal(parsed?.host, "host:abc");
  assert.equal(parsed?.port, 631);
});

test("parseDeviceUri lowercases the scheme and drops the query string", () => {
  const parsed = parseDeviceUri(" IPP://Host:631/ipp/print?x=1 ");
  assert.equal(parsed?.scheme, "ipp");
  assert.equal(parsed?.host, "Host");
  assert.equal(parsed?.path, "/ipp/print");
});

test("parseAvahiLine rejects lines that are not resolved services", () => {
  assert.equal(parseAvahiLine("+;eth0;IPv4;Name;_ipp._tcp;local", false), null);
  assert.equal(parseAvahiLine("=;eth0;IPv4;Name", false), null);
  assert.equal(
    parseAvahiLine('=;eth0;IPv4;Name;_ipp._tcp;local;host.local;;631;"rp=ipp/print"', false),
    null
  );
});

test("parseAvahiLine parses unquoted comma-separated TXT records", () => {
  const device = parseAvahiLine(
    "=;eth0;IPv4;Name;_ipp._tcp;local;host.local;10.0.0.1;631;rp=ipp/port1,ty=Foo",
    false
  );
  assert.equal(device?.uri, "ipp://10.0.0.1:631/ipp/port1");
  assert.equal(device?.makeAndModel, "Foo");
  assert.equal(device?.info, "Name");
  assert.equal(device?.kind, "network");
});

test("parseAvahiLine strips a leading slash from rp and defaults it", () => {
  const withSlash = parseAvahiLine(
    '=;eth0;IPv4;N;_ipp._tcp;local;h;10.0.0.2;631;"rp=/ipp/print"',
    false
  );
  assert.equal(withSlash?.uri, "ipp://10.0.0.2:631/ipp/print");

  const noRp = parseAvahiLine('=;eth0;IPv4;N;_ipp._tcp;local;h;10.0.0.3;631;"ty=Y"', false);
  assert.equal(noRp?.uri, "ipp://10.0.0.3:631/ipp/print");
});

test("parseAvahiLine falls back to usb_MDL then the service name for the model", () => {
  const usb = parseAvahiLine('=;eth0;IPv4;N;_ipp._tcp;local;h;10.0.0.4;631;"usb_MDL=MFC"', false);
  assert.equal(usb?.makeAndModel, "MFC");
  const name = parseAvahiLine('=;eth0;IPv4;Named;_ipp._tcp;local;h;10.0.0.5;631;"note=x"', false);
  assert.equal(name?.makeAndModel, "Named");
});

test("parseAvahiLine uses ipps when asked for a secure browse", () => {
  const device = parseAvahiLine('=;eth0;IPv4;N;_ipps._tcp;local;h;10.0.0.6;631;"rp=ipp/print"', true);
  assert.equal(device?.uri, "ipps://10.0.0.6:631/ipp/print");
});

test("looksLikePpd accepts any PPD header line within the first 4 KiB", () => {
  assert.equal(looksLikePpd(Buffer.from("*FormatVersion: \"4.3\"\n")), true);
  assert.equal(looksLikePpd(Buffer.from("*LanguageEncoding: ISOLatin1\n")), true);
  assert.equal(looksLikePpd(Buffer.from("")), false);
  const late = Buffer.concat([Buffer.alloc(5000, 0x78), Buffer.from("*PPD-Adobe: \"4.3\"")]);
  assert.equal(looksLikePpd(late), false);
});
