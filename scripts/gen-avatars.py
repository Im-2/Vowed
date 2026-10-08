"""
Generates the original Vowed avatar set (14 illustrated characters plus a neutral fallback) as Android vector drawables
(res/drawable/avatar_01.xml ... avatar_14.xml, avatar_neutral.xml) and a preview sheet docs/ui/avatars.png.
Every character is built from the same few primitives (circles, ellipses, polygons, strokes), so the XML and the preview always agree.
All artwork is original. Run: python scripts/gen-avatars.py   (the preview needs Pillow)
"""
import math
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "android", "app", "src", "main", "res", "drawable")
UI = os.path.join(ROOT, "docs", "ui")
os.makedirs(RES, exist_ok=True)
os.makedirs(UI, exist_ok=True)

SKIN = ["#FAD7B8", "#F2C29B", "#E0A878", "#C98E62", "#A56B45", "#7B4B2E", "#5A3520"]
EYE = "#2B2350"


def arc_points(cx, cy, rx, ry, a0, a1, n=28):
    return [(cx + rx * math.cos(math.radians(a0 + (a1 - a0) * i / n)), cy + ry * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]


def darker(hex_color, f=0.82):
    h = hex_color.lstrip("#")
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    return "#%02X%02X%02X" % (int(r * f), int(g * f), int(b * f))


def build(c):
    """a list of primitives for one character"""
    P = []
    skin, hair, shirt, bg = c["skin"], c["hair"], c["shirt"], c["bg"]
    P.append(("circle", 50, 50, 50, bg, 1.0))
    # soft shine on the background
    P.append(("ellipse", 30, 20, 26, 14, "#FFFFFF", 0.14))
    style = c["hair_style"]
    # hair that sits behind the head
    if style == "long":
        P.append(("ellipse", 50, 56, 25, 32, hair, 1.0))
    if style == "afro":
        P.append(("circle", 50, 38, 28, hair, 1.0))
    if style == "ponytail":
        P.append(("circle", 74, 44, 7, hair, 1.0))
        P.append(("ellipse", 78, 56, 6, 12, hair, 1.0))
    # body
    P.append(("ellipse", 50, 106, 36, 30, shirt, 1.0))
    P.append(("ellipse", 50, 84, 12, 5, darker(shirt, 0.88), 1.0))  # collar shadow
    P.append(("rect", 43, 62, 14, 18, darker(skin, 0.9), 1.0))
    # head and ears
    P.append(("circle", 30.5, 49, 4.2, skin, 1.0))
    P.append(("circle", 69.5, 49, 4.2, skin, 1.0))
    P.append(("ellipse", 50, 47, 20, 22.5, skin, 1.0))
    # beard goes on the face before the mouth
    if c.get("beard"):
        pts = arc_points(50, 47, 20, 22.5, 20, 160) + arc_points(50, 58, 12, 8, 160, 20)
        P.append(("poly", pts, hair, 1.0))
    # hair in front
    top = arc_points(50, 47, 21.2, 24.2, 180, 360)
    if style in ("short", "bun", "curly", "ponytail", "long"):
        fringe = [(70, 40), (62, 33), (52, 36), (42, 32), (33, 38), (30, 44)]
        P.append(("poly", top + [(71, 46)] + fringe[::-1][:0] + [(66, 38), (56, 34), (46, 36), (36, 34), (30, 42)], hair, 1.0))
    if style == "bun":
        P.append(("circle", 50, 20, 8.5, hair, 1.0))
    if style == "curly":
        for a in range(200, 345, 22):
            P.append(("circle", 50 + 21 * math.cos(math.radians(a)), 47 + 23 * math.sin(math.radians(a)), 6.2, hair, 1.0))
    if style == "spiky":
        pts = [(29, 40), (30, 24), (38, 33), (43, 16), (50, 31), (57, 16), (62, 33), (70, 24), (71, 40)]
        P.append(("poly", pts, hair, 1.0))
    if style == "afro":
        P.append(("poly", arc_points(50, 47, 21.5, 24.5, 190, 350) + [(67, 33), (50, 30), (33, 33)], hair, 1.0))
    if style == "sidepart":
        P.append(("poly", top + [(70, 44), (60, 32), (36, 38), (30, 46)], hair, 1.0))
        P.append(("poly", [(30, 44), (46, 30), (62, 32), (36, 40)], darker(hair, 0.85), 1.0))
    # face
    P.append(("circle", 42, 47, 2.4, EYE, 1.0))
    P.append(("circle", 58, 47, 2.4, EYE, 1.0))
    P.append(("circle", 41.3, 46.2, 0.8, "#FFFFFF", 1.0))
    P.append(("circle", 57.3, 46.2, 0.8, "#FFFFFF", 1.0))
    P.append(("stroke", [(38, 41.5), (42, 40.3), (46, 41.3)], darker(hair, 0.9) if style != "bald" else darker(skin, 0.6), 1.6, 1.0))
    P.append(("stroke", [(54, 41.3), (58, 40.3), (62, 41.5)], darker(hair, 0.9) if style != "bald" else darker(skin, 0.6), 1.6, 1.0))
    P.append(("circle", 36, 55, 4, "#FF7A9A", 0.28))
    P.append(("circle", 64, 55, 4, "#FF7A9A", 0.28))
    mouth = c.get("mouth", "smile")
    if mouth == "smile":
        P.append(("stroke", arc_points(50, 55, 7, 5, 25, 155, 10), "#9C3F4F" if not c.get("beard") else "#FFFFFF", 1.8, 1.0))
    elif mouth == "grin":
        P.append(("poly", arc_points(50, 55, 7.5, 6.5, 0, 180, 12), "#7A2E3E", 1.0))
        P.append(("poly", arc_points(50, 55, 6, 2.2, 0, 180, 12), "#FFFFFF", 1.0))
    else:  # calm
        P.append(("stroke", [(45.5, 58), (50, 59.2), (54.5, 58)], "#9C3F4F" if not c.get("beard") else "#FFFFFF", 1.8, 1.0))
    # accessories
    acc = c.get("acc")
    if acc == "glasses":
        P.append(("ring", 42, 47, 6.4, EYE, 1.4))
        P.append(("ring", 58, 47, 6.4, EYE, 1.4))
        P.append(("stroke", [(48.4, 47), (51.6, 47)], EYE, 1.4, 1.0))
    if acc == "headband":
        P.append(("poly", arc_points(50, 47, 21.4, 24.5, 200, 340, 20) + arc_points(50, 47, 21.4, 20.5, 340, 200, 20), c["acc_color"], 1.0))
    if acc == "cap":
        P.append(("poly", arc_points(50, 44, 22.5, 22, 180, 360), c["acc_color"], 1.0))
        P.append(("poly", [(26, 44), (74, 44), (84, 47), (84, 50), (26, 50)], darker(c["acc_color"], 0.85), 1.0))
    if acc == "beanie":
        P.append(("poly", arc_points(50, 42, 22.5, 22, 180, 360), c["acc_color"], 1.0))
        P.append(("rect", 27, 39, 46, 8, darker(c["acc_color"], 0.85), 1.0))
        P.append(("circle", 50, 18, 4.5, "#FFFFFF", 1.0))
    if acc == "earrings":
        P.append(("circle", 30, 56, 2.2, "#FFC857", 1.0))
        P.append(("circle", 70, 56, 2.2, "#FFC857", 1.0))
    if acc == "star":
        pts = []
        for i in range(10):
            ang = math.pi / 5 * i - math.pi / 2
            rad = 5 if i % 2 == 0 else 2.2
            pts.append((74 + rad * math.cos(ang), 22 + rad * math.sin(ang)))
        P.append(("poly", pts, "#FFC857", 1.0))
    return P


CHARS = [
    dict(skin=SKIN[1], hair="#2B1B12", shirt="#4A35D0", bg="#CFC6FF", hair_style="short", mouth="smile"),
    dict(skin=SKIN[3], hair="#1B1B2E", shirt="#E5566D", bg="#FFD9C9", hair_style="afro", mouth="grin", acc="earrings"),
    dict(skin=SKIN[0], hair="#C58B3C", shirt="#1E9E6F", bg="#CDEFE2", hair_style="long", mouth="smile", acc="headband", acc_color="#FF7A9A"),
    dict(skin=SKIN[5], hair="#14101C", shirt="#F2A93B", bg="#FFEBC2", hair_style="short", mouth="calm", acc="glasses"),
    dict(skin=SKIN[2], hair="#8A3B22", shirt="#3566D6", bg="#CFE3FF", hair_style="bun", mouth="smile", acc="earrings"),
    dict(skin=SKIN[4], hair="#2B1B12", shirt="#7C6CF0", bg="#E4DDFF", hair_style="bald", beard=True, mouth="smile", acc="glasses"),
    dict(skin=SKIN[1], hair="#6A3FB0", shirt="#FF8F6B", bg="#FFE1D4", hair_style="curly", mouth="grin"),
    dict(skin=SKIN[6], hair="#14101C", shirt="#38C7A0", bg="#C9F2E6", hair_style="afro", mouth="smile", acc="star"),
    dict(skin=SKIN[0], hair="#B1352E", shirt="#2F2096", bg="#D9D2FF", hair_style="sidepart", mouth="calm"),
    dict(skin=SKIN[3], hair="#1B1B2E", shirt="#E8932A", bg="#FFE9C4", hair_style="short", mouth="grin", acc="cap", acc_color="#4A35D0"),
    dict(skin=SKIN[2], hair="#1D8F78", shirt="#C2457F", bg="#FFD3E6", hair_style="ponytail", mouth="smile"),
    dict(skin=SKIN[4], hair="#3C3C5C", shirt="#5BC8FF", bg="#D3F0FF", hair_style="spiky", mouth="grin"),
    dict(skin=SKIN[1], hair="#5A3A1E", shirt="#9945FF", bg="#EBD9FF", hair_style="long", mouth="calm", acc="glasses"),
    dict(skin=SKIN[5], hair="#14101C", shirt="#FF9F68", bg="#FFE4D1", hair_style="bald", beard=True, mouth="grin", acc="beanie", acc_color="#2F2096"),
]


def f(v):
    return ("%.2f" % v).rstrip("0").rstrip(".")


def path_for(p):
    k = p[0]
    if k == "circle":
        _, cx, cy, r = p[:4]
        return f"M{f(cx - r)},{f(cy)}a{f(r)},{f(r)} 0 1,0 {f(2 * r)},0a{f(r)},{f(r)} 0 1,0 {f(-2 * r)},0Z"
    if k == "ellipse":
        _, cx, cy, rx, ry = p[:5]
        return f"M{f(cx - rx)},{f(cy)}a{f(rx)},{f(ry)} 0 1,0 {f(2 * rx)},0a{f(rx)},{f(ry)} 0 1,0 {f(-2 * rx)},0Z"
    if k == "rect":
        _, x, y, w, h = p[:5]
        return f"M{f(x)},{f(y)}h{f(w)}v{f(h)}h{f(-w)}Z"
    if k in ("poly",):
        return "M" + "L".join(f"{f(x)},{f(y)}" for x, y in p[1]) + "Z"
    if k == "stroke":
        return "M" + "L".join(f"{f(x)},{f(y)}" for x, y in p[1])
    if k == "ring":
        _, cx, cy, r = p[:4]
        return f"M{f(cx - r)},{f(cy)}a{f(r)},{f(r)} 0 1,0 {f(2 * r)},0a{f(r)},{f(r)} 0 1,0 {f(-2 * r)},0Z"
    raise ValueError(k)


def to_xml(prims, name):
    body = ['    <group>', '        <clip-path android:pathData="M0,50a50,50 0 1,0 100,0a50,50 0 1,0 -100,0Z" />']
    for p in prims:
        k = p[0]
        d = path_for(p)
        if k in ("circle", "ellipse"):
            color, alpha = p[-2], p[-1]
            body.append(f'        <path android:pathData="{d}" android:fillColor="{color}"' + (f' android:fillAlpha="{alpha}"' if alpha != 1.0 else "") + " />")
        elif k == "rect":
            body.append(f'        <path android:pathData="{d}" android:fillColor="{p[-2]}"' + (f' android:fillAlpha="{p[-1]}"' if p[-1] != 1.0 else "") + " />")
        elif k == "poly":
            body.append(f'        <path android:pathData="{d}" android:fillColor="{p[2]}"' + (f' android:fillAlpha="{p[3]}"' if p[3] != 1.0 else "") + " />")
        elif k == "stroke":
            body.append(f'        <path android:pathData="{d}" android:strokeColor="{p[2]}" android:strokeWidth="{f(p[3])}" android:strokeLineCap="round" android:strokeLineJoin="round" />')
        elif k == "ring":
            body.append(f'        <path android:pathData="{d}" android:strokeColor="{p[4]}" android:strokeWidth="{f(p[5])}" />')
    body.append("    </group>")
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n<!-- generated by scripts/gen-avatars.py: original artwork -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="100dp" android:height="100dp" android:viewportWidth="100" android:viewportHeight="100">\n'
        + "\n".join(body) + "\n</vector>\n"
    )


