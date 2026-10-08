"""
Builds every logo asset from the owner's artwork design/logo-3d.png (a glossy 3D "V" on white):
  1. removes the white background (flood fill from the border, soft edges, colour un-matting so there is no white fringe),
  2. writes design/logo-3d-transparent.png,
  3. writes the Android launcher layers (foreground with safe-zone padding, monochrome silhouette), the in-app mark,
     the 512x512 store icon and preview sheets under docs/ui/.
Run: python scripts/gen-logo-3d.py   (needs Pillow, numpy, scipy)
"""
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from scipy import ndimage

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SRC = os.path.join(ROOT, "design", "logo-3d.png")
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
UI = os.path.join(ROOT, "docs", "ui")
for d in (os.path.join(RES, "drawable-nodpi"), os.path.join(RES, "drawable"), UI):
    os.makedirs(d, exist_ok=True)

BG_TOP, BG_BOTTOM = (247, 244, 255), (229, 220, 255)


def cutout(path):
    im = np.asarray(Image.open(path).convert("RGB")).astype(np.float32)
    d = 255.0 - im.min(axis=2)  # how far from white
    reachable = d < 60.0
    labels, n = ndimage.label(reachable)
    border = set(np.unique(np.concatenate([labels[0, :], labels[-1, :], labels[:, 0], labels[:, -1]]))) - {0}
    bg = np.isin(labels, list(border))
    alpha = np.ones(d.shape, np.float32)
    ramp = np.clip((d - 3.0) / 57.0, 0.0, 1.0)
    ramp = ramp * ramp * (3 - 2 * ramp)  # smoothstep
    alpha[bg] = ramp[bg]
    a3 = np.maximum(alpha, 1e-3)[..., None]
    # un-matte: observed = a * C + (1 - a) * white
    color = np.clip((im - (1 - a3) * 255.0) / a3, 0, 255)
    out = np.dstack([color, alpha * 255.0]).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def trim(im, pad_ratio=0.0):
    a = np.asarray(im)[..., 3]
    ys, xs = np.where(a > 20)
    box = (xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)
    c = im.crop(box)
    if pad_ratio:
        p = int(max(c.size) * pad_ratio)
        out = Image.new("RGBA", (c.width + 2 * p, c.height + 2 * p), (0, 0, 0, 0))
        out.paste(c, (p, p))
        return out
    return c


