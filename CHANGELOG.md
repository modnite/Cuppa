# Changelog

Notable changes to Cuppa, newest first. Loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- The webapp has light/dark/system themes and a choice of accent colours.
- Cuppa now uses the host's Avahi when the NAS already runs one (OpenMediaVault,
  Synology), so printer discovery and advertising work there; on a host with no
  Avahi it still runs its own.
- A **Diagnostics** page in the webapp. It shows the exact device URI of every
  queue, the CUPS error log, USB devices and their driver tree, the PPD options
  each queue exposes, the running services, the mDNS/Avahi sockets and the active
  cupsd configuration, each with a copy button.
- **Network printer diagnostics.** Every network queue is contacted directly: a
  TCP reachability check, then a live IPP query for its state, state reasons,
  model and supply levels. Any discovered or manually entered address can be
  probed the same way, so wireless printers Cuppa is not yet printing to can be
  diagnosed too.
- Network discovery also browses mDNS directly with `avahi-browse`. That works
  when the container borrows the host's Avahi even though CUPS' own
  dnssd/driverless backend does not, so IPP printers CUPS could not see (a
  Brother MFC-L2717DW, for example) now appear with a direct IPP address.
- A printer can be added with a **user-supplied PPD**, or an existing queue can
  have one installed, instead of choosing from the driver list.
- The webapp shows its own version in Settings → About and the sidebar, baked in
  from the image tag at build time, so it is obvious which build is running.
- A one-click **USB self-test**. It sends a diagnostic label straight to a USB
  printer through the stock backend, the paced Cuppa backend and the kernel
  device node, bypassing the scheduler, so a broken queue can be told apart from
  a printer that cannot be driven over USB at all.
- The webapp bundles the OpenPrinting driver set — Gutenprint, hpcups, SpliX,
  Epson ESC/P-R, foo2zjs, P-touch, DYMO, C2ESP, PXLJR, SAG-GDI, OKI and the
  foomatic PPD collection — alongside brlaser, so far more USB and network
  printers can be driven locally instead of falling back to a raw queue.
- All five thermal command languages (TSPL, ZPL II, EPL2, ESC/POS, PCL 5) can
  now be chosen in the webapp when adding or editing a thermal printer; the
  dialect was previously fixed to TSPL in the UI.

### Changed
- The Cuppa mark — sidebar, login screen and browser tab — now takes its
  background colour from the selected accent instead of a fixed teal.
- Printer discovery is live. `avahi-browse` runs continuously and a printer is
  added the instant mDNS announces it, instead of waiting for a timed scan to
  finish; CUPS is re-scanned on a short timer for USB/socket devices. The API
  reads a warm cache, so the Add-printer and Diagnostics lists update on their
  own. A printer stays listed for a couple of minutes after it stops answering,
  so a sleeping printer no longer flickers in and out.
- When a printer's Bonjour records do not resolve — some Brother units collide
  on the same service name and one never resolves — Cuppa sweeps the local
  subnet for hosts answering IPP on port 631, so it is still found. The
  Diagnostics **Discovery (resolved)** section lists what discovery found.
- Raw network devices (socket/LPD/HTTP) are hidden from the Add-printer and
  Diagnostics lists by default; a **Show raw** toggle reveals them.
- The image is built for **`linux/amd64` only** (the office NAS is an Intel
  x86_64), which roughly halves the CI build time by removing QEMU emulation.
  The bundled driver set is trimmed to `brlaser` plus IPP Everywhere; `ipp-usb`
  and the foomatic PPD collection are gone. IPP Everywhere covers the office
  printers, and any PPD can still be uploaded.

### Removed
- The "Connect a device" how-to on the Dashboard (the macOS/iOS/Android steps).

### Fixed
- User-initiated prints (Test Print, Print a file) re-enable the queue first, so
  a printer stopped by an earlier error no longer holds new jobs as pending and
  looks like "nothing happened". The thermal USB transport is also selectable
  now (`CUPPA_USB_TRANSPORT=usblp` default, `libusb` for clones that need it).
- **Thermal label printers now print a test page.** The Rollo X1038 (and the
  Xprinter/Munbyn/Phomemo rebrands, IEEE-1284 id `CMD:XPP,XL`) only print TSPL
  **BITMAP** jobs; the TEXT/BOX/BARCODE/QRCODE test label was accepted and then
  silently ignored. Test Print now sends a real PDF page through the queue's
  filter, and the self-test rasterizes the page into a TSPL BITMAP job, matching
  the Android app's proven behaviour.
