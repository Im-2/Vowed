#!/usr/bin/env bash
# Usage: scripts/ui-shot.sh <preview-screen> <file-name-without-ext>   (debug build installed on the emulator)
# Opens the debug-only PreviewActivity with made-up data, waits until the picture is stable (and is not the system launch screen), saves docs/ui/<name>.png
A=/c/Users/hp/Android/Sdk/platform-tools/adb
OUT="$(dirname "$0")/../docs/ui/$2.png"
$A shell am force-stop app.vowed
$A shell am start -n app.vowed/.debug.PreviewActivity --es screen "$1" >/dev/null
sleep "${3:-12}"
prev=""
for i in $(seq 1 25); do
  sleep 2
  $A exec-out screencap -p > "$OUT"
  sz=$(stat -c %s "$OUT"); h=$(md5sum "$OUT" | cut -d' ' -f1)
  # the Android launch screen is about 70 KB; real screens differ and must stay the same for two looks in a row
  if [ "$h" = "$prev" ] && [ "$sz" -gt 110000 ]; then break; fi
  prev=$h
done
echo "saved docs/ui/$2.png ($sz bytes, look $i)"
