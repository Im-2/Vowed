"""Checks the finished video: format, duration, size, loudness, clipping, music under the voice, and frames to look at
(every scene boundary and every 10 s), written to build/out/check/."""
import json
import os
import subprocess
import numpy as np

from tools import FFMPEG, FFPROBE, ROOT
from render import scene_times, CACHE

OUT = os.path.join(ROOT, "demo-video", "vowed-demo-3min.mp4")
CHK = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out", "check")


def main():
    os.makedirs(CHK, exist_ok=True)
    p = json.loads(subprocess.run([FFPROBE, "-v", "error", "-show_entries", "format=duration,size:stream=codec_name,width,height,r_frame_rate,sample_rate",
                                   "-of", "json", OUT], capture_output=True, text=True).stdout)
    v = [s for s in p["streams"] if s.get("width")][0]
    a = [s for s in p["streams"] if s.get("sample_rate")][0]
    dur, size = float(p["format"]["duration"]), int(p["format"]["size"])
    print(f"video {v['codec_name']} {v['width']}x{v['height']} {v['r_frame_rate']} | audio {a['codec_name']} {a['sample_rate']} Hz")
    print(f"duration {dur:.2f}s ({'OK' if dur <= 180 else 'TOO LONG'}) | size {size / 1e6:.1f} MB ({'OK' if size < 100e6 else 'TOO BIG'})")
    ebu = subprocess.run([FFMPEG, "-hide_banner", "-i", OUT, "-af", "ebur128=peak=true", "-f", "null", "-"], capture_output=True, text=True).stderr
    summ = ebu[ebu.rfind("Summary:"):]
    i_line = [l.strip() for l in summ.splitlines() if l.strip().startswith(("I:", "Peak:", "LRA:"))]
    print("loudness:", " | ".join(i_line))
    # voice vs music while speaking, from the stems
    import wave
    def rd(n):
        with wave.open(os.path.join(CACHE, n)) as w:
            return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32).reshape(-1, 2).mean(1) / 32768
    vo, mu = rd("voice.wav"), rd("music.wav")
    blk = 4410
    nb = len(vo) // blk
    ve = np.sqrt((vo[:nb * blk].reshape(nb, blk) ** 2).mean(1)) + 1e-9
    me = np.sqrt((mu[:nb * blk].reshape(nb, blk) ** 2).mean(1)) + 1e-9
    sp = ve > 0.02
    print(f"music under the voice while speaking: median {np.median(20 * np.log10(me[sp] / ve[sp])):.1f} dB; music in pauses "
          f"{np.median(20 * np.log10(me[~sp][20:-20])):.1f} dBFS vs while speaking {np.median(20 * np.log10(me[sp])):.1f} dBFS")
    tl = json.load(open(os.path.join(CACHE, "timeline.json"), encoding="utf-8"))
    times = scene_times(tl)
    stamps = sorted({round(x, 2) for _, s, e in times for x in (s + 0.6, e - 0.4)} | {float(t) for t in range(1, int(dur), 10)})
    for t in stamps:
        subprocess.run([FFMPEG, "-v", "error", "-y", "-ss", f"{t:.2f}", "-i", OUT, "-frames:v", "1", "-vf", "scale=640:-1",
                        os.path.join(CHK, f"t{t:07.2f}.jpg")], check=True)
    print(f"{len(stamps)} check frames in {CHK}")


if __name__ == "__main__":
    main()
