# Cuppa Webapp — Read-Only Audit

**Scope:** `webapp/server/src`, `webapp/web/src`, `webapp/docker`, `webapp/Dockerfile`
(plus the compose files and `README.md` for cross-checking). Paths below are relative to
`C:\Users\c\projects\Cuppa`.

**Method:** static reading of every source file, plus targeted greps for stubs, swallowed
errors and unused symbols. No source file was modified. `server/dist` and `web/dist` are
untracked build output (gitignored) and are ignored except as a staleness note.

---

## 0. Executive summary

The webapp is a thin, opinionated control plane over a real CUPS scheduler. The
add/rename/remove/share/default/location/enable/accept path is genuinely wired up, and the
thermal subsystem is unusually complete. The weak points are almost all in the same class
this project has been bitten by before: **a failure that is caught, logged at `debug`, and
then reported to the UI as success** — so the user sees "done" while nothing happened.
The advertising path is the most fragile area, because it contains a one-way latch and a
"content unchanged" early-return that together can suppress a retry.

---

## 1. STUBS / PLACEHOLDERS

No literal "coming soon"/"not implemented" strings exist. The stubs are silent no-ops,
canned returns, and fallbacks that claim success.

### Server

- `webapp/server/src/advertise.ts:16-19` — `refreshAdvertisements` catches every error,
  logs at `debug`, returns `false`. Callers that ignore the boolean (all routes) treat the
  refresh as done.
- `webapp/server/src/cups.ts:148-151` — `getDefaultQueue` catch returns `""`; a CUPS hiccup
  silently makes every printer non-default.
- `webapp/server/src/cups.ts:583-585` — `migrateThermalUsbQueues` catch returns without
  migrating; the caller marks migration complete anyway (see §6).
- `webapp/server/src/cups.ts:895` — `child.on("error", () => undefined)`; an `avahi-browse`
  spawn failure (missing binary, no D-Bus) is a complete no-op.
- `webapp/server/src/cups.ts:992-998` — error-log reader fallback `"(no /var/log/cups/error_log)"`.
- `webapp/server/src/cups.ts:1095-1097` — `findUsbPrintDevice` catch returns `null`.
- `webapp/server/src/thermal/config.ts:51-59` — `loadThermalConfig` catch returns `null`; a
  corrupt/unreadable per-queue JSON silently falls back to `defaultThermalConfig()` (TSPL).
- `webapp/server/src/thermal/testpage.ts:26-56` — `rasterizeTestPage` returns `null` on any
  failure; `generateThermalTestLabel:82` then falls back to the text TSPL label the Rollo
  silently ignores.
- `webapp/server/src/thermal/filter.ts:38-47` — `maybeGunzip` catch returns the still-gzipped
  buffer, which later fails mime detection.
- `webapp/server/src/thermal/drivers/escpos.ts:49-52` — `setUnderline` is implemented but
  never called by any code path (canned API).
- `webapp/server/src/thermal/drivers/escpos.ts:154-157` — `openCashDrawer` implemented, never
  called.
- `webapp/server/src/mdns.ts:120-129` — `useInternalResponder` is a one-shot latch; once set
  there is no path back to host Avahi for the process lifetime.
- `webapp/server/src/mdns-internal.ts:107-119` — `readQuestions` returns `[]` for anything
  that is not a query; malformed/response packets are ignored (intended, but silent).
- `webapp/server/src/mdns-internal.ts:165-171` — `announce` returns silently when there is no
  socket or no services.
- `webapp/server/src/store.ts:33-46` — `load` catch logs and starts from defaults; a corrupt
  `cuppa.json` silently discards the admin password and all printer metadata.
- `webapp/server/src/index.ts:66-69` — "No built web UI found" canned JSON at `/`.
- `webapp/server/src/index.ts:112-114` — the first-publish `kick` gives up after 20 attempts
  and never retries beyond the 60 s timer.

### Front end

- `webapp/web/src/pages/Settings.tsx:10-15` — `DEFAULT_SETTINGS` are displayed before/if the
  real settings never load; the retry loop (`:44-60`) stops after 20 tries and leaves the
  canned defaults on screen with no error.
