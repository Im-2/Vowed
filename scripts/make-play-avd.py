"""Creates the Google Play AVD vowed_play from the existing vowed_api36 one (avdmanager fails on this machine because of a missing devices.xml).
The Play system image must be installed first: system-images/android-36-ext19/google_apis_playstore/x86_64 (see docs/emulator-real-wallet-testing.md)."""
import os

root = os.path.join(os.path.expanduser("~"), ".android", "avd")
src = open(os.path.join(root, "vowed_api36.avd", "config.ini"), encoding="utf-8").read()
s = src.replace("AvdId=vowed_api36", "AvdId=vowed_play").replace("avd.ini.displayname=vowed_api36", "avd.ini.displayname=vowed_play")
s = s.replace("PlayStore.enabled=false", "PlayStore.enabled=true")
s = s.replace("image.sysdir.1=system-images\\android-36\\google_apis\\x86_64\\", "image.sysdir.1=system-images\\android-36-ext19\\google_apis_playstore\\x86_64\\")
s = s.replace("tag.display=Google APIs", "tag.display=Google Play").replace("tag.id=google_apis", "tag.id=google_apis_playstore")
os.makedirs(os.path.join(root, "vowed_play.avd"), exist_ok=True)
open(os.path.join(root, "vowed_play.avd", "config.ini"), "w", encoding="utf-8", newline="\n").write(s)
open(os.path.join(root, "vowed_play.ini"), "w", encoding="utf-8", newline="\n").write(
    "avd.ini.encoding=UTF-8\npath=" + os.path.join(root, "vowed_play.avd") + "\npath.rel=avd\\vowed_play.avd\ntarget=android-36\n"
)
print(open(os.path.join(root, "vowed_play.avd", "config.ini"), encoding="utf-8").read().count("playstore"), "playstore lines written")
