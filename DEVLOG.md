# Development Log

Informal running notes on notable debugging sessions and design decisions. See CHANGELOG.md for
the user-facing summary.

## 2026-09-17 — Getting real printing working end to end

Spent a debugging session tracing "testing a print simply does nothing" down to five separate,
independently-masking bugs, found by adding fine-grained native tracing and testing incrementally
against a real Epson L3250 over Wi-Fi:

1. A `std::mutex` deadlock in `CupsServer` — `getDefaultPrinterName()` self-locks, but was being
   called from a code path that already held the same mutex. Froze the entire server.
2. An unbounded retry loop in `cupsSendRequest()` — a `for(;;)` with no cap on `httpPost()`
   failures, discovered when it hammered the real printer with 1300+ connection attempts/minute.
3. TLS was never actually wired in — `HAVE_SSL` was undefined, so the upgrade-on-demand code path
   was compiled out entirely. Cross-compiled OpenSSL 1.1.1 for all four Android ABIs from a
   Windows/Git-Bash environment with no `make`/`cmake` on PATH (the NDK bundles its own) to fix it.
4. A job-id extraction bug: `ippFindAttribute(..., IPP_TAG_INTEGER)` did an exact tag match that
   failed against the real printer's response, so an actually-successful print reported "spool
   failed."
5. A 15-second HTTP timeout too short for a real ~25MB uncompressed color raster page, which can
   take 15–17 seconds for the printer to fully ingest and respond to.

Once those were fixed, the missing piece was that the Epson has no onboard PDF interpreter — it
accepted PDF print jobs at the IPP layer (200 OK) but silently produced nothing. Built a real
PWG-Raster encoder using CUPS's own raster-writing API and converted before sending whenever the
target printer's supported formats didn't include `application/pdf`. First real color print out of
the app: "IT WORKED!!!"

Follow-up fixes from real-hardware testing: test page margins were clipping (right-edge color
ramps and last-column text), a font-size ladder was rendering multiple sizes identically because
it shrank-to-fit instead of truncating text, and duplicate printer entries were showing up in
discovery because Cuppa's own mDNS re-advertisement of an added printer looked like a second,
not-yet-added printer.

## 2026-09-17 — Jobs tab, TLS, and a false lead on root privileges

Wired up persistent job history and active-job tracking (previously the Jobs tab was a literal
hardcoded "Phase 0" stub). The first attempt had a real bug: `CupsRepository.printFile()` ran on
a coroutine scope tied to the Test Print bottom sheet's composition, so dismissing the sheet
before the ~10s printer round-trip finished silently cancelled the history-recording code even
though the native print had already succeeded. Fixed by moving the actual work onto the
repository's own process-lifetime scope.

Implemented real IPPS/TLS support for Cuppa's own hosted listener (previously only the *outgoing*
client side had working TLS) — self-signed cert via Bouncy Castle, opportunistic upgrade on the
same port using the same 426-then-reconnect pattern real IPP Everywhere printers use.

Chased what looked like a port-privilege issue ("port stuck on 8631 regardless of Settings") that
turned out to have nothing to do with root or Shizuku at all — it was a race between `BootReceiver`
reacting to `MY_PACKAGE_REPLACED` and `MainActivity`'s own auto-resume both starting the server in
the same process, with no guard against a duplicate start. Along the way, confirmed that port 631
actually binds fine on modern Android without root or Shizuku in the first place — most of the
existing "Root Privileges" UI was solving a problem that doesn't exist on real devices, so it was
stripped down to a low-key diagnostic line instead of a prominent call-to-action.

Also found and fixed: six Settings toggles (subnet access restriction, HTTP Basic Auth, mDNS
enable, AirPrint compatibility, and two since-removed toggles) that were UI-only — written to
SharedPreferences but never read anywhere else in the codebase. Wired the real ones up for real,
removed the redundant ones. Same pattern turned up again in Thermal Settings: darkness, print
speed, dither mode, and invert-polarity were all stored but never actually applied to printed
output on either the real dispatch path or Test Print — fixed by threading `ThermalPreferences`
through both.
