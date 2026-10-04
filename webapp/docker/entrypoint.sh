#!/bin/sh
# Prepares the container and hands off to supervisord, which runs cupsd,
# avahi-daemon and the Cuppa backend together.
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

# D-Bus and Avahi both want a machine id.
if [ ! -s /etc/machine-id ] && [ ! -s /var/lib/dbus/machine-id ]; then
  dbus-uuidgen --ensure || true
fi

# Let the root user (the only user here) administer CUPS without a password.
if ! grep -q '^SystemGroup' /etc/cups/cups-files.conf 2>/dev/null; then
  echo 'SystemGroup root lpadmin' >> /etc/cups/cups-files.conf
fi

# The system bus Avahi talks to.
if [ ! -S /run/dbus/system_bus_socket ]; then
  dbus-daemon --system --fork
fi

exec /usr/bin/supervisord -c /etc/cuppa/supervisord.conf
