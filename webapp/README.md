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
| `CUPPA_DATA_DIR` | `/data` | Where settings and printer names persist. |
| `TZ` | `UTC` | Timezone for job timestamps. |

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

Network/AirPrint printers need nothing special. To share a printer plugged into
the NAS over USB, uncomment the device mount in `docker-compose.yml`:

```yaml
volumes:
  - ./data:/data
  - /dev/bus/usb:/dev/bus/usb
```

Some NAS firmware also needs `privileged: true` (commented in the compose file).

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
