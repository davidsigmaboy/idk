#!/usr/bin/env python3
"""Turns a Nekogram checkout into the Umbra Android client (interface of Nekogram, Umbra's name and keys).
Usage: neko_brand.py <nekogram-dir> <api_id> <api_hash> <keystore> <store_pass> <build_number>
Every edit asserts that it found its target, so an upstream change fails loudly instead of silently."""
import json, os, pathlib, re, sys

root = pathlib.Path(sys.argv[1])
api_id, api_hash, keystore, store_pass, build = sys.argv[2:7]
PACKAGE = "app.umbra.client"


def edit(path, fn):
    p = root / path
    s = p.read_text()
    n = fn(s)
    assert n != s, f"no change in {path}"
    p.write_text(n)


def sub(old, new, count=1):
    def f(s):
        assert old in s, f"missing: {old[:60]}"
        return s.replace(old, new, count)
    return f


# package, version, name
edit("gradle.properties", lambda s: re.sub(r"APP_PACKAGE=.*", f"APP_PACKAGE={PACKAGE}", s))
edit("gradle.properties", lambda s: re.sub(r"APP_VERSION_CODE=\d+", f"APP_VERSION_CODE={build}", s, count=1))
edit("gradle.properties", lambda s: re.sub(r"APP_VERSION_NAME=.*", f"APP_VERSION_NAME=umbra-{build}", s, count=1))
edit("TMessagesProj/src/main/res/values/strings.xml", sub('<string name="AppName">Telegram</string>', '<string name="AppName">Umbra</string>'))

app = "TMessagesProj_App/build.gradle"
# no Sentry upload, no minify (CI time), one ABI (arm64), our own signing
edit(app, sub("    id 'io.sentry.android.gradle'\n", ""))
edit(app, lambda s: re.sub(r"sentry \{.*?\n\}\n", "", s, count=1, flags=re.S))
edit(app, sub("            minifyEnabled true\n            shrinkResources true\n", "            minifyEnabled false\n            shrinkResources false\n"))
edit(app, sub("            ndk.debugSymbolLevel 'FULL'\n", ""))
edit(app, lambda s: re.sub(r"    splits\.abi \{.*?\n    \}\n", "    defaultConfig.ndk.abiFilters.add('arm64-v8a')\n", s, count=1, flags=re.S))
edit(app, sub('"Nekogram-${output.versionName.get()}-${baseVersionCode}-${abiName}.apk"', '"Umbra.apk"'))
edit(app, sub("output.versionCode.set(baseVersionCode * 10)", "output.versionCode.set(baseVersionCode)"))
edit(app, sub("manifestPlaceholders = [MAPS_API_KEY: localProperties.get(\"mapsApiKey\")]", "manifestPlaceholders = [MAPS_API_KEY: localProperties.getProperty(\"mapsApiKey\", \"\")]"))

(root / "local.properties").write_text("\n".join([
    f"apiId={api_id}", f"apiHash={api_hash}", "sentryDsn=", "mapsApiKey=",
    f"storeFile={keystore}", f"storePassword={store_pass}", f"keyAlias=umbra", f"keyPassword={store_pass}", ""]))

# Firebase needs a google-services.json that names the package; a placeholder keeps the plugin happy.
(root / "TMessagesProj_App/google-services.json").write_text(json.dumps({
    "project_info": {"project_number": "1", "project_id": "umbra-local", "storage_bucket": "umbra-local.appspot.com"},
    "client": [{
        "client_info": {"mobilesdk_app_id": "1:1:android:0000000000000000", "android_client_info": {"package_name": PACKAGE}},
        "api_key": [{"current_key": "placeholder"}]}],
    "configuration_version": "1"}))
print("branded")
