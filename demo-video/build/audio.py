"""Builds the soundtrack and the timeline.

voice: the six ElevenLabs acts, each trimmed at its speech edges, levelled to the same loudness, the silence between sentences shortened to
       SENT_GAP, acts joined with ACT_GAP of silence and 100 ms fades (no stretching, no speed change)
music: an original ambient pad made here in code (slow chords of sine + triangle voices, low-pass, light reverb), so there is no licence question
mix:   music ducked to about 22 dB under the voice while it speaks, a little higher in the pauses; 2 s fade in, 3 s fade out; loudness -16 LUFS
Writes build/cache/voice.wav, music.wav, mix.wav (pre-loudnorm) and timeline.json (sentence and caption times on the final clock).
"""
import json
import os
import subprocess
import numpy as np
from scipy.signal import butter, sosfilt, fftconvolve

from tools import FFMPEG

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "cache")
SR = 44100
INTRO = 4.0
OUTRO = 5.0
VOICE_START = INTRO + 0.35
SENT_GAP = 0.38
ACT_GAP = 0.55
FADE = 0.10
CAPTION_MAX = 64


def decode(path):
    raw = subprocess.run([FFMPEG, "-v", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).copy()


def movavg(x, w):
    """centred moving average of length w, O(n)"""
    c = np.concatenate([[0.0], np.cumsum(x, dtype=np.float64)])
    h = w // 2
    lo = np.clip(np.arange(len(x)) - h, 0, len(x))
    hi = np.clip(np.arange(len(x)) + (w - h), 0, len(x))
    return (c[hi] - c[lo]) / np.maximum(hi - lo, 1)


def env(x, win=0.02):
    return np.sqrt(np.maximum(movavg(x.astype(np.float64) ** 2, max(1, int(win * SR))), 0))


def fades(x, fin=FADE, fout=FADE):
    x = x.copy()
    a, b = int(fin * SR), int(fout * SR)
    if a:
        x[:a] *= np.sin(np.linspace(0, np.pi / 2, a)) ** 2
    if b:
        x[-b:] *= np.cos(np.linspace(0, np.pi / 2, b)) ** 2
    return x


MAX_PAUSE = 0.45


def compress_pauses(seg):
    """Shortens every silence inside a sentence segment to MAX_PAUSE (cut in the middle of the silence, with a short crossfade).
    Returns the new segment and a function mapping old segment time to new segment time."""
    blk = int(0.02 * SR)
    nb = len(seg) // blk
    if nb < 3:
        return seg, (lambda t: t)
    e = np.sqrt((seg[:nb * blk].reshape(nb, blk).astype(np.float64) ** 2).mean(1))
    quiet = e < 0.004
    cuts, i = [], 0
    while i < nb:
        if quiet[i]:
            j = i
            while j < nb and quiet[j]:
                j += 1
            length = (j - i) * 0.02
            if length > MAX_PAUSE and i > 0 and j < nb:
                excess = length - MAX_PAUSE
                mid = (i + j) / 2 * 0.02
                cuts.append((mid - excess / 2, excess))
            i = j
        else:
            i += 1
    if not cuts:
        return seg, (lambda t: t)
    pieces, last = [], 0
    xf = int(0.01 * SR)
    for p, r in cuts:
        a, b = int(p * SR), int((p + r) * SR)
        pieces.append(seg[last:a])
        last = b
    pieces.append(seg[last:])
    out = pieces[0]
    for pc in pieces[1:]:
        if len(out) > xf and len(pc) > xf:
            fade = np.linspace(0, 1, xf, dtype=np.float32)
            out = np.concatenate([out[:-xf], out[-xf:] * (1 - fade) + pc[:xf] * fade, pc[xf:]])
        else:
            out = np.concatenate([out, pc])

    def remap(t):
        shift = 0.0
        for p, r in cuts:
            if t >= p + r:
                shift += r
            elif t > p:
                shift += t - p
        return t - shift
    return out.astype(np.float32), remap


def caption_pieces(text, chars, st, en, k0):
    """Splits one sentence into caption pieces of at most CAPTION_MAX characters, timed by the character alignment."""
    words, pieces, cur, cur_start = text.split(" "), [], "", 0
    pos = 0
    for w in words:
        cand = (cur + " " + w).strip()
        if len(cand) > CAPTION_MAX and cur:
            pieces.append((cur, cur_start, pos - 1))
            cur, cur_start = w, pos
        else:
            if not cur:
                cur_start = pos
            cur = cand
        pos += len(w) + 1
    if cur:
        pieces.append((cur, cur_start, len(text) - 1))
    out = []
    for t, a, b in pieces:
        ia, ib = min(k0 + a, len(st) - 1), min(k0 + b, len(en) - 1)
        out.append({"text": t, "start": st[ia], "end": en[ib]})
    return out


def build_voice():
    n = json.load(open(os.path.join(HERE, "narration.json"), encoding="utf-8-sig"))
    timing = json.load(open(os.path.join(CACHE, "timing.json"), encoding="utf-8"))
    out = [np.zeros(int(VOICE_START * SR), dtype=np.float32)]
    t = VOICE_START
    sentences, captions, act_marks = [], [], []
    target_rms = 10 ** (-20 / 20)
    for act, tm in zip(n["acts"], timing["acts"]):
        x = decode(os.path.join(CACHE, "tts", f"{act['id']}.mp3"))
        al = json.load(open(os.path.join(CACHE, "tts", f"{act['id']}.align.json"), encoding="utf-8"))["alignment"]
        chars = "".join(al["characters"])
        st, en = al["character_start_times_seconds"], al["character_end_times_seconds"]
        e = env(x)
        voiced = e > 0.01
        rms = np.sqrt(np.mean(x[voiced] ** 2)) if voiced.any() else 1.0
        x = x * (target_rms / rms)
        act_start = t
        sents = tm["sentences"]
        for i, s in enumerate(sents):
            # cut points: inside the silence before and after the sentence (never inside a word)
            a = s["start"] - 0.06 if i == 0 else (sents[i - 1]["end"] + s["start"]) / 2
            b = s["end"] + 0.45 if i == len(sents) - 1 else (s["end"] + sents[i + 1]["start"]) / 2
            a, b = max(0.0, a), min(len(x) / SR, b)
            seg = x[int(a * SR):int(b * SR)]
            # tighten the silence at both edges to keep SENT_GAP between sentences
            ev = env(seg) > 0.006
            idx = np.flatnonzero(ev)
            lead = idx[0] / SR if len(idx) else 0
            tail = (len(seg) - idx[-1]) / SR if len(idx) else 0
            trim_a = max(0.0, lead - 0.04)
            trim_b = max(0.0, tail - 0.09)
            seg = seg[int(trim_a * SR):len(seg) - int(trim_b * SR)]
            seg, remap = compress_pauses(seg)
            seg = fades(seg, 0.012, 0.03)
            abs_start = t
            offset = a + trim_a  # act time at abs_start (before the pauses were shortened)

            def at(act_t):
                return round(abs_start + remap(max(0.0, act_t - offset)), 3)
            sentences.append({"act": act["id"], "scene": s["scene"], "text": s["text"], "start": at(s["start"]), "end": at(s["end"])})
            k0 = chars.find(s["text"][:12])
            for c in caption_pieces(s["text"], chars, st, en, max(0, k0)):
                captions.append({"text": c["text"], "start": at(c["start"]), "end": at(c["end"])})
            out.append(seg.astype(np.float32))
            t += len(seg) / SR
            if i < len(sents) - 1:
                out.append(np.zeros(int(SENT_GAP * SR), dtype=np.float32))
                t += SENT_GAP
        act_marks.append({"act": act["id"], "start": round(act_start, 3), "end": round(t, 3)})
        out.append(np.zeros(int(ACT_GAP * SR), dtype=np.float32))
        t += ACT_GAP
    voice_end = act_marks[-1]["end"]
    total = voice_end + 0.6 + OUTRO
    v = np.concatenate(out)
    v = np.concatenate([v, np.zeros(int(total * SR) - len(v), dtype=np.float32)])[: int(total * SR)]
    return v, {"sentences": sentences, "captions": captions, "acts": act_marks, "voice_end": voice_end,
               "outro_start": round(voice_end + 0.6, 3), "total": round(total, 3), "intro": INTRO}


def pad_music(total):
    """Original ambient pad: four slow chords (D major key), each voice a sine plus a soft triangle, gently detuned per channel."""
    rng = np.random.default_rng(7)
    n = int(total * SR)
    tt = np.arange(n) / SR
    chords = [[146.83, 185.00, 220.00, 277.18], [123.47, 185.00, 220.00, 293.66], [98.00, 146.83, 196.00, 246.94], [110.00, 164.81, 220.00, 277.18]]
    length = 8.0
    out = np.zeros((n, 2), dtype=np.float64)
    for ci in range(int(np.ceil(total / length)) + 1):
        notes = chords[ci % len(chords)]
        a, b = int(max(0, (ci * length - 2.0)) * SR), int(min(total, ci * length + length + 2.0) * SR)
        if a >= n:
            break
        seg_t = tt[a:b] - ci * length
        envl = np.clip((seg_t + 2.0) / 3.0, 0, 1) * np.clip((length + 2.0 - seg_t) / 3.0, 0, 1)
        envl = np.sin(envl * np.pi / 2) ** 2
        for f in notes:
            for ch, det in ((0, -0.8), (1, 0.8)):
                ph = 2 * np.pi * (f + det) * tt[a:b]
                tri = 2 / np.pi * np.arcsin(np.sin(ph))
                out[a:b, ch] += envl * (0.6 * np.sin(ph) + 0.25 * tri) / len(notes)
        # a soft sub note an octave down
        f0 = notes[0] / 2
        out[a:b, :] += (envl * 0.25 * np.sin(2 * np.pi * f0 * tt[a:b]))[:, None]
    sos = butter(2, 900, "low", fs=SR, output="sos")
    out = sosfilt(sos, out, axis=0)
    # light reverb: decaying noise impulse, 2.4 s
    ir_n = int(2.4 * SR)
    ir = rng.standard_normal(ir_n) * np.exp(-np.arange(ir_n) / (0.55 * SR))
    ir /= np.sqrt(np.sum(ir ** 2))
    wet = np.stack([fftconvolve(out[:, c], ir)[:n] for c in range(2)], axis=1)
    out = 0.75 * out + 0.35 * wet
    # slow movement in level
    out *= (0.85 + 0.15 * np.sin(2 * np.pi * tt / 23.0))[:, None]
    out /= np.max(np.abs(out)) + 1e-9
    return out.astype(np.float32)


def duck_gain(v, total):
    """Music gain (linear) that follows the voice: about -22 dB relative to the voice while it speaks, -15 dB in pauses."""
    e = env(v, 0.05)
    speaking = (e > 0.004).astype(np.float64)
    # hold speech state across short word gaps, then smooth (attack 80 ms, release 450 ms)
    hold = int(0.25 * SR)
    speaking = movavg(speaking, hold) > 0
    target_db = np.where(speaking, -22.0, -15.0)
    # one-pole smoother on 10 ms blocks (attack 80 ms, release 450 ms), then back to samples
    blk = int(0.01 * SR)
    nb = int(np.ceil(len(target_db) / blk))
    tb = np.array([target_db[i * blk:(i + 1) * blk].min() for i in range(nb)])
    gb = np.empty(nb)
    a_att, a_rel = np.exp(-1 / 8.0), np.exp(-1 / 45.0)
    cur = tb[0]
    for i in range(nb):
        k = a_att if tb[i] < cur else a_rel
        cur = k * cur + (1 - k) * tb[i]
        gb[i] = cur
    return np.interp(np.arange(len(target_db)), np.arange(nb) * blk + blk / 2, gb)


def main():
    v, tl = build_voice()
    total = tl["total"]
    m = pad_music(total)
    voice_rms = 10 ** (-20 / 20)
    m_rms = np.sqrt(np.mean(m ** 2))
    g_db = duck_gain(v, total)
    # downsample the smoother loop for speed: compute on 10 ms blocks
    m = m * (voice_rms / m_rms) * (10 ** (g_db / 20))[:, None]
    n = len(m)
    fi, fo = int(2.0 * SR), int(3.0 * SR)
    m[:fi] *= np.linspace(0, 1, fi)[:, None]
    m[-fo:] *= np.linspace(1, 0, fo)[:, None]
    mix = m + v[:, None]
    peak = np.max(np.abs(mix))
    if peak > 0.95:
        mix *= 0.95 / peak
    import wave
    for name, data in (("voice.wav", np.stack([v, v], 1)), ("music.wav", m), ("mix.wav", mix)):
        d = (np.clip(data, -1, 1) * 32767).astype(np.int16)
        with wave.open(os.path.join(CACHE, name), "wb") as w:
            w.setnchannels(2)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(d.tobytes())
    json.dump(tl, open(os.path.join(CACHE, "timeline.json"), "w", encoding="utf-8"), indent=1)
    print(f"voice ends {tl['voice_end']:.2f}s, outro {tl['outro_start']:.2f}s, total {total:.2f}s, {len(tl['captions'])} caption pieces")


if __name__ == "__main__":
    main()
