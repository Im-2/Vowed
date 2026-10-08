"""
Compresses the owner's category photos (design/photos/*) into Android WebP drawables (res/drawable-nodpi/photo_<category>.webp), about 800 px wide,
and the SKR token icon (design/brand/skr-logo.png) into res/drawable-nodpi/skr_logo.png. Categories without a photo keep the gradient tile.
Run: python scripts/gen-photos.py   (needs Pillow)
"""
import os
from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SRC = os.path.join(ROOT, "design", "photos")
OUT = os.path.join(ROOT, "android", "app", "src", "main", "res", "drawable-nodpi")
os.makedirs(OUT, exist_ok=True)

# category key used in the app -> file names that may hold its photo
MAP = {
    "study": ["study"], "fitness": ["fitness"], "steps": ["steps"], "sleep": ["sleep"], "detox": ["screen-time", "screen time", "screentime"],
    "focus": ["focus"], "location": ["places", "place"], "custom": ["custom"],
}
total = 0
for key, names in MAP.items():
    found = None
    for n in names:
        for ext in (".jpg", ".jpeg", ".png", ".webp"):
            p = os.path.join(SRC, n + ext)
            if os.path.exists(p):
                found = p
                break
        if found:
            break
    dest = os.path.join(OUT, f"photo_{key}.webp")
    if not found:
        if os.path.exists(dest):
            os.remove(dest)
        print(f"{key}: no photo (gradient tile stays)")
        continue
    im = Image.open(found).convert("RGB")
    w = 800
    h = round(im.height * w / im.width)
    im = im.resize((w, h), Image.LANCZOS)
    im.save(dest, "WEBP", quality=72, method=6)
    size = os.path.getsize(dest)
    total += size
    print(f"{key}: {os.path.basename(found)} -> photo_{key}.webp {w}x{h}, {size // 1024} KB")
print(f"photos total: {total // 1024} KB")

skr = os.path.join(ROOT, "design", "brand", "skr-logo.png")
if os.path.exists(skr):
    Image.open(skr).convert("RGBA").resize((256, 256), Image.LANCZOS).save(os.path.join(OUT, "skr_logo.png"), optimize=True)
    print("skr_logo.png written")
