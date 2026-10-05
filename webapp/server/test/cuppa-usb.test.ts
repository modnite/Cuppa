import assert from "node:assert/strict";
import { chmodSync, existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { runWithInput } from "../src/exec.js";

/**
 * Regression tests for the paced USB backend.
 *
 * CUPS passes the device URI in DEVICE_URI, not in argv. An earlier backend read
 * argv[0] as the URI, matched nothing and exited 0 without sending a byte, so
 * CUPS reported the job as completed while the printer never saw it.
 *
 * The stub backend is a POSIX shell script, so these run on Linux (CI) and are
 * skipped on Windows.
 */

const script = path.resolve(process.cwd(), "../docker/cuppa-usb.js");

function makeStub(dir: string): { stub: string; stdinOut: string; uriOut: string; argsOut: string } {
  const stub = path.join(dir, "stub-backend");
  const stdinOut = path.join(dir, "stdin.bin");
  const uriOut = path.join(dir, "uri.txt");
  const argsOut = path.join(dir, "args.txt");
  writeFileSync(
    stub,
    `#!/bin/sh\ncat > "${stdinOut}"\nprintf '%s' "$DEVICE_URI" > "${uriOut}"\nprintf '%s' "$*" > "${argsOut}"\n`
  );
  chmodSync(stub, 0o755);
  return { stub, stdinOut, uriOut, argsOut };
}

test(
  "cuppa-usb takes the device URI from DEVICE_URI and forwards the job",
  { skip: process.platform === "win32" },
  async () => {
    const dir = mkdtempSync(path.join(os.tmpdir(), "cuppa-usb-test-"));
    const { stub, stdinOut, uriOut, argsOut } = makeStub(dir);

    const payload = Buffer.alloc(5000, 0x41);
    const result = await runWithInput(
      process.execPath,
      [script, "7", "root", "Cuppa self-test", "1", "cuppa-test=1"],
      payload,
      {
        timeoutMs: 20_000,
        env: {
          CUPPA_USB_BACKEND: stub,
          CUPPA_USB_START_DELAY_MS: "0",
          DEVICE_URI: "cuppa-usb://Test/Printer?serial=1",
        },
      }
    );

    assert.equal(result.code, 0, result.stderr);
    // Every job byte reached the real backend.
    assert.equal(readFileSync(stdinOut).length, payload.length);
    // The URI is rewritten to usb:// and the job arguments pass through intact.
    assert.equal(readFileSync(uriOut, "utf8"), "usb://Test/Printer?serial=1");
    assert.equal(readFileSync(argsOut, "utf8"), "7 root Cuppa self-test 1 cuppa-test=1");
  }
);

test(
  "cuppa-usb ignores a job whose device URI is not cuppa-usb://",
  { skip: process.platform === "win32" },
  async () => {
    const dir = mkdtempSync(path.join(os.tmpdir(), "cuppa-usb-test-"));
    const { stub, stdinOut } = makeStub(dir);

    const result = await runWithInput(
      process.execPath,
      [script, "7", "root", "Cuppa self-test", "1", "cuppa-test=1"],
      Buffer.from("hello"),
      {
        timeoutMs: 20_000,
        env: { CUPPA_USB_BACKEND: stub, CUPPA_USB_START_DELAY_MS: "0", DEVICE_URI: "usb://Other/Printer" },
      }
    );

    assert.equal(result.code, 0);
    // The real backend must not have been started.
    assert.equal(existsSync(stdinOut), false);
  }
);
