"""Where ffmpeg and ffprobe are (PATH first, then the winget install), and shared paths."""
import glob
import os
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def _find(name):
    p = shutil.which(name)
    if p:
        return p
    hits = glob.glob(os.path.join(os.environ.get("LOCALAPPDATA", ""), "Microsoft", "WinGet", "Packages", "*FFmpeg*", "*", "bin", name + ".exe"))
    if hits:
        return hits[0]
    raise SystemExit(f"{name} not found: install it with `winget install Gyan.FFmpeg`")


FFMPEG = _find("ffmpeg")
FFPROBE = _find("ffprobe")
FONT = os.path.join(HERE, "cache", "fonts", "DMSans.ttf")
FOOTAGE = os.path.join(ROOT, "demo-video", "vowedd.mp4")
LOGO = os.path.join(ROOT, "design", "logo-3d.png")
