"""Checks the voice track as data: gaps longer than 0.6 s inside each act, clipping, loudness of each act (should match), and
whether any sentence is cut off at its end (energy in the last 30 ms of each sentence segment)."""
import json
import os
import wave
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "cache")
SR = 44100

with wave.open(os.path.join(CACHE, "voice.wav")) as w:
    v = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32).reshape(-1, 2)[:, 0] / 32768
with wave.open(os.path.join(CACHE, "mix.wav")) as w:
    m = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32).reshape(-1, 2) / 32768
tl = json.load(open(os.path.join(CACHE, "timeline.json"), encoding="utf-8"))
blk = int(0.02 * SR)
nb = len(v) // blk
e = np.sqrt((v[:nb * blk].reshape(nb, blk) ** 2).mean(1))
voiced = e > 0.008
bad = 0
for a in tl["acts"]:
    s, t = int(a["start"] / 0.02), int(a["end"] / 0.02)
    run, gaps = 0, []
    for i in range(s, t):
        if not voiced[i]:
            run += 1
        else:
            if run * 0.02 > 0.6:
                gaps.append(round((i - run) * 0.02, 2))
            run = 0
    seg = v[int(a["start"] * SR):int(a["end"] * SR)]
    rms = 20 * np.log10(np.sqrt(np.mean(seg[np.abs(seg) > 0.01] ** 2)))
    print(f"{a['act']}: {a['start']:.1f}-{a['end']:.1f}s, speech level {rms:.1f} dBFS, gaps > 0.6 s: {gaps or 'none'}")
    bad += len(gaps)
# a sentence is cut off if the voice is still loud right where its audio segment ends (the sound would stop mid-word)
for s in tl["sentences"]:
    k = int(s["end"] * SR)
    # find the end of this sentence's audio: the first 60 ms of near silence after the aligned end
    j = k
    while j < len(v) - 2646 and np.sqrt(np.mean(v[j:j + 2646] ** 2)) > 0.004:
        j += 441
    before = np.sqrt(np.mean(v[max(0, j - 1323):j] ** 2))
    if before > 0.05:
        print(f"possible cut-off: '{s['text'][-40:]}' (level {before:.3f} right before the silence)")
        bad += 1
print(f"mix peak {np.max(np.abs(m)):.3f} (clipping {'YES' if np.max(np.abs(m)) >= 0.999 else 'no'}); issues: {bad}")
