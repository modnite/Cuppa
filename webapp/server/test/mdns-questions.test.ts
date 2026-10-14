import assert from "node:assert/strict";
import { test } from "node:test";
import { advertisedHost, readQuestions } from "../src/mdns-internal.js";

/**
 * Stub resolvers (Android's NsdManager among them) ask for a unicast answer by
 * setting the top bit of QCLASS. A responder that ignores it can be invisible to
 * those clients even though it answers everything else.
 */

function query(name: string, qclass: number): Buffer {
  const labels = name.split(".").filter(Boolean);
  const parts: Buffer[] = [];
  for (const label of labels) {
    const bytes = Buffer.from(label, "utf8");
    parts.push(Buffer.from([bytes.length]), bytes);
  }
  parts.push(Buffer.from([0]));
  const qname = Buffer.concat(parts);
  const header = Buffer.alloc(12);
  header.writeUInt16BE(0, 2); // standard query
  header.writeUInt16BE(1, 4); // one question
  const tail = Buffer.alloc(4);
  tail.writeUInt16BE(12, 0); // QTYPE PTR
  tail.writeUInt16BE(qclass, 2); // QCLASS
  return Buffer.concat([header, qname, tail]);
}

test("readQuestions reports a plain query as multicast", () => {
  const questions = readQuestions(query("_ipp._tcp.local", 0x0001));
  assert.equal(questions.length, 1);
  assert.equal(questions[0]!.name, "_ipp._tcp.local");
  assert.equal(questions[0]!.unicast, false);
});

test("readQuestions honours the unicast-response (QU) bit", () => {
  const questions = readQuestions(query("_ipp._tcp.local", 0x8001));
  assert.equal(questions.length, 1);
  assert.equal(questions[0]!.unicast, true);
});

test("readQuestions ignores responses and truncated packets", () => {
  const response = query("_ipp._tcp.local", 0x0001);
  response.writeUInt16BE(0x8400, 2);
  assert.deepEqual(readQuestions(response), []);
  assert.deepEqual(readQuestions(Buffer.alloc(4)), []);
});

test("advertisedHost borrows the host's Avahi name when it has one", () => {
  // The host's Avahi owns this name and its address, so Cuppa must not publish
  // a second copy of the A record.
  assert.deepEqual(advertisedHost("66b5ba038d62", "dxp4800plus-b52.local"), {
    host: "dxp4800plus-b52",
    publishAddress: false,
  });
  assert.deepEqual(advertisedHost("66b5ba038d62", "  NAS.LOCAL "), {
    host: "nas",
    publishAddress: false,
  });
});

test("advertisedHost falls back to a private name with no host Avahi", () => {
  assert.deepEqual(advertisedHost("66B5BA038D62", ""), {
    host: "66b5ba038d62-cuppa",
    publishAddress: true,
  });
  // Anything that is not a plausible .local name is ignored rather than trusted.
  assert.deepEqual(advertisedHost("box", "not a name"), {
    host: "box-cuppa",
    publishAddress: true,
  });
  assert.deepEqual(advertisedHost("box", "nas.local.evil"), {
    host: "box-cuppa",
    publishAddress: true,
  });
});
