import assert from "node:assert/strict";
import { test } from "node:test";
import { runWithInput } from "../src/exec.js";

/**
 * `runWithInput` is what hands job bytes to a CUPS backend during the USB
 * self-test, so its stdin/stdout plumbing and exit-code reporting are worth
 * pinning down.
 */

test("runWithInput feeds stdin and captures stdout", async () => {
  const result = await runWithInput(
    process.execPath,
    ["-e", "process.stdin.pipe(process.stdout)"],
    Buffer.from("cuppa-usb selftest")
  );
  assert.equal(result.code, 0);
  assert.equal(result.stdout, "cuppa-usb selftest");
});

test("runWithInput reports a non-zero exit code", async () => {
  const result = await runWithInput(process.execPath, ["-e", "process.exit(3)"], Buffer.from("x"));
  assert.equal(result.code, 3);
});

test("runWithInput captures stderr", async () => {
  const result = await runWithInput(
    process.execPath,
    ["-e", "process.stderr.write('backend says no'); process.exit(1)"],
    Buffer.from("")
  );
  assert.equal(result.code, 1);
  assert.match(result.stderr, /backend says no/);
});
