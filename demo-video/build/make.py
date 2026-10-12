"""One command for the whole demo video:  python demo-video/build/make.py
tts (cached: no characters are used again unless the narration changes) -> measure -> audio -> render -> check."""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
for step in ("tts.py", "measure.py", "audio.py", "audio_check.py", "render.py", "gen_script.py", "check.py"):
    print(f"== {step}", flush=True)
    r = subprocess.run([sys.executable, os.path.join(HERE, step)], cwd=HERE)
    if r.returncode != 0:
        sys.exit(f"{step} failed ({r.returncode})")
