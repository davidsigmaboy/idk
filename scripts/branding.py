#!/usr/bin/env python3
"""Replaces every app icon of the Ghostgram tree with branding/icon.jpg (same pixel sizes as the originals).

usage: branding.py <ghostgram-root> <icon.jpg>
"""
import glob
import os
import sys

from PIL import Image, ImageFilter

root, icon_path = sys.argv[1], sys.argv[2]


def master(path, size=2048):
    im = Image.open(path).convert("L")
    side = max(im.size)
    canvas = Image.new("L", (side, side), 255)           # square, white background
    canvas.paste(im, ((side - im.width) // 2, (side - im.height) // 2))
    big = canvas.resize((size, size), Image.LANCZOS)
    big = big.filter(ImageFilter.GaussianBlur(size / 400))
    big = big.point(lambda v: 0 if v < 100 else (255 if v > 156 else int((v - 100) * 255 / 56)))  # crisp edges
    return big.convert("RGB")


M = master(icon_path)
patterns = [
    "Telegram/Telegram-iOS/*.xcassets/**/*.appiconset/*.png",
    "Telegram/Telegram-iOS/*.alticon/*.png",
    "Telegram/Telegram-iOS/IconDefault*.png",
    "Telegram/Watch/App/Assets.xcassets/AppIcon.appiconset/*.png",
    "Telegram/WatchApp/**/AppIcon.appiconset/*.png",
    "Ghostgram/Features/Interface/Assets/AppIcons/*.alticon/*.png",
]
seen = set()
for pattern in patterns:
    for path in glob.glob(os.path.join(root, pattern), recursive=True):
        if path in seen:
            continue
        seen.add(path)
        with Image.open(path) as old:
            size = old.size
        M.resize(size, Image.LANCZOS).save(path, "PNG")
print("icons replaced:", len(seen))
if not seen:
    sys.exit("no icons found")
