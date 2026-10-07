"""
Generates the Vowed logo from ONE geometry: Android vector drawables (launcher foreground, monochrome, in-app mark, background), an SVG, a 512x512
store PNG and shape/wallpaper preview sheets. Run: python scripts/gen-logo.py   (needs Pillow for the PNGs)

The mark: a bold rounded "V" made of two folded faces (a light violet face and a deep indigo face) that read as a solid ribbon, with a small
check mark in the notch, a thin highlight and a soft shadow. Original artwork.
"""
import math
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
UI = os.path.join(ROOT, "docs", "ui")
os.makedirs(os.path.join(RES, "drawable"), exist_ok=True)
os.makedirs(os.path.join(RES, "mipmap-anydpi-v26"), exist_ok=True)
os.makedirs(UI, exist_ok=True)

LIGHT = "#A99BFF"      # light violet face
LIGHT_HI = "#C9BFFF"   # highlight on that face
DEEP = "#3A2AA8"       # deep indigo face
DEEP_DK = "#2B1D86"
CHECK = "#1FBF8A"
SHADOW = "#2A1B7A"
BG_TOP, BG_BOTTOM = "#F8F5FF", "#E2DAFF"

S = 0.86
CX, CY = 54.0, 55.0


def sc(p):
    return (CX + (p[0] - 54.0) * S, CY + (p[1] - 55.0) * S)


# the V in a 108 x 108 viewport (before scaling): outer top corners A and B, bottom vertex C, notch M, inner top corners E and F
A, B, C, M, E, F = (27, 31), (81, 31), (54, 81), (54, 61), (39.5, 31), (68.5, 31)
LEFT = [sc(p) for p in (A, E, M, C)]
RIGHT = [sc(p) for p in (B, F, M, C)]
TICK = [sc(p) for p in ((48.2, 38.2), (52.6, 43.4), (60.2, 35.4))]
HI_LEFT = [sc(p) for p in ((29.5, 33.2), (37.5, 33.2))]
HI_FOLD = [sc(p) for p in ((41, 36), (52.6, 58))]
HI_RIGHT = [sc(p) for p in ((79, 33.2), (70.5, 33.2))]


def d(points, close=True):
    s = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in points)
    return s + (" Z" if close else "")


def shift(points, dx, dy):
    return [(x + dx, y + dy) for x, y in points]


def vd(width, height, body, viewport=108):
    return (
        f'<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    xmlns:aapt="http://schemas.android.com/aapt"\n    android:width="{width}dp" android:height="{height}dp"\n'
        f'    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">\n{body}</vector>\n'
    )


def path(data, fill=None, alpha=1.0, stroke=None, sw=0, cap="round"):
    s = f'    <path android:pathData="{data}"'
    if fill:
        s += f' android:fillColor="{fill}"'
    if alpha != 1.0:
        s += f' android:fillAlpha="{alpha}"'
    if stroke:
        s += f' android:strokeColor="{stroke}" android:strokeWidth="{sw}" android:strokeLineJoin="round" android:strokeLineCap="{cap}"'
        if alpha != 1.0:
            s += f' android:strokeAlpha="{alpha}"'
    return s + " />\n"


def mark_body(with_shadow=True):
    body = ""
    if with_shadow:
        for i, (dy, a) in enumerate(((5.0, 0.06), (3.6, 0.08), (2.2, 0.10))):
            for face in (LEFT, RIGHT):
                body += path(d(shift(face, 0, dy)), SHADOW, a, SHADOW, 3.2)
    body += path(d(LEFT), LIGHT, 1.0, LIGHT, 3.2)
    body += path(d(RIGHT), DEEP, 1.0, DEEP, 3.2)
    # a darker band along the fold on the right face gives the folded look
    body += path(d([sc((54, 61)), sc((60, 50)), sc((54, 81))]), DEEP_DK, 0.55, DEEP_DK, 1.0)
    body += path(d(HI_LEFT, False), None, 0.75, "#FFFFFF", 1.6)
    body += path(d(HI_FOLD, False), None, 0.45, LIGHT_HI, 1.4)
    body += path(d(HI_RIGHT, False), None, 0.35, "#FFFFFF", 1.4)
    body += path(d(TICK, False), None, 1.0, CHECK, 4.2)
    return body


def write(p, text):
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


