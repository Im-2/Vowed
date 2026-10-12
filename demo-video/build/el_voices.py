"""Lists the ElevenLabs account's voices and the subscription's character usage. Reads ELEVENLABS_API_KEY from demo-video/.env; never prints it."""
import json
import os
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def api_key():
    with open(os.path.join(HERE, "..", ".env"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("ELEVENLABS_API_KEY="):
                return line.split("=", 1)[1].strip()
    raise SystemExit("ELEVENLABS_API_KEY missing in demo-video/.env")


def get(path):
    req = urllib.request.Request("https://api.elevenlabs.io" + path, headers={"xi-api-key": api_key()})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


if __name__ == "__main__":
    voices = get("/v1/voices")["voices"]
    for v in voices:
        if "bill" in v["name"].lower():
            print("MATCH", v["voice_id"], "|", v["name"], "|", v.get("category"))
    print(len(voices), "voices in the account")
    try:
        s = get("/v1/user/subscription")
        print("tier:", s.get("tier"), "| used:", s.get("character_count"), "of", s.get("character_limit"), "| resets:", s.get("next_character_count_reset_unix"))
    except Exception as e:  # the key may not have the user scope
        print("subscription not readable:", e)
