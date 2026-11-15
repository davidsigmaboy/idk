#!/usr/bin/env python3
# Umbra - Copyright (c) 2026 Anonymous Author. Grumpy Anonymous Author-First Source-Available Noncommercial License, see LICENSE.md and NOTICE.md.
"""Serves the Umbra web client on 127.0.0.1:8080.
The client is built with placeholder Telegram API credentials; this server swaps them for the ones the
person typed on the first screen, in memory, so no key is ever stored inside the ISO."""
import json, mimetypes, os, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

ROOT = "/usr/share/umbra/web"
KEYS = os.path.expanduser("~/.config/umbra/keys.json")
SENTINEL_ID = b"90000001"
SENTINEL_HASH = b"umbra00000000000000000000000000x"

SETUP = """<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>Umbra</title>
<style>body{font:18px system-ui;background:#fdf0f6;color:#3a1226;display:grid;place-items:center;height:100vh;margin:0}
form{background:#fff;padding:32px 36px;border-radius:24px;box-shadow:0 8px 40px #e8a0c040;width:min(440px,90vw)}
h1{margin:0 0 6px}input{width:100%;box-sizing:border-box;font-size:18px;padding:12px;margin:6px 0 14px;border:1px solid #e0b0c8;border-radius:12px}
button{font-size:18px;padding:12px 22px;border:0;border-radius:12px;background:#e0558f;color:#fff;width:100%}small{color:#7a5568}</style>
<form method=post action=/umbra-setup><h1>Umbra</h1>
<small>Нужны твои собственные ключи Telegram API: my.telegram.org → API development tools.</small><br><br>
api_id<input name=api_id inputmode=numeric required>
api_hash<input name=api_hash required>
<button>Войти</button></form>"""


def load_keys():
    try:
        with open(KEYS) as f:
            k = json.load(f)
        if str(k.get("api_id", "")).isdigit() and len(k.get("api_hash", "")) >= 16:
            return k
    except Exception:
        pass
    return None


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body, ctype, extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if urlparse(self.path).path != "/umbra-setup":
            return self.send(404, b"", "text/plain")
        data = parse_qs(self.rfile.read(int(self.headers.get("Content-Length", 0))).decode())
        api_id = data.get("api_id", [""])[0].strip()
        api_hash = data.get("api_hash", [""])[0].strip()
        if not api_id.isdigit() or len(api_hash) != 32:
            return self.send(400, "api_id — число, api_hash — 32 символа".encode(), "text/plain; charset=utf-8")
        os.makedirs(os.path.dirname(KEYS), exist_ok=True)
        with open(KEYS, "w") as f:
            json.dump({"api_id": api_id, "api_hash": api_hash}, f)
        os.chmod(KEYS, 0o600)
        self.send(303, b"", "text/plain", {"Location": "/"})

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/umbra-ping":
            return self.send(200, b"ok", "text/plain")
        keys = load_keys()
        if path == "/umbra-setup" or keys is None:
            return self.send(200, SETUP.encode(), "text/html; charset=utf-8")
        rel = path.lstrip("/") or "index.html"
        full = os.path.normpath(os.path.join(ROOT, rel))
        if not full.startswith(ROOT) or not os.path.isfile(full):
            full = os.path.join(ROOT, "index.html")
        with open(full, "rb") as f:
            body = f.read()
        ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
        if full.endswith((".js", ".mjs", ".html", ".json", ".webmanifest")):
            body = body.replace(SENTINEL_ID, keys["api_id"].encode()).replace(SENTINEL_HASH, keys["api_hash"].encode())
        if ctype.startswith("text/") or "javascript" in ctype or "json" in ctype:
            ctype += "; charset=utf-8"
        self.send(200, body, ctype, {"Service-Worker-Allowed": "/"})


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 8080), H).serve_forever()