- `webapp/web/src/api.ts:44-48` — non-JSON responses silently become `{}`.
- `webapp/web/src/theme.ts:29-49` — `loadThemePrefs`/`saveThemePrefs` catch and fall back /
  no-op (storage unavailable).
- Empty `catch {}` blocks that intentionally discard errors: `pages/Printers.tsx:40`,
  `pages/Dashboard.tsx:31`, `pages/Jobs.tsx:49`, `pages/Settings.tsx:37,51`,
  `components/AddPrinterDialog.tsx:102`, `App.tsx:54`.

---

## 2. DEAD CODE

### Unused exports / symbols

- `webapp/server/src/env.ts:22` — `avahiServicesDir` (`CUPPA_AVAHI_DIR`) is never read. It is
  a leftover from when Cuppa wrote static Avahi service files. `docker/avahi-daemon.conf:5`
  still documents that mechanism, which no longer exists.
- `webapp/server/src/network.ts:24-26` — `systemUptimeSeconds` is unused; `index.ts:110` uses
  `process.uptime()` directly.
- `webapp/server/src/ipp.ts:348-351` — `firstValue` is exported but never used.
- `webapp/server/src/ipp.ts:21` — `CUPS_GET_JOBS: 0x400a` is unused (the code deliberately
  uses `GET_JOBS`).
- `webapp/server/src/cups.ts:1474-1476` — `localQueueName` is exported but never called.
- `webapp/server/src/types.ts:98-102` — `AppInfo` interface is unused.
- `webapp/server/src/thermal/raster.ts:11` — `DITHER_MODES` is unused.
- `webapp/server/src/thermal/config.ts:12` — `THERMAL_DIALECTS` is unused server-side (the web
  app has its own copy in `web/src/thermal.ts`).
- `webapp/server/src/thermal/drivers/zpl.ts:100-102` — `drawVerticalLine` is unused.
- `webapp/server/src/thermal/drivers/zpl.ts:37` — the `dpi` constructor field is never read.
- `webapp/web/src/components/Icons.tsx:138` — `ChevronRightIcon` is unused.
- `webapp/web/src/components/Icons.tsx:173-179` — `LogoutIcon` is unused (and there is **no
  logout button anywhere in the UI**, see §5).
- `webapp/web/src/api.ts:60` — `api.logout` is defined but never called.

### Dead / unreachable branches

- `webapp/server/src/mdns.ts:36-38` — in `documentFormats`, `chosen.unshift("application/pdf")`
  guarantees `chosen.length >= 1`, so the following `if (chosen.length === 0)` branch is
  unreachable.
- `webapp/server/src/ipp.ts:99-106` and `:107-113` — the `resolution` and `rangeOfInteger`
  encoders in `encodeValue` are never reachable: no caller ever builds an attribute with
  those tags (they are only decoded).
- `webapp/server/src/cups.ts:165-168` — `const records = printerGroupRecords;` is a redundant
  alias.
- `webapp/server/src/ipp.ts:215-222` — collection / textWithLanguage decode paths are only
  reachable if CUPS returns those tags; nothing requests them, so they are effectively dead
  for the current attribute sets.
- Many `IPP_TAG` members (`octetString`, `uriScheme`, `memberName`, `resolution`, `rangeOfInteger`,
  `textWithLanguage`, `nameWithLanguage`, `collection`) are declared and only partly exercised.

### Build artefacts

- `webapp/server/dist/**` and `webapp/web/dist/**` exist in the working tree but are
  gitignored (`.gitignore:3`). They are stale local build output; the Dockerfile rebuilds both
  from source, so they are harmless but misleading if read.

---

## 3. ERROR HANDLING

The recurring failure mode: **an operation is reported as successful even though its side
effect was not persisted or delivered.**

### High-impact silent failures

- `webapp/server/src/store.ts:48-59` — `persist()` writes asynchronously (150 ms debounce) and
  its `catch` only logs. `updateSettings` (`:66-70`), `setMeta` (`:80-83`) and `patchMeta`
  (`:85-92`) return the new value regardless. So a read-only/full `/data`, or a process exit
  within 150 ms, silently loses settings, printer names, **and the admin password**
  (`auth.ts:21-31` → `store.updateSettings`). The UI shows the change as saved.
