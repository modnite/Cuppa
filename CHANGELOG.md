# Changelog

All notable milestone releases for the **Cuppa** (formerly RolloPrint) application are documented in this file.

---

### `v5.3.0` — September 11, 2026
- **Restoration of IPP Network Server Initializer**: Fixed a bug where toggling the server ON bound the background service without instantiating `IppServer`, mDNS, or RAW 9100. Re-wired `startIppServer()` to inject `UsbPrintManager`, `JobQueueManager`, and global logging callbacks, restoring full driverless AirPrint discovery and network logs.
- **Bottom Navigation Reordering**: Reordered the navigation bar to **Printers ➔ Jobs ➔ Log ➔ Admin**.
- **"About Cuppa" Version Card**: Added a dedicated Material Card in the Admin tab displaying current app version (`v5.3.0`) and core system specifications.

### `v5.2.0` — September 11, 2026
- **Test Print & Server Toggle Functional Wiring**: Completely removed all placeholder/stub code. Wired the "Test Page" button in the Printers Tab to instantly generate and queue a diagnostic CUPS label. Wired the Server Toggle to cleanly start/stop the `PrintServerService`.
- **Global Logging Memory Buffer (Zero Lost Logs)**: Added a central `logBuffer` in `MainActivity`. Background USB and IPP Server components now permanently write to this buffer. The Log Tab reads this full history upon opening, eliminating the "empty log" bug caused by lazy fragment initialization.
- **High-Performance Navigation UI (DeX Optimized)**: Ripped out the slow `ViewPager2` tab system. Implemented a lightning-fast Material 3 `BottomNavigationView` with strict fragment hide/show transactions. Tab switching is now completely instantaneous and immune to Samsung DeX window-resizing measurement bugs.

### `v5.1.0` — September 11, 2026
- **Deep Package Rebrand & Directory Migration (`com.modnite.cuppa`)**: Completely migrated the core Android package ID, namespace, and directory structures from `com.example.rolloprint` to `com.modnite.cuppa`. Cuppa is now a distinct, standalone application.
- **Authentic CUPS Job Controls**: Purged the legacy "Print All" and "Clear Queue" actions. The Jobs Tab now exclusively manages jobs using standard CUPS operations: **Hold**, **Release**, **Cancel**, and **Reprint**.
- **Flawless UI Layouts (No Wrapping)**: Rebuilt list items and controls using highly constrained layouts. Elements now truncate gracefully (`ellipsize="end"`) instead of wrapping to a second line or breaking vertical constraints in Portrait/Landscape/DeX modes.
- **Native Local Log Saving**: Completely stripped out Etherpad/Pastebin log-dumping. Implemented Android's native Storage Access Framework (SAF), allowing you to choose exactly which local folder to save `cuppa_server_log.txt` via a "Save Log As..." button.
- **Diagnostic Test Print Generator**: Wired the "Test Page" button to generate a standard CUPS-style diagnostic label (containing server status, protocols, and interfaces) and inject it directly into the print queue.

### `v5.0.0` — September 11, 2026
- **Global Project Rebranding (`Cuppa`)**: The application has been entirely rewritten and rebranded as **Cuppa** (formerly RolloPrint), accompanied by a brand new coffee cup application icon.
- **100% Ground-Up UI/UX Overhaul (Tabbed CUPS WebUI Port)**: Completely nuked the old single-card layout. Cuppa now features a modern Material 3 Tabbed & Navigation Bar architecture mirroring the official OpenPrinting CUPS 2.4.x WebUI.
- **Tab 1: Printers (`PrinterListFragment`)**: Lists installed CUPS printers, featuring the primary default `Rollo_X1038` and an "Add Printer" workflow for discovering secondary network/USB printers.
- **Tab 2: Jobs (`CupsJobsFragment`)**: Features a native CUPS Jobs Table listing Active and Completed Jobs with Client OS Badges, Formats, and direct actions (**Cancel**, **Hold**, **Release**, **Reprint**). Replaces flawed modal popups entirely.
- **Tab 3: Administration (`CupsAdminFragment`)**: OpenPrinting CUPS Server Configuration panel managing Server IPs, AirPrint mDNS, RAW 9100 toggles, and system maintenance.
- **Tab 4: Server Log (`CupsLogFragment`)**: Full-screen, real-time streaming CUPS system log with 1-tap "Dump Log" action.
- **Multi-Printer Registry Architecture (`CupsPrinterRegistry.kt`)**: Central registry tracking multiple active printers and device URIs concurrently.

### `v4.0.0` — September 6, 2026 at 10:00 AM
- **Complete Removal of Local File Picker**: Removed the "Select PDF" card and local file picker pipeline. RolloPrint operates exclusively as a dedicated, black-box networked CUPS print server for Android Print Service, Share Sheet, AirPrint, IPP, and RAW 9100 streams.
- **Native In-App OpenPrinting CUPS Server Dashboard**: Re-architected main dashboard into 5 native CUPS cards: CUPS Server Status, Printer State & Hardware, CUPS Print Queue, Print History Cache, and Activity Console.
- **In-App Clear Cache Controls**: Added prominent "Clear Cache" buttons in the Print Cache Gallery modal and Settings screen to delete all cached JPEGs and reset cache counts in 1 tap.
- **Wide 94% Dialog Window Expansion**: Overrode `onStart()` in `QueueManagerDialogFragment` and `PrintCacheGalleryDialogFragment` to expand window widths to 94% screen width, matching main/settings page horizontal margins.