def fit(im, canvas, width_frac, cy_frac=0.5):
    """the logo scaled to a fraction of the canvas width, centred horizontally, vertically at cy_frac"""
    w = int(canvas * width_frac)
    h = int(im.height * w / im.width)
    if h > canvas * 0.9:
        h = int(canvas * 0.9)
        w = int(im.width * h / im.height)
    r = im.resize((w, h), Image.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    out.paste(r, ((canvas - w) // 2, int(canvas * cy_frac - h / 2)), r)
    return out


def background(size):
    top, bottom = BG_TOP, BG_BOTTOM
    ys, xs = np.mgrid[0:size, 0:size].astype(np.float32)
    t = np.clip((xs / size * 0.45 + ys / size * 0.7), 0, 1)[..., None]
    arr = np.array(top, np.float32) + (np.array(bottom, np.float32) - np.array(top, np.float32)) * t
    return Image.fromarray(np.dstack([arr, np.full((size, size), 255.0)]).astype(np.uint8), "RGBA")


full = cutout(SRC)
full.save(os.path.join(ROOT, "design", "logo-3d-transparent.png"))
tight = trim(full)

# in-app mark: tight crop with a little air, 512 px
mark = trim(full, 0.04).resize((512, int(512 * trim(full, 0.04).height / trim(full, 0.04).width)), Image.LANCZOS)
mark.save(os.path.join(RES, "drawable-nodpi", "logo_mark.png"))

# adaptive icon layers are 108 dp; 432 px is xxxhdpi. The safe zone is the centre 66 dp: keep the V within about 60 percent of the width.
fg = fit(tight, 432, 0.47, 0.53)
fg.save(os.path.join(RES, "drawable-nodpi", "ic_launcher_foreground.png"))
sil = np.asarray(fg).copy()
sil[..., :3] = 0
sil[..., 3] = np.where(sil[..., 3] > 40, 255, sil[..., 3] * 0)  # a flat silhouette of both faces
Image.fromarray(sil.astype(np.uint8), "RGBA").save(os.path.join(RES, "drawable-nodpi", "ic_launcher_monochrome.png"))

# remove the old vector layers (same resource names as the PNGs)
for old in ("ic_launcher_foreground.xml", "ic_launcher_monochrome.xml", "ic_logo_mark.xml"):
    p = os.path.join(RES, "drawable", old)
    if os.path.exists(p):
        os.remove(p)
bg_xml = (
    '<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:aapt="http://schemas.android.com/aapt"\n'
    '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
    '    <path android:pathData="M0,0h108v108h-108z">\n        <aapt:attr name="android:fillColor">\n'
    '            <gradient android:startX="20" android:startY="0" android:endX="88" android:endY="108" android:type="linear">\n'
    f'                <item android:offset="0" android:color="#{BG_TOP[0]:02X}{BG_TOP[1]:02X}{BG_TOP[2]:02X}" />\n'
    f'                <item android:offset="1" android:color="#{BG_BOTTOM[0]:02X}{BG_BOTTOM[1]:02X}{BG_BOTTOM[2]:02X}" />\n'
    "            </gradient>\n        </aapt:attr>\n    </path>\n</vector>\n"
)
with open(os.path.join(RES, "drawable", "ic_launcher_background.xml"), "w", encoding="utf-8", newline="\n") as f:
    f.write(bg_xml)

# 512 store icon: full-bleed square, no rounding (the store applies its own mask)
store = Image.alpha_composite(background(512), fit(tight, 512, 0.66, 0.52))
store.save(os.path.join(UI, "vowed-store-512.png"))
fit(tight, 512, 0.66, 0.52).save(os.path.join(UI, "vowed-foreground-512.png"))
old_svg = os.path.join(UI, "vowed-logo.svg")
if os.path.exists(old_svg):
    os.remove(old_svg)


def mask(shape, size):
    big = size * 4
    m = Image.new("L", (big, big), 0)
    dr = ImageDraw.Draw(m)
    if shape == "circle":
        dr.ellipse((0, 0, big - 1, big - 1), fill=255)
    elif shape == "squircle":
        n = 4.0
        pts = []
        for i in range(720):
            t = i / 720 * 2 * math.pi
            c, s = math.cos(t), math.sin(t)
            pts.append((abs(c) ** (2 / n) * (1 if c >= 0 else -1) * big / 2 + big / 2, abs(s) ** (2 / n) * (1 if s >= 0 else -1) * big / 2 + big / 2))
        dr.polygon(pts, fill=255)
    else:
        dr.rounded_rectangle((0, 0, big - 1, big - 1), radius=int(big * 0.22), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def launcher_icon(shape, size=192):
    full_icon = Image.alpha_composite(background(432), fg)
    inner = full_icon.crop((72, 72, 360, 360)).resize((size, size), Image.LANCZOS)  # the visible 72 of 108 dp
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(inner, (0, 0), mask(shape, size))
    return out


def sheet(bgcolor, name):
    W, H = 3 * 240 + 40, 300
    im = Image.new("RGBA", (W, H), bgcolor)
    for i, shape in enumerate(("circle", "squircle", "rounded square")):
        ic = launcher_icon(shape)
        x = 20 + i * 240 + 24
        sh = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(sh).ellipse((x + 6, 70, x + 186, 250), fill=(0, 0, 0, 60))
        im = Image.alpha_composite(im, sh.filter(ImageFilter.GaussianBlur(10)))
        im.paste(ic, (x, 50), ic)
        ImageDraw.Draw(im).text((x + 40, 260), shape, fill=(255, 255, 255, 255) if sum(bgcolor[:3]) < 300 else (30, 30, 60, 255))
    im.convert("RGB").save(os.path.join(UI, name))


sheet((238, 233, 255, 255), "icon-shapes-light-wallpaper.png")
sheet((18, 16, 38, 255), "icon-shapes-dark-wallpaper.png")
themed_bg = Image.new("RGBA", (432, 432), (74, 53, 208, 255))
white_sil = Image.fromarray(np.dstack([np.full(sil.shape[:2] + (3,), 255, np.uint8), sil[..., 3]]), "RGBA")
themed = Image.alpha_composite(themed_bg, white_sil).crop((72, 72, 360, 360)).resize((192, 192), Image.LANCZOS)
out = Image.new("RGBA", (192, 192), (0, 0, 0, 0))
out.paste(themed, (0, 0), mask("circle", 192))
sheet_im = Image.new("RGBA", (260, 240), (238, 233, 255, 255))
sheet_im.paste(out, (34, 24), out)
ImageDraw.Draw(sheet_im).text((20, 214), "themed (monochrome layer), circle mask", fill=(30, 30, 60, 255))
sheet_im.convert("RGB").save(os.path.join(UI, "icon-themed-monochrome.png"))
print("logo assets written from", SRC)
