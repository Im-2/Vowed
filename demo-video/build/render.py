"""Renders the 1920x1080 video.

1. left: one clip per scene from vowedd.mp4 (cut, sped up at most 2x, gentle zoom in and out on key details, last frame held if the footage is
   shorter than the sentence), joined into left.mp4 (486x1080, the phone picture scaled to full height, never stretched)
2. the rest of the frame (intro, brand panels, right-hand teaching visuals, captions, outro) is drawn here with Pillow and streamed to ffmpeg
3. the phone clip is overlaid on the left half, the soundtrack is added, loudness is normalised to -16 LUFS
Scene timing comes from the narration (timeline.json): the footage is fitted to the voice, never the other way round.
"""
import json
import math
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from scenes import SCENES, ORDER
from tools import FFMPEG, FFPROBE, FONT, FOOTAGE, LOGO, ROOT

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "cache")
OUT = os.path.join(ROOT, "demo-video")
W, H, FPS = 1920, 1080, 30
PHONE_W, PHONE_H = 486, 1080
PHONE_X = (960 - PHONE_W) // 2
BG = (0x17, 0x11, 0x2B)
PANEL = (0x22, 0x1A, 0x3D)
ACCENT = (0x5B, 0x3F, 0xD1)
ACCENT2 = (0xA7, 0x8B, 0xFA)
TEXT = (0xF5, 0xF2, 0xFC)
MUTED = (0xC9, 0xC1, 0xE6)
RX = 960 + 72  # right content x
RW = 960 - 144  # right content width


def font(size, weight="Regular"):
    f = ImageFont.truetype(FONT, size)
    try:
        f.set_variation_by_name(weight)
    except Exception:
        pass
    return f


F_TITLE = font(56, "Bold")
F_BULLET = font(38, "Medium")
F_BULLET_B = font(38, "Bold")
F_NOTE = font(36, "Regular")
F_CAP = font(36, "Medium")
F_DIA = font(36, "SemiBold")
F_DIA_S = font(36, "Regular")


def ease(x):
    x = min(1.0, max(0.0, x))
    return 1 - (1 - x) ** 3


# ------------------------------------------------------------------ timing
def scene_times(tl):
    first = {}
    for s in tl["sentences"]:
        first.setdefault(s["scene"], s["start"])
    starts = []
    for i, k in enumerate(ORDER):
        st = tl["intro"] if i == 0 else first[k] - 0.25
        starts.append(st)
    out = []
    for i, k in enumerate(ORDER):
        end = starts[i + 1] if i + 1 < len(ORDER) else tl["outro_start"]
        out.append((k, round(starts[i], 3), round(end, 3)))
    return out


# ------------------------------------------------------------------ left footage
def zoom_expr(zooms, dur):
    if not zooms:
        return "1", "0", "0"
    terms, cx_terms, cy_terms = [], [], []
    for (a, b, cx, cy, z) in zooms:
        b = min(b, dur - 0.1)
        ramp = f"min(1,max(0,(T-{a:.3f})/0.9))*min(1,max(0,({b:.3f}-T)/0.9))"
        ramp = f"(0.5-0.5*cos(PI*{ramp}))"
        terms.append(f"({z - 1:.3f})*{ramp}")
        cx_terms.append((a, b, cx))
        cy_terms.append((a, b, cy))
    zexp = "1+" + "+".join(terms)
    # focus point: the window's own, switching half way between two windows (the zoom is back to 1 there)
    def piece(vals):
        expr = f"{vals[-1][2]}"
        for i in range(len(vals) - 2, -1, -1):
            cut = (vals[i][1] + vals[i + 1][0]) / 2
            expr = f"if(lt(T,{cut:.3f}),{vals[i][2]},{expr})"
        return expr
    cx, cy = piece(cx_terms), piece(cy_terms)
    return zexp.replace("T", "(on/30)"), cx.replace("T", "(on/30)"), cy.replace("T", "(on/30)")


