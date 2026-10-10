# Manual real-device tests

Run on a physical Android phone (and on a Seeker if available). Record the device model, Android version and date next to each result.
This file grows with each phase; the sections below are what the backend needs verified now.

## Device key attestation (Phase 2 backend, Phase 4 app)

| # | Test | Expected | Result |
|---|---|---|---|
| A1 | Generate a P-256 key in Android Keystore with `setAttestationChallenge(<challenge from /v1/devices/challenge>)`; send the certificate chain to `/v1/devices/register` | 200, `attestation.level` is `tee` or `strongbox`, `trustCap` is `high` on a locked, stock device | |
| A2 | Same on a Seeker | Same. If the chain is rejected or lacks hardware attestation, record the exact `attestation.note` and adjust expectations in the SPEC | |
| A3 | Re-register after changing the challenge by one byte | 400 `attestation_failed` or `bad_challenge` | |
| A4 | Phone with an unlocked bootloader (if available) | Accepted but `trustCap` is `low` (verified boot state is not "verified") | |
| A5 | Emulator | No hardware attestation: with `REQUIRE_ATTESTATION=false` registers with `trustCap` `low`; with `true` is refused | |
| A6 | The revocation list is reachable from the server's network | `attestation.note` says "verified", not "revocation list not reachable" | |

## Signing path

| # | Test | Expected | Result |
|---|---|---|---|
| S1 | Sign a ProofPackage with the Keystore key (`SHA256withECDSA`) over the canonical JSON described in `backend/src/proofs/verify.ts` | `/v1/proofs/submit` verifies the DER signature | |
| S2 | Wallet signs the device registration message through MWA `signMessages` | Backend accepts the 64-byte ed25519 signature | |

## Later phases add: pose counting, usage stats, step counting, geofence, notifications.


## Phase 3: wallet and app flow (real phone / Seeker)

| # | Test | Expected | Result |
|---|---|---|---|
| W1 | Install the debug APK and a real MWA wallet (Phantom, Solflare or Seed Vault); connect from onboarding | Wallet shows "Vowed wants to connect"; the app signs in and registers the phone | |
| W2 | Create a demo pool, join, wait, claim (devnet) | Same result as the emulator run; stake leaves and returns to the wallet | |
| W3 | Approve a transaction after waiting more than a minute | App shows the wallet-did-not-complete message; retrying works | |
| W4 | Backend URL: the debug build points at `http://10.0.2.2:8787` (emulator only). On a phone set the backend URL to a reachable HTTPS host or the PC's LAN address through the debug override | Connect works | |


## Phase 4: daily proofs (real phone / Seeker)

Install the **release** build for anything that must prove the proof is real (it has no test-data buttons). The debug build adds an "Inject test data" panel; on a phone use it only to skip waiting.
Use a demo pool with 2-minute days (New challenge: Demo pool on, "2 min days") so each test takes minutes. Demo goals ask for small amounts (20 s focus, 20 steps, 10 s at a place).

| # | Test | Expected | Result |
|---|---|---|---|
| P1 | Focus timer: start it, keep the app open for the target, submit | "Recorded", a Solana transaction id, streak +1 | |
| P2 | Focus timer: start it, press Home for 30 s, come back | The counter did not grow while away; submit stays disabled until enough foreground time | |
| P3 | Steps: allow Physical activity, walk about 30 steps | The counter rises (it counts since the check-in screen first opened that day, and says so); Submit enables at the target | |
| P4 | Steps: deny the permission | A clear explanation and an "Allow" button; nothing is sent | |
| P5 | Place goal: create it at your current spot (New challenge, gym goal, "Use my current location") then check in standing there | "You are at the place", time there grows, Submit enables after the target | |
| P6 | Place goal: walk 300 m away | "About N m away", the timer stops growing | |
| P7 | Place goal on a second phone with the same wallet | "The spot ... is not on this one": coordinates never left the first phone | |
| P8 | Usage goal (Instagram under 30 min): grant Usage access, open Instagram for a minute, check in | The reported usage matches reality within a few seconds; under the limit is accepted, over is refused with a reason | |
| P9 | No-use window (TikTok after 10pm): use the app during the window, check in | Refused: "used the app during the blocked window" | |
| P10 | Self-attest | Recorded with trust "low" and the lowest stake cap | |
| P11 | Replay: submit, then press Replay (debug build) | Same result, the day count does not change | |
| P12 | Release build | There is no "Inject" panel anywhere; `DebugProofs` in the APK only throws (verified on the compiled classes) | |
| P13 | Proof after the day window closed | "the check-in window for that day is not open" | |
| P14 | Phone clock set one hour ahead | Proof refused (timestamp in the future) | |