- `webapp/server/src/cups.ts:577-593` — `migrateThermalUsbQueues` swallows the `listPrinters`
  failure (`:581-585`) and returns; `index.ts:107-110` still sets `migrated = true`, so
  pre-existing thermal USB queues are **never** retried and keep the unpaced `usb://` URI
  that the Rollo drops.
- `webapp/server/src/cups.ts:495-498` — `ensureEnabled` calls `run` (not `runOrThrow`) and
  ignores both `cupsenable` and `cupsaccept` failures. `printFile` then submits anyway
  (`:501-505`), so a queue that cannot be enabled silently holds the job as pending.
- `webapp/server/src/thermal/config.ts:51-59` — a corrupt config yields `null` → defaults
  (TSPL). The filter (`filter.ts:163`) and the test label (`testpage.ts`) then silently use
  the wrong dialect.
- `webapp/server/src/thermal/config.ts:76-84` — `listThermalQueues` catch returns `[]`, so a
  permission problem on `/data/thermal` silently makes every thermal queue look non-thermal.
- `webapp/server/src/thermal/testpage.ts:51-53` — `rasterizeTestPage` returns `null`; the
  text-label fallback (`:82`) prints nothing on TSPL-only hardware, so "Test print" reports
  success while nothing comes out.

### Swallowed errors (lower impact, but same class)

- `webapp/server/src/advertise.ts:16-19` — every advertisement error is `debug`-level.
- `webapp/server/src/cups.ts:148-151` — default-queue lookup failure.
- `webapp/server/src/cups.ts:583-585`, `:992-998`, `:1095-1097` — as above.
- `webapp/server/src/cups.ts:371-376, 422-428, 443-449, 530-536, 563-569` — temp-file `unlink`
  best-effort (acceptable).
- `webapp/server/src/cups.ts:895` — `avahi-browse` spawn error discarded, so discovery can be
  permanently empty with no visible cause.
- `webapp/server/src/exec.ts:44-50` — `run` converts any non-zero exit into a return value and
  a `warn`; callers that only read `stdout` silently operate on partial output.
- `webapp/server/src/mdns-internal.ts:196-198, 222-224` — packet-parse and socket-close catches.
- `webapp/server/src/thermal/filter.ts:42-44` — corrupt gzip passed through.
- `webapp/server/src/store.ts:42-45` — corrupt store resets to defaults (loses data silently).

### Error surface / auth

- `webapp/server/src/index.ts:74-78` — the global error handler returns `error.message`
  verbatim, which can leak filesystem paths and command output to the browser.
- `webapp/server/src/routes.ts:342-352` — `setPassword` accepts any string including `""`
  (which disables auth) with no length/confirmation; success is reported even if the store
  write silently failed.
- `webapp/server/src/routes.ts:186-201` and `:254-272` — `request.file()` is called outside the
  `try`; a missing/!multipart body throws into the global handler as a 500 rather than the
  intended 400.

### Front-end

- `webapp/web/src/App.tsx:33-45` — a 401 flips `authenticated=false`; every other failure
  (including a 500 that is not transient) only flips `online=false` with no detail.
- `webapp/web/src/pages/Settings.tsx:44-60` — after 20 failed loads the toggles keep showing
  `DEFAULT_SETTINGS`, which may not match the server.

---

## 4. WHERE EVERYTHING STANDS

Legend: **complete** = works end to end and is surfaced; **partial** = works for the common
case with gaps; **fragile** = works but can silently fail or depends on environment.

### Printer management
- **Add** — `routes.ts:118-146` → `cups.ts:274-411`. Partial/fragile: driver auto-detection
  and thermal branches are elaborate, but the raw-queue outcome is only a `log.warn`
  (`cups.ts:392-394`), and a failed `driverless` build is detected only by checking
  `/etc/cups/ppd/<queue>.ppd` (`cups.ts:383-391`).
- **Rename** — `cups.ts:453-459` + `store.patchMeta`. Complete.
- **Remove** — `cups.ts:471-476`. Complete.
- **Share on/off** — `cups.ts:466-469`. Complete.
- **Default** — `cups.ts:478-480`. Complete.
- **Location** — `cups.ts:461-464`. Complete.
- **Enable / accept** — `cups.ts:482-488`. Complete, though `enabled` is inferred as
  `state !== 5` (`cups.ts:197`), conflating "stopped" with "disabled".
