import assert from "node:assert/strict";
import { test } from "node:test";
import {
  advertisedName,
  deterministicUuid,
  resourceName,
  stripCuppaSuffix,
  uniqueResourceName,
} from "../src/naming.js";

/**
 * Edge cases the original `naming.test.ts` leaves out: unicode, repeated
 * separators, case-insensitive collisions and suffix handling.
 */

test("resourceName collapses runs of separators and trims trailing ones", () => {
  assert.equal(resourceName("a  b"), "a_b");
  assert.equal(resourceName("Printer #1"), "Printer_1");
  assert.equal(resourceName("a___b"), "a___b"); // underscores are already safe
  assert.equal(resourceName("___"), "printer"); // nothing but separators
  assert.equal(resourceName("a-"), "a-"); // trailing "-" and "." are safe
  assert.equal(resourceName("a."), "a.");
});

test("resourceName replaces non-ASCII code points with a single underscore", () => {
  assert.equal(resourceName("héllo"), "h_llo");
  assert.equal(resourceName("日本語"), "printer");
  assert.equal(resourceName("🖨️ printer"), "printer");
  // A leading non-ASCII character must not produce a leading underscore.
  assert.equal(resourceName("éclair"), "clair");
});

test("advertisedName treats an existing suffix case-insensitively", () => {
  assert.equal(advertisedName("Office (cuppa)"), "Office (cuppa)");
  assert.equal(advertisedName("  Office Laser  "), "Office Laser (Cuppa)");
  assert.equal(advertisedName("   "), "Cuppa Printer (Cuppa)");
});

test("stripCuppaSuffix strips the spaced suffix and is case-insensitive", () => {
  assert.equal(stripCuppaSuffix("  Office Laser (Cuppa)  "), "Office Laser");
  assert.equal(stripCuppaSuffix("Office (cuppa)"), "Office");
  assert.equal(stripCuppaSuffix(""), "");
});

test("stripCuppaSuffix requires the spaced suffix and never eats a character", () => {
  // A bare "(Cuppa)" with no leading space is not the suffix, so it is left
  // intact rather than trimming the character before it.
  assert.equal(stripCuppaSuffix("Office(Cuppa)"), "Office(Cuppa)");
});

test("advertisedName appends the suffix unless the spaced suffix is present", () => {
  assert.equal(advertisedName("Office"), "Office (Cuppa)");
  assert.equal(advertisedName("Office (Cuppa)"), "Office (Cuppa)");
  assert.equal(advertisedName("Office (cuppa)"), "Office (cuppa)");
  assert.equal(advertisedName("Office(cuppa)"), "Office(cuppa) (Cuppa)");
});

test("advertisedName appends the location when set", () => {
  assert.equal(advertisedName("Printer", "Lobby"), "Printer (Lobby)");
  assert.equal(advertisedName("Printer", "  Office  "), "Printer (Office)");
  assert.equal(advertisedName("Printer", ""), "Printer (Cuppa)");
  // Already suffixed with the same location: left alone.
  assert.equal(advertisedName("Printer (Lobby)", "Lobby"), "Printer (Lobby)");
});

test("uniqueResourceName compares case-insensitively and accepts any iterable", () => {
  assert.equal(uniqueResourceName("Office Laser", new Set(["office_laser"])), "Office_Laser_2");
  assert.equal(
    uniqueResourceName("Office Laser", ["OFFICE_LASER", "office_laser_2", "office_laser_3"]),
    "Office_Laser_4"
  );
  assert.equal(uniqueResourceName("", []), "printer");
});

test("deterministicUuid is stable, distinct and RFC 4122 shaped for odd seeds", () => {
  const shape = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
  for (const seed of ["", "a", "日本語", "🖨️", "Office_Laser", "Office_Laser_2"]) {
    const uuid = deterministicUuid(seed);
    assert.match(uuid, shape, `seed ${JSON.stringify(seed)}`);
    assert.equal(uuid, deterministicUuid(seed));
  }
  assert.notEqual(deterministicUuid(""), deterministicUuid("a"));
});