- **IPP Everywhere printers can be added again.** `lpadmin -m everywhere` asks
  cupsd's driver helper, which falls back to DNS-SD discovery and fails when the
  container borrows the host's Avahi (`ippfind` cannot use Bonjour). Cuppa now
  builds the driverless PPD with `driverless <uri>`, which queries the printer
  directly, both when adding a printer and when probing a raw/socket/LPD address
  for an IPP endpoint.
- **Thermal USB label printers now print.** CUPS' stock USB backend uses libusb,
  which detaches the kernel `usblp` driver. The printer — the Rollo X1038 and
  the Xprinter/Munbyn/Phomemo rebrands, IEEE-1284 id `CMD:XPP,XL` — then ACKs
  the transfer, prints nothing and wedges. The `cuppa-usb` backend now writes
  the job straight to the kernel `/dev/usb/lpN` node in 1 KiB chunks, and only
  falls back to libusb when no such node exists.
- The USB self-test ran the libusb backend before the kernel writes, which
  wedged the printer and left the kernel writes with nothing to print. It now
  runs the kernel path first and then exercises the queue's real backend.
- The network probe treated IPP status `0x0001` (`successful-ok-ignored-or-
  substituted-attributes`) as a failure, so printers that answer with it were
  reported as "IPP unavailable". Every successful status code (below `0x0100`)
  is now accepted.
- **Thermal USB printers never printed.** The paced `cuppa-usb` backend read the
  device URI from its first argument, but CUPS passes the URI in the `DEVICE_URI`
  environment variable. The backend matched nothing and exited successfully, so
  CUPS reported every job as completed while the printer received no data. This
  is what made the Rollo X1038 look like it had printed but produce nothing.
- The USB self-test invoked the CUPS backends with the device URI as an
  argument, so both backend steps exited with a usage message. They now use
  `DEVICE_URI` exactly as CUPS does, and the self-test also does an unpaced and a
  paced kernel-device write so the paths can be compared.
- Diagnostics no longer fail when `lpinfo -v` returns an error, and it now
  reports the running services and the mDNS/Avahi sockets.
- The webapp showed only the first printer when several were configured. CUPS
  returns one attributes group per printer and the parser read only the first.
- The webapp's PCL test page was an empty reset. It now prints a real
  diagnostic page using PCL's built-in Courier font.

## [0.8.0] - 2026-10-04

### Added
- A web version of Cuppa for running on a NAS, packaged as a Docker image. It runs real CUPS and
  Avahi behind a browser UI, and shares printers over Bonjour/AirPrint and IPP Everywhere for
  macOS, iOS and Android. See [`webapp/README.md`](webapp/README.md).
- Printers can be renamed. The chosen name is broadcast on the network with " (Cuppa)" appended,
  while the internal queue name stays the same so jobs and clients are unaffected.
- The webapp supports thermal label and receipt printers with the same command engines as the
  Android app (TSPL/Rollo X1038, ZPL, EPL2, ESC/POS, PCL). A generated PPD routes each queue's
  PDF jobs through a `cuppa-thermal` CUPS filter, so macOS, iOS and Android can print to a thermal
  printer shared by Cuppa without knowing anything about its command language.
- Thermal settings are per printer on Android now, matching the webapp. Each USB thermal printer
  can override the global defaults (dialect, label size, density, speed, gap, dithering, polarity)
  from its card in the Printers screen. A printer without an override keeps the global settings and
  the automatic dialect detection.

## [0.7.6] - 2026-09-20

### Changed
- Printer status updates faster. Cuppa checks every 10 seconds while the app is on screen and
  every 30 seconds in the background while the server is on. It also checks at once when the
  network changes (Wi-Fi, mobile data or a VPN coming or going), when the app comes to the front
  and when the saved printers change. With the server off and the app in the background it does
  not probe at all.
- Labels update even when the server is off, as long as the app is on screen.
- A failed check tries once more with a longer wait before a printer counts as offline. Fast
  checks made one printer flicker between Idle and Offline.

## [0.7.5] - 2026-09-20

### Changed
- A printer whose own address is `ipps://` is announced as `_ipps` only and its IPP reply lists an
  `ipps://` address with `tls`, even when "Require IPPS" is off. Other printers keep their plain
  `_ipp` announcement. Linux setup dialogs now offer only the choice that works for them.

## [0.7.4] - 2026-09-20

### Fixed
- With "Require IPPS" on, Linux setup dialogs offered IPP Everywhere and Driverless (IPP). Both
  fail because Cuppa refuses plain IPP in that mode. Cuppa now advertises its printers as `_ipps`
  only when secure connections are required, and its IPP replies list an `ipps://` address with
  `tls` as the security. Only the working Driverless (IPPS) choice is left. With "Require IPPS"
  off nothing changes.

## [0.7.3] - 2026-09-20

### Changed
- Settings uses two columns on wide windows. Permissions and Server sit on the left. Drivers and
  App sit on the right. Phones still get one column.