def left_clip(key, dur):
    sc = SCENES[key]
    path = os.path.join(CACHE, "left", f"{key}.mp4")
    meta = path + ".json"
    sig = json.dumps([sc["segs"], sc["zoom"], round(dur, 3), 4])
    if os.path.exists(path) and os.path.exists(meta) and open(meta).read() == sig:
        return path
    os.makedirs(os.path.dirname(path), exist_ok=True)
    segs = list(sc["segs"])
    src_len = sum((b - a) / sp for a, b, sp in segs)
    # never more than 2x; if the footage is still too long for the sentence, the end of the last segment is cut
    inputs, chains = [], []
    for i, (a, b, sp) in enumerate(segs):
        inputs += ["-ss", f"{a:.3f}", "-t", f"{b - a:.3f}", "-i", FOOTAGE]
        chains.append(f"[{i}:v]setpts=(PTS-STARTPTS)/{sp},fps={FPS},scale=972:2160:flags=lanczos,setsar=1[v{i}]")
    cat = "".join(f"[v{i}]" for i in range(len(segs))) + f"concat=n={len(segs)}:v=1:a=0[cat]"
    hold = max(0.0, dur - src_len) + 0.2
    zexp, cx, cy = zoom_expr(sc["zoom"], dur)
    zp = (f"zoompan=z='{zexp}':x='max(0,min(iw-iw/zoom,({cx})*iw-iw/zoom/2))':y='max(0,min(ih-ih/zoom,({cy})*ih-ih/zoom/2))'"
          f":d=1:s={PHONE_W}x{PHONE_H}:fps={FPS}")
    graph = ";".join(chains + [cat, f"[cat]tpad=stop_mode=clone:stop_duration={hold:.3f},{zp},trim=duration={dur:.3f},setpts=PTS-STARTPTS[out]"])
    cmd = [FFMPEG, "-v", "error", "-y", *inputs, "-filter_complex", graph, "-map", "[out]", "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "16",
           "-pix_fmt", "yuv420p", "-r", str(FPS), path]
    subprocess.run(cmd, check=True)
    open(meta, "w").write(sig)
    print(f"  left {key}: {dur:.2f}s from {src_len:.2f}s of footage" + (f", hold {dur - src_len:.2f}s" if dur > src_len else f", cut {src_len - dur:.2f}s"))
    return path


def build_left(times):
    parts = [left_clip(k, b - a) for k, a, b in times]
    lst = os.path.join(CACHE, "left", "list.txt")
    with open(lst, "w") as f:
        for p in parts:
            f.write(f"file '{p}'\n")
    out = os.path.join(CACHE, "left.mp4")
    subprocess.run([FFMPEG, "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", out], check=True)
    return out


# ------------------------------------------------------------------ drawing helpers
def rich_text(draw, xy, text, base=F_BULLET, bold=F_BULLET_B, color=TEXT, hi=ACCENT2):
    """draws text with **highlighted** parts"""
    x, y = xy
    parts = text.split("**")
    for i, p in enumerate(parts):
        f = bold if i % 2 else base
        c = hi if i % 2 else color
        draw.text((x, y), p, font=f, fill=c)
        x += draw.textlength(p, font=f)
    return x


def layer(w, h):
    return Image.new("RGBA", (w, h), (0, 0, 0, 0))


def box(d, xy, label, sub=None, fill=PANEL, outline=ACCENT, tc=TEXT, w=3):
    x0, y0, x1, y1 = xy
    d.rounded_rectangle(xy, radius=22, fill=fill, outline=outline, width=w)
    tw = d.textlength(label, font=F_DIA)
    if sub:
        d.text(((x0 + x1 - tw) / 2, y0 + (y1 - y0) / 2 - 42), label, font=F_DIA, fill=tc)
        sw = d.textlength(sub, font=F_DIA_S)
        d.text(((x0 + x1 - sw) / 2, y0 + (y1 - y0) / 2 + 4), sub, font=F_DIA_S, fill=MUTED)
    else:
        d.text(((x0 + x1 - tw) / 2, y0 + (y1 - y0) / 2 - 22), label, font=F_DIA, fill=tc)


