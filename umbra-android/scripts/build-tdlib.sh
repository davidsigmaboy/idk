#!/usr/bin/env bash
# Builds the official TDLib (Java interface) for Android and copies it into app/src/tdlib.
# Needs: Android SDK (ANDROID_HOME), JDK 17, git, cmake, ninja, php, perl, gperf, make.
# Takes 20-40 minutes. Result: app/src/tdlib/{java,jniLibs}.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK_VERSION="${NDK_VERSION:-23.2.8568313}"
TD_REF="${TD_REF:-master}"
SDK="${ANDROID_HOME:?set ANDROID_HOME to your Android SDK}"

"$SDK/cmdline-tools/latest/bin/sdkmanager" --install "ndk;$NDK_VERSION" "cmake;3.22.1" "platforms;android-34" "build-tools;34.0.0" >/dev/null

WORK="${WORK:-$ROOT/build/td}"
rm -rf "$WORK"; mkdir -p "$(dirname "$WORK")"
git clone --depth 1 --branch "$TD_REF" https://github.com/tdlib/td.git "$WORK"
cd "$WORK/example/android"
./build-openssl.sh "$SDK" "$NDK_VERSION" third-party/openssl
./build-tdlib.sh "$SDK" "$NDK_VERSION" third-party/openssl c++_static Java 24 "arm64-v8a armeabi-v7a x86_64"

DST="$ROOT/app/src/tdlib"
rm -rf "$DST"; mkdir -p "$DST/jniLibs"
cp -R tdlib/java "$DST/java"
for abi in tdlib/libs/*/; do
  name="$(basename "$abi")"
  mkdir -p "$DST/jniLibs/$name"
  cp "$abi"/*.so "$DST/jniLibs/$name/"
done
echo "TDLib ready in $DST"
