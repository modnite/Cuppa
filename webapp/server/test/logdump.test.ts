import assert from "node:assert/strict";
import { test } from "node:test";
import { tidyCupsLog } from "../src/cups.js";
import { log, recentLogLines } from "../src/logger.js";

/**
 * The log dump exists because Cuppa's own log otherwise only reaches the
 * container's stdout, and because CUPS' error log is flooded by a benign
 * ippfind failure whenever there is no local Avahi socket.
 */

test("tidyCupsLog collapses the repeating ippfind/driverless lines", () => {
  const raw = [
    "I [14/Oct/2026:19:00:00 +0000] Printer added",
    "E [14/Oct/2026:19:00:01 +0000] [CGI] ippfind (PID 36) stopped with status 2!",
    "E [14/Oct/2026:19:00:01 +0000] [cups-deviced] PID 32 (driverless) stopped with status 2!",
    "E [14/Oct/2026:19:00:09 +0000] [CGI] ippfind (PID 66) stopped with status 2!",
    "E [14/Oct/2026:19:00:09 +0000] [cups-deviced] PID 61 (driverless) stopped with status 2!",
    "E [14/Oct/2026:19:00:10 +0000] [Job 1] Backend returned status 1 (failed)",
  ].join("\n");
  const tidy = tidyCupsLog(raw);
  assert.ok(tidy.includes("Printer added"), "keeps real lines");
  assert.ok(tidy.includes("Backend returned status 1"), "keeps the real job failure");
  assert.ok(!tidy.includes("ippfind (PID"), "drops the flood");
  assert.ok(/\(4 benign ippfind\/driverless discovery line\(s\) hidden/.test(tidy), "reports what it hid");
});

test("tidyCupsLog leaves a clean log untouched", () => {
  const clean = "I [14/Oct/2026:19:00:00 +0000] Printer added";
  assert.equal(tidyCupsLog(clean), clean);
});

test("recentLogLines keeps what was logged, newest last", () => {
  const before = recentLogLines().length;
  log.info("cuppa-test marker one");
  log.warn("cuppa-test marker two");
  const lines = recentLogLines();
  assert.ok(lines.length >= before + 2);
  assert.ok(lines.some((line) => line.includes("cuppa-test marker one") && line.includes("[INFO]")));
  assert.ok(lines.some((line) => line.includes("cuppa-test marker two") && line.includes("[WARN]")));
});
