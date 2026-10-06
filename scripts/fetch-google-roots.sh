curl -sS -m 30 https://android.googleapis.com/attestation/root -o /tmp/google-roots.raw.json -w "http %{http_code} bytes %{size_download}\n"
head -c 150 /tmp/google-roots.raw.json; echo
cp /tmp/google-roots.raw.json /mnt/c/Users/hp/Vowed/backend/src/devices/google-roots.raw.json 2>/dev/null && echo copied
