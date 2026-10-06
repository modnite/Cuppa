#!/usr/local/bin/node
"use strict";

/**
 * cuppa-usb — a CUPS backend for USB thermal label printers (Rollo X1038 and
 * the Xprinter/Munbyn/Phomemo rebrands).
 *
 * CUPS invokes a backend as: backend job-id user title copies options [file]
 * and passes the device URI in DEVICE_URI, not in argv.
 *
 * Transport (CUPPA_USB_TRANSPORT):
 *   usblp (default) — write straight to the kernel /dev/usb/lpN node, paced.
 *     This is the path proven on the Rollo X1038: the printer accepts a whole
 *     BITMAP job over usblp and fires the head.
 *   libusb — spawn the stock CUPS `usb` backend instead. Kept selectable for
 *     clones that only print through libusb.
 */

const { spawn } = require("node:child_process");
const { once } = require("node:events");
const fs = require("node:fs");
const path = require("node:path");

const REAL_BACKEND = process.env.CUPPA_USB_BACKEND || "/usr/lib/cups/backend/usb";
const TRANSPORT = (process.env.CUPPA_USB_TRANSPORT || "usblp").toLowerCase();
const CHUNK_SIZE = 1024;
const INTER_CHUNK_MS = 10;
const START_DELAY_MS = Number(process.env.CUPPA_USB_START_DELAY_MS ?? 250);
const MAX_JOB_BYTES = 256 * 1024 * 1024;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const jobArgs = process.argv.slice(2);
const uri = process.env.DEVICE_URI || "";

// Device discovery (no DEVICE_URI, or `-l`) and any non-cuppa-usb job is not
// ours: stay silent so the real `usb` backend remains the one that lists
// devices.
if (!uri || uri.startsWith("-") || !/^cuppa-usb:\/\//i.test(uri)) {
  process.exit(0);
}

const realUri = uri.replace(/^cuppa-usb:/i, "usb:");

/** The serial query from a usb:// device URI, if present. */
function serialFromUri(value) {
  const match = /[?&]serial=([^&]+)/i.exec(value);
  return match ? decodeURIComponent(match[1]) : "";
}

/** The /dev/usb/lpN node for this printer, matching on the USB serial if possible. */
function findLpDevice(serial) {
  const base = "/sys/class/usbmisc";
  let names;
  try {
    names = fs
      .readdirSync(base)
      .filter((name) => /^lp\d+$/.test(name))
      .sort();
  } catch {
    return null;
  }

  if (serial) {
    for (const name of names) {
      try {
        let dir = fs.realpathSync(path.join(base, name, "device"));
        for (let level = 0; level < 4; level += 1) {
          const candidate = path.join(dir, "serial");
          if (fs.existsSync(candidate)) {
            if (fs.readFileSync(candidate, "utf8").trim() === serial) return `/dev/usb/${name}`;
            break;
          }
          dir = path.dirname(dir);
        }
      } catch {
        // Keep looking.
      }
    }
  }

  return names.length > 0 ? `/dev/usb/${names[0]}` : null;
}

/** Writes a whole buffer, looping over any short writes. */
async function writeFully(handle, buffer) {
  let offset = 0;
  while (offset < buffer.length) {
    const { bytesWritten } = await handle.write(buffer, offset, buffer.length - offset);
    if (!bytesWritten || bytesWritten <= 0) throw new Error("short write");
    offset += bytesWritten;
  }
}

/** Writes the whole job to the kernel usblp device, paced. */
async function writeKernelDevice(device, data) {
  const handle = await fs.promises.open(device, "w");
  try {
    await sleep(START_DELAY_MS);
    for (let offset = 0; offset < data.length; offset += CHUNK_SIZE) {
      await writeFully(handle, data.subarray(offset, offset + CHUNK_SIZE));
      await sleep(INTER_CHUNK_MS);
    }
  } finally {
    await handle.close().catch(() => undefined);
  }
}

/** Spawns the stock CUPS USB backend and feeds it the job, 1 KiB at a time. */
function runLibusbBackend(data) {
  let backChannel = "ignore";
  try {
    fs.fstatSync(3);
    backChannel = "inherit";
  } catch {
    // No CUPS back channel.
  }

  const child = spawn(REAL_BACKEND, jobArgs, {
    stdio: ["pipe", "inherit", "inherit", backChannel],
    env: { ...process.env, DEVICE_URI: realUri },
  });

  let finished = false;
  const terminate = (signal) => {
    if (finished) return;
    finished = true;
    try {
      child.kill(signal);
    } catch {
      // already gone
    }
  };
  process.on("SIGTERM", () => terminate("SIGTERM"));
  process.on("SIGINT", () => terminate("SIGINT"));

  child.on("error", (error) => {
    process.stderr.write(`ERROR: cuppa-usb cannot start ${REAL_BACKEND}: ${error.message}\n`);
    process.exit(1);
  });
  child.on("exit", (code, signal) => process.exit(signal ? 1 : code ?? 1));

  (async () => {
    try {
      await sleep(START_DELAY_MS);
      for (let offset = 0; offset < data.length; offset += CHUNK_SIZE) {
        const slice = data.subarray(offset, offset + CHUNK_SIZE);
        if (!child.stdin.write(slice)) await once(child.stdin, "drain");
        await sleep(INTER_CHUNK_MS);
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
}

(async () => {
  const chunks = [];
  let total = 0;
  for await (const chunk of process.stdin) {
    chunks.push(chunk);
    total += chunk.length;
    if (total > MAX_JOB_BYTES) {
      process.stderr.write("ERROR: cuppa-usb job exceeds 256 MB\n");
      process.exit(1);
    }
  }
  const data = Buffer.concat(chunks);

  if (TRANSPORT === "usblp") {
    const device = process.env.CUPPA_USB_DEVICE || findLpDevice(serialFromUri(uri));
    if (device && fs.existsSync(device)) {
      try {
        await writeKernelDevice(device, data);
        process.stderr.write(`DEBUG: cuppa-usb wrote ${data.length} bytes to ${device}\n`);
        process.exit(0);
      } catch (error) {
        process.stderr.write(
          `WARNING: cuppa-usb kernel write to ${device} failed (${error.message}); using libusb\n`
        );
      }
    } else {
      process.stderr.write("WARNING: cuppa-usb found no usblp device; using libusb\n");
    }
  }

  runLibusbBackend(data);
})();