for i, c in enumerate(CHARS, 1):
    with open(os.path.join(RES, f"avatar_{i:02d}.xml"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(to_xml(build(c), f"avatar_{i:02d}"))

neutral = [("circle", 50, 50, 50, "#E4E0F5", 1.0), ("circle", 50, 40, 16, "#B3ABD8", 1.0), ("ellipse", 50, 100, 32, 30, "#B3ABD8", 1.0)]
with open(os.path.join(RES, "avatar_neutral.xml"), "w", encoding="utf-8", newline="\n") as fh:
    fh.write(to_xml(neutral, "avatar_neutral"))

try:
    from PIL import Image, ImageDraw
except ImportError:
    print("Pillow missing: XML written, preview skipped")
    raise SystemExit(0)

K = 4


def hexrgb(h, a=1.0):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), int(255 * a))


def render(prims, size=160):
    big = size * K
    k = big / 100.0
    im = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    for p in prims:
        layer = Image.new("RGBA", (big, big), (0, 0, 0, 0))
        dr = ImageDraw.Draw(layer)
        kind = p[0]
        if kind == "circle":
            _, cx, cy, r, col, a = p
            dr.ellipse(((cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k), fill=hexrgb(col, a))
        elif kind == "ellipse":
            _, cx, cy, rx, ry, col, a = p
            dr.ellipse(((cx - rx) * k, (cy - ry) * k, (cx + rx) * k, (cy + ry) * k), fill=hexrgb(col, a))
        elif kind == "rect":
            _, x, y, w, h, col, a = p
            dr.rectangle((x * k, y * k, (x + w) * k, (y + h) * k), fill=hexrgb(col, a))
        elif kind == "poly":
            dr.polygon([(x * k, y * k) for x, y in p[1]], fill=hexrgb(p[2], p[3]))
        elif kind == "stroke":
            dr.line([(x * k, y * k) for x, y in p[1]], fill=hexrgb(p[2], p[4]), width=max(1, int(p[3] * k)), joint="curve")
        elif kind == "ring":
            _, cx, cy, r, col, w = p
            dr.ellipse(((cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k), outline=hexrgb(col), width=max(1, int(w * k)))
        im = Image.alpha_composite(im, layer)
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, big - 1, big - 1), fill=255)
    out = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out.resize((size, size), Image.LANCZOS)


cols = 5
rows = math.ceil((len(CHARS) + 1) / cols)
sheet = Image.new("RGBA", (cols * 180 + 20, rows * 180 + 20), (244, 241, 255, 255))
for i, prims in enumerate([build(c) for c in CHARS] + [neutral]):
    ic = render(prims)
    sheet.paste(ic, (20 + (i % cols) * 180, 20 + (i // cols) * 180), ic)
sheet.convert("RGB").save(os.path.join(UI, "avatars.png"))
print(f"{len(CHARS)} avatars + neutral written")
