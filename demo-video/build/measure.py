"""Measures each narration act: duration, sentence start/end (from the ElevenLabs character alignment), silent gaps, peak and loudness.
Writes build/cache/timing.json, used by the render step."""
import json
import os
import subprocess
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "cache")
from tools import FFMPEG  # noqa: E402

SR = 44100


def decode(mp3):
    raw = subprocess.run([FFMPEG, "-v", "error", "-i", mp3, "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32)


def main():
    n = json.load(open(os.path.join(HERE, "narration.json"), encoding="utf-8-sig"))
    out = {"acts": []}
    for act in n["acts"]:
        a = json.load(open(os.path.join(CACHE, "tts", f"{act['id']}.align.json"), encoding="utf-8"))
        al = a["alignment"]
        chars = "".join(al["characters"])
        st, en = al["character_start_times_seconds"], al["character_end_times_seconds"]
        x = decode(os.path.join(CACHE, "tts", f"{act['id']}.mp3"))
        dur = len(x) / SR
        # speech envelope (20 ms windows)
        w = int(0.02 * SR)
        env = np.sqrt(np.convolve(x ** 2, np.ones(w) / w, mode="same"))
        voiced = env > 0.01
        idx = np.flatnonzero(voiced)
        first, last = (idx[0] / SR, idx[-1] / SR) if len(idx) else (0, dur)
        # gaps inside speech
        gaps, run = [], 0
        for i in range(idx[0], idx[-1], w):
            if not voiced[i]:
                run += w
            else:
                if run / SR > 0.6:
                    gaps.append(round((i - run) / SR, 2))
                run = 0
        sentences, pos = [], 0
        for s in act["sentences"]:
            k = chars.find(s["text"][:12], pos)
            if k < 0:
                k = pos
            end_k = min(k + len(s["text"]) - 1, len(en) - 1)
            sentences.append({"scene": s["scene"], "text": s["text"], "start": round(st[k], 3), "end": round(en[end_k], 3)})
            pos = k + len(s["text"])
        peak = float(np.max(np.abs(x)))
        rms_db = 20 * np.log10(np.sqrt(np.mean(x[int(first * SR):int(last * SR)] ** 2)) + 1e-9)
        out["acts"].append({"id": act["id"], "duration": round(dur, 3), "speech_start": round(first, 3), "speech_end": round(last, 3),
                            "peak": round(peak, 3), "rms_db": round(float(rms_db), 1), "gaps_over_0_6s": gaps, "sentences": sentences})
        print(f"{act['id']}: {dur:6.2f}s speech {first:.2f}-{last:.2f} peak {peak:.2f} rms {rms_db:.1f} dB gaps>0.6s {gaps}")
    json.dump(out, open(os.path.join(CACHE, "timing.json"), "w", encoding="utf-8"), indent=1)
    print("total speech", round(sum(a["speech_end"] - a["speech_start"] for a in out["acts"]), 1), "s")


if __name__ == "__main__":
    main()
