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


# ---- Umbra icon (replaces Nekogram's launcher icon) ----
from PIL import Image
res = root / "TMessagesProj/src/main/res"
logo = Image.open(pathlib.Path(__file__).resolve().parent.parent / "branding/icon.jpg").convert("RGB")
side = max(logo.size)
square = Image.new("RGB", (side, side), (255, 255, 255))
square.paste(logo, ((side - logo.width) // 2, (side - logo.height) // 2))
for d, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    folder = res / f"mipmap-{d}"
    assert folder.is_dir(), folder
    for old in folder.glob("ic_launcher*"):
        old.unlink()
    square.resize((px, px), Image.LANCZOS).save(folder / "ic_launcher.png")
big = square.resize((432, 432), Image.LANCZOS)
(res / "drawable-nodpi").mkdir(exist_ok=True)
big.save(res / "drawable-nodpi/umbra_logo.png")
(res / "drawable/umbra_logo_fg.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<inset xmlns:android="http://schemas.android.com/apk/res/android" '
    'android:drawable="@drawable/umbra_logo" android:inset="17%"/>\n')
(res / "mipmap-anydpi-v26/ic_launcher.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <background android:drawable="@color/umbra_white" />\n'
    '    <foreground android:drawable="@drawable/umbra_logo_fg" />\n'
    '</adaptive-icon>\n')
(res / "values/umbra_colors.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources><color name="umbra_white">#FFFFFF</color></resources>\n')

# ---- crash screen: shows the error text instead of silently closing the app ----
java = root / "TMessagesProj/src/main/java/app/umbra"
java.mkdir(parents=True, exist_ok=True)
(java / "CrashReporter.java").write_text(r"""package app.umbra;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Umbra: if the app dies, show the error text on a screen that can be copied or photographed. */
public final class CrashReporter {
    private CrashReporter() {
    }

    public static boolean isCrashProcess() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/cmdline"))) {
            String name = r.readLine();
            return name != null && name.trim().endsWith(":crash");
        } catch (Throwable ignore) {
            return false;
        }
    }

    public static void install(final Context context) {
        if (isCrashProcess()) {
            return;
        }
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                Intent intent = new Intent(context, CrashActivity.class);
                intent.putExtra("trace", sw.toString());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                context.startActivity(intent);
                Thread.sleep(600);
            } catch (Throwable ignore) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                Process.killProcess(Process.myPid());
            }
        });
    }

    public static class CrashActivity extends Activity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            final String trace = getIntent().getStringExtra("trace");
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(32, 64, 32, 32);
            TextView title = new TextView(this);
            title.setText("Umbra crashed. Tap Copy and send this text.");
            title.setTextSize(18);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            root.addView(title);
            Button copy = new Button(this);
            copy.setText("Copy");
            copy.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("crash", trace));
            });
            root.addView(copy);
            ScrollView scroll = new ScrollView(this);
            TextView text = new TextView(this);
            text.setText(trace);
            text.setTextSize(11);
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextIsSelectable(true);
            scroll.addView(text);
            root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            setContentView(root);
        }
    }
}
""")
manifest = "TMessagesProj/src/main/AndroidManifest.xml"
edit(manifest, sub("    </application>", '        <activity android:name="app.umbra.CrashReporter$CrashActivity" android:process=":crash" android:exported="false" android:excludeFromRecents="true" />\n    </application>'))
loader = "TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java"
edit(loader, sub("        super.attachBaseContext(base);\n        FirebaseFix.check(base);", "        super.attachBaseContext(base);\n        app.umbra.CrashReporter.install(base);\n        FirebaseFix.check(base);"))
edit(loader, sub("    public void onCreate() {\n        applicationLoaderInstance = this;", "    public void onCreate() {\n        if (app.umbra.CrashReporter.isCrashProcess()) {\n            super.onCreate();\n            return;\n        }\n        applicationLoaderInstance = this;"))
print("icon and crash screen added")
