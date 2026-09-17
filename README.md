# Cuppa

A native Android CUPS print server — no cloud, no WebView, no vendor lock-in. Cuppa turns your
Android phone into a local IPP Everywhere / AirPrint / Mopria-compatible driverless print server,
so any Mac, Windows PC, iPhone, or iPad on the same network can discover and print to a printer
connected to (or reachable from) your phone without installing a driver.

## What it does

- **Driverless network printing (IPP Everywhere / AirPrint / Mopria).** Cuppa hosts a real CUPS-based
  IPP server on your phone and advertises it over mDNS/Bonjour, so it shows up automatically in the
  system print dialog on macOS, Windows, iOS, and Linux — the same way a real network printer would.
- **USB thermal & label printer support.** Native ESC/POS, ZPL, EPL2, and TSPL drivers, calibrated
  against real hardware (e.g. the Rollo X1038), with configurable darkness, print speed, dithering
  algorithm, and print polarity.
- **Network printer passthrough.** Add any existing IPP/AirPrint network printer (e.g. an inkjet or
  laser office printer) to Cuppa's queue, with automatic PDF → PWG-Raster conversion for printers
  without an onboard PDF interpreter.
- **TLS/IPPS.** Optional encrypted printing for devices connecting to Cuppa, using a self-signed
  certificate generated on first use.
- **Persistent job history.** The Jobs tab tracks active and completed/failed print jobs, including
  jobs Cuppa dispatches as a client (Test Print) and jobs relayed from other devices printing to it.
- **Material 3 native UI.** Built entirely in Jetpack Compose — no WebView, no embedded browser.

## Architecture

Cuppa embeds a ported CUPS 2.2.9 client library (`libcups`) compiled for Android via the NDK, plus a
statically-linked OpenSSL build for real TLS support (client and server side). The native layer
handles IPP protocol logic and PDF→PWG-Raster conversion; the Kotlin/Compose layer handles the UI,
mDNS advertising (via Android's `NsdManager`), USB device I/O, and job dispatch.

- `app/` — the Android application: UI, services, printer/job management, thermal drivers' dispatch
  logic, network discovery.
- `cups-core/` — the native CUPS engine module: ported `libcups` C sources, JNI bridge, thermal
  printer command-language encoders (ESC/POS, ZPL, EPL2, TSPL, PCL).

## Requirements

- Android 8.0 (API 26) or later.
- A local network (Wi-Fi) shared with the devices you want to print from.

## Installation

Download the latest release APK from the [Releases](../../releases) page, or track updates
automatically with [Obtainium](https://github.com/ImranR98/Obtainium) by adding this repository as
an app source. Cuppa also includes an in-app updater under Settings that can check for and install
new releases directly.

Since releases aren't distributed through the Play Store, Android will ask you to allow installs
from Cuppa (or your browser/file manager) the first time you install or update it — this is normal
for APKs distributed outside a store.

## Building from source

```bash
git clone https://github.com/modnite/Cuppa.git
cd Cuppa
./gradlew :app:assembleDebug
```

Requires the Android NDK (native CUPS/OpenSSL build) — installed automatically via Gradle if you
have the Android SDK's NDK component available, or install it through Android Studio's SDK Manager.

A release build additionally needs a signing keystore — copy `keystore.properties.example` to
`keystore.properties` and fill in your own keystore details (this file is gitignored).

## License

See individual source headers — the ported CUPS sources retain their original Apache 2.0 licensing
from the AOSP/CUPS project.
