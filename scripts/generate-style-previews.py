#!/usr/bin/env python3
"""Render illustrative Minimal / Lovely / Fanciest popup animations from the Shokz media."""
from pathlib import Path
import math, shutil, subprocess, tempfile
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "store" / "gifs"
OUT.mkdir(parents=True, exist_ok=True)
W, H = 480, 320
FPS = 15
NAME_FONT = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"
TEXT_FONT = "/System/Library/Fonts/Supplemental/Arial.ttf"
SOURCE = ROOT / "store" / "assets" / "shokz.mp4"
BG = (10, 17, 30)
CARD_BG = (18, 24, 36)
CARD_LINE = (58, 70, 90)
WHITE = (245, 248, 255)
MUTED = (190, 201, 219)
GREEN = (52, 199, 89)

def font(path, size): return ImageFont.truetype(path, size)
def out_cubic(x): return 1 - (1 - max(0, min(1, x))) ** 3
def out_back(x):
    x = max(0, min(1, x)); c1 = 1.70158; c3 = c1 + 1
    return 1 + c3 * (x - 1) ** 3 + c1 * (x - 1) ** 2
def cover(img, size):
    tw, th = size; w, h = img.size; scale = max(tw / w, th / h)
    r = img.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.Resampling.LANCZOS)
    left = (r.width - tw) // 2; top = (r.height - th) // 2
    return r.crop((left, top, left + tw, top + th))
def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.width - 1, img.height - 1), radius=radius, fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0)); out.paste(img, (0, 0), mask); return out

def battery_badge(draw, box, value, known=True):
    left, top, right, bottom = box
    draw.rounded_rectangle(box, radius=(bottom - top) // 2, fill=(255, 255, 255, 30), outline=(255, 255, 255, 70), width=1)
    color = GREEN if known else (140, 148, 162)
    bx, by = left + 12, top + 9
    draw.rounded_rectangle((bx, by, bx + 16, by + 10), radius=2, outline=color, width=1)
    draw.rectangle((bx + 16, by + 3, bx + 19, by + 7), fill=color)
    if known: draw.rectangle((bx + 2, by + 2, bx + 11, by + 8), fill=color)
    f = font(NAME_FONT, 16)
    tw = draw.textlength(value, font=f)
    draw.text((left + 36, top + 7), value, font=f, fill=WHITE if known else (170, 178, 192))

def card(media, rect, name="Shokz", battery="85%", minimal=False, alpha=255):
    x, y, w, h = rect
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.rounded_rectangle((0, 0, w - 1, h - 1), radius=22, fill=CARD_BG + (255,), outline=CARD_LINE + (255,), width=1)
    if minimal:
        icon = rounded(cover(media, (72, 72)), 15)
        layer.paste(icon, (16, (h - 72) // 2), icon)
        d.text((104, 38), name, font=font(NAME_FONT, 22), fill=WHITE)
        d.ellipse((105, 78, 112, 85), fill=GREEN)
        d.text((118, 70), "Connecté", font=font(TEXT_FONT, 14), fill=MUTED)
        battery_badge(d, (w - 112, 48, w - 14, 84), battery)
        d.text((w - 34, 10), "×", font=font(TEXT_FONT, 18), fill=MUTED)
    else:
        pic = rounded(cover(media, (w, h)), 22)
        layer.paste(pic, (0, 0), pic)
        for yy in range(min(150, h)):
            a = int(165 * (1 - yy / min(150, h)) ** 1.5)
            d.line((0, yy, w, yy), fill=(0, 0, 0, a))
        d.rounded_rectangle((w // 2 - 18, 10, w // 2 + 18, 15), radius=3, fill=(255, 255, 255, 150))
        d.text((16, 26), name, font=font(NAME_FONT, 22), fill=(0, 0, 0, 150))
        d.text((14, 24), name, font=font(NAME_FONT, 22), fill=WHITE)
        d.ellipse((15, 62, 22, 69), fill=GREEN)
        d.text((28, 54), "Connecté", font=font(TEXT_FONT, 14), fill=(224, 224, 224))
        battery_badge(d, (w - 112, 20, w - 14, 56), battery)
    if alpha < 255:
        a = layer.getchannel("A").point(lambda v: v * alpha // 255)
        layer.putalpha(a)
    return layer

def paste_center(base, layer, cx, cy, scale=1.0, rotate=0):
    if scale != 1.0:
        layer = layer.resize((max(1, int(layer.width * scale)), max(1, int(layer.height * scale))), Image.Resampling.LANCZOS)
    if rotate:
        layer = layer.rotate(rotate, expand=True, resample=Image.Resampling.BICUBIC)
    base.alpha_composite(layer, (int(cx - layer.width / 2), int(cy - layer.height / 2)))

def render(style, frames, target):
    tmp = Path(tempfile.mkdtemp(prefix="pkbt-style-"))
    n = 33 if style == "minimal" else 42
    for i in range(n):
        t = i / FPS
        base = Image.new("RGBA", (W, H), BG + (255,))
        media = frames[(i * 224 // 1000) % len(frames)]
        if style == "minimal":
            card_img = card(media, (0, 0, 380, 132), minimal=True)
            ok = out_cubic(t / 0.35)
            paste_center(base, card_img, W / 2, H / 2, 0.9 + 0.1 * ok)
        elif style == "lovely":
            card_img = card(media, (0, 0, 380, 230))
            y = H - 16 - 230 + (1 - out_back((t - 0.15) / 0.75)) * 230
            base.alpha_composite(card_img, (50, int(y)))
        else:
            scrim = Image.new("RGBA", (W, H), (0, 0, 0, int(150 * out_cubic(t / 0.5))))
            full = cover(media, (W, H)).resize((int(W * (1.06 + 0.10 * (1 - out_cubic(t / 0.7)))), int(H * (1.06 + 0.10 * (1 - out_cubic(t / 0.7))))), Image.Resampling.LANCZOS)
            left = (full.width - W) // 2; top = (full.height - H) // 2
            base.paste(full.crop((left, top, left + W, top + H)), (0, 0))
            base.alpha_composite(scrim)
            card_img = card(media, (0, 0, 380, 230))
            p = out_back((t - 0.25) / 0.65)
            if p > 0:
                paste_center(base, card_img, W / 2, H / 2, 0.55 + 0.45 * p)
        base.convert("RGB").save(tmp / f"f{i:04d}.png")
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-framerate", str(FPS),
                    "-i", str(tmp / "f%04d.png"), "-filter_complex",
                    "split[s0][s1];[s0]palettegen=max_colors=128:stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3",
                    "-loop", "0", str(target)], check=True)
    shutil.rmtree(tmp)
    print(f"{target.relative_to(ROOT)} — {target.stat().st_size / 1024:.0f} KB")

def main():
    tmp = Path(tempfile.mkdtemp(prefix="pkbt-style-src-"))
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(SOURCE),
                    "-vf", "fps=15,scale=480:320:force_original_aspect_ratio=increase,crop=480:320",
                    str(tmp / "s%04d.png")], check=True)
    frames = [Image.open(p).convert("RGBA") for p in sorted(tmp.glob("s*.png"))]
    try:
        render("minimal", frames, OUT / "style-minimal.gif")
        render("lovely", frames, OUT / "style-lovely.gif")
        render("fanciest", frames, OUT / "style-fanciest.gif")
    finally:
        shutil.rmtree(tmp)

if __name__ == "__main__":
    main()