### `v3.2.1` — September 6, 2026 at 9:30 AM
- **Wide 94% Dialog Window Expansion**: Overrode `onStart()` in `QueueManagerDialogFragment.kt` and `PrintCacheGalleryDialogFragment.kt` to expand dialog window widths to 94% screen width, matching the 16dp horizontal margins of the Main and Settings pages.
- **Global Sequential Job ID Generator**: Created a thread-safe atomic counter (`101`, `102`, `103`, `104`...) in `JobQueueManager.kt` shared across local PDF, IPP, AirPrint, and RAW 9100 print streams. Guarantees every cached screenshot and queue entry receives a unique, strictly incrementing Job ID.

### `v3.2.0` — September 6, 2026 at 9:00 AM
- **In-App OpenPrinting CUPS Control Panel**: Replaced web UI requirements with a native Material 3 in-app CUPS control panel inside `QueueManagerDialogFragment` displaying `Rollo Thermal Printer 4x6 (Idle - Accepting Jobs)`, active protocols, client OS badges (`[iOS]`, `[macOS]`, `[Windows]`), and job formats.
- **In-App Print Cache Screenshot Gallery**: Added `PrintCacheGalleryDialogFragment` allowing users to view rendered $832 \times 1218$ JPEG thumbnails directly in-app, sort by Newest/Oldest, and export individual JPEGs on demand via `FileProvider`.
- **Universal CUPS Stream Decoder Suite**: Natively parses and decodes Apple URF (`image/urf`), PWG Raster, CUPS Raster (`application/vnd.cups-raster`), Zebra ZPL (`^XA`), HP PCL (`\x1BE`), PostScript, PDF, Images, CUPS Banners, and plain text.
- **1:1 UI Padding Alignment**: Standardized left/right content padding on `MainActivity` (`activity_main.xml`) to `16dp`, matching `SettingsActivity` 1:1.

### `v3.1.2` — September 6, 2026 at 8:30 AM
- **PostScript Filter Bypass & Direct PDF Streaming**: Removed `application/postscript` from IPP `documentFormatSupported` in `IppServer.kt` and mDNS `pdl` TXT record in `PrintServerService.kt`. Forces macOS, Linux, and Windows clients to bypass PostScript CUPS filters (`cgpdftops`) and stream raw PDF documents (`srcdoc.pdf`) directly over IPP without filter text boxes.
- **Enhanced PostScript Text & Metadata Extraction**: Updated `createPdfFromText` in `IppServer.kt` to extract all PostScript document string calls (`(...) show`, `(...) Tj`, `(...) TJ`) and render actual document content onto 4x6 label canvases whenever PostScript streams are submitted.

### `v5.3.0` — September 11, 2026
- **Restoration of IPP Network Server Initializer**: Fixed a bug where toggling the server ON bound the background service without instantiating `IppServer`, mDNS, or RAW 9100. Re-wired `startIppServer()` to inject `UsbPrintManager`, `JobQueueManager`, and global logging callbacks, restoring full driverless AirPrint discovery and network logs.
- **Bottom Navigation Reordering**: Reordered the navigation bar to **Printers ➔ Jobs ➔ Log ➔ Admin**.
- **"About Cuppa" Version Card**: Added a dedicated Material Card in the Admin tab displaying current app version (`v5.3.0`) and core system specifications.

### `v5.2.0` — September 11, 2026
- **Test Print & Server Toggle Functional Wiring**: Completely removed all placeholder/stub code. Wired the "Test Page" button in the Printers Tab to instantly generate and queue a diagnostic CUPS label. Wired the Server Toggle to cleanly start/stop the `PrintServerService`.
- **Global Logging Memory Buffer (Zero Lost Logs)**: Added a central `logBuffer` in `MainActivity`. Background USB and IPP Server components now permanently write to this buffer. The Log Tab reads this full history upon opening, eliminating the "empty log" bug caused by lazy fragment initialization.
- **High-Performance Navigation UI (DeX Optimized)**: Ripped out the slow `ViewPager2` tab system. Implemented a lightning-fast Material 3 `BottomNavigationView` with strict fragment hide/show transactions. Tab switching is now completely instantaneous and immune to Samsung DeX window-resizing measurement bugs.

### `v5.1.0` — September 11, 2026
- **Deep Package Rebrand & Directory Migration (`com.modnite.cuppa`)**: Completely migrated the core Android package ID, namespace, and directory structures from `com.example.rolloprint` to `com.modnite.cuppa`. Cuppa is now a distinct, standalone application.
- **Authentic CUPS Job Controls**: Purged the legacy "Print All" and "Clear Queue" actions. The Jobs Tab now exclusively manages jobs using standard CUPS operations: **Hold**, **Release**, **Cancel**, and **Reprint**.
- **Flawless UI Layouts (No Wrapping)**: Rebuilt list items and controls using highly constrained layouts. Elements now truncate gracefully (`ellipsize="end"`) instead of wrapping to a second line or breaking vertical constraints in Portrait/Landscape/DeX modes.
- **Native Local Log Saving**: Completely stripped out Etherpad/Pastebin log-dumping. Implemented Android's native Storage Access Framework (SAF), allowing you to choose exactly which local folder to save `cuppa_server_log.txt` via a "Save Log As..." button.
- **Diagnostic Test Print Generator**: Wired the "Test Page" button to generate a standard CUPS-style diagnostic label (containing server status, protocols, and interfaces) and inject it directly into the print queue.

