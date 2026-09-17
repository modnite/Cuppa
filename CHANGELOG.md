# Changelog

All notable changes to Cuppa are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

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
