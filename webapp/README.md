# Cuppa Web

A self-hosted web version of [Cuppa](../README.md): a friendly control panel over
a real CUPS print server, packaged as a Docker image you can pull onto a NAS and
run with the Docker/Compose plugin.

It shares printers over Bonjour/AirPrint and IPP Everywhere, so they show up
automatically in the print dialog on **macOS, iOS/iPadOS and Android** — no
driver, no vendor app, no cloud account.

```
┌──────────────────────────── Cuppa container ────────────────────────────┐
│  CUPS (cupsd)  ── queues, drivers, job spooling ──  port 631            │
│  Avahi         ── Bonjour/AirPrint records ──────── mDNS (host net)     │
│  Cuppa backend ── REST API + control plane ────────  port 8631          │
│  Cuppa web UI  ── the interface you open in a browser ── served by API  │
└─────────────────────────────────────────────────────────────────────────┘
```

## Quick start

The image is built and published by CI, so the NAS only ever pulls.

1. Copy the compose file and (optionally) an env file next to it:

   ```sh
   mkdir cuppa && cd cuppa
   curl -O https://raw.githubusercontent.com/modnite/Cuppa/main/webapp/docker-compose.yml
   curl -o .env https://raw.githubusercontent.com/modnite/Cuppa/main/webapp/.env.example
   ```

2. Start it:

   ```sh
   docker compose pull
   docker compose up -d
   ```

3. Open `http://<nas-ip>:8631`.

On the NAS's Docker plugin (UGREEN UGOS, Synology, QNAP, etc.) you can paste the
same compose file into the "Project" / "Compose" editor instead.

On **OpenMediaVault**, use the Compose plugin (**Services → Compose → Files → Add**)
and paste [`compose.omv.yml`](compose.omv.yml) instead — it uses OMV's
`CHANGE_TO_COMPOSE_DATA_PATH` so data lands on your storage pool.

> **Host networking is required.** Bonjour/AirPrint discovery is multicast, and
> Docker's default bridge network hides it. The compose file sets
> `network_mode: host`, which also lets CUPS see printers on the LAN.

> **Package visibility.** GHCR packages are private by default. Either set the
> `cuppa-web` package to Public (Package settings → Change visibility), or log the
> NAS in first: `echo <PAT-with-read:packages> | docker login ghcr.io -u <owner> --password-stdin`.

## Sharing a printer

1. **Printers → Add printer**.
2. Pick a printer found on the network, or type its IPP address
   (`ipp://192.168.1.50:631/ipp/print`).
3. Give it a name. Other devices will see it as **`<your name> (Cuppa)`**.
4. Add it. It is now advertised and ready.

### Renaming

Open a printer and choose **Rename**. The name you type becomes the Bonjour
service name with `" (Cuppa)"` appended automatically. The internal CUPS queue
name stays put, so jobs and settings are preserved and clients keep a stable
identity.

The `(Cuppa)` suffix is what tells you at a glance, in the macOS/iOS/Android
print dialog, that a printer is being shared through this server.

### Connect a client

- **macOS** — System Settings → Printers & Scanners → Add Printer. It appears
  under "Nearby Printers", no driver to choose.
- **iPhone / iPad** — Share → Print in any app, then pick it.
- **Android** — app print menu or Settings → Connected devices → Printing
  (IPP Everywhere / Mopria).

## Configuration

Environment variables (see `.env.example`):

| Variable | Default | Meaning |
| --- | --- | --- |
| `CUPPA_WEB_PORT` | `8631` | Web UI/API port on the host. |
| `CUPPA_IPP_PORT` | `631` | IPP port clients print to. |
| `CUPPA_ADMIN_PASSWORD` | *(empty)* | Optional admin password applied on first start. |
| `CUPPA_AVAHI_MODE` | `auto` | `auto` uses the host's Avahi when the mounted D-Bus has one, otherwise runs Avahi in the container. `host`/`container` force one. |
| `CUPPA_DATA_DIR` | `/data` | Where settings and printer names persist. |
| `TZ` | `UTC` | Timezone for job timestamps. |

The compose files mount the host's D-Bus at `/host-dbus`. Only one process can
own mDNS port 5353 on a host, so on a NAS that already runs Avahi (OMV,
Synology) Cuppa automatically uses it instead of starting a second responder.
On a host without Avahi it runs its own.

The admin password can also be set, changed or removed at any time in
**Settings → Security**. Authentication is **off by default**.

### Data and persistence

Everything lives under the `./data` folder next to the compose file:

- `data/cuppa.json` — Cuppa's settings and the printer names you chose.
- `data/cups/` — the CUPS queues, PPDs and TLS certificate.

Both are mounted into the container, so printers and their names survive a
`docker compose pull` and restart. Back up `./data` and you have backed up
everything.

## USB printers

Network/AirPrint printers need nothing special. USB printers work too — the
compose file already passes through `/dev/bus/usb`, so CUPS sees them:

- **Thermal / label printers** (e.g. the Rollo X1038): add as a thermal printer
  and pick the command language. Cuppa encodes the label itself and writes it
  through a paced backend. These printers (and the Xprinter/Munbyn/Phomemo
  rebrands) must be driven through the kernel `usblp` device, not libusb: CUPS'
  stock USB backend detaches `usblp`, after which the printer ACKs the transfer,
  prints nothing and wedges. Cuppa writes to `/dev/usb/lpN` directly for exactly
  this reason.