### `v5.0.0` — September 11, 2026
- **Global Project Rebranding (`Cuppa`)**: The application has been entirely rewritten and rebranded as **Cuppa** (formerly RolloPrint), accompanied by a brand new coffee cup application icon.
- **100% Ground-Up UI/UX Overhaul (Tabbed CUPS WebUI Port)**: Completely nuked the old single-card layout. Cuppa now features a modern Material 3 Tabbed & Navigation Bar architecture mirroring the official OpenPrinting CUPS 2.4.x WebUI.
- **Tab 1: Printers (`PrinterListFragment`)**: Lists installed CUPS printers, featuring the primary default `Rollo_X1038` and an "Add Printer" workflow for discovering secondary network/USB printers.
- **Tab 2: Jobs (`CupsJobsFragment`)**: Features a native CUPS Jobs Table listing Active and Completed Jobs with Client OS Badges, Formats, and direct actions (**Cancel**, **Hold**, **Release**, **Reprint**). Replaces flawed modal popups entirely.
- **Tab 3: Administration (`CupsAdminFragment`)**: OpenPrinting CUPS Server Configuration panel managing Server IPs, AirPrint mDNS, RAW 9100 toggles, and system maintenance.
- **Tab 4: Server Log (`CupsLogFragment`)**: Full-screen, real-time streaming CUPS system log with 1-tap "Dump Log" action.
- **Multi-Printer Registry Architecture (`CupsPrinterRegistry.kt`)**: Central registry tracking multiple active printers and device URIs concurrently.

### `v4.0.0` — September 6, 2026 at 10:00 AM
- **Complete Removal of Local File Picker**: Removed the "Select PDF" card and local file picker pipeline. RolloPrint operates exclusively as a dedicated, black-box networked CUPS print server for Android Print Service, Share Sheet, AirPrint, IPP, and RAW 9100 streams.
- **Native In-App OpenPrinting CUPS Server Dashboard**: Re-architected main dashboard into 5 native CUPS cards: CUPS Server Status, Printer State & Hardware, CUPS Print Queue, Print History Cache, and Activity Console.
- **In-App Clear Cache Controls**: Added prominent "Clear Cache" buttons in the Print Cache Gallery modal and Settings screen to delete all cached JPEGs and reset cache counts in 1 tap.
- **Wide 94% Dialog Window Expansion**: Overrode `onStart()` in `QueueManagerDialogFragment` and `PrintCacheGalleryDialogFragment` to expand window widths to 94% screen width, matching main/settings page horizontal margins.

### `v3.2.1` — September 6, 2026 at 9:30 AM
- **Wide 94% Dialog Window Expansion**: Overrode `onStart()` in `QueueManagerDialogFragment.kt` and `PrintCacheGalleryDialogFragment.kt` to expand dialog window widths to 94% screen width, matching the 16dp horizontal margins of the Main and Settings pages.
- **Global Sequential Job ID Generator**: Created a thread-safe atomic counter (`101`, `102`, `103`, `104`...) in `JobQueueManager.kt` shared across local PDF, IPP, AirPrint, and RAW 9100 print streams. Guarantees every cached screenshot and queue entry receives a unique, strictly incrementing Job ID.

### `v3.2.0` — September 6, 2026 at 9:00 AM
- **In-App OpenPrinting CUPS Control Panel**: Replaced web UI requirements with a native Material 3 in-app CUPS control panel inside `QueueManagerDialogFragment` displaying `Rollo Thermal Printer 4x6 (Idle - Accepting Jobs)`, active protocols, client OS badges (`[iOS]`, `[macOS]`, `[Windows]`), and job formats.
- **In-App Print Cache Screenshot Gallery**: Added `PrintCacheGalleryDialogFragment` allowing users to view rendered $832 \times 1218$ JPEG thumbnails directly in-app, sort by Newest/Oldest, and export individual JPEGs on demand via `FileProvider`.
- **Universal CUPS Stream Decoder Suite**: Natively parses and decodes Apple URF (`image/urf`), PWG Raster, CUPS Raster (`application/vnd.cups-raster`), Zebra ZPL (`^XA`), HP PCL (`\x1BE`), PostScript, PDF, Images, CUPS Banners, and plain text.
- **1:1 UI Padding Alignment**: Standardized left/right content padding on `MainActivity` (`activity_main.xml`) to `16dp`, matching `SettingsActivity` 1:1.

### `v3.1.2` — September 6, 2026 at 8:30 AM
- **PostScript Filter Bypass & Direct PDF Streaming**: Removed `application/postscript` from IPP `documentFormatSupported` in `IppServer.kt` and mDNS `pdl` TXT record in `PrintServerService.kt`. Forces macOS, Linux, and Windows clients to bypass PostScript CUPS filters (`cgpdftops`) and stream raw PDF documents (`srcdoc.pdf`) directly over IPP without filter text boxes.
- **Enhanced PostScript Text & Metadata Extraction**: Updated `createPdfFromText` in `IppServer.kt` to extract all PostScript document string calls (`(...) show`, `(...) Tj`, `(...) TJ`) and render actual document content onto 4x6 label canvases whenever PostScript streams are submitted.

### `v3.1.1` — September 6, 2026 at 8:00 AM
- **Safari / Mac PostScript Header Extraction**: Fixed `detectOsFamily` and `createPdfFromText` in `IppServer.kt`. Inspects `cgpdftops` / PostScript wrappers, extracts document Title and Creator metadata, and renders clean, formatted 4x6 document labels instead of printing raw PostScript code.
- **Accurate `macos` OS Classification**: Corrected OS family detection when Mac CUPS sends `User-Agent: CUPS/2.4.x` so Mac jobs are properly categorized and logged as `macos`.

