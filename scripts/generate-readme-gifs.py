#!/usr/bin/env python3
"""Compose the real popup chrome (name, status, battery) over included media and export README GIFs."""
from pathlib import Path
import subprocess
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "store" / "assets"
OUT = ROOT / "store" / "gifs"
OUT.mkdir(parents=True, exist_ok=True)
W, H = 720, 480
NAME_FONT = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"
TEXT_FONT = "/System/Library/Fonts/Supplemental/Arial.ttf"

DEVICES = [
    ("airpods_white", "AirPods Pro", "85%", True, "popup-airpods.gif"),
    ("peugeot", "Peugeot 3008", "--%", False, "popup-peugeot.gif"),
]

def font(path, size):
    return ImageFont.truetype(path, size)

def overlay(name, battery, known):
    image = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    # Top scrim for legibility over any media.
    for y in range(170):
        alpha = int(150 * (1 - y / 170) ** 1.6)
        draw.line([(0, y), (W, y)], fill=(0, 0, 0, alpha))
    # Drag handle.
    draw.rounded_rectangle((W // 2 - 32, 14, W // 2 + 32, 22), radius=4, fill=(255, 255, 255, 150))
    # Device name with a soft shadow.
    name_font = font(NAME_FONT, 34)
    draw.text((40, 44), name, font=name_font, fill=(0, 0, 0, 140))
    draw.text((38, 41), name, font=name_font, fill=(255, 255, 255, 255))
    # Connection status.
    status_font = font(TEXT_FONT, 22)
    draw.ellipse((40, 94, 52, 106), fill=(48, 209, 88, 255))
    draw.text((60, 87), "Connecté", font=status_font, fill=(224, 224, 224, 255))
    # Battery badge.
    text_font = font(NAME_FONT, 26)
    badge_color = (255, 255, 255, 38)
    border_color = (255, 255, 255, 77)
    green = (52, 199, 89, 255) if known else (150, 150, 155, 255)
    text_w = draw.textlength(battery, font=text_font)
    badge_w = int(24 + 22 + 14 + text_w + 24)
    left = W - 40 - badge_w
    top = 36
    draw.rounded_rectangle((left, top, left + badge_w, top + 48), radius=24, fill=badge_color, outline=border_color, width=2)
    # Battery outline and nub.
    bx, by = left + 24, top + 17
    draw.rounded_rectangle((bx, by, bx + 22, by + 14), radius=2, outline=green, width=2)
    draw.rectangle((bx + 22, by + 4, bx + 25, by + 10), fill=green)
    if known:
        draw.rectangle((bx + 3, by + 3, bx + 3 + 14, by + 11), fill=green)
    draw.text((bx + 38, top + 9), battery, font=text_font, fill=(255, 255, 255, 255) if known else (200, 200, 205, 255))
    return image

def main():
    for media, name, battery, known, gif_name in DEVICES:
        source = ASSETS / f"{media}.mp4"
        if not source.exists():
            raise SystemExit(f"Missing media: {source}")
        shot = overlay(name, battery, known)
        overlay_path = OUT / f".{media}-overlay.png"
        shot.save(overlay_path)
        target = OUT / gif_name
        subprocess.run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-i", str(source), "-i", str(overlay_path),
            "-filter_complex",
            "[0:v]fps=15,scale=720:480:force_original_aspect_ratio=increase,crop=720:480,setsar=1[bg];"
            "[bg][1:v]overlay=0:0,scale=480:320:flags=lanczos,split[s0][s1];"
            "[s0]palettegen=max_colors=128:stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3",
            "-loop", "0", str(target),
        ], check=True)
        overlay_path.unlink()
        print(f"{target.relative_to(ROOT)} — {target.stat().st_size / 1024:.0f} KB")

if __name__ == "__main__":
    main()