- **Classes** — absent.

### Drivers
- **IPP Everywhere via `driverless`** — `cups.ts:261-271` (probe) and `:319-335` (PPD build).
  Partial/fragile: up to 5 candidate paths × 30 s probe (`:246-253`, `:267`) plus a 45 s build,
  with no HTTP request timeout (`index.ts:42`). Works, but can look like a hang.
- **brlaser auto-selection** — `cups.ts:299-305`. Partial: only triggers when the queue fell
  back to `raw` **and** `makeAndModel` matches `/brother/i`; a Brother added by IPP URI never
  hits this.
- **PPD upload** — `cups.ts:314-317, 344-348, 414-431` and `routes.ts:186-201`. Complete,
  validated by `looksLikePpd` (`cups.ts:234-236`).
- **Thermal dialects** — see below. Complete-ish.

### Jobs
- **List** — `cups.ts:596-643`, `routes.ts:293-297`. Complete (active/history/all).
- **Cancel** — `cups.ts:645-647`. Complete.
- **Hold / release / reprint / reprioritise / move** — absent. `jobStateLabel` knows the
  "Held" state (`cups.ts:93`) but nothing can set it.

### Thermal subsystem
- **Filter** — `thermal/filter.ts`. Partial/fragile: `pdftoppm`/`gs` via `execFileSync`
  (`:66-96`), PDF/PS/PGM/PBM input, copies loop (`:183-186`). Silent gzip and config fallbacks
  (above). Runs as the CUPS filter user against `/data/thermal`.
- **PPD** — `thermal/ppd.ts`. Complete: routes PDF/PS through `cuppa-thermal` via
  `*cupsFilter2` (`:66-67`).
- **Dialects** — TSPL (`tspl.ts`), ZPL (`drivers/zpl.ts`), EPL (`drivers/epl.ts`),
  ESC/POS (`drivers/escpos.ts`), PCL (`drivers/pcl.ts`). Complete, unit-tested
  (`test/thermal-drivers.test.ts`).
- **USB pacing** — `docker/cuppa-usb.js` (usblp, 1 KiB / 10 ms) and the `cuppa-usb://` URI
  rewrite (`cups.ts:310-312`). Fragile: depends on `/dev/usb/lpN` existing and on
  `/sys/class/usbmisc` layout; if the node is missing it falls back to the stock libusb
  backend that wedges these printers (`cuppa-usb.js:187-189`, `:192`). With more than one USB
  printer, `findLpDevice` (`:52-83`) returns the first `lpN` when the serial cannot be matched.
- **Self-test** — `cups.ts:1107-1206`. Complete and well-ordered (kernel paths first).
- **Migration of legacy `usb://` thermal queues** — `cups.ts:577-593`. Fragile (one-shot, see §3/§6).

### Discovery
- **mDNS streaming** — `cups.ts:871-898` (`avahi-browse -rp`) folded into a cache. Partial:
  spawn errors are swallowed (`:895`) and the process restarts every 3 s forever.
- **Periodic CUPS scan** — `cups.ts:800-806`, `:905-909`. Complete.
- **Subnet sweep** — `cups.ts:815-852`. Partial: /24 only, skips if `primaryIPv4` is 127.x,
  48 workers × 800 ms TCP probe; runs on a 120 s timer and on every `fresh` discover.
- **USB devices** — via `lpinfo -l -v` (`cups.ts:711-756`, `:802`). Complete.
- **Cache/stickiness** — `CACHE_MS=3000`, `STICKY_MS=300000` (`cups.ts:763-766`). Complete.

### Advertising (host Avahi vs built-in responder)
- `mdns.ts` publishes via `avahi-publish-service` in host mode (`:131-174`) and via the
  built-in UDP responder (`mdns-internal.ts`) otherwise. `preferInternal` at `mdns.ts:185`
  selects: internal when `usingInternal || avahiMode !== "host" || mdnsMode === "internal"`.
  **Fragile:** the `usingInternal` latch is one-way (`:121-129`), and the "content unchanged"
  early-return (`:181-183`) means a failed first publish is only rescued by the child-exit
  handler, not by the periodic refresh.
