# Changelog

Notable changes to Cuppa, newest first. Loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.5.5] - 2026-09-17

### Changed
- Release builds now only bundle the two ABIs a real phone can use (arm64-v8a, armeabi-v7a)
  instead of all four. Cuts the APK roughly in half. Debug builds still get all four so the
  emulator keeps working.
- Removed an unused `lifecycle-service` dependency.
- Removed 8 unused string resources.
- The "Bundled" label on built-in drivers in Driver Management no longer looks like a button
  that does nothing when tapped.

## [0.5.4] - 2026-09-17

### Changed
- About Cuppa now links straight to the GitHub repo.
- Cleaned device/root/Shizuku diagnostics out of the About dialog. That info still lives in
  Logging & Diagnostics, where it's actually useful.

## [0.5.3] - 2026-09-17

### Changed
- New app icon. Printer and coffee cup artwork, drawn by me, replaces the old placeholder icon.
- Added real screenshots to the README.

## [0.5.2] - 2026-09-17

### Fixed
- Install button in the update dialog stayed disabled after granting the install-unknown-apps
  permission until I closed and reopened the dialog. Same root cause as the 0.5.1 permission
  fixes. No resume-driven refresh for a system setting with no result callback.

## [0.5.1] - 2026-09-17

### Fixed
- Battery optimization exemption status didn't refresh until a second tap. The "Exempt" button
  re-checked the system status right after firing the system intent. That intent is asynchronous
  so the check ran before the dialog even appeared. Separately,
  `PowerManager.isIgnoringBatteryOptimizations()` can lag briefly behind the dialog's own
  confirmation on some devices' battery management layers.
- Notification permission status on the Dashboard didn't refresh after granting it through the
  app-launch prompt. It was only ever updated by a separate unrelated request path.

### Changed
- Consolidated permission granting to one place. Both notification and battery-optimization
  exemption now get requested automatically at app launch. Settings is the only place left with
  grant buttons and status. Removed the Dashboard's duplicate (and independently buggy)
  notification and battery banner sections.
- Replaced the large mostly-empty collapsing app bar on all four tabs with a compact one. None of
  the screens used the extra header space for anything beyond a plain title.

## [0.5.0] - 2026-09-17

### Added
- GitHub Actions workflow that builds and publishes a signed release APK on version tags.
- In-app update checker, manual and automatic, that reads GitHub Releases directly. Keeps the app
  current without the Play Store and makes releases trackable through Obtainium.

## [0.4.0]

### Added
- Real TLS and IPPS support for Cuppa's hosted IPP listener. Self-signed certificate generated on
  first use. Opportunistic upgrade on the same port, plaintext requests get a `426 Upgrade
  Required`, matching how real IPP Everywhere printers negotiate encryption.
- HTTP Basic Auth and subnet or localhost access restriction as real enforced settings. Previously
  present in the UI but never wired to the server.
- Persistent Jobs tab. Active jobs and job history now survive process restarts and get recorded
  for both incoming jobs relayed to a printer and outgoing jobs like Test Print.
- Thermal print settings (darkness, print speed, dithering algorithm, print polarity) now
  actually apply to printed output, for real dispatched jobs and Test Print alike.
- PDF to PWG-Raster conversion for network printers without an onboard PDF interpreter.

### Fixed
- Deadlock in the native CUPS server caused by non-reentrant mutex re-locking.
- Unbounded connection retry loop that could hammer a target printer with over a thousand
  connection attempts a minute under certain failure conditions.
- Print job "spool failed" false negative caused by an overly strict IPP attribute tag match.
- HTTP timeout too short for large uncompressed color raster print jobs.
- Duplicate "self-reflected" printer entries in network discovery. Cuppa's own mDNS
  re-advertisement of an added printer showed up as a separate discoverable printer.
- Print server occasionally fell back to an alternate port because of a race between two
  independent startup triggers (boot receiver and app resume) both starting the server at once.
- Standard test page layout: color and CMYK ramps clipped past the right margin, and a font-size
  ladder rendered multiple sizes identically because it shrank to fit instead of truncating text.

## Earlier versions

I didn't track detailed change history before 0.4.0. Driverless IPP Everywhere and AirPrint
network printing, plus USB thermal and label printing (ESC/POS, ZPL, EPL2, TSPL), have been
supported since early development.