### `v3.1.0` — September 6, 2026 at 7:30 AM
- **macOS Driverless AirPrint Discovery**: Registered AirPrint mDNS subtypes `_universal._sub._ipp._tcp.` and `_airprint._sub._ipp._tcp.` in `PrintServerService.kt` with full AirPrint TXT keys (`Color=F`, `Duplex=F`, `Copies=T`, `URF=CP1,SM1,RS203`, `pdl=application/pdf,image/pwg-raster,image/jpeg,image/png,application/postscript`). Eliminates macOS "Choose a Driver..." prompts completely.
- **Smart Client OS Family Detection**: Inspects HTTP `User-Agent` headers, IPP attributes, and stream comments to detect client OS family (`macos`, `windows`, `linux`, `android`).
- **Diagnostic JPEG Screenshot Cache Engine (`PrintHistoryCacheManager.kt`)**: Saves a compact JPEG screenshot of every rendered print job into `getExternalFilesDir("print_history_cache")` with filename format `job_<id>_<origin>_<osFamily>_<timestamp>.jpg`.
- **Export Print Cache (.zip) Button**: Added a dedicated button in `SettingsActivity.java` to package all cached JPEGs into `print_cache_export.zip` for easy pulling/sharing.

### `v3.0.8` — September 6, 2026 at 7:00 AM
- **TSPL BITMAP Bit Polarity Correction**: Fixed bit packing in `UsbPrintManager.kt`. Initialized monochrome buffer to `0xFF` (all white) and cleared black bits to `0`, restoring crisp black text/barcodes on white paper and eliminating printer power supply trips / reboots.
- **Bitmap Heap Memory Recycling**: Added `bitmap.recycle()` calls in `printBitmapAsync` to free uncompressed $4\text{ MB}$ bitmap arrays immediately after TSPL encoding, eliminating app process crashes.

### `v2.3.0` — September 6, 2026 at 2:00 AM
- **Dummy Sink Architecture (Mask Hardware State from Clients)**: Configured `IppServer.kt` to always report `printerState = PrinterState.idle` and `printerStateReasons = ["none"]` to network clients. Clients receive immediate job acceptance (`JobState.completed`) and hand off payloads cleanly without client-side filter halts or "Out of Paper" popups.
- **Strict Sequential Job ID Tracking**: Added `activeJobMap` to `IppServer.kt` linking 2-step `Create-Job` and `Send-Document` IPP requests to the same `job-id`. Network jobs now increment sequentially as `Job #1`, `Job #2`, `Job #3`, `Job #4` without skipping numbers.
- **Universal Android Default Print Service Auto-Discovery**: Configured full mDNS TXT descriptors (`kind`, `URF`, `papercustom`, `pdl`) in `PrintServerService.kt`. Android Default Print Service, Mopria, iOS AirPrint, and Linux CUPS auto-discover `Rollo Printer` as a native networked label printer.

### `v3.0.7` — September 6, 2026 at 6:30 AM
- **Print Server UI Status & Notification Callback Binding**: Fixed `initializeServer()` in `PrintServerService.kt` to update `statusCallback` and `loggerCallback` references dynamically on service binding. Resolves the issue where `tvServerStatus` remained displaying `"Status: Disabled"` while the service notification was active.

### `v3.0.6` — September 6, 2026 at 6:00 AM
- **Console Log Stream Binding in Settings Hardware Check**: Updated `btnDiagnostics` in `SettingsActivity.java` to finish and return directly to `MainActivity` on click. Triggers `runPrinterDiagnosticsAsync()` instantly and streams all USB bus scan details (`[USB_DIAG]`) into the main Activity Console (`tvLog`).
- **Quiet 5-Second Background Status Poller**: Re-introduced a non-blocking 5-second background poller in `MainActivity.java` (`pollHandler`). Keeps `tvHardwareStatus` on `cardHeader` updated continuously without polluting the Activity Console with repetitive log entries.

### `v3.0.7` — September 6, 2026 at 6:30 AM
- **Print Server UI Status & Notification Callback Binding**: Fixed `initializeServer()` in `PrintServerService.kt` to update `statusCallback` and `loggerCallback` references dynamically on service binding. Resolves the issue where `tvServerStatus` remained displaying `"Status: Disabled"` while the service notification was active.

### `v3.0.6` — September 6, 2026 at 6:00 AM
- **Activity Console Log Binding in Settings Hardware Check**: Updated `btnDiagnostics` in `SettingsActivity.java` to finish and return to `MainActivity` on click, triggering `runPrinterDiagnosticsAsync()` instantly and streaming all USB bus scan details (`[USB_DIAG]`) into the main Activity Console (`tvLog`).
- **Quiet 5-Second Background Status Poller**: Re-introduced a non-blocking 5-second background poller in `MainActivity.java` (`pollHandler`). Keeps `tvHardwareStatus` on `cardHeader` updated continuously without polluting the Activity Console with repetitive log entries.