- TXT records mirror the Android advertiser (`mdns.ts:45-71`); `pdl` always claims
  `application/pdf` (`:36`) even for a raw queue.

### Auth
- Single admin password, scrypt, in-memory sessions (`auth.ts`). Partial: sessions are lost on
  restart; no user accounts; no logout in the UI (`api.logout` and `LogoutIcon` are dead);
  no CSRF token (mitigated by `sameSite: "lax"`).

### Diagnostics
- `cups.ts:987-1059` collects ~17 sections; `routes.ts:280-290`; UI `pages/Diagnostics.tsx`.
  Complete-ish, but it is raw text plus a network/USB probe, and loading the page calls
  `discoverDevices(true)` (`cups.ts:1039`) which triggers a full subnet sweep every time.

---

## 5. GAPS vs a full CUPS control panel

- **Printer classes** — none (no add/remove class, no class membership).
- **PPD options** — `lpoptions -l` is *shown* (`cups.ts:1025-1028`) but cannot be *set*; no
  media size, resolution, duplex, tray, or `-o` editing UI.
- **Job hold / release / reprint / move / priority** — none.
- **Supplies** — `marker-levels` are read and shown only inside Diagnostics probes
  (`cups.ts:1365-1374`, `Diagnostics.tsx:70-88`); not on the Printers page, no low-supply alert.
- **Logs UI** — only a 200-line tail of `error_log` as plain text (`cups.ts:992-998`); no
  job log, no page log, no filtering.
- **Access control** — one shared admin password; no per-user accounts, roles, or CUPS
  `SystemGroup`/policy management. Meanwhile CUPS itself allows the whole LAN unauthenticated
  access (`docker/cupsd.conf:27-31`), so the CUPS web UI on port 631 is reachable by any host
  on the subnet.
- **Server settings** — `cupsd.conf` is displayed read-only (`cups.ts:1029`); no edit, no
  restart-cupsd, no max-jobs/quota/banner-page config.
- **Certificate management** — the `tlsEnabled` toggle only adds `_ipps._tcp`
  (`mdns.ts:86-94`); nothing verifies CUPS actually serves TLS, and there is no cert UI.
- **Browsing / ipp-usb** — `cups-browsed` and `ipp-usb` are neither installed nor managed
  (the process list even greps for `ipp-usb`, `cups.ts:1030-1033`).
- **Print-from-anywhere / share links** — none.
- **Logout** — absent from the UI.

---

## 6. REGRESSION RISKS

Anything here could silently break discovery, advertising or printing.

1. **One-way internal-responder latch + conflicting responders.**
   `mdns.ts:120-129` never returns to host Avahi. On a host that *does* run Avahi, a transient
   `avahi-publish-service` failure at boot permanently starts Cuppa's own responder on the same
   port 5353 — the exact "two responders on one host fight" scenario the comments warn about
   (`env.ts:27-32`).
2. **`syncAdvertisements` early-return on unchanged content.**
   `mdns.ts:181-183` returns before re-publishing if the service list is byte-identical. A
   first publish that failed for a reason other than a child `exit`/`error` event is not retried
   by the 60 s timer.
3. **`mdnsAdvertiser.update()` before the socket is bound.**
   `syncAdvertisements` calls `start()` then `update()` (`mdns.ts:187-188`); `start()`
   (`mdns-internal.ts:179-212`) sets `this.socket` synchronously but binds asynchronously, and
   `update()` immediately calls `announce()` (`:174-177`). A `send` before the bind completes
   can throw; the throw is swallowed by `refreshAdvertisements`, which reports `false`.
4. **One-shot thermal-USB migration that fails silently.**
   `index.ts:107-110` sets `migrated = true` on the first successful advertisement even when
   `migrateThermalUsbQueues` bailed at `cups.ts:583-585`. Legacy queues keep the unpaced
   `usb://` URI → the Rollo ACKs and drops the label.
5. **Silent store-persistence failure.**
   `store.ts:48-59` reports success to the UI while the write may have failed; the admin
   password set in `routes.ts:342-352` can be silently lost on the next restart.
