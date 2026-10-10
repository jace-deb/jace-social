"""Phone app icons and splash screens from the Jace Social icon (server/app/icon.png).
Run from mobile/: python3 make_icons.py"""
from pathlib import Path

from PIL import Image, ImageDraw

SRC = Image.open(Path(__file__).parent.parent / "server/app/icon.png").convert("RGBA")
GREEN = SRC.getpixel((256, 40))[:3]                      # the icon's green
INNER = SRC.crop((64, 64, 448, 448))                     # full-bleed green square with the chat bubble
DARK = (0x11, 0x13, 0x17)
RES = Path("android/app/src/main/res")
DENSITY = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def square(size):
    return INNER.resize((size, size), Image.LANCZOS)


def rounded(size, radius):
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), radius, fill=255)
    im.paste(square(size), (0, 0), mask)
    return im


def circle(size):
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(im).ellipse((0, 0, size - 1, size - 1), fill=GREEN + (255,))
    inner = round(size * 0.8)                            # the bubble's tail would touch the edge otherwise
    im.paste(square(inner), ((size - inner) // 2,) * 2, square(inner).convert("L").point(lambda _: 255))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    im.putalpha(mask)
    return im


for name, d in DENSITY.items():
    folder = RES / f"mipmap-{name}"
    legacy = round(48 * d)
    rounded(legacy, legacy // 5).save(folder / "ic_launcher.png")
    circle(legacy).save(folder / "ic_launcher_round.png")
    # adaptive icon: 108dp layer, the launcher shows the middle 72dp
    fg_size = round(108 * d)
    fg = Image.new("RGBA", (fg_size, fg_size), GREEN + (255,))
    inner = round(72 * d)
    fg.paste(square(inner), ((fg_size - inner) // 2,) * 2)
    fg.save(folder / "ic_launcher_foreground.png")

(RES / "values/ic_launcher_background.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
    f'    <color name="ic_launcher_background">#{"%02X%02X%02X" % GREEN}</color>\n</resources>\n')

# splash screens: the icon in the middle of the app's dark background
for splash in RES.glob("drawable*/splash.png"):
    w, h = Image.open(splash).size
    im = Image.new("RGB", (w, h), DARK)
    s = min(w, h) // 3
    icon = rounded(s, s // 5)
    im.paste(icon, ((w - s) // 2, (h - s) // 2), icon)
    im.save(splash)

# iOS: one 1024 px icon without transparency (iOS rounds the corners itself)
ios = Path("ios/App/App/Assets.xcassets/AppIcon.appiconset")
if ios.exists():
    for f in ios.glob("*.png"):
        square(1024).convert("RGB").save(f)
    for splash in Path("ios/App/App/Assets.xcassets/Splash.imageset").glob("*.png"):
        w, h = Image.open(splash).size
        im = Image.new("RGB", (w, h), DARK)
        s = min(w, h) // 4
        icon = rounded(s, s // 5)
        im.paste(icon, ((w - s) // 2, (h - s) // 2), icon)
        im.save(splash)
print("icons done")