### `v3.0.5` — September 6, 2026 at 5:30 AM
- **Broad USB Bus Scan & Universal Dock Detection**: Enhanced `findRolloDevice()` in `UsbPrintManager.kt` to scan VID 2501 ($0x09C5$), PID 1416 ($0x0588$), or any attached USB Printer Class ($7$) device. Verbose diagnostic logging (`[USB_DIAG]`) lists every connected USB device on the bus.
- **System-Wide USB Attach/Detach Broadcast Handlers**: Registered `usbReceiver` in `MainActivity.java` with `ACTION_USB_DEVICE_ATTACHED` and `ACTION_USB_DEVICE_DETACHED` under `RECEIVER_EXPORTED` flags. Automatically detects dock insertions and triggers USB permission prompts instantly.
- **Settings Hardware Check Diagnostic Fix**: Connected "Run hardware check" in `SettingsActivity` directly to UI Toast messages and console output.

### `v3.0.4` — September 6, 2026 at 5:00 AM
- **Universal USB Dock Device Detection & Auto-Permission Prompting**: Updated `findRolloDevice()` in `UsbPrintManager.kt` to match VID 2501 ($0x09C5$), PID 1416 ($0x0588$), or any attached USB Printer Class ($7$) device. Automatically triggers USB permission prompts on status poll when docked.
- **Activity Log Card Button Padding & Label Simplification**: Simplified "Dump to pastebin" to "Dump log" (`btnDumpLogs`), "Select PDF & print" to "Select PDF" (`btnSelect`), and added compact horizontal button padding to eliminate "Activity log" header title wrapping.

### `v3.0.3` — September 6, 2026 at 4:30 AM
- **Automatic USB Permission Request during Polling**: Updated `pollHardwareStatus()` in `UsbPrintManager.kt` to trigger USB permission prompts automatically on launch/docking if the Rollo printer device is present.
- **Landscape & DeX Scroll Insets Padding Fix**: Applied `clipToPadding="true"` to `mainScrollView` in `activity_main.xml` and `MainActivity.java`. Prevents UI elements from scrolling behind the status bar or taskbar in landscape and Samsung DeX modes.

### `v3.0.2` — September 6, 2026 at 4:00 AM
- **Initial Hardware State Badge Synchronization**: Updated `UsbPrintManager.kt` and `MainActivity.java` so that on app launch, `tvHardwareStatus` initializes to `● Hardware: Disconnected / Unknown` and immediately triggers status polling to evaluate connection state without defaulting to `READY` when un-docked.
- **In-App Auto-Updater Toast & Dialog Handlers**: Connected `SettingsActivity` update button directly to UI thread Toast messages and `showUpdateAvailableDialog`, downloading update APKs cleanly via `FileProvider`.

### `v3.0.1` — September 6, 2026 at 3:30 AM
- **In-App Auto-Update Repair in SettingsActivity**: Updated `SettingsActivity.java` to fetch dynamic package version names, display Toast messages during updates, and fire the Material Update Dialog on UI thread when new releases are detected on GitHub.

### `v3.0.0` — September 6, 2026 at 3:00 AM
- **Official Rollo v1.8.4 Print Head Dot Width**: Updated `UsbPrintManager.kt` raster target width to `#define ROLLO_DOT_WIDTH 832` ($104 \text{ bytes} \times 8 = 832 \text{ dots}$ @ 203 DPI) with exact output centering across the print head, matching official Rollo Linux/Mac v1.8.4 (2024) drivers and AirPrint firmware back-ports.
- **Official Rollo Thermal Power Protection (`rastertorollo.c`)**: Implemented official Rollo thermal head current protection. Bitmaps containing >100 consecutive dense black lines dynamically drop TSPL speed and density (`SPEED 4`, `DENSITY 6`) to protect the Rollo X1038 power supply from current starvation trips.
- **OpenPrinting CUPS 2.4.x & AirPrint Alignment**: Configured `IppServer.kt` to mirror official OpenPrinting CUPS 2.4.x server attributes (`media-supported` for 4x6, 4x8, 2x1, Round labels; `pdf-versions-supported`; `pwg-raster-document-resolution-supported`).
- **Clean Hardware Badge (Zero Log Pollution)**: Re-introduced `tvHardwareStatus` on `cardHeader`. Background status polling updates the UI header badge quietly without writing repetitive lines to the Activity Console. Hardware state is logged exclusively during genuine state transitions and simultaneously alongside job transfers (`[JOB_TRANSFER] ... | Hardware State: [READY]`).
- **Session-Bound Log History Preservation**: Preserved Activity Console log text (`tvLog`) in memory across Activity recreations, theme changes, and screen rotations via `onSaveInstanceState`.
- **Dedicated Settings Activity (`SettingsActivity.java`)**: Replaced modal settings popup with a full-screen `SettingsActivity` featuring a top Material Toolbar with back navigation.
- **Light Mode System Bar Icon Awareness**: Applied `WindowInsetsControllerCompat` to dynamically invert status bar and navigation bar icons to dark icons in Light Mode, preventing white-on-white status bar icon washouts.

### `v2.5.0` — September 6, 2026 at 2:30 AM
- **Dedicated Settings Activity (`SettingsActivity.java`)**: Replaced modal settings popup with a full-screen `SettingsActivity` featuring a top Material Toolbar with back navigation. Toggling themes or options no longer recreates or closes the settings UI.
- **Light Mode System Bar Icon Awareness**: Applied `WindowInsetsControllerCompat` to dynamically invert status bar and navigation bar icons to dark icons in Light Mode, preventing white-on-white status bar icon washouts.
- **Complete Elimination of Hardware Status Badge & Polling Noise**: Removed `tvHardwareStatus` badge from `cardHeader`. The app runs as a quiet, efficient Black-Box Print Engine without thread churn or USB locks.
- **Multi-Protocol Network Server (AirPrint & RAW Port 9100)**: Added `RawSocketServer.kt` on Port 9100 for JetDirect / AppSocket raw stream ingestion. Registered AirPrint mDNS subtype `_universal._sub._ipp._tcp.` alongside `_ipp._tcp.` with full TXT attributes (`URF=CP1,SM1,RS203`, `pdl=application/pdf,image/pwg-raster`, `kind=document,label`).
- **"Hold Incoming Network Prints" Switch**: Added `PREF_HOLD_NETWORK_JOBS` setting switch. When enabled, incoming network jobs enter `JobQueueManager` in `HELD` status for paper-free troubleshooting and job batching.
- **Directional Arrow Sort Order Indicator**: Updated Queue Manager sort order button to a directional arrow toggle ($1 \rightarrow 9$ vs $9 \rightarrow 1$).

