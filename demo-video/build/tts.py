"""Generates the narration with ElevenLabs, one request per act, and caches every clip.

- voice: found by NAME in the account's voice list (never a remembered id)
- the same model, voice settings and seed for every request
- stitching: previous_request_ids (the earlier acts' request ids, up to 3) and next_text (the following act's text)
- the /with-timestamps endpoint returns character timings, used to line up each sentence with the footage
- cache: build/cache/tts/<hash>.json (audio + alignment + request id); a cached act is never requested again
- on HTTP 429 or a quota error: wait and retry once, then stop and say which acts are missing
The API key is read from demo-video/.env and never printed.
"""
import base64
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "cache", "tts")
SEED = 4242


def api_key():
    with open(os.path.join(HERE, "..", ".env"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("ELEVENLABS_API_KEY="):
                return line.split("=", 1)[1].strip()
    raise SystemExit("ELEVENLABS_API_KEY missing in demo-video/.env")


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request("https://api.elevenlabs.io" + path, data=data, method=method,
                                 headers={"xi-api-key": api_key(), "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.load(r), r.headers.get("request-id")


def act_text(act):
    return " ".join(s["text"] for s in act["sentences"])


def main():
    n = json.load(open(os.path.join(HERE, "narration.json"), encoding="utf-8-sig"))
    os.makedirs(CACHE, exist_ok=True)
    voices, _ = call("GET", "/v1/voices")
    match = [v for v in voices["voices"] if v["name"] == n["voice_name"]]
    if not match:
        raise SystemExit(f"voice '{n['voice_name']}' is not in this account; not substituting silently")
    voice_id = match[0]["voice_id"]
    print("voice:", n["voice_name"], voice_id, "| model:", n["model_id"])

    acts = n["acts"]
    prev_ids, used, missing = [], 0, []
    for i, act in enumerate(acts):
        text = act_text(act)
        nxt = act_text(acts[i + 1]) if i + 1 < len(acts) else None
        key = hashlib.sha256(json.dumps([voice_id, n["model_id"], n["voice_settings"], SEED, text, nxt], sort_keys=True).encode()).hexdigest()[:16]
        path = os.path.join(CACHE, f"{act['id']}-{key}.json")
        if os.path.exists(path):
            rec = json.load(open(path, encoding="utf-8"))
            print(f"{act['id']}: cached ({len(text)} chars)")
        else:
            body = {"text": text, "model_id": n["model_id"], "voice_settings": n["voice_settings"], "seed": SEED}
            if prev_ids:
                body["previous_request_ids"] = prev_ids[-3:]
            if nxt:
                body["next_text"] = nxt
            rec = None
            for attempt in range(2):
                try:
                    resp, rid = call("POST", f"/v1/text-to-speech/{voice_id}/with-timestamps?output_format=mp3_44100_128", body)
                    rec = {"act": act["id"], "text": text, "request_id": rid, "audio_base64": resp["audio_base64"], "alignment": resp["alignment"]}
                    break
                except urllib.error.HTTPError as e:
                    detail = e.read().decode(errors="replace")[:300]
                    print(f"{act['id']}: HTTP {e.code}: {detail}")
                    if attempt == 0 and e.code in (429, 500, 502, 503):
                        time.sleep(20)
                        continue
                    break
            if rec is None:
                missing.append(act["id"])
                print("stopping: not generated:", ", ".join(missing + [a["id"] for a in acts[i + 1:]]))
                sys.exit(2)
            json.dump(rec, open(path, "w", encoding="utf-8"))
            used += len(text)
            print(f"{act['id']}: generated ({len(text)} chars), request id {'yes' if rec['request_id'] else 'NOT RETURNED'}")
        with open(os.path.join(CACHE, f"{act['id']}.mp3"), "wb") as f:
            f.write(base64.b64decode(rec["audio_base64"]))
        json.dump({"text": rec["text"], "alignment": rec["alignment"]}, open(os.path.join(CACHE, f"{act['id']}.align.json"), "w", encoding="utf-8"))
        if rec.get("request_id"):
            prev_ids.append(rec["request_id"])
    print("characters used in this run:", used)


if __name__ == "__main__":
    main()
