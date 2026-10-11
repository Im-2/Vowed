# Testing with a real wallet on the Windows emulator

Goal: run Vowed's sign-in, create-and-stake and join against a REAL wallet app (Phantom, Solflare), not only the Mock wallet. Devnet only, throwaway wallets only, no real funds, no faucet called automatically.

## What you need
1. A **Google Play** emulator image (the default `google_apis` image has no Play Store). `sdkmanager --install "system-images;android-36-ext19;google_apis_playstore;x86_64"`, then an AVD whose `config.ini` has `PlayStore.enabled=true`, `tag.id=google_apis_playstore` and `image.sysdir.1=system-images\android-36-ext19\google_apis_playstore\x86_64\` (copy `~/.android/avd/vowed_api36.avd/config.ini` and change those three lines; `avdmanager create avd` fails on this machine because of a missing `devices.xml`).
2. The wallet app from its OFFICIAL route. **Phantom publishes no APK on phantom.com**: the only official Android route is its Google Play listing (publisher Phantom Technologies Inc). Do not use APK mirror sites. Installing from Play needs a Google sign-in on the emulator; entering that password is the owner's step.
3. A throwaway wallet created inside the wallet app. Turn on Testnet Mode (Phantom: Settings, Developer Settings, Testnet Mode, Solana Devnet). **Keep the recovery phrase only in a git-ignored local file under `~/.vowed-testing/`; never in the repo, a log, a chat or a report.** Fund it only through Vowed's own Get test SOL button.

## Driving the emulator
`adb exec-out screencap -p` (use `cmd /c "adb exec-out screencap -p > file.png"` in PowerShell, a PowerShell `>` corrupts PNGs), `adb shell uiautomator dump`, `adb shell input tap x y`, `scripts/emu-lib.ps1` (Tap-Text, Has-Text, Screen-Texts, Pin-If-Asked). Screenshots of wallet screens may show public addresses only.

## Results
See `docs/verified-facts.md`, "Round 8: real Phantom on the emulator".
