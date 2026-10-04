#!/bin/sh
# Prepares the container and hands off to supervisord, which runs cupsd and the
# Cuppa backend (and, unless the host already provides mDNS, Avahi too).
set -e

DATA_DIR="${CUPPA_DATA_DIR:-/data}"

mkdir -p \
  /run/dbus /var/run/dbus \
  /run/avahi-daemon /var/run/avahi-daemon \
  /var/spool/cups /var/cache/cups /var/log/cups \
  /etc/avahi/services \
  "$DATA_DIR" "$DATA_DIR/thermal"

# CUPS' own state (queues, PPDs, certificates) lives in /etc/cups. When that
# directory is a mounted volume it starts empty, so seed it from the defaults
# baked into the image. This is what makes printers survive a container update.
if [ ! -f /etc/cups/cupsd.conf ]; then
  echo "Seeding CUPS configuration into the empty volume"
  mkdir -p /etc/cups
  cp -a /etc/cuppa/cups-defaults/. /etc/cups/
fi
mkdir -p /etc/cups/ppd /etc/cups/ssl

# Let the root user (the only user here) administer CUPS without a password.
if ! grep -q '^SystemGroup' /etc/cups/cups-files.conf 2>/dev/null; then
  echo 'SystemGroup root lpadmin' >> /etc/cups/cups-files.conf
fi

# Decide how mDNS is provided.
#   host      = use the host's Avahi (mounted at /host-dbus). Required when the
#               host already runs mDNS; two responders on one host fight over 5353.
#   container = run Avahi in this container (works on a host without Avahi).
#   auto      = host if the mounted host D-Bus has Avahi, otherwise container.
HOST_BUS=/host-dbus/system_bus_socket

host_avahi_available() {
  [ -S "$HOST_BUS" ] || return 1
  DBUS_SYSTEM_BUS_ADDRESS="unix:path=$HOST_BUS" \
    dbus-send --system --print-reply --dest=org.freedesktop.Avahi \
      / org.freedesktop.Avahi.Server.GetHostName >/dev/null 2>&1
}

case "${CUPPA_AVAHI_MODE:-auto}" in
  host)      MODE=host ;;
  container) MODE=container ;;
  *)         if host_avahi_available; then MODE=host; else MODE=container; fi ;;
esac

if [ "$MODE" = "host" ]; then
  echo "mDNS: using the host's Avahi (CUPPA_AVAHI_MODE=host)"
  export CUPPA_AVAHI_MODE=host
  export DBUS_SYSTEM_BUS_ADDRESS="unix:path=$HOST_BUS"
  exec /usr/bin/supervisord -c /etc/cuppa/supervisord-host-avahi.conf
fi

echo "mDNS: running Avahi in the container (CUPPA_AVAHI_MODE=container)"
export CUPPA_AVAHI_MODE=container

# D-Bus and Avahi both want a machine id.
if [ ! -s /etc/machine-id ] && [ ! -s /var/lib/dbus/machine-id ]; then
  dbus-uuidgen --ensure || true
fi

# The system bus Avahi talks to.
if [ ! -S /run/dbus/system_bus_socket ]; then
  dbus-daemon --system --fork
fi

exec /usr/bin/supervisord -c /etc/cuppa/supervisord.conf
