# Cuppa — Development Journey & Engineering Progress Report

> **Author:** Project Lead & Core Developer  
> **Engineering Partner:** Gemini AI Coding Assistant  
> **Repository:** [modnite/Cuppa](https://github.com/modnite/Cuppa)  
> **Target Hardware:** Rollo X1038 / Thermal Printers via USB OTG Bulk Streaming  

---

## Introduction: Why I Built Cuppa

In our office, the Rollo thermal printer was originally plugged into our boss's Windows computer. When that PC went down, printing shipping labels ground to a halt. Whenever a label arrived via WhatsApp from our boss, I felt completely useless not being able to print it directly. Getting a label printed required someone in the office to physically plug the printer into their MacBook, log into WhatsApp Web, download the PDF, and print it.

I rely heavily on my **Samsung Galaxy S23 paired with Samsung DeX and a docking station**. Whenever my phone is docked at my desk, I wanted a setup where it could connect directly to the Rollo printer over USB and host a wireless print server for the whole office.

To bring this vision to life rapidly, I paired up with my AI co-pilot (Gemini). Together, we architected **Cuppa** (formerly RolloPrint): a zero-dependency, driverless IPP Everywhere / AirPrint CUPS server and USB thermal utility for Android that runs natively, accepts PDF/raster jobs over standard AirPrint / IPP protocols, and streams bit-packed TSPL commands directly to the Rollo USB bulk endpoint.

---

## Milestone 1.0.0 — September 2, 2026 at 12:17 PM: The Core TSPL Engine & USB OTG Bulk Pipeline

My first goal was raw hardware communication over USB OTG. The Rollo X1038 expects monochrome 203 DPI TSPL2 bitmap streams formatted as $832 \times 1218$ pixels for standard $101.6 \times 152.4 \text{ mm}$ (4x6 inch) labels.

### Core Challenges & Breakthroughs:
1. **Bit-Packing & Luminance Thresholding:**
   I built a custom monochrome bitmap packer that iterates over $832 \times 1218$ pixel arrays, calculates ITU-R BT.601 luminance ($0.299R + 0.587G + 0.114B$), thresholding white vs black pixels, and packs 8 pixels per byte across 104 bytes per line ($104 \times 8 = 832$ pixels).
2. **TSPL Command Packaging:**
   Wrapped the monochrome bitmap bytes in a clean TSPL header:
   ```text
   SIZE 104 mm,153 mm
   REFERENCE 0,0
   DIRECTION 0,0
   GAP 3 mm,0 mm
   DENSITY 8
   SPEED 6
   CLS
   BITMAP 0,0,104,1218,1,[mono_bytes]
   PRINT 1,1
   ```
3. **Intent Share Sheet Integration:**
   Added `ACTION_SEND` and `ACTION_VIEW` intent filters so sharing a PDF label from WhatsApp, Email, or File Manager directly opens Cuppa.

---

## Milestone 1.1.0 – 1.3.0 — September 5, 2026 at 10:58 AM: Driverless IPP Everywhere Server & HP `jIPP` Engine

To allow any Linux (CUPS), macOS, or Windows PC on the office network to discover and print to the Rollo thermal printer without installing vendor drivers, I implemented an embedded HTTP/1.1 IPP Everywhere server listening on Port `8631`.

### Highlights:
- **HP `jipp-core` Integration (`com.hp.jipp:jipp-core:0.7.18`):** Replaced custom IPP frame serialization with HP's official, production-grade IPP Everywhere library for 100% PWG 5100.14 compliance.
- **mDNS / Zeroconf Auto-Discovery:** Advertised `_ipp._tcp` on Port 8631 with TXT records (`pdl=image/pwg-raster,application/pdf`, `product=(Rollo Thermal Printer 4x6)`, `printer-type=0x4000000`).
- **CUPS `#PDF-BANNER` Renderer:** When Linux CUPS sent `#PDF-BANNER` test pages, I built a native 4x6 PDF label renderer that generates a crisp **Cuppa Test Page** complete with resolution, protocol specs, and live timestamp.

---

## Milestone 1.5.0 — September 5, 2026 at 4:25 PM: Real-Time Hardware Status Polling & Onboard RAM Purging

When paper ran out during printing, jobs would sit buffered in memory. Working with my AI pair-programmer, I conducted hardware status investigations on the Rollo X1038 USB endpoints to achieve real-time hardware status detection.

### Empirical Hardware Commands Discovered:
- **Status Query (`<ESC>!?` / `0x1B, 0x21, 0x3F`):**
  Sending `<ESC>!?` over USB bulk OUT returns 1 status byte over bulk IN:
  - `0x00`: Printer Ready (Green LED)
  - Bit 0 (`0x01`): Cover / Print Head Open
  - Bits 1/2 (`0x06`): Media Empty / Out of Paper (Red LED)
  - Bit 5 (`0x20`): Paused
- **Queue Purge (`~!C` / `0x7E, 0x21, 0x43`):**
  Sending `~!C` directly over USB immediately purges the Rollo printer's onboard RAM buffer memory and halts label feeding!

I wired status polling into a background thread in `PrintServerService` and added a live **Hardware Status Badge** (`● Hardware: Ready`, `● Hardware: Out of Paper (Red LED)`) on the app header.

---

## Milestone 5.0.0 – 5.5.0 — September 14, 2026: Enterprise Native CUPS Server & Cuppa Rebranding

In Milestone 5.0.0+, Cuppa underwent a complete architectural refactor:
1. **Global Rebranding:** Rebranded the entire application and repository to **Cuppa** with a dedicated coffee cup + printer app icon.
2. **In-App OpenPrinting CUPS Admin Interface:** Replaced old single-card layouts with a 4-tab Material 3 CUPS Server Administration interface (`Printers`, `Jobs`, `Log`, `Admin`).
3. **Exhaustive Stream Decoders:** Native decoders for Apple URF (`image/urf`), PWG Raster, CUPS Raster (`RaS2`), Zebra ZPL, HP PCL, PostScript, PDF, and images.
4. **Local Log Exporting:** Native Android Storage Access Framework (SAF) integration via "Save Log As..." allowing local log exports directly to user-selected folders.

---

## Summary & Current Architecture

Cuppa is now a production-grade, zero-dependency Android utility that turns any Rollo thermal printer into an enterprise driverless AirPrint / IPP network printer.

```
[ Linux / macOS / Windows / iOS / Android ]
             │ (Driverless AirPrint / IPP Everywhere / Port 8631 & RAW 9100)
             ▼
    [ Android Device (Cuppa) ]
             │
             ├── IppServer (jIPP Core Engine) & RawSocketServer
             ├── JobQueueManager (Sequential Queue & Pre-Transfer Hardware Guard)
             ├── UsbPrintManager (<ESC>!? Polling & ~!C Purging)
             ▼ (USB OTG Bulk OUT Stream @ 203 DPI)
    [ Rollo X1038 Thermal Printer ]
```
