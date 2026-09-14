# Cuppa (formerly RolloPrint)

[![Build & Publish APK](https://github.com/modnite/Cuppa/actions/workflows/release.yml/badge.svg)](https://github.com/modnite/Cuppa/actions/workflows/release.yml)

**Cuppa** is an enterprise-grade, open-source Android application that turns your Android device into a native **OpenPrinting CUPS Administration Server**, dedicated to direct driving the **Rollo X1038** thermal label printer and managing an office-wide wireless print server.

---

## Development Journey & Progress Report

For the complete narrative history of how Cuppa was designed, engineered, and built, see the [Development Progress Report (DEVLOG.md)](DEVLOG.md).

---

## What Cuppa Does

- **Direct USB Thermal Printing**: Plug your Android device into the Rollo printer via USB OTG or a USB-C dock to print 4x6 labels instantly.
- **Built-in OpenPrinting CUPS Server**: Toggle on the Print Server, and any Mac, Windows, Linux PC, iOS, or Android device on the local Wi-Fi network can discover the printer automatically via AirPrint / IPP.
- **WhatsApp & File Share Support**: Tap "Share" on any PDF label in WhatsApp or your file manager, select Cuppa, preview it, and print.
- **Auto-Formatting for 4x6 Thermal Labels**: Automatically converts PDFs, images, or text into crisp 4" x 6" 203 DPI monochrome shipping labels.
- **Samsung DeX & Phone Friendly**: Fully aware of display cutouts (camera notches) on phones and scales naturally in Samsung DeX desktop mode with a responsive BottomNavigationView layout.
- **Auto Hardware Detection**: Detects when the Rollo printer is plugged in and prompts for connection permissions automatically.

---

## Hardware Compatibility

| Device | Vendor ID (VID) | Product ID (PID) | Status |
| :--- | :--- | :--- | :--- |
| **Rollo X1038 USB Thermal Printer** | `2501` (`0x09C5`) | `1416` (`0x0588`) | Fully Supported |

> *Note: Compatible with standard TSPL-2 direct thermal label printers over USB.*

---

## How It Works

```
[ PDF Label / Network Job ] ➔ [ 832x1218 Canvas (4x6 @ 203 DPI) ]
                                             │
                                             ▼
[ Rollo USB Hardware ] ◄── [ USB Bulk Stream ] ◄── [ TSPL Command Stream ]
```

1. **Input**: A PDF label is selected manually, shared from WhatsApp, or sent over the local Wi-Fi network.
2. **Render**: The document is formatted and scaled onto an 832 × 1218 pixel canvas matching 4x6 inches at 203 DPI.
3. **Convert**: Pixel luminance is packed into 1-bit monochrome bytes with polarity calibrated specifically for Rollo thermal heads (`0xFF` white background, clearing black bits to `0`).
4. **Stream**: The TSPL command stream is sent directly to the printer over the USB OTG connection.

---

## Getting Started

### Requirements
- **Android Device**: Android 7.0 or newer with USB OTG support (works great on Samsung DeX, phones, and tablets).
- **USB Cable / Hub**: USB OTG adapter or USB-C docking station connecting the phone to the printer.
- **Printer**: Rollo X1038 thermal label printer with 4x6 labels loaded.

### Installation & Download
Download the latest pre-compiled APK from the [Releases](https://github.com/modnite/Cuppa/releases) page and install it on your device.

---

## Changelog

For the complete release history, detailed feature updates, and exact timestamps, see [CHANGELOG.md](CHANGELOG.md).

---

## License

This project is open-source and available under the [MIT License](LICENSE).
