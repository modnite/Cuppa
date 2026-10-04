# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1 — build the web UI
# ---------------------------------------------------------------------------
FROM node:22-bookworm-slim AS web-build
WORKDIR /build/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ---------------------------------------------------------------------------
# Stage 2 — build the backend
# ---------------------------------------------------------------------------
FROM node:22-bookworm-slim AS server-build
WORKDIR /build/server
COPY server/package.json server/package-lock.json ./
RUN npm ci
COPY server/ ./
RUN npm run build && npm prune --omit=dev

# ---------------------------------------------------------------------------
# Stage 3 — runtime: CUPS + Avahi + Node
# ---------------------------------------------------------------------------
FROM node:22-bookworm-slim AS runtime

ENV DEBIAN_FRONTEND=noninteractive \
    CUPPA_DATA_DIR=/data \
    CUPPA_WEB_PORT=8631 \
    CUPPA_IPP_PORT=631 \
    CUPPA_WEB_ROOT=/app/web

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        cups \
        cups-client \
        cups-filters \
        cups-ppdc \
        avahi-daemon \
        avahi-utils \
        dbus \
        ghostscript \
        poppler-utils \
        supervisor \
        tini \
        ca-certificates \
        curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=server-build /build/server/dist ./server/dist
COPY --from=server-build /build/server/node_modules ./server/node_modules
COPY --from=server-build /build/server/package.json ./server/package.json
COPY --from=web-build /build/web/dist ./web

COPY docker/ /etc/cuppa/
RUN chmod +x /etc/cuppa/entrypoint.sh

# cupsd.conf replaces the distro default; avahi-daemon.conf likewise.
RUN cp /etc/cuppa/cupsd.conf /etc/cups/cupsd.conf \
    && cp /etc/cuppa/avahi-daemon.conf /etc/avahi/avahi-daemon.conf \
    && mkdir -p /etc/cuppa/cups-defaults \
    && cp -a /etc/cups/. /etc/cuppa/cups-defaults/

# Install the thermal filter. CUPS runs it for PDF/PostScript jobs on thermal
# queues; the wrapper just hands the filter arguments to the compiled backend.
RUN printf '#!/bin/sh\nexec /usr/local/bin/node /app/server/dist/thermal/filter.js "$@"\n' \
        > /usr/lib/cups/filter/cuppa-thermal \
    && chmod 0755 /usr/lib/cups/filter/cuppa-thermal

VOLUME ["/data"]
EXPOSE 631 8631

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${CUPPA_WEB_PORT}/healthz" || exit 1

ENTRYPOINT ["/usr/bin/tini", "--", "/etc/cuppa/entrypoint.sh"]
