Release candidate 1 (update 4, version 0.1.0-rc1.4, build 5) of Vowed for the Solana Mobile "CLOCK IN" hackathon. **Solana devnet only, test tokens with no value. The program is unaudited.**

**APK:** `vowed-v0.1.0-rc1.apk` (95.9 MB, signed with the same demo key as the earlier uploads, so it installs over them as an update)
**SHA-256:** `9e00d576d49d134b772f7df5dc662e9cad16ca7235febb1c61318b40df71a23d`

Check it after downloading: `sha256sum vowed-v0.1.0-rc1.apk` (Linux, Git Bash) or `Get-FileHash vowed-v0.1.0-rc1.apk -Algorithm SHA256` (PowerShell).

**What changed in this update (real-phone regression: sign-in looped back to "Testnet Mode"):**
- **One wallet approval to sign in.** Vowed sends the official Sign-In-With-Solana payload with the connection request, so a wallet that supports it shows one sheet. The phone's proof key is then registered by Vowed without another wallet screen. A returning user opens the app with zero wallet screens.
- **The "Your wallet needs one switch / Testnet Mode" screen now appears only when the wallet itself says the network is not supported.** Every other failure names its step ("Wallet approved, but signing in with the Vowed server failed", "Registering this phone failed", "Vowed could not reach its server"), and **Try again** repeats only that step; it does not open the wallet again.
- **Create a challenge and stake in one transaction** (one wallet approval instead of two). Joining is one approval.
- The server is woken when the app opens, and the app waits up to a minute and says so ("Waking up the Vowed server"). Details > Copy diagnostics now has a per-step timeline (server, wallet, verify, register) with times and reasons.
- A problem found before the wallet opens (not enough devnet SOL, no test tokens) is a framed card that scrolls into view with a **Get test tokens** button.

Kept from earlier updates: the full-screen camera with the body map and the live "n / target" rep counter, choosing test USDC or test SKR as the stake token, friendly errors.

The app talks to the hosted devnet backend `https://vowed-backend.onrender.com`. It runs on a free plan, so the first request after a pause can take up to a minute.

**Not verified yet (needs a real phone):** what Phantom does with the sign-in payload (one sheet or Connect and Sign message), that the loop is gone with Phantom, the approval sheet for a transaction, camera counting accuracy and hardware key attestation. Tested on the emulator with the Mock wallet only. See `README.md`, `docs/verified-facts.md` and `docs/device-tests.md`.

Built from commit 4eafcf4; see `docs/progress.md`.