def arrow(d, a, b, color=ACCENT2, width=5):
    d.line([a, b], fill=color, width=width)
    ang = math.atan2(b[1] - a[1], b[0] - a[0])
    for s in (-1, 1):
        d.line([b, (b[0] - 18 * math.cos(ang + s * 0.5), b[1] - 18 * math.sin(ang + s * 0.5))], fill=color, width=width)


def diagram(kind):
    """returns a list of (RGBA image, delay in seconds) parts, drawn in an 816x380 area"""
    parts = []
    if kind in ("loop", "stake_prove_settle"):
        names = [("Stake", "test tokens"), ("Prove", "each day"), ("Settle", "on Solana")]
        for i, (a, b) in enumerate(names):
            im = layer(RW, 380)
            d = ImageDraw.Draw(im)
            x0 = i * 290
            done = kind == "stake_prove_settle" and i == 0
            box(d, (x0, 40, x0 + 236, 220), a, b, fill=ACCENT if done else PANEL, outline=ACCENT2 if done else ACCENT)
            if i < 2:
                arrow(d, (x0 + 244, 130), (x0 + 284, 130))
            parts.append((im, 0.35 * i))
        im = layer(RW, 380)
        d = ImageDraw.Draw(im)
        msg = "Stake held by the Vowed program" if kind == "stake_prove_settle" else "Kept by the program, paid out at the end"
        d.text((0, 270), msg, font=F_DIA_S, fill=MUTED)
        parts.append((im, 1.1))
    elif kind == "mwa_signin":
        im = layer(RW, 380)
        d = ImageDraw.Draw(im)
        box(d, (0, 20, 230, 150), "Vowed")
        box(d, (586, 20, 816, 150), "Phantom")
        parts.append((im, 0))
        im = layer(RW, 380)
        d = ImageDraw.Draw(im)
        arrow(d, (240, 60), (576, 60))
        d.text((262, 0), "connect + sign-in", font=F_DIA_S, fill=TEXT)
        parts.append((im, 0.5))
        im = layer(RW, 380)
        d = ImageDraw.Draw(im)
        arrow(d, (576, 112), (240, 112))
        d.text((262, 124), "address + signature", font=F_DIA_S, fill=TEXT)
        parts.append((im, 1.1))
        im = layer(RW, 380)
        d = ImageDraw.Draw(im)
        d.rounded_rectangle((0, 230, 816, 330), radius=22, fill=PANEL, outline=ACCENT, width=3)
        d.text((28, 256), "Vowed's server checks the signature", font=F_DIA_S, fill=TEXT)
        parts.append((im, 1.7))
    elif kind == "trust":
        rows = [("Low", "you confirm it yourself"), ("Medium", "phone sensors or a timer"), ("High", "camera, app-usage data")]
        for i, (a, b) in enumerate(rows):
            im = layer(RW, 380)
            d = ImageDraw.Draw(im)
            y = 10 + i * 120
            hi = a == "High"
            d.rounded_rectangle((0, y, 816, y + 100), radius=20, fill=ACCENT if hi else PANEL, outline=ACCENT2 if hi else ACCENT, width=3)
            d.text((28, y + 26), a, font=F_DIA, fill=TEXT)
            d.text((230, y + 26), b, font=F_DIA_S, fill=TEXT if hi else MUTED)
            parts.append((im, 0.3 * i))
    elif kind == "costs":
        rows = [("Network fee", "~0.0001 SOL"), ("Account rent", "~0.0048 SOL"), ("Total needed", "~0.0054 SOL")]
        for i, (a, b) in enumerate(rows):
            im = layer(RW, 380)
            d = ImageDraw.Draw(im)
            y = 10 + i * 110
            last = i == 2
            d.rounded_rectangle((0, y, 816, y + 92), radius=18, fill=ACCENT if last else PANEL, outline=ACCENT, width=3)
            d.text((28, y + 22), a, font=F_DIA if last else F_DIA_S, fill=TEXT)
            tw = d.textlength(b, font=F_DIA)
            d.text((790 - tw, y + 22), b, font=F_DIA, fill=TEXT)
            parts.append((im, 0.4 * i))
        im = layer(RW, 380)
        ImageDraw.Draw(im).text((0, 340), "devnet SOL, shown before signing", font=F_DIA_S, fill=MUTED)
        parts.append((im, 1.4))
    elif kind == "sign_flow":
        steps = [("Vowed builds", 0), ("Phantom signs", 1), ("Server sends once", 2), ("Program holds stake", 3)]
        for label, i in steps:
            im = layer(RW, 380)
            d = ImageDraw.Draw(im)
            y = i * 92
            d.rounded_rectangle((0, y, 600, y + 72), radius=18, fill=PANEL, outline=ACCENT, width=3)
            d.text((24, y + 14), label, font=F_DIA_S, fill=TEXT)
            if i < 3:
                arrow(d, (300, y + 74), (300, y + 90), width=4)
            parts.append((im, 0.35 * i))
    elif kind == "privacy":
        im = layer(RW, 380)
        d = ImageDraw.Draw(im)
        d.rounded_rectangle((0, 0, 400, 360), radius=22, fill=PANEL, outline=ACCENT2, width=3)
        d.text((24, 20), "On the phone", font=F_DIA, fill=ACCENT2)
        for j, t in enumerate(["camera picture", "body points", "rep counting"]):
            d.text((24, 90 + j * 62), t, font=F_DIA_S, fill=TEXT)
        parts.append((im, 0))
        im = layer(RW, 380)
        d = ImageDraw.Draw(im)
        d.rounded_rectangle((436, 0, 816, 360), radius=22, fill=PANEL, outline=ACCENT, width=3)
        d.text((460, 20), "Sent to Vowed", font=F_DIA, fill=TEXT)
        for j, t in enumerate(["number of reps", "signed proof"]):
            d.text((460, 90 + j * 62), t, font=F_DIA_S, fill=TEXT)
        parts.append((im, 0.6))
    return parts


