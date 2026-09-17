# Changelog

All notable changes to Cuppa are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.5.1] — 2026-09-17

### Fixed
- Battery optimization exemption status not refreshing until a second tap — the "Exempt" button
  re-checked the system status synchronously right after firing the (asynchronous) system intent,
  before the dialog had even appeared, and separately `PowerManager.isIgnoringBatteryOptimizations()`
  can lag briefly behind the dialog's own confirmation on some devices' battery management layers.
- Notification permission status on the Dashboard not refreshing after granting it via the
  app-launch prompt (it was only ever updated by a separate, unrelated request path).

### Changed
- Consolidated permission granting to one place: both notification and battery-optimization
  exemption are now requested automatically at app launch, and Settings is the only remaining
  place with grant buttons/status — removed the Dashboard's duplicate (and, as above,
  independently buggy) notification/battery banner sections.
- Replaced the large, mostly-empty collapsing app bar on all four tabs with a compact one, since
  none of the screens used the extra header space for anything beyond a plain title.

## [0.5.0] — 2026-09-17

### Added
- GitHub Actions workflow to build and publish a signed release APK on version tags.
- In-app update checker (manual + automatic) that reads GitHub Releases directly, so the app can
  be kept current without the Play Store — also makes releases trackable via Obtainium.

## [0.4.0]

### Added
- Real TLS/IPPS support for Cuppa's hosted IPP listener: self-signed certificate generated on
  first use, opportunistic upgrade on the same port (plaintext requests get `426 Upgrade
  Required`, matching how real IPP Everywhere printers negotiate encryption).
- HTTP Basic Auth and subnet/localhost access restriction as real, enforced settings (previously
  present in the UI but not actually wired to the server).
- Persistent Jobs tab: active jobs and job history (completed/failed) now survive process
  restarts and are recorded for both incoming jobs (relayed to a printer) and outgoing jobs
  (Test Print, dispatched directly to a target printer).
- Thermal print settings (darkness, print speed, dithering algorithm, print polarity) now
  actually apply to printed output, for both real dispatched jobs and Test Print.
- PDF → PWG-Raster conversion for network printers without an onboard PDF interpreter.

### Fixed
- Deadlock in the native CUPS server caused by non-reentrant mutex re-locking.
- Unbounded connection retry loop that could hammer a target printer with 1000+ connection
  attempts per minute under certain failure conditions.
- Print job "spool failed" false negative caused by an overly strict IPP attribute tag match.
- HTTP timeout too short for large uncompressed color raster print jobs.
- Duplicate/"self-reflected" printer entries in network discovery (Cuppa's own mDNS
  advertisement of an added printer showing up as a separate discoverable printer).
- Print server occasionally falling back to an alternate port due to a race between two
  independent startup triggers (boot receiver + app resume) both starting the server
  simultaneously.
- Standard test page layout: color/CMYK ramp clipping past the right margin, and a font-size
  ladder that rendered multiple sizes identically due to auto-shrink-to-fit.

## Earlier versions

Detailed change history prior to 0.4.0 was not tracked. The project has supported driverless IPP
Everywhere/AirPrint network printing and USB thermal/label printing (ESC/POS, ZPL, EPL2, TSPL)
since early development.
