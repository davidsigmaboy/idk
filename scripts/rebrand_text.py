#!/usr/bin/env python3
"""Replaces the visible name Ghostgram with Umbra in the iOS .strings resources (app name, permission texts).
usage: rebrand_text.py <ghostgram-root>
"""
import glob
import os
import sys

root = sys.argv[1]
changed = 0
for path in glob.glob(os.path.join(root, "Telegram/Telegram-iOS/**/*.strings"), recursive=True):
    with open(path, "rb") as f:
        raw = f.read()
    for enc in ("utf-8", "utf-16"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            text = None
    if text is None or "Ghostgram" not in text:
        continue
    with open(path, "wb") as f:
        f.write(text.replace("Ghostgram", "Umbra").encode(enc))
    changed += 1
print("strings files rebranded:", changed)
