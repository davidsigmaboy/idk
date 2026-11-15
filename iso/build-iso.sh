#!/bin/sh -e
# Umbra - Copyright (c) 2026. Non-Commercial Source-Available License (NCSAL) v1.0, see LICENSE.md and NOTICE.md.
# Runs inside an Alpine aarch64 container. /work = the repository, /web = the built client, /out = result.
apk add --no-cache alpine-sdk alpine-conf xorriso squashfs-tools grub grub-efi mtools dosfstools sudo git
adduser -D build
addgroup build abuild
echo "build ALL=(ALL) NOPASSWD: ALL" > /etc/sudoers.d/build
su build -c "abuild-keygen -a -i -n" || true
set -e
su build -c "git clone --depth 1 --branch 3.22-stable https://github.com/alpinelinux/aports.git /home/build/aports"
cp /work/iso/mkimg.umbra.sh /home/build/aports/scripts/
cp /work/iso/genapkovl-umbra.sh /home/build/aports/scripts/
chown build /home/build/aports/scripts/mkimg.umbra.sh /home/build/aports/scripts/genapkovl-umbra.sh
mkdir -p /out && chown build /out
su build -c "cd /home/build/aports/scripts && UMBRA_OVERLAY=/work/iso/overlay UMBRA_WEB=/web sh mkimage.sh \
  --tag umbra --outdir /out --arch aarch64 --profile umbra --workdir /home/build/work \
  --repository https://dl-cdn.alpinelinux.org/alpine/v3.22/main \
  --repository https://dl-cdn.alpinelinux.org/alpine/v3.22/community"
test -n "$(ls /out/*.iso)"
ls -la /out