def scene_elements(key, dur):
    """the right-hand elements of one scene: (image, x, y, appear_time)"""
    sc = SCENES[key]
    els = []
    im = layer(RW, 80)
    ImageDraw.Draw(im).text((0, 0), sc["title"], font=F_TITLE, fill=TEXT)
    els.append((im, RX, 96, 0.05))
    im = layer(RW, 8)
    ImageDraw.Draw(im).rounded_rectangle((0, 0, 72, 7), radius=3, fill=ACCENT2)
    els.append((im, RX, 176, 0.2))
    y = 214
    gap = min(0.55, max(0.3, (dur * 0.35) / max(1, len(sc["bullets"]))))
    for i, b in enumerate(sc["bullets"]):
        im = layer(RW, 56)
        d = ImageDraw.Draw(im)
        d.ellipse((0, 18, 14, 32), fill=ACCENT2)
        rich_text(d, (34, 0), b)
        els.append((im, RX, y, 0.35 + i * gap))
        y += 66
    dy = max(y + 30, 440)
    if sc.get("diagram"):
        for im, delay in diagram(sc["diagram"]):
            els.append((im, RX, dy, 0.6 + len(sc["bullets"]) * gap * 0.6 + delay))
    if sc.get("note"):
        im = layer(RW, 48)
        rich_text(ImageDraw.Draw(im), (0, 0), sc["note"], base=F_NOTE, bold=F_NOTE, color=MUTED)
        els.append((im, RX, 836, min(dur - 1.0, 1.6 + len(sc["bullets"]) * gap)))
    return els


def wrap(text, f, width, d):
    words, lines, cur = text.split(), [], ""
    for w_ in words:
        cand = (cur + " " + w_).strip()
        if d.textlength(cand, font=f) > width and cur:
            lines.append(cur)
            cur = w_
        else:
            cur = cand
    if cur:
        lines.append(cur)
    return lines


# ------------------------------------------------------------------ frames
def base_frame():
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    d.rectangle((PHONE_X - 26, 0, PHONE_X + PHONE_W + 26, H), fill=PANEL)
    d.rectangle((960, 0, W, H), fill=BG)
    d.line((960, 60, 960, H - 60), fill=PANEL, width=2)
    return im