### `v2.2.1` — September 6, 2026 at 1:30 AM
- **Linux CUPS Page Size Attribute Alignment**: Added top-level `mediaSupported` array (`na_index-4x6_4x6in`, `oe_4x6-label_4x6in`, `custom_min_4x6in`, `na_letter_8.5x11in`, `iso_a4_210x297mm`) and `pdfVersionsSupported` to `IppServer.kt`. Resolves Linux CUPS `pdftopdf` page-size matching filter crashes on Linux KDE Plasma.
- **Surgical `Operation.createJob` Integration**: Preserved the lightweight `v1.8.1` IPP Everywhere response architecture while returning assigned `job-id` attributes for `createJob` requests.

### `v2.2.0` — September 6, 2026 at 1:00 AM
- **Memory & Bitmap Cleanup**: Added automatic bitmap recycling and temp file deletion (`temp_incoming_*.pdf`) after IPP rendering in `IppServer.kt`. Eliminates heap memory leaks and prevents app crashes when receiving multiple consecutive network jobs.
- **Permanent Activity Log & Server State Persistence**: Persisted activity log history (`tvLog`) and Print Server active state in `SharedPreferences` (`PREF_SERVER_RUNNING`). If the app or device restarts, log history and print server status are 100% preserved.
- **mDNS Service Name Uniqueness**: Cleared previous NsdListeners prior to service registration in `PrintServerService.kt`, guaranteeing the mDNS service name remains strictly `"Rollo Printer"`.

### `v2.1.7` — September 6, 2026 at 12:00 AM
- **mDNS Service Registration Uniqueness & Full AirPrint Descriptors**: Added automatic pre-cleanup of previous NSD registration listeners in `PrintServerService.kt`. Ensures the mDNS service name remains strictly `"Rollo Printer"` (never `"Rollo Printer (3)"`), allowing `lpadmin -v "dnssd://Rollo%20Printer._ipp._tcp.local/ipp/print"` and Linux `driverless -d` to resolve the printer instantly. Added AirPrint TXT descriptors (`kind`, `URF`, `papercustom`).

### `v2.1.2` — September 5, 2026 at 11:30 PM
- **Preserved Activity Log Console Across Theme Recreations**: Added `onSaveInstanceState` and `onRestoreInstanceState` handlers in `MainActivity.java`. Changing app theme or rotating screen now 100% preserves the Activity Console log history (`tvLog`) and expanded/collapsed view states.
- **Muted Repetitive Polling Logs**: Removed redundant 1.5s `[DIAGNOSTIC] Current Rollo Hardware States` console logs. Status logs are now emitted exclusively on genuine hardware state transitions (`[HARDWARE_STATE] Transitioned to:`), leaving the Activity Console quiet, readable, and uncluttered.

### `v2.1.1` — September 5, 2026 at 11:00 PM
- **IPP Operation.createJob Response Handler**: Implemented RFC 8011 `Create-Job` operation handling returning assigned `job-id`, `job-uri`, and `job-state` attributes in `jobAttributes`. Eliminates CUPS `pdftopdf stopped with status 1` and `universal filter failed` errors on Linux KDE Plasma.

### `v2.1.0` — September 5, 2026 at 10:30 PM
- **Linux CUPS `pdftopdf` Compatibility Descriptors**: Added missing IPP Everywhere attributes (`media-supported`, `media-ready`, `media-default`, `media-type-supported`, `pdf-versions-supported`, `printer-device-id`) to `IppServer.kt`. Resolves CUPS `universal filter failed` and `pdftopdf stopped with status 1` errors on Linux KDE Plasma.
- **Continuous 1.5s Hardware Polling Loop**: Decoupled hardware status polling from `PrintServerService`. Background USB status polling runs continuously every 1.5 seconds from app launch regardless of whether the Print Server is ON or OFF, updating status badges in <1.5s.
- **Full-Width RadioButton Touch Targets**: Added `clickable="true"`, `focusable="true"`, and `background="?attr/selectableItemBackground"` to Theme option RadioButtons in `dialog_settings.xml`. Tapping anywhere across the full width of the row selects the theme cleanly.

### `v2.0.4` — September 5, 2026 at 10:00 PM
- **USB Interface Claim Retry Loop**: Added a 300ms retry loop on `connection.claimInterface(usbInterface, true)` in `UsbPrintManager.kt`. Eliminates interface lockouts and spurious `UNKNOWN` state transitions when rapidly starting/stopping background services or re-plugging USB OTG docks.
- **Full-Width RadioButton Touch Bounds**: Set `clickable="true"`, `focusable="true"`, and `background="?attr/selectableItemBackground"` on Theme options in `dialog_settings.xml`. Tapping anywhere across the full width of the row selects the theme cleanly.