## [0.7.2] - 2026-09-20

### Fixed
- On wide windows the title bar only spanned the middle of the window. The bar now runs edge to
  edge on every screen. Content is centered under it. Dashboard and Printers use up to 1600 dp.
  Jobs, Settings and the driver and thermal pages stay at 960 dp so lines stay readable.

## [0.7.1] - 2026-09-20

### Changed
- Wide windows use two columns. The Dashboard puts what Cuppa is doing on the left and how to
  connect a device on the right. Printers puts your printers on the left and the ones found nearby
  on the right. Content can now be up to 1280 dp wide so there is less empty space at the sides.

## [0.7.0] - 2026-09-20

### Changed
- The Dashboard is a real home screen. It says whether Cuppa is on and how many printers it is
  sharing in plain words. A Get started checklist shows until Cuppa is on and a printer is added.
  A Connect a device card has the address with a copy button and short steps for iPhone and Mac,
  Windows, Linux and other Android phones. A Your printers card shows which are reachable. The raw
  IPP query and engine details moved into a collapsed Advanced section.
- Wide windows such as tablets and DeX get a side rail and content that stops stretching. Phones
  keep the bottom bar.
- Jobs are grouped by day. Active jobs show a progress bar. Failed jobs are tinted and say what
  to check.
- Printer cards show a badge for USB or network, and removing a printer asks first.
- The USB permission wording says the prompt appears on the phone's own screen.
- Printer names on the Printers tab wrap to two lines instead of being cut off.
- The Dashboard shows the real server state the moment it opens. It used to say Cuppa was off
  for a second while it was on.

## [0.6.14] - 2026-09-19

### Changed
- Cuppa only advertises printers that answer. A network printer must accept a connection on its
  port. A USB printer must be plugged in with permission granted. It checks every 30 seconds and a
  printer that stops answering stays listed for 5 more minutes so a short blip does not remove it.
  Before this every saved printer was announced all the time, so a printer left at the office
  still showed up on other devices at home and failed when picked.
- Saved printers that do not answer show "Offline" on the Printers tab. Nothing is deleted.

## [0.6.13] - 2026-09-19

### Fixed
- Adding a Cuppa printer in Windows failed with "That didn't work". Windows asks for the queue
  path in lowercase and Cuppa only matched the exact case, so it answered "printer not found".
  Queue paths match without regard to case now.

## [0.6.12] - 2026-09-19

### Fixed
- Secure connections (IPPS) never worked. The server tried to wrap a connection in TLS with a
  call this version of Android does not support, so every secure attempt failed. That also made
  "Require IPPS" useless. Cuppa now checks the first byte a client sends without consuming it and
  hands secure connections to Android's normal TLS layer.
- Windows found Cuppa's printers and then dropped them. Windows opens a secure connection first
  and Cuppa refused it unless "Require IPPS" was on. Cuppa now always accepts both. The setting
  only decides whether plain connections are turned away.

## [0.6.11] - 2026-09-19

### Fixed
- After one failed USB job (a paper feed problem in my case) every later job to that printer
  failed too until the cable was replugged. Cuppa now closes the IPP-over-USB connection the way
  the spec says after every request. A request that never reached the printer is retried once.
  A print job that reached the printer is never retried, so nothing prints twice.

## [0.6.10] - 2026-09-19

### Changed
- Test Print on a USB printer Cuppa does not recognize as a label or receipt printer starts on
  Vector PDF, Letter and Color. It used to start on a 4 x 6 label format, which a DeskJet or
  any other page printer cannot use.

## [0.6.9] - 2026-09-19

### Fixed
- Pages sent to a driverless USB printer lost their bottom edge. Cuppa asks the printer for its
  unprintable border now and shrinks the page to fit inside it. The DeskJet reports about 3 mm on
  three sides and 12.7 mm at the bottom.

### Changed
- The log records the printer's ink levels and warnings when a USB job starts.

## [0.6.8] - 2026-09-19

### Added
- Driverless USB printers work. Cuppa uses the printer's IPP-over-USB interface, asks what
  formats it takes and sends PDF or PWG-Raster as a normal job. Tested on an HP DeskJet 2700.
- USB jobs report how they ended. Cuppa asks the printer for the job state and only marks the job
  completed when the printer says it is. A raw USB write can only say the bytes left the phone.
- Test Print shows the Color / Black & white choice for USB printers too.

### Fixed
- HP DeskJet jobs over USB were accepted and printed nothing. Cuppa was writing to the wrong
  interface with a printer language the model does not speak.

## [0.6.7] - 2026-09-19

### Fixed
- Printers found over `ipps://` (secure IPP) failed every job. Cuppa only forwarded to `ipp://`
  and `http://` addresses. The Epson L3250 at home is advertised as secure so every job to it
  was aborted. Secure addresses are accepted now and the connection uses TLS from the start.

