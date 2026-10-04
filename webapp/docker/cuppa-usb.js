#!/usr/local/bin/node
"use strict";

/**
 * cuppa-usb — a CUPS backend that paces USB bulk writes for thermal label
 * printers (the Rollo X1038 in particular).
 *
 * The Rollo's firmware ACKs a large, unpaced bulk write and then silently
 * drops it, so the print head never fires even though CUPS reports the job as
 * completed. The Android app works around this by sending 1 KiB at a time with
 * a 10 ms pause; this backend does the same.
 *
 * It is installed as /usr/lib/cups/backend/cuppa-usb, so a queue created with
 * a `cuppa-usb://...` device URI routes through here. The URI is rewritten to
 * `usb://...` and the real CUPS USB backend is run as a child: because we only
 * feed the child 1 KiB at a time, it can only write 1 KiB at a time.
 */

const { spawn } = require("node:child_process");
const { once } = require("node:events");

const REAL_BACKEND = process.env.CUPPA_USB_BACKEND || "/usr/lib/cups/backend/usb";
const CHUNK_SIZE = 1024;
const INTER_CHUNK_MS = 10;
// Give the child backend time to open the device and block on its first read,
// otherwise the first few chunks queue up and it consumes them in one big
// unpaced write (which is exactly what the printer drops).
const START_DELAY_MS = Number(process.env.CUPPA_USB_START_DELAY_MS ?? 500);

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const argv = process.argv.slice(2);
const uri = argv[0] || "";

// Device discovery (no URI, or `-l`) and any non-cuppa-usb job is not ours:
// stay silent so the real `usb` backend remains the one that lists devices.
if (!uri || uri.startsWith("-") || !/^cuppa-usb:\/\//i.test(uri)) {
  process.exit(0);
}

const realUri = uri.replace(/^cuppa-usb:/i, "usb:");

const child = spawn(REAL_BACKEND, [realUri, ...argv.slice(1)], {
  // fd 3 (the CUPS back channel) is inherited so the real backend keeps working.
  stdio: ["pipe", "inherit", "inherit", "inherit"],
});

let finished = false;
function terminate(signal) {
  if (finished) return;
  finished = true;
  try {
    child.kill(signal);
  } catch {
    // already gone
  }
}
process.on("SIGTERM", () => terminate("SIGTERM"));
process.on("SIGINT", () => terminate("SIGINT"));

child.on("error", (error) => {
  process.stderr.write(`cuppa-usb: cannot start ${REAL_BACKEND}: ${error.message}\n`);
  process.exit(1);
});

child.on("exit", (code, signal) => {
  process.exit(signal ? 1 : code ?? 1);
});

(async () => {
  try {
    await sleep(START_DELAY_MS);
    for await (const chunk of process.stdin) {
      for (let offset = 0; offset < chunk.length; offset += CHUNK_SIZE) {
        const slice = chunk.subarray(offset, offset + CHUNK_SIZE);
        if (!child.stdin.write(slice)) await once(child.stdin, "drain");
        await sleep(INTER_CHUNK_MS);
      }
    }
    child.stdin.end();
  } catch {
    try {
      child.stdin.destroy();
    } catch {
      // best effort
    }
  }
})();