### `v2.0.3` — September 5, 2026 at 9:30 PM
- **Pinned Queue Manager Action Buttons**: Re-architected `dialog_queue_manager.xml` with `layout_height="0dp"` and `layout_weight="1"` on `rvQueueJobs`. Action buttons (`Print all`, `Clear all`, `Done`) are permanently pinned at the bottom with full height regardless of how many jobs fill the queue.
- **TextInput Overlapping Hint Fix**: Removed duplicate edit hints from `TextInputEditText` inside `dialog_settings.xml`. Material 3 `TextInputLayout` floating hints float up smoothly with zero text overlap.
- **Full-Width Theme Touch Targets**: Expanded `RadioButton` touch targets (`rbThemeSystem`, `rbThemeDark`, `rbThemeLight`) across the full row width with ripple feedback.

### `v2.0.2` — September 5, 2026 at 9:00 PM
- **Pinned Dialog Button Bar & Internal List Scrolling**: Re-structured `dialog_queue_manager.xml` using `layout_weight="1"` on `rvQueueJobs`. The bottom action buttons (`Print all`, `Clear all`, `Done`) remain permanently pinned at the bottom with full height regardless of how many jobs populate the queue.
- **TextInput Overlapping Hint Fix**: Removed duplicate edit hints from `TextInputEditText` inside `dialog_settings.xml`. Material 3 `TextInputLayout` floating hints now animate smoothly without text overlapping.
- **Full-Width Theme Touch Targets**: Set `android:layout_width="match_parent"` and vertical padding on Theme option radio buttons (`rbThemeSystem`, `rbThemeDark`, `rbThemeLight`), enabling instant selection by tapping anywhere across the full width of the row.

### `v2.0.1` — September 5, 2026 at 8:30 PM
- **App Theme Section Expansion Fix**: Fixed click listener binding for Section 1 ("App theme") in `showSettingsDialog()`. Expanding and collapsing options (`System default`, `Dark theme`, `Light theme`) now works smoothly with animated arrow indicators.
- **Detailed UI Event Logging (`[UI_EVENT]`)**: Added comprehensive logging for every button tap, switch toggle, radio selection, and section expansion across the app interface for live diagnostic visibility.
- **Etherpad Log Exporter Multipart Upload**: Standardized log dumping via multipart form-data file upload (`/p/<padID>/import`) for 100% reliable log exports across all Etherpad instances.
- **Accurate Release Timestamps**: Synchronized all CHANGELOG and DEVLOG release entries with exact GitHub Actions build start times.

### `v2.0.0` — September 5, 2026 at 8:03 PM
- **Single-Action Print Queue Card**: Streamlined `cardQueue` on the main dashboard to feature a single prominent "View queue" button, giving the card title "Print queue" 80%+ width so it never truncates into `Print qu...`.
- **Equal-Width Dialog Action Buttons**: Applied `layout_weight="1"` and `0dp` width to all 3 bottom buttons (`Print all`, `Clear all`, `Done`) in `dialog_queue_manager.xml`. Completely eliminates vertical pill button deformation.
- **Top-Level App Theme Section**: Positioned "App theme" as Section 1 at the top of the Settings popup with smooth expand/collapse animations.
- **Unified Material 3 Button Design**: Standardized all buttons across the application (Outlined & Filled Material 3 buttons), eliminating floating text-only buttons for consistent visual aesthetics across portrait, landscape, and Samsung DeX desktop modes.

### `v1.9.0` — September 5, 2026 at 6:51 PM
- **"Print All" Sequential Execution**: Added a "Print all" button on the Print Queue Manager modal (`btnPrintAllQueue`). Sets all held jobs to pending status and processes them sequentially in job order.
- **Compact Sort Order Icon Button**: Replaced the huge sort order text button with a clean 40x40dp icon button (`ImageButton`) in the Queue Manager modal header.
- **App Theme Selector Setting**: Added an "App theme" section in the Settings menu with System default, Dark theme, and Light theme options (`AppCompatDelegate.setDefaultNightMode()`).

### `v1.8.0` — September 5, 2026 at 6:14 PM
- **Full Landscape & DeX Mode Scrollability (`NestedScrollView`)**: Wrapped the main dashboard layout in `NestedScrollView`. Eliminates layout truncation on landscape phone screens, tablets, and Samsung DeX desktop mode.
- **Collapsible Settings Menu Sections**: Organized the Settings popup into 3 expandable/collapsible sections (*Print Preview Options*, *Pastebin / Etherpad Settings*, and *Diagnostics & Maintenance*).
- **Spacious Queue Manager Modal**: Expanded the Print Queue Manager dialog window to `92%` screen width (`360dp` RecyclerView height) for an open, un-claustrophobic management experience.
- **Standard Sentence Case Capitalization**: Applied standard English capitalization rules across all UI text strings, card headers, popup titles, and buttons throughout the application.

### `v1.7.0` — September 5, 2026 at 5:32 PM
- **Held Jobs Queue Manager & Interactive Modal**: Added a "View queue" button on the Print Queue card launching a full Queue Manager modal (`QueueManagerDialogFragment`). Displays individual pending/held jobs with client names, job status, a "Preview" action to visually inspect rendered label bitmaps, a "Print now" action, and individual job deletion.
- **In-App GitHub Auto-Update Checker (`AppUpdateManager`)**: Built an in-app GitHub Release update engine that checks `https://api.github.com/repos/modnite/RolloPrint/releases/latest` every 15 minutes (and on-demand via a "Check for updates" button in Settings). Automatically prompts with release notes and installs updated APKs seamlessly via `FileProvider`.
- **Universal Etherpad / Pastebin Log Exporter**: Added a "Dump to pastebin" action button and configurable Pastebin URL setting (`PREF_ETHERPAD_URL`). Uses multipart form-data import (`/p/<padID>/import`) to overwrite pad text reliably across all Etherpad instances.
- **Enabled Local Cleartext Traffic**: Added `android:usesCleartextTraffic="true"` to `AndroidManifest.xml` enabling instant HTTP activity log dumps to local Etherpad pastebins.