## Test tokens and wallets on devnet (real phone / Seeker)

| # | Test | Expected | Result |
|---|---|---|---|
| T1 | Phantom: enable Testnet Mode and Solana Devnet, connect Vowed | Connects; sign-in works; no `ERROR_CHAIN_NOT_SUPPORTED` | |
| T2 | Solflare: Settings -> Network -> Devnet, connect Vowed | Same | |
| T3 | Seed Vault Wallet on a Seeker: connect Vowed on devnet | Record exactly what happens (works / chain not supported / needs a setting) | |
| T4 | Today -> Get test tokens on a new wallet | 20 tUSDC and 20 tSKR arrive; the card says TEST TOKENS | |
| T5 | Press it again | Disabled with "Available again in ..." | |
| T6 | A wallet with no devnet SOL tries to create a pool | A clear failure from the wallet; the card tells the user to get devnet SOL | |
| T7 | Circle devnet USDC (get 20 from https://faucet.circle.com/ by hand): create a normal pool, join, check in the next day, claim after it ends | Everything works like the test tokens | |


## Phase 5: camera rep counting and plain-language goals (real phone / Seeker)

Pose counting must be tested by a real person on a real phone; the emulator camera shows a virtual room with nobody in it. Use a demo pool (3 reps) for the first runs, then a normal one.

| # | Test | Expected | Result |
|---|---|---|---|
| C1 | Squats: new challenge "Do 20 squats every day", stake in a demo pool, open the check-in, allow the camera, press Start | 3-second countdown, then the counter rises by one for each full squat | |
| C2 | Phone leaned against a wall, 2 to 3 m away, whole body in view, side-on | Reps counted within about 1 of your own count over 10 squats | |
| C3 | Same, facing the camera | Reps counted within about 1 of your own count | |
| C4 | Half squats only (knees barely bend) | Not counted; the hint says "Go lower" | |
| C5 | Step out of frame mid-set | The hint asks you to step back; no phantom reps appear | |
| C6 | The hand-raise prompt appears 2 to 6 s in: raise the named hand | "Recorded" after the goal is reached | |
| C7 | Ignore the prompt twice | Session ends without a result, "start again" message | |
| C8 | Raise the OTHER hand | Not accepted | |
| C9 | Play a video of someone squatting at the phone | Reps may count, but the hand prompt cannot be answered: no proof is submitted | |
| C10 | Push-ups (goal "Do 15 push-ups every day"), phone on the floor at the side | Reps counted within about 1 of your own count | |
| C11 | Dim light | Hints appear instead of wrong counts | |
| C12 | Check Android settings, camera access | Only while the check-in is open; nothing stored (no new files in the app's storage) | |
| G1 | Type a free-form goal that the templates do not know, with AI on, on a network that can reach Google | A plan labelled "Understood by AI (Gemini); check it carefully" | |
| G2 | Same with AI off | "I need a little more" with examples; nothing sent to Google | |
| G3 | Type "lose 5 kg" | "A phone cannot check this goal", with a checkable alternative and a low-trust option | |
## Phase 7 and wallet-session checklist (to run on a real phone, or an emulator with enough memory)
- W1. Connect a wallet, then start a challenge and sign create then join in a row; neither wallet request should show "cancelled before connected". If one does, the app retries once by itself; a second failure must show the "closed before it connected" message and sign nothing.
- W2. Open Squads on a phone that has not granted notification permission: no dialog appears until you tap "Turn on notifications".
- L1. Letters: write a "Day 3" letter and a "Streak breaks" letter; both show as Sealed with no text. Finish 3 days in a (demo) pool: the first is delivered with a notification that does not contain the text; open it. Miss a day after a done day: the second is delivered.
- L2. Debug build only: use "Simulate" on the Letters screen; the reason line starts with SIMULATED.
- L3. "Erase it after I have read it": the letter is gone after Close.
- C1. Coach: after a real (non-demo) challenge with missed days, Coach shows "go a little easier"; with all days done twice, "ready for a harder one".
- X1. Widget: You > Add the widget; accept the system dialog; open Today and refresh; the widget shows the count of check-ins due, then "All done today" after a check-in, and the best streak.

## Real-wallet test checklist (sign-in, session, devnet)

Use a **throwaway wallet** made only for this test, on a phone with Phantom or Solflare (or the Seed Vault Wallet on a Seeker). Never type or share a seed phrase; Vowed never asks for one. Result column: fill in per wallet and wallet version.

| # | Test | Expected | Result |
|---|---|---|---|
| R1 | Wallet still on mainnet: Sign in | Either the wallet refuses (error -7, chain not supported) and Vowed says "Your wallet is not on Solana devnet..." with a pointer to "Using a real wallet", or sign-in works but the first transaction fails; the message must mention devnet, not a raw error | |
| R2 | Switch the wallet to devnet (Phantom: Settings, Developer Settings, Testnet Mode, Solana Devnet; Solflare: Settings, Network, Devnet), Sign in again | "Wallet connected", Home opens; the TEST pill and DEVNET labels are visible | |
| R3 | You > "Using a real wallet" | The three rules and the switch steps are readable; nothing asks for a key | |
| R4 | Home > test-token pill > "Get test tokens" on a wallet with 0 SOL | tUSDC, tSKR and "Plus 0.01 SOL (devnet) for network fees" (if the SOL faucet is on on the server); balance shows the SOL | |
| R5 | Same wallet, claim again after the cooldown | Tokens again, no second SOL gift | |
| R6 | Create a demo challenge, review, sign | The wallet shows a devnet transaction; the app shows the stake after approval | |
| R7 | Approve more than a minute late | "The transaction expired before it was approved" message, nothing lost | |
| R8 | Lock the wallet, then tap Sign in | A message that says to open the wallet and unlock it, then try again | |
| R9 | Decline the sign-in in the wallet | "The request was declined in the wallet, so nothing was signed" | |
| S1 | Sign in, then close the app from recents and open it again | Home opens at once with no wallet prompt (the encrypted session was restored) | |
| S2 | Leave the app closed for more than 6 hours (or 7 days), open it | Asks to sign in with the wallet again, with one clear sentence | |
| S3 | Keep the app open for a few hours | No sign-in prompt; the session is renewed quietly (checked every 10 minutes) | |
| S4 | Server update that wipes the database (Render redeploy on the free plan), then try to join or check in | "The server forgot this phone (it was updated), so Vowed is registering it again. Approve the request in your wallet." then "This phone is registered again. Please repeat what you were doing." | |
| S5 | You > Reset connection, then Sign in | Back to the sign-in screen with one sentence; sign-in works; challenges and stakes are still there | |
| S6 | Mock wallet: after a cold start, a first Connect that times out | Message about the wallet taking long and what to do; the second try works once the wallet is warm | |

## Release APK and submission checks (Phase 9)

Run on a **clean real phone** (not the development emulator), with the signed release APK from `scripts/android-release.ps1`. Record the APK file name, size and SHA-256 next to the results.

| # | Test | Expected | Result |
|---|---|---|---|
| A1 | Download the APK from the stable link you will submit (GitHub Release or cloud storage) on the phone, install | Installs without a "blocked" warning beyond Android's normal unknown-source prompt; app name Vowed and the V icon | |
| A2 | `apksigner verify --print-certs app-release.apk` on the PC | Verifies; certificate CN "Vowed hackathon demo" | |
| A3 | Launch, onboarding, Try camera practice | Practice works with no wallet; nothing is sent | |
| A4 | Connect a wallet (see R1 to R9 above), Get test tokens | Test tokens and 0.01 SOL arrive; labels say TEST | |
| A5 | Run the demo script (`docs/demo-script.md`) end to end | Every step works; note anything that waits more than 3 seconds | |
| A6 | Airplane mode, open the app | Clear "could not reach the server" messages, no crash, no endless spinner | |
| A7 | Rotate the phone, switch to dark mode, large font | Nothing is cut off or unreadable (dark theme has not been checked yet) | |
| A8 | Check Android settings, Apps, Vowed, Permissions | Camera, location, activity recognition, usage access, notifications are each asked for only when a feature needs them | |
| A9 | Seeker only: connect with the Seed Vault Wallet | Works on devnet or shows the "wrong network" message (see verified-facts) | |

## Real-phone bug fixes, round 1 (checklist)

| # | Test | Expected | Result |
|---|---|---|---|
| N1 | Samsung phone, Phantom installed, first run: tap Connect wallet | "One quick step before connecting" appears first, with Open Phantom | |
| N2 | Phantom still on the real network: tap "I've switched it, connect now" | Phantom turns it down; Vowed shows the same screen again with "Your wallet is on the real network. Switch it to Testnet Mode and try again." No raw error. **Record the exact error Phantom sends (code and text) in docs/verified-facts.md** | |
| N3 | In Phantom: Settings, Developer Settings, Testnet Mode on (Solana Devnet), come back, tap connect now | Phantom shows its connect sheet; approve; Home opens | |
| N4 | Open Phantom button with Phantom installed / uninstalled | Launches Phantom / opens the Google Play page with a one-line explanation | |
| N5 | After one successful connection, Disconnect, tap Connect wallet | No setup screen (experienced users skip it). You > Wallet setup help reopens it | |
| N6 | Turn Data Saver ON (Samsung: Settings, Connections, Data usage, Data saver) and remove Vowed from "Allow while Data saver on", then connect through Phantom | Sign-in works (the nonce is fetched before the wallet opens). If it fails, You > Check connection tells whether the phone can reach the server, with the reason under Details | |
| N7 | Airplane mode, tap Connect wallet | "Vowed couldn't reach its server. Check your internet connection and try again. The server may take up to a minute to wake up." and a Details link with the raw text; Check connection shows FAIL | |
| N8 | Update over the old rc1 APK (same signing key) | Installs as an update, keeps data | |

## Phantom and Solflare checklist (round 3, run on a real phone)

Setup for each wallet: a NEW throwaway wallet, then the wallet's own switch to the practice network. Phantom: Settings, Developer Settings, Testnet Mode on, Solana Devnet. Solflare: Settings, Network, Devnet. After a test, You > Copy diagnostics (under Details of an error) and paste the timeline into the Result column or an issue: it has no keys, signatures or tokens.

| # | Wallet | Test | Expected | Result |
|---|---|---|---|---|
| P1 | Phantom (Testnet Mode, Solana Devnet) | Connect wallet, then "I've switched it, connect now" | Phantom shows "Connect" for Vowed (it may say the identity could not be verified: expected, see verified-facts). Approve | |
| P2 | Phantom | After approving | Either Phantom returns to Vowed by itself, or Vowed shows "Approved in your wallet. Switch back to Vowed to continue." Switch back by hand | |
| P3 | Phantom | Second wallet screen | A "Sign message" sheet appears (a second session) and, once approved, Vowed shows "Wallet connected" and opens Home. The connection may need one automatic retry the first time (see the timeline: "retry") | |
| P4 | Phantom | Connect a second time after disconnecting | Goes straight to the two-session flow (remembered), no failed first try | |
| P5 | Phantom | If it still fails | The message "The wallet closed the connection before it finished..." with Details and Copy diagnostics; paste the timeline. Check whether the timeline shows onPause/onResume or "restored from saved state" around the session | |
| S1 | Solflare (Devnet) | Connect wallet | Solflare shows an approval prompt for Vowed. If it only shows the portfolio, return to Vowed and copy the diagnostics: note the wallet package and version in the first lines | |
| S2 | Solflare | Approve | Same behaviour as P2 and P3 | |
| I1 | both | Identity | Note exactly what the wallet shows for the app name, icon and any warning. If the icon is the Vowed V on a lavender square, the identity file and icon were read | |
| T1 | both | Wait 60 s on the wallet's sheet before approving | The app keeps waiting (the wallet request timeout is 120 s) and still connects | |

## Camera checklist (round 4, full-screen camera and counter)

Do each on a real phone, with a person who is not you holding the phone if possible. Use the front and the rear camera. Exercises: squats and push-ups. Note phone model and Android version.

| # | Test | Expected | Result |
|---|---|---|---|
| K1 | Open the camera from a check-in or from the practice screen | Preview fills the whole screen edge to edge (status and navigation bars hidden, nothing stretched, picture cut equally left and right); Android may show "Viewing full screen" once | |
| K2 | Phone with a notch or punch-hole | Picture runs behind it, the close button and counter stay visible | |
| K3 | Turn the phone while the camera is open | The screen stays as it is (orientation locked), the set is not restarted | |
| K4 | Stand 2 to 3 m away, whole body in view (front camera) | Dashed frame turns green, skeleton lines and dots sit on your joints, "You are in frame. Press Start." and the Start button enables after about a second | |
| K5 | Move your hand: does the dot on your wrist follow it on screen, without a sideways flip? | Overlay matches the live picture (checks mirroring) for the FRONT camera | |
| K6 | Same with the REAR camera | Overlay matches, no mirroring | |
| K7 | Step so your feet are cut off | Hint "Step back so your whole body is in view" (or "Raise your phone"); Start stays disabled; counting would pause | |
| K8 | Stand at the left or right edge | "Move to the right" / "Move to the left" | |
| K9 | Dim light | "Better lighting needed" after a few seconds | |
| K10 | Nobody in view for 5 s | Faint figure guide, then the lighting hint | |
| K11 | Slow phone (low-end device) | Preview stays smooth; the overlay may update less often; no crash | |
| C1 | Press Start | 3-2-1 countdown, then counting; the number shows "0 / <target>" with an empty ring | |
| C2 | Do 3 squats / push-ups | Each rep adds one at the moment you come back up: "1 / 20", "2 / 20", "3 / 20"; a short vibration and a brief pulse of the number; the ring fills | |
| C3 | Do half reps | Not counted ("Go lower") | |
| C4 | Bounce quickly at the bottom | Not counted twice | |
| C5 | Walk out of frame mid-set | "... Counting is paused."; no rep is added until you are back in frame | |
| C6 | Reach the target | Counting stops at the target; "That is all of them. One last step: raise your hand when it asks." then, after the hand-raise check, the green "Done" with a check mark and the submit button | |
| C7 | Count against a person who counts aloud | Counted reps within about one of the true number over 10 reps. **Accuracy on a real person has not been verified yet** | |
| C8 | The sound toggle | Off by default; on gives a short beep per rep | |
| C9 | Look at the screen | The line "Your camera picture is analysed on this phone only. Nothing is stored or sent" is visible | |

## Transaction approval and token choice (round 4)

| # | Test | Expected | Result |
|---|---|---|---|
| T1 | Phantom, join a challenge | Phantom shows an approval sheet for the transaction (note exactly what it shows). If not: Details > Copy diagnostics and paste the timeline | |
| T2 | A wallet with no SOL, tap join | Before the wallet opens: "Your wallet needs a little devnet SOL for network fees. Tap Get test tokens to receive some." with the button | |
| T3 | A wallet with SOL but no tokens | "Your wallet has no tUSDC yet..." (or tSKR) with the button | |
| T4 | Wallet that already used its SOL gift | The message says the free SOL was already sent and points to faucet.solana.com | |
| S1 | Stake with SKR: New challenge > plan | A "Stake token" selector with tUSDC and tSKR, each with its icon and your balance; the note that tSKR is a test token, not real SKR | |
| S2 | Choose tSKR, stake 1, review, sign | The review shows the tSKR mint; after signing the pot shows "1 tSKR" | |
| S3 | Open that pool as another wallet | "This challenge is staked in tSKR", the token cannot be changed, balance of tSKR shown | |
| S4 | Wallet with tUSDC only joins the tSKR pool | Pre-flight: "Your wallet has no tSKR yet..." and nothing opens | |
| S5 | Explore token filter | All / tUSDC / tSKR pills filter the list; cards show the token symbol | |
| S6 | Settle and claim in a tSKR pool | The claim button and the payout are in tSKR with the right decimals | |
| S7 | Streak freeze and rewards | Still paid in tSKR as before | |