- **USB IPP / AirPrint printers**: add them by their IPP address (they usually
  expose one), or upload the vendor PPD if the printer does not speak IPP
  Everywhere.

On some NAS firmware USB still needs `privileged: true` (commented in the
compose file).

### Brother laser printers

Monochrome Brother lasers (e.g. the MFC-L2717DW) frequently expose only their
raw port and cannot interpret a PDF. Sending one straight to the printer shows
up as **endless blank pages**. Cuppa bundles the open-source
[`brlaser`](https://github.com/pdewacht/brlaser) driver and selects it
automatically when a discovered Brother falls back to a raw queue. When adding
one by hand, choose **Driver → Specific driver…** and pick a brlaser/Brother
entry.

## Thermal label printers

Cuppa carries the same thermal command engines as the Android app, so a label
printer added here works from **any** device — macOS, iOS and Android all print
a normal PDF and Cuppa turns it into the printer's own language.

1. **Printers → Add printer**, pick the device, then turn on **Thermal label
   printer**.
2. Choose the dialect, label size, density, speed, dithering and polarity, then
   add it.
3. Other devices see `<name> (Cuppa)` and can print to it like any other queue.
   **Test print** sends a diagnostic label.

Supported dialects:

| Dialect | Hardware |
| --- | --- |
| `tspl` | Rollo X1038 and TSC label printers (the Rollo's `~@` reset and 832-dot head centring are handled) |
| `zpl` | Zebra ZD/GK and ZPL II label printers |
| `epl` | Eltron / Zebra LP2844 EPL2 label printers |
| `escpos` | ESC/POS receipt printers |
| `pcl` | Generic PCL raster (laser/inkjet fallback) |

Each queue gets a generated PPD that routes PDF and PostScript through the
`cuppa-thermal` CUPS filter, and its settings live in `data/thermal/<queue>.json`
(part of the persistent volume). Edit them any time with the gear button on the
printer card.

## Diagnostics

The **Diagnostics** page collects everything needed to work out why a printer
will not print, and lets you copy it with one click:

- **Network printers** are probed live: a TCP reachability check, then an IPP
  query for the printer's own state, state reasons, model and supply levels
  (toner/ink/paper). Every network queue is listed, and any discovered or
  manually entered address can be probed the same way — so a wireless printer
  Cuppa is not yet printing to can be diagnosed too.
- The exact device URI behind every queue (`lpstat -v`), so you can see whether a
  thermal USB queue is really on the paced `cuppa-usb://` backend.
- The last 200 lines of the CUPS error log.
- USB devices and their driver tree (`lsusb`, `lsusb -t`), and the kernel device
  nodes.
- The PPD options each queue exposes (`lpoptions -l`).
- The running services, the mDNS/Avahi sockets, and the active `cupsd.conf`.

For USB printers it also offers a **USB self-test**. This sends a diagnostic
label directly to the printer without the scheduler or spooler: a paced and an
unpaced write to the kernel `usblp` device, then the queue's real backend end to
end. The kernel path runs first on purpose — the libusb backend wedges these
printers, so it must never run before the path that actually prints.

## Drivers

Cuppa prefers **IPP Everywhere (driverless)** for any printer that speaks it —
which is nearly everything made in the last decade, including the office
Brother. For the rare printer that still needs a driver, the image bundles
[`brlaser`](https://github.com/pdewacht/brlaser) for Brother lasers, and you can
**upload any PPD** when adding a printer, or install one on an existing queue
from the printer card.

If a printer is missing from the list — or only a near-identical model is listed
— you can **upload its PPD** when adding it, or install one on an existing queue
from the printer card. A PPD from the vendor, or from a compatible model, is
usually all that is needed.

## Building the image locally

```sh
cd webapp
docker build -t cuppa-web .
docker run --rm --network host -v "$PWD/data:/data" cuppa-web
```

Or with Compose, building instead of pulling:

```sh
cd webapp
docker compose -f docker-compose.yml -f docker-compose.build.yml up -d --build
```

## Development

Two processes: the backend and Vite's dev server (which proxies `/api`).

```sh
# terminal 1 — backend (needs CUPS reachable at 127.0.0.1:631)
cd server && npm install && npm run dev

# terminal 2 — web UI
cd web && npm install && npm run dev
# open http://localhost:5173
```

Tests:

```sh
cd server && npm test
```

The tests cover the naming rules (identical to the Android app) and the IPP
codec.

## How it fits together

- **`server/`** — Node/TypeScript. Reads printer and job state over IPP,
  performs add/rename/remove/share through the standard CUPS tools
  (`lpadmin`, `lp`, `cancel`, `cupsenable`), and writes Avahi service records.
- **`web/`** — React/TypeScript UI, built to static files and served by the
  backend. macOS-native-inspired: system type, translucent sidebar, light/dark
  from the OS setting.
- **`docker/`** — `cupsd.conf` (CUPS with its own DNS-SD turned off), the Avahi
  config, and a supervisord config that runs CUPS, Avahi and the backend
  together. The image also installs the `cuppa-thermal` CUPS filter.

Cuppa publishes its own Bonjour records rather than letting CUPS do it, so the
advertised name is exactly what you chose plus `" (Cuppa)"` — CUPS would
otherwise decorate it with a host suffix. The TXT records mirror the Android
app's `NetworkPrinterAdvertiser` so a printer shared from either front end looks
identical to clients.
