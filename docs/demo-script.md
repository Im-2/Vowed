# Demo script (SPEC 13.3), about 90 seconds, up to 3 minutes for the video

The submission needs a demo video of at most 3 minutes **recorded on a real Android phone** (an emulator-only recording is not acceptable). This script is the 90-second core from SPEC 13.3, matched to what the app really does today, with what to say, what to tap, what can go wrong and the fallback. It has **not** been run on a real phone yet: the first real-phone run is the rehearsal below.

## Before you record (10 minutes, do it once)

| Check | How | Done |
|---|---|---|
| Release APK installed | `scripts/android-release.ps1` prints the path, size and SHA-256; install `app-release.apk` on the phone (uninstall any debug build first, the signatures differ) | |
| Backend awake | open `https://vowed-backend.onrender.com/v1/health` in the phone browser; the free plan sleeps and the first call takes up to a minute | |
| Wallet ready | a **throwaway** wallet (Phantom or Solflare switched to devnet, or the Seed Vault Wallet on a Seeker); see You > Using a real wallet. Never show a seed phrase on screen | |
| Signed in and funded | Sign in; Home > test-token pill > Get test tokens (tUSDC, tSKR and 0.01 SOL) | |
| A friend in a squad | a second phone or `npm run demo:friend -- <pool> --stake 2` in `backend/` so the squad feed has activity | |
| Notifications and camera allowed | Android settings; the app asks only when you tap the matching button | |
| Do not disturb on, battery above 50 percent, screen recording on | | |

Use **demo pools** (labelled DEMO) so a "week" takes minutes. Say so out loud: the video must not pretend a week passed.

## The script

| # | Time | Say | Show and tap | Notes and fallback |
|---|---|---|---|---|
| 1 | 0:00 to 0:10 | "Everyone fails their goals. What if failing cost something, and winning paid?" | Home with the banner, the TEST pill and the DEVNET label | Keep the TEST pill visible: it says these are test tokens |
| 2 | 0:10 to 0:25 | "I just type the goal." | New challenge, type **No TikTok after 10pm for a week**, Preview plan. Point at the plan, the proof method and the **trust level** | Templates answer instantly. A free-form goal uses Gemini through the server and can take 12 to 15 seconds: say "the AI plan is checked by strict rules" while it loads. If it falls back to templates, say so |
| 3 | 0:25 to 0:45 | "I stake test USDC, and my wallet signs. The app decodes the transaction first." | Pick Soft mode and a small stake, tap Review: show the decoded transaction (program, vault, amount), then Sign with wallet and approve in the wallet | Today the stake token is test USDC; SKR is shown in step 6. Approve within a minute or the transaction expires (the app says so) |
| 4 | 0:45 to 1:05 | "Each day I prove it with the phone, not with a promise." | Open the check-in. On a real phone: the camera counts 3 squats in a demo pool (hand-raise check), or the focus timer. Show "Recorded" and the streak going up | **Camera counting has not been tried on a real person yet.** If it misbehaves, use the focus timer or steps proof, which are the same flow |
| 5 | 1:05 to 1:20 | "Friends keep you honest." | Squads: the feed with a friend's check-in, a miss, tap Nudge | Needs the friend from the checklist. Push when the app is closed is not built; the feed is polled while the app is open |
| 6 | 1:20 to 1:50 | "Winners get paid, and the best streaks earn SKR." | Wait for the demo pool to end (minutes), settle and claim: payout shows. Then You > Rewards: the podium, the leaderboard (SAMPLE rows are labelled), the Streak freeze. Open a Letter to future me that arrived | Settlement needs the crank and takes a minute or two after the pool ends; start the pool 15 minutes before recording. If it is not ready, show the settled sample from an earlier run and say it was recorded earlier |
| 7 | 1:50 to 2:10 | "Any goal, phone-native proofs, a program that holds the money, and a clear trust model." | Home with Past challenges; You > Using a real wallet | Say what is next: plug-ins from other apps (the format exists, only a sample provider is built) |

Close by saying the honest limits: devnet only, test tokens, unaudited program, the oracle is the main trust assumption (see the threat model).

## What not to claim

- No real money, no mainnet, no real yield (it is labelled SIMULATED).
- Do not call the camera counting or any phone proof "verified on real phones" until `docs/device-tests.md` has been run.
- Do not say Phantom, Solflare or the Seed Vault Wallet work until their rows in the real-wallet checklist are filled in.
- Push notifications when the app is closed are not built.
- Do not show a seed phrase, a private key or the Render dashboard.

## Rehearsal (do it once on the real phone, then record)

Run steps 1 to 7 with a timer. Note every place where the app waited more than 3 seconds or showed an error message, fix the script or the app, and run it again. Keep the best take under 3 minutes.