# ---- Android: adaptive icon layers and the in-app mark
write(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), vd(108, 108, mark_body(True)))
mono = path(d(LEFT), "#000000", 1.0, "#000000", 3.2) + path(d(RIGHT), "#000000", 1.0, "#000000", 3.2) + path(d(TICK, False), None, 1.0, "#000000", 4.2)
write(os.path.join(RES, "drawable", "ic_launcher_monochrome.xml"), vd(108, 108, mono))
write(os.path.join(RES, "drawable", "ic_logo_mark.xml"), vd(108, 108, mark_body(True)))
bg = (
    '    <path android:pathData="M0,0h108v108h-108z">\n        <aapt:attr name="android:fillColor">\n'
    f'            <gradient android:startX="20" android:startY="0" android:endX="88" android:endY="108" android:type="linear">\n'
    f'                <item android:offset="0" android:color="{BG_TOP}" />\n                <item android:offset="1" android:color="{BG_BOTTOM}" />\n'
    "            </gradient>\n        </aapt:attr>\n    </path>\n"
)
write(os.path.join(RES, "drawable", "ic_launcher_background.xml"), vd(108, 108, bg))
adaptive = (
    '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <background android:drawable="@drawable/ic_launcher_background" />\n    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
    '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>\n'
)
write(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher.xml"), adaptive)
write(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher_round.xml"), adaptive)

# ---- SVG
def svg_path(data, fill=None, alpha=1.0, stroke=None, sw=0):
    s = f'<path d="{data}"'
    s += f' fill="{fill}"' if fill else ' fill="none"'
    if alpha != 1.0:
        s += f' opacity="{alpha}"'
    if stroke:
        s += f' stroke="{stroke}" stroke-width="{sw}" stroke-linejoin="round" stroke-linecap="round"'
    return s + "/>"


svg_parts = [f'<rect width="108" height="108" fill="url(#bg)"/>']
for dy, a in ((5.0, 0.06), (3.6, 0.08), (2.2, 0.10)):
    for face in (LEFT, RIGHT):
        svg_parts.append(svg_path(d(shift(face, 0, dy)), SHADOW, a, SHADOW, 3.2))
svg_parts += [
    svg_path(d(LEFT), LIGHT, 1.0, LIGHT, 3.2),
    svg_path(d(RIGHT), DEEP, 1.0, DEEP, 3.2),
    svg_path(d([sc((54, 61)), sc((60, 50)), sc((54, 81))]), DEEP_DK, 0.55, DEEP_DK, 1.0),
    svg_path(d(HI_LEFT, False), None, 0.75, "#FFFFFF", 1.6),
    svg_path(d(HI_FOLD, False), None, 0.45, LIGHT_HI, 1.4),
    svg_path(d(HI_RIGHT, False), None, 0.35, "#FFFFFF", 1.4),
    svg_path(d(TICK, False), None, 1.0, CHECK, 4.2),
]
svg = (
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">\n'
    f'<defs><linearGradient id="bg" x1="20" y1="0" x2="88" y2="108" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="{BG_TOP}"/><stop offset="1" stop-color="{BG_BOTTOM}"/></linearGradient></defs>\n'
    + "\n".join(svg_parts) + "\n</svg>\n"
)
write(os.path.join(UI, "vowed-logo.svg"), svg)

# ---- PNGs (Pillow)
try:
    from PIL import Image, ImageDraw, ImageFilter, ImageChops
except ImportError:
    print("Pillow not installed: skipped PNG exports")
    raise SystemExit(0)

K = 6  # supersampling
SIZE = 512


def hexrgb(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def poly_layer(points, color, size, width=3.2):
    """a filled polygon with rounded corners (a stroke of the same colour), on a transparent layer"""
    big = size * K
    k = big / 108.0
    im = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    dr = ImageDraw.Draw(im)
    pts = [(x * k, y * k) for x, y in points]
    dr.polygon(pts, fill=color)
    r = width * k / 2
    for x, y in pts:
        dr.ellipse((x - r, y - r, x + r, y + r), fill=color)
    for (x1, y1), (x2, y2) in zip(pts, pts[1:] + pts[:1]):
        dr.line((x1, y1, x2, y2), fill=color, width=int(width * k))
    return im


def line_layer(points, color, size, width):
    big = size * K
    k = big / 108.0
    im = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    dr = ImageDraw.Draw(im)
    pts = [(x * k, y * k) for x, y in points]
    dr.line(pts, fill=color, width=int(width * k), joint="curve")
    r = width * k / 2
    for x, y in (pts[0], pts[-1]):
        dr.ellipse((x - r, y - r, x + r, y + r), fill=color)
    return im


def foreground(size=SIZE):
    """the V with its shadow, on a transparent canvas of size x size (108-unit design space)"""
    big = size * K
    canvas = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    shadow = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    for face in (LEFT, RIGHT):
        shadow = Image.alpha_composite(shadow, poly_layer(shift(face, 0, 3.4), hexrgb(SHADOW, 70), size))
    shadow = shadow.filter(ImageFilter.GaussianBlur(radius=1.6 * big / 108))
    canvas = Image.alpha_composite(canvas, shadow)
    canvas = Image.alpha_composite(canvas, poly_layer(LEFT, hexrgb(LIGHT), size))
    canvas = Image.alpha_composite(canvas, poly_layer(RIGHT, hexrgb(DEEP), size))
    canvas = Image.alpha_composite(canvas, poly_layer([sc((54, 61)), sc((60, 50)), sc((54, 81))], hexrgb(DEEP_DK, 140), size, 1.0))
    canvas = Image.alpha_composite(canvas, line_layer(HI_LEFT, hexrgb("#FFFFFF", 190), size, 1.6))
    canvas = Image.alpha_composite(canvas, line_layer(HI_FOLD, hexrgb(LIGHT_HI, 115), size, 1.4))
    canvas = Image.alpha_composite(canvas, line_layer(HI_RIGHT, hexrgb("#FFFFFF", 90), size, 1.4))
    canvas = Image.alpha_composite(canvas, line_layer(TICK, hexrgb(CHECK), size, 4.2))
    return canvas.resize((size, size), Image.LANCZOS)


def background(size=SIZE):
    top, bottom = hexrgb(BG_TOP), hexrgb(BG_BOTTOM)
    im = Image.new("RGBA", (size, size))
    px = im.load()
    for y in range(size):
        for x in range(size):
            t = max(0.0, min(1.0, ((x - 20 * size / 108) * 68 + y * 108) / (68 * 68 + 108 * 108) / (size / 108) / (1 / 1)))
            t = max(0.0, min(1.0, (x / size * 0.45 + y / size * 0.7) / 1.0))
            px[x, y] = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(4))
    return im


fg = foreground()
store = Image.alpha_composite(background(), fg)
store.save(os.path.join(UI, "vowed-store-512.png"))
fg.save(os.path.join(UI, "vowed-foreground-512.png"))


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
            pts.append(((abs(c) ** (2 / n)) * (1 if c >= 0 else -1) * big / 2 + big / 2, (abs(s) ** (2 / n)) * (1 if s >= 0 else -1) * big / 2 + big / 2))
        dr.polygon(pts, fill=255)
    else:  # rounded square
        dr.rounded_rectangle((0, 0, big - 1, big - 1), radius=int(big * 0.22), fill=255)
    return m.resize((size, size), Image.LANCZOS)


# the adaptive icon's visible area is the central 72 of 108 units; masks crop the outer part
def launcher_icon(shape, size=192):
    full = Image.alpha_composite(background(432), foreground(432))
    inner = full.crop((18 * 4, 18 * 4, 90 * 4, 90 * 4)).resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(inner, (0, 0), mask(shape, size))
    return out


def sheet(bgcolor, name):
    W, H = 3 * 240 + 40, 300
    im = Image.new("RGBA", (W, H), bgcolor)
    dr = ImageDraw.Draw(im)
    for i, shape in enumerate(("circle", "squircle", "rounded square")):
        ic = launcher_icon(shape)
        x = 20 + i * 240 + 24
        sh = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(sh).ellipse((x + 6, 70, x + 186, 70 + 180), fill=(0, 0, 0, 60))
        im = Image.alpha_composite(im, sh.filter(ImageFilter.GaussianBlur(10)))
        im.paste(ic, (x, 50), ic)
        ImageDraw.Draw(im).text((x + 40, 260), shape, fill=(255, 255, 255, 255) if sum(bgcolor[:3]) < 300 else (30, 30, 60, 255))
    im.convert("RGB").save(os.path.join(UI, name))


sheet((238, 233, 255, 255), "icon-shapes-light-wallpaper.png")
sheet((18, 16, 38, 255), "icon-shapes-dark-wallpaper.png")
# a themed (monochrome) icon preview: the monochrome layer tinted with a themed colour on a themed background
down = lambda im: im.resize((432, 432), Image.LANCZOS)
mono_im = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
mono_im = Image.alpha_composite(mono_im, down(poly_layer(LEFT, (255, 255, 255, 255), 432)))
mono_im = Image.alpha_composite(mono_im, down(poly_layer(RIGHT, (255, 255, 255, 255), 432)))
mono_im = Image.alpha_composite(mono_im, down(line_layer(TICK, (255, 255, 255, 255), 432, 4.2)))
themed_bg = Image.new("RGBA", (432, 432), hexrgb("#4A35D0"))
themed = Image.alpha_composite(themed_bg, mono_im).crop((72, 72, 360, 360)).resize((192, 192), Image.LANCZOS)
out = Image.new("RGBA", (192, 192), (0, 0, 0, 0))
out.paste(themed, (0, 0), mask("circle", 192))
sheet_im = Image.new("RGBA", (260, 240), (238, 233, 255, 255))
sheet_im.paste(out, (34, 24), out)
ImageDraw.Draw(sheet_im).text((20, 214), "themed (monochrome layer), circle mask", fill=(30, 30, 60, 255))
sheet_im.convert("RGB").save(os.path.join(UI, "icon-themed-monochrome.png"))
print("logo files written")
