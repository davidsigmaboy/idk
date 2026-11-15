#!/bin/sh -e
# Builds the overlay that turns the Alpine live system into the Umbra kiosk.
# UMBRA_OVERLAY: iso/overlay, UMBRA_WEB: the built web client (both set by build-iso.sh).
HOSTNAME="$1"
[ -n "$HOSTNAME" ] || { echo "usage: $0 hostname"; exit 1; }

cleanup() { rm -rf "$tmp"; }
rc_add() {
	mkdir -p "$tmp"/etc/runlevels/"$2"
	ln -sf /etc/init.d/"$1" "$tmp"/etc/runlevels/"$2"/"$1"
}

tmp="$(mktemp -d)"
trap cleanup EXIT

mkdir -p "$tmp"/etc/apk "$tmp"/usr/share/umbra
cp -a "$UMBRA_OVERLAY"/. "$tmp"/
cp -a "$UMBRA_WEB" "$tmp"/usr/share/umbra/web
echo "$HOSTNAME" > "$tmp"/etc/hostname
cat > "$tmp"/etc/apk/world <<WORLD
alpine-base
chromium
cage
seatd
mesa-dri-gallium
mesa-egl
mesa-gles
mesa-gbm
eudev
dbus
libinput
font-dejavu
font-noto-emoji
python3
openssl
ca-certificates
agetty
doas
WORLD
chmod 0755 "$tmp"/usr/local/bin/* "$tmp"/etc/local.d/*

for s in devfs dmesg mdev; do rc_add "$s" sysinit; done
for s in hwclock modules sysctl hostname bootmisc syslog; do rc_add "$s" boot; done
for s in udev udev-trigger; do rc_add "$s" sysinit; done
for s in networking dbus seatd local; do rc_add "$s" default; done
for s in mount-ro killprocs savecache; do rc_add "$s" shutdown; done

tar -c -C "$tmp" etc usr | gzip -9n > "$HOSTNAME".apkovl.tar.gz
