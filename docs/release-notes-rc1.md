Release candidate 1 (update 5, version 0.1.0-rc1.5, build 6) of Vowed for the Solana Mobile "CLOCK IN" hackathon. **Solana devnet only, test tokens with no value. The program is unaudited.**

**APK:** `vowed-v0.1.0-rc1.apk` (95.97 MB, signed with the same demo key as the earlier uploads, so it installs over them as an update)
**SHA-256:** `0a8d167afdb956382423668e03b2c02150fc572201a6ebcfd80a212cf447653b`

Check it after downloading: `sha256sum vowed-v0.1.0-rc1.apk` (Linux, Git Bash) or `Get-FileHash vowed-v0.1.0-rc1.apk -Algorithm SHA256` (PowerShell).

**What changed in this update (real-phone bug: staking opened Phantom's Connect sheet again, which vanished, and the approval never appeared):**
- **The stake, join and claim wallet visit was rewritten.** Vowed opens the wallet once, waits up to 45 s for a slow wallet to connect (the Kotlin library only waits 10 s), sends the saved authorization so a returning user should see no Connect sheet, then asks for the one transaction approval. If the wallet refuses the saved authorization it is asked again in the same visit, never relaunched.
- **No hidden timer closes the wallet session.** The library closes the session 5 s after the wallet screen returns a result, even while the wallet still shows its sheet; Vowed now closes it only when the visit ends, and says why.
- **No silent waiting.** If the wallet has not answered after 8 s, the screen says so and offers **Try again** (a tap only, one wallet request at a time).
- **Coming back to Vowed:** Vowed's own screen is reordered to the top of its task, so recent apps should show Vowed and not the wallet.
- **Details > Copy diagnostics** now has a timestamped timeline of the wallet visit (association, launch, connection, authorize, sign request, answer, close reason, which screen is on top).

Kept: one-approval sign-in, zero wallet screens for a returning user, create-and-stake in one transaction, the full-screen camera and rep counter, test USDC or test SKR as the stake token.

The app talks to the hosted devnet backend `https://vowed-backend.onrender.com`. It runs on a free plan, so the first request after a pause can take up to a minute.

**Not verified yet (needs a real phone):** whether Phantom still shows Connect for the stake, who ended the Connect sheet in the recording (the new trace will show it), the approval sheet itself, camera counting accuracy and hardware key attestation. Tested on the emulator with the Mock wallet only. See `docs/verified-facts.md` and `docs/device-tests.md`.

Built from commit 62a9b36; see `docs/progress.md`.