6. **Thermal config unreadable → wrong dialect.**
   `config.ts:51-59` returns `null`, and the filter/testpage fall back to TSPL. If the CUPS
   filter user cannot read `/data/thermal` (root-owned volume), labels silently come out in the
   wrong command language.
7. **Raw-queue PDF passthrough.**
   `cups.ts:392-394` only warns; `addPrinter` can leave a `raw` queue that prints blank pages —
   the exact Brother failure the project already fought.
8. **Thermal test-print text fallback.**
   `testpage.ts:82` returns a text TSPL label when rasterisation fails; the Rollo silently
   ignores it, so "Test print" appears to succeed while nothing prints.
9. **Discovery can be empty with no error.**
   `cups.ts:895` discards `avahi-browse` errors, and `mdns-internal.ts` socket errors are only
   `warn`ed (`:186-189`). A missing D-Bus/binary yields an empty Add-printer list with no
   visible cause.
10. **Advertising `pdl` over-claims.**
    `mdns.ts:36` always prepends `application/pdf` to `pdl`, so clients send PDF even to a raw
    queue (compounding risk 7).
11. **`tlsEnabled` without verifying TLS.**
    `mdns.ts:86-94` advertises `_ipps._tcp` on the strength of a boolean; if CUPS has no usable
    cert, IPPS clients fail to connect.
12. **Multiple USB printers → wrong device.**
    `cuppa-usb.js:52-83` returns the first `lpN` when the serial cannot be matched, so a job
    can be written to the wrong printer.
13. **Long, unbounded add-printer request.**
    `cups.ts:267, 326` (30 s × up to 5 + 45 s) with `requestTimeout: 0` (`index.ts:42`); a
    reverse proxy in front of Cuppa may 504 while the backend is still probing.
14. **Diagnostics triggers a full sweep every load.**
    `cups.ts:1039` (`discoverDevices(true)`) → `scanAll` → `sweepSubnet` (`:862`), up to ~5 s+
    per page load, on top of the separate `networkDiagnostics` and `discover` calls in
    `Diagnostics.tsx:166-171`.
15. **Avahi resource limits.**
    `docker/avahi-daemon.conf:22-28` sets `rlimit-data=4194304` and `rlimit-nproc=3`, which can
    be too tight on busy NAS hosts and make Avahi die/restart (silently, via supervisord).
16. **CUPS exposed unauthenticated to the LAN.**
    `docker/cupsd.conf:27-31` (`<Location /> Allow @LOCAL`) plus `WebInterface Yes` (`:18`)
    means any subnet host can reach CUPS' own web UI/API on 631 without the Cuppa password.

---

## 7. TOP 10 FINDINGS (by severity)

1. **Silent store write failures** — `store.ts:48-59`; settings/printer names/admin password
   can be lost while the UI says "saved".
2. **One-way mDNS internal-responder latch** — `mdns.ts:120-129`; a transient host-Avahi
   failure permanently starts a second responder on port 5353.
3. **Advertisement refresh early-return** — `mdns.ts:181-183`; a failed first publish may never
   be retried by the periodic timer.
4. **One-shot thermal-USB migration that swallows its failure** — `index.ts:107-110` +
   `cups.ts:577-585`; legacy `usb://` queues silently keep dropping labels.
5. **`ensureEnabled` ignores its failures then submits anyway** — `cups.ts:495-505`; jobs stay
   pending with no error.
6. **Thermal config read failure silently changes the dialect** — `config.ts:51-59` +
   `filter.ts:163`; wrong command language, no error.
7. **Discovery errors swallowed / `avahi-browse` restart loop** — `cups.ts:895`,
   `mdns-internal.ts:186-189`; an empty printer list with no visible cause.
8. **Raw-queue PDF passthrough is only a warning** — `cups.ts:392-394`; blank pages, the
   project's known Brother failure.
9. **Thermal test-print text fallback is ignored by TSPL hardware** — `testpage.ts:82`;
   "Test print" reports success while nothing prints.
10. **No logout in the UI / dead auth affordances** — `api.ts:60`, `Icons.tsx:173`; once
    logged in there is no way to end the session from the app.

---

*End of audit. No source or configuration files were modified; this document is the only
artefact produced.*
