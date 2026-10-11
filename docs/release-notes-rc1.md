Release candidate 1 (update 6, version 0.1.0-rc1.6, build 7) of Vowed for the Solana Mobile "CLOCK IN" hackathon. **Solana devnet only, test tokens with no value. The program is unaudited.**

**APK:** `vowed-v0.1.0-rc1.apk` (95.99 MB, signed with the same demo key as the earlier uploads, so it installs over them as an update)
**SHA-256:** `3afa21915f83f1c7b5a3da92df41950391ace2f5ca2966c9b4c3eb4a127c8167`
**Version:** 0.1.0-rc1.6 (build 7). You > bottom of the screen shows the version and the build time, and every diagnostics text starts with them.

Check it after downloading: `sha256sum vowed-v0.1.0-rc1.apk` (Linux, Git Bash) or `Get-FileHash vowed-v0.1.0-rc1.apk -Algorithm SHA256` (PowerShell).

**What changed in this update (real-phone bug: staking opened Phantom's Connect sheet, then nothing, with 0.00158 SOL in the wallet):**
- **SOL is checked three times before the wallet opens.** A create-and-stake needs about 0.0054 SOL on devnet and a join about 0.0019 (fee 0.000005, account rent, and the 0.00065 a wallet must keep). The server refuses a short wallet before it builds the transaction, the review screen shows what the step costs in SOL (rent is not refunded in this test version) and disables Sign when the wallet is short, and a failed pre-flight check no longer lets the step through. The message says the numbers and has a **Get test SOL** button.
- **More test SOL:** a gift is now 0.03 SOL (was 0.01), a wallet that is nearly empty again can be topped up once more, never more than 0.06 SOL per wallet.
- **A wallet that dies or closes mid-request no longer leaves the app waiting for ever** (this was a silent end in the code): you get "The wallet closed the connection before it finished" with Try again, and any wait is bounded at 90 s with "Your wallet did not ask you to approve. Open your wallet app, or tap Try again."
- **The waiting screen has Cancel**, Try again after 8 s, and Details with Copy diagnostics after 15 s. **You > Wallet diagnostics** keeps the last wallet attempt so it can be read after returning from the wallet.
- A review that has been open for more than 40 s is rebuilt before the wallet opens (a stale transaction is dropped by wallets).
- **The official USDC logo** now shows for tUSDC everywhere the blue dollar icon was, always with a small "t" test badge and the TEST wording.
- The wallet visit for stake, join and claim is Vowed's own code: one launch, the saved authorization first (no Connect screen when the wallet honours it), no timer that closes the wallet session, a timestamped trace.

Kept: one-approval sign-in, zero wallet screens for a returning user, create-and-stake in one transaction, the full-screen camera and rep counter, test USDC or test SKR as the stake token.

The app talks to the hosted devnet backend `https://vowed-backend.onrender.com`. It runs on a free plan, so the first request after a pause can take up to a minute.

**Not verified yet (needs a real phone):** whether Phantom still shows Connect for a stake, what Phantom shows after it, the approval sheet itself, camera counting accuracy and hardware key attestation. Tested on the emulator with the Mock wallet only. See `docs/verified-facts.md` and `docs/device-tests.md`.

Built from commit 5b2288b; see `docs/progress.md`.