### `v1.6.0` — September 5, 2026 at 5:05 PM
- **Etherpad / Pastebin Activity Log Dumper**: Added a "Dump to pastebin" action button under the Activity Log console and an Etherpad / Pastebin URL configuration field in the Settings menu (`PREF_ETHERPAD_URL`). Easily posts activity logs directly to your Etherpad instance with a single tap.

### `v1.5.0` — September 5, 2026 at 4:25 PM
- **Real-Time Hardware Status Polling (`<ESC>!?`)**: Added background status polling every 3 seconds using verified Rollo X1038 status command `0x1B, 0x21, 0x3F` (`<ESC>!?`) over USB bulk endpoints.
- **Hardware Queue Purge (`~!C`)**: Updated the "Clear queue" button to send `0x7E, 0x21, 0x43` (`~!C`) directly over USB to purge the Rollo printer's onboard RAM buffer memory and halt label feeding immediately.
- **Main UI Hardware Status Badge**: Displays real-time hardware status in the app header card (`● Hardware: Ready`, `● Hardware: Out of Paper (Red LED)`, `● Hardware: Cover Open`).
- **IPP State Synchronization**: Automatically synchronizes IPP `printer-state` (idle vs stopped) and `printer-state-reasons` (`media-empty-error`, `door-open-error`, `none`) with real-time USB hardware states.

### `v1.4.0` — September 5, 2026 at 3:06 PM
- **Print Queue Card with Clear Queue Button**: Added a dedicated Print Queue Card in the main UI displaying real-time queue count (`Queue: Empty` or `Queue: X job(s) waiting`) and a `Clear queue` button. Un-printed jobs waiting during paper changes or hardware disconnects can now be purged instantly.
- **`JobQueueManager` Integration**: Built a thread-safe sequential job queue manager that processes local and network jobs sequentially and updates queue state in real-time.
- **Collapsible Activity Log Console**: Formatted the activity log card to be collapsible (collapsed by default). Tapping the header bar smoothly expands or collapses the console with an animated arrow indicator.

### `v1.3.0` — September 5, 2026 at 10:58 AM
- **Direct Network Print (No Dialogs)**: Incoming IPP print jobs bypass all UI preview dialogs, converting to 4x6 203 DPI bitmaps and streaming directly to the USB bulk endpoint in the background with activity logging.
- **Show Preview for Local Prints Settings Switch**: Added a MaterialSwitch in the main UI (`Show preview for local prints`, persisted in `SharedPreferences`, default `true`). Controls whether local PDF prints prompt for confirmation before sending to USB.
- **Aspect-Ratio Preserving Letterbox/Pillarbox PDF Renderer**: Calculates `min(816.0 / pdfWidth, 1218.0 / pdfHeight)` and centers scaled pages on an 816x1218 white canvas, ensuring non-4x6 documents (e.g. Letter, US Standard, or square slips) print centered without distortion or truncation.

### `v1.2.0` — September 5, 2026 at 10:08 AM
- **mDNS Service Layer Update**: Configured `_ipp._tcp` on port `8631` with exact driverless discovery TXT keys (`txtvers`, `ty`, `product`, `rp`, `pdl="image/pwg-raster,application/pdf"`, `qtotal`, `printer-state`, `printer-type`, `note`, `UUID`).
- **HTTP Transport Refactor (Catch-All & Chunked Decoder)**: Accepts HTTP POST requests sent to ANY URI path (`/`, `/cups`, `/ipp/print`), fully decodes `Transfer-Encoding: chunked` HTTP body streams, and reads exact `Content-Length` byte counts.
- **Non-Blocking IPP Job Handling**: Immediately returns HTTP 200 `successful-ok` response with assigned `job-id` and `job-state` before closing socket, preventing client keep-alive deadlocks.

### `v1.1.0` — September 5, 2026 at 8:34 AM
- **Integrated HP `jipp-core` IPP Engine (`com.hp.jipp:jipp-core:0.7.18`)**: Replaced custom IPP response serializer with HP's official, production-grade IPP Everywhere library. HP `jipp-core` guarantees 100% PWG 5100.14 / RFC 8010 compliant binary IPP frames.
- **HTTP/1.1 Expect 100-Continue Handshake**: Handles `Expect: 100-continue` headers from Windows IPP and Linux CUPS clients automatically.

### `v1.0.0` — September 2, 2026 at 12:17 PM
- **Core TSPL Engine**: Built expert native TSPL2 USB communication manager.
- **Rollo X1038 Integration**: Resolved thermal head polarity and bit-packing order for crisp label output.
- **PDF Label Renderer**: Added native PDF rendering pipeline scaling labels to exact 4x6 dimensions (816x1218 @ 203 DPI).
- **Print Preview Dialog**: Added visual confirmation preview dialog before hardware transfer.
- **USB Permission Auto-Handling**: BroadcastReceiver for USB connection events and permission prompts.
- **Live Diagnostics Console**: Built UI activity log view for streaming transfer statistics.