def logo(size):
    """the logo artwork is on a white square: shown as a rounded app-icon tile"""
    lg = Image.open(LOGO).convert("RGBA")
    lg.thumbnail((size, size), Image.LANCZOS)
    mask = Image.new("L", lg.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, lg.width - 1, lg.height - 1), radius=int(lg.width * 0.22), fill=255)
    lg.putalpha(Image.composite(lg.getchannel("A"), mask, mask))
    return lg


def intro_frame(t, lg):
    im = Image.new("RGB", (W, H), BG)
    a = ease(t / 0.7) * (1 - ease((t - 3.45) / 0.5))
    layer_ = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    layer_.alpha_composite(lg, ((W - lg.width) // 2, 230 - int(20 * (1 - ease(t / 0.9)))))
    d = ImageDraw.Draw(layer_)
    f = font(60, "SemiBold")
    msg = "Put something on the line for your goals"
    d.text(((W - d.textlength(msg, font=f)) / 2, 700), msg, font=f, fill=TEXT)
    f2 = font(36)
    m2 = "Android · Solana Mobile · devnet test tokens only"
    d.text(((W - d.textlength(m2, font=f2)) / 2, 800), m2, font=f2, fill=MUTED)
    layer_.putalpha(Image.eval(layer_.getchannel("A"), lambda v: int(v * a)))
    im.paste(layer_, (0, 0), layer_)
    return im


def outro_frame(t, lg):
    im = Image.new("RGB", (W, H), BG)
    a = ease(t / 0.6) * (1 - ease((t - 4.4) / 0.6))
    L = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    L.alpha_composite(lg, ((W - lg.width) // 2, 120))
    d = ImageDraw.Draw(L)
    rows = [("Download the APK", "github.com/Im-2/Vowed/releases", 560), ("Source code", "github.com/Im-2/Vowed", 720)]
    for label, url, y in rows:
        f1, f2 = font(40, "Medium"), font(56, "Bold")
        d.text(((W - d.textlength(label, font=f1)) / 2, y), label, font=f1, fill=MUTED)
        d.text(((W - d.textlength(url, font=f2)) / 2, y + 56), url, font=f2, fill=ACCENT2)
    f3 = font(36)
    m = "Solana devnet · test tokens with no value · demo and sample data are labelled"
    d.text(((W - d.textlength(m, font=f3)) / 2, 940), m, font=f3, fill=MUTED)
    L.putalpha(Image.eval(L.getchannel("A"), lambda v: int(v * a)))
    im.paste(L, (0, 0), L)
    return im


def main(preview=None):
    tl = json.load(open(os.path.join(CACHE, "timeline.json"), encoding="utf-8"))
    times = scene_times(tl)
    for k, a, b in times:
        print(f"  {k:14s} {a:7.2f} - {b:7.2f}  ({b - a:5.2f}s)")
    left = build_left(times)
    total = tl["total"]
    nframes = int(round(total * FPS))
    lg_big, lg_small = logo(420), logo(300)
    base = base_frame()
    scene_els = {k: scene_elements(k, b - a) for k, a, b in times}
    caps = tl["captions"]
    dtmp = ImageDraw.Draw(Image.new("RGB", (10, 10)))
    cap_lines = [wrap(c["text"], F_CAP, RW - 48, dtmp) for c in caps]
    gfx = os.path.join(CACHE, "gfx.mp4")
    enc = subprocess.Popen([FFMPEG, "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
                            "-c:v", "libx264", "-preset", "veryfast", "-crf", "14", "-pix_fmt", "yuv420p", gfx], stdin=subprocess.PIPE)
    frames = range(nframes) if preview is None else preview
    for fi in frames:
        t = fi / FPS
        if t < tl["intro"]:
            frame = intro_frame(t, lg_big)
        elif t >= tl["outro_start"]:
            frame = outro_frame(t - tl["outro_start"], lg_small)
        else:
            frame = base.copy().convert("RGBA")
            for k, a, b in times:
                if a <= t < b:
                    st = t - a
                    out_a = 1 - ease((st - (b - a - 0.4)) / 0.4) if k != ORDER[-1] else 1.0
                    for im, x, y, appear in scene_els[k]:
                        p = ease((st - appear) / 0.5)
                        if p <= 0:
                            continue
                        alpha = p * out_a
                        if alpha < 1:
                            im2 = im.copy()
                            im2.putalpha(Image.eval(im.getchannel("A"), lambda v, al=alpha: int(v * al)))
                        else:
                            im2 = im
                        frame.alpha_composite(im2, (x, y + int(22 * (1 - p))))
            # caption
            for c, lines in zip(caps, cap_lines):
                if c["start"] - 0.05 <= t < c["end"] + 0.25:
                    ca = min(ease((t - c["start"] + 0.05) / 0.15), 1 - ease((t - c["end"] - 0.1) / 0.15))
                    L = layer(960, 190)
                    d = ImageDraw.Draw(L)
                    h = 28 + 48 * len(lines)
                    d.rounded_rectangle((48, 190 - h - 16, 912, 190 - 16), radius=18, fill=(0x0F, 0x0B, 0x1D, 235))
                    for j, ln in enumerate(lines):
                        tw = d.textlength(ln, font=F_CAP)
                        d.text((480 - tw / 2, 190 - h - 16 + 14 + j * 48), ln, font=F_CAP, fill=TEXT)
                    if ca < 1:
                        L.putalpha(Image.eval(L.getchannel("A"), lambda v, al=ca: int(v * al)))
                    frame.alpha_composite(L, (960, H - 190))
                    break
            frame = frame.convert("RGB")
        if preview is not None:
            frame.save(os.path.join(CACHE, f"preview_{fi:05d}.png"))
        enc.stdin.write(frame.tobytes())
        if fi % 300 == 0:
            print(f"  frame {fi}/{nframes}", flush=True)
    enc.stdin.close()
    enc.wait()
    if preview is not None:
        return
    # compose: phone footage onto the left half between intro and outro, then the soundtrack, loudness -16 LUFS (two pass)
    mix = os.path.join(CACHE, "mix.wav")
    meas = subprocess.run([FFMPEG, "-hide_banner", "-i", mix, "-af", "loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json", "-f", "null", "-"],
                          capture_output=True, text=True).stderr
    js = json.loads(meas[meas.rindex("{"):meas.rindex("}") + 1])
    ln = (f"loudnorm=I=-16:TP=-1.5:LRA=11:measured_I={js['input_i']}:measured_TP={js['input_tp']}:measured_LRA={js['input_lra']}"
          f":measured_thresh={js['input_thresh']}:offset={js['target_offset']}:linear=true,aresample=48000")
    t0, t1 = tl["intro"], tl["outro_start"]
    out = os.path.join(OUT, "vowed-demo-3min.mp4")
    graph = (f"[1:v]setpts=PTS-STARTPTS+{t0}/TB,fade=t=in:st={t0}:d=0.5,fade=t=out:st={t1 - 0.5}:d=0.5[ph];"
             f"[0:v][ph]overlay=x={PHONE_X}:y=0:enable='between(t,{t0},{t1})':eof_action=pass,format=yuv420p[v];[2:a]{ln}[a]")
    subprocess.run([FFMPEG, "-v", "error", "-y", "-i", gfx, "-i", left, "-i", mix, "-filter_complex", graph, "-map", "[v]", "-map", "[a]",
                    "-c:v", "libx264", "-preset", "slow", "-crf", "20", "-profile:v", "high", "-pix_fmt", "yuv420p", "-r", str(FPS),
                    "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-t", f"{total:.3f}", out], check=True)
    # poster: the camera scene
    k, a, b = [x for x in times if x[0] == "s12_camera"][0]
    subprocess.run([FFMPEG, "-v", "error", "-y", "-ss", f"{a + 11.5:.2f}", "-i", out, "-frames:v", "1", os.path.join(OUT, "poster.png")], check=True)
    print("wrote", out)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "preview":
        main(preview=[int(x) for x in sys.argv[2:]])
    else:
        main()