## [0.6.6] - 2026-09-18

### Fixed
- Jobs sent to Cuppa from another device did not appear in the Jobs tab after the app had been
  restarted. Job numbers start again at 1 on every start and Cuppa treated a new job 1 as one it
  had already saved.

## [0.6.5] - 2026-09-18

### Fixed
- Three copies from a Mac printed one. When Cuppa converts a PDF to raster for a printer it now
  writes every copy into the raster itself instead of asking the printer for copies. The Brother
  answered "ignored some attributes" and printed once.

### Note
- A Mac queue added before 0.6.3 keeps the old paper margins and the old format list. It clips
  the top of the page and can send PostScript. Remove the printer and add it again.

## [0.6.4] - 2026-09-18

### Fixed
- A Mac sent PostScript to the Brother and the Brother refused it. Cuppa advertised only what
  the Brother takes natively. Printers that only accept raster now also advertise PDF. Cuppa
  converts the PDF to raster itself before forwarding.

## [0.6.3] - 2026-09-18

### Fixed
- Mac jobs to the Brother were accepted and then failed inside the printer with a document
  format error. Cuppa told every client its raster resolution was 203 dpi, which is the Rollo's
  number. The Brother wants 300 or 600. Printers that take raster now advertise 300 dpi.

## [0.6.2] - 2026-09-18

### Fixed
- Printing from a Mac to a Brother did nothing. The Mac sends PWG-Raster. Cuppa passed it on
  labelled as generic binary data and the Brother refused it. Cuppa now recognizes raster,
  JPEG and PNG by their first bytes and labels them correctly.
- Jobs the printer refused were still shown as completed. A rejection from the printer now marks
  the job failed.

### Added
- Print quality, sides (duplex), color mode, orientation and media are passed on to network
  printers. If a printer refuses them the job is sent again with only the copies count.
- Monochrome jobs are rendered in grayscale when Cuppa converts a PDF to raster itself.
- USB printers still ignore these options.

## [0.6.1] - 2026-09-18

### Fixed
- Jobs sent from Android's print dialog to a printer shared by Cuppa were accepted and then lost.
  The spool folder was never created so the document had nowhere to go and the job showed as
  failed. The folder is created at start now. If a job still cannot be stored the server says so
  instead of pretending it worked.
- The number of copies was ignored. It is passed on to network printers and repeated for USB
  printers.
- Multi-page PDFs sent to a printer with no PDF support only printed the first page. All pages
  are converted now.
- The Dashboard and Printers tab still offered to grant USB access to audio adapters, disks and
  network dongles. They only list printers now.

### Changed
- The mDNS record now carries a `URF` key and a few other fields Apple's Add Printer dialog looks
  at. Without them macOS can ask for a driver before it ever queries the printer. Confirmed on a
  Mac: printers are found, added and printed to with no driver step.

## [0.6.0] - 2026-09-18

### Fixed
- The Rollo X1038 prints over USB. It needs a reset command before every job and its data sent in
  small paced chunks. Without those it accepted the whole job with no error and did nothing. It
  also shows up only as a plain "Printer" with its own USB ID (0x09C5 / 0x0588) so it is now
  recognized and named properly.
- Labels stay inside the paper. Pages are laid out at true 4 inch width and centered on the print
  head. Long values on the test label wrap instead of getting cut off.
- macOS asked for a driver when adding a printer from Cuppa. The IPP server returned URIs with
  spaces in them so CUPS rejected the whole reply. Queue URIs are clean now and a job goes to the
  queue it was sent to instead of the default one. CUPS's own driverless tool builds a working PPD
  for every queue.
- Job URIs used a stale IP after the phone moved to a different network.
- Adding a second printer of the same model replaced the first or hid it. Printers are told apart
  by address and mDNS UUID now.
- Available Printers no longer lists Cuppa's own shared queues or printers you already added. The
  "(Cuppa)" tag only shows on My Printers.
- Cuppa asked for USB permission for every device that got plugged in. That included audio
  adapters and game controllers. It only asks about printers now.
- Type ladder and color ramps on the test page no longer run off a 4 inch label.

### Changed
- Test Print for the Rollo sends the label as a raster image and reads the printer's status first.
- The format and paper size rows scroll with a mouse wheel.
- Test Print for a printer Cuppa shares over the network offers the same formats as the USB one.

## [0.5.6] - 2026-09-18

### Fixed
- Printers tab crashed once a second printer got added. Cuppa hosts every added printer's queue
  on the same shared IPP port, so their self-advertised mDNS entries all resolved to the same
  host:port and collapsed into one duplicate list key.

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
