"""Shows what is around each long gap inside the voice (which words), so a gap can be judged: a natural pause or a fault."""
import json
import os
import wave
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "cache")
SR = 44100
with wave.open(os.path.join(CACHE, "voice.wav")) as w:
    v = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32).reshape(-1, 2)[:, 0] / 32768
tl = json.load(open(os.path.join(CACHE, "timeline.json"), encoding="utf-8"))
blk = int(0.02 * SR)
nb = len(v) // blk
e = np.sqrt((v[:nb * blk].reshape(nb, blk) ** 2).mean(1))
for thr in (0.008, 0.003):
    voiced = e > thr
    print(f"threshold {thr}:")
    for a in tl["acts"]:
        s, t = int(a["start"] / 0.02), int(a["end"] / 0.02)
        run = 0
        for i in range(s, t):
            if not voiced[i]:
                run += 1
            else:
                if run * 0.02 > 0.6:
                    g0, g1 = (i - run) * 0.02, i * 0.02
                    cap = [c["text"] for c in tl["captions"] if c["start"] - 0.3 <= g0 <= c["end"] + 0.3]
                    inside = any(x["start"] < g0 - 0.05 and g1 + 0.05 < x["end"] for x in tl["sentences"])
                    print(f"  {a['act']} {g0:7.2f}-{g1:7.2f} ({g1 - g0:.2f}s) {'INSIDE a sentence' if inside else 'between sentences'}: {cap[:1]}")
                run = 0
