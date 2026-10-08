# Vowed UI screenshots and brand assets

Screens were captured from the debug build on the Android emulator (API 36). **Screens with data (Home, Explore, Squads, Detail, Rewards, Coach, Letters, You, the sheets) use a debug-only preview activity with made-up data** (`android/app/src/debug/.../PreviewActivity.kt`, `Samples.kt`); it talks to no server or wallet and is not in release builds. The wallet-connect, splash and onboarding screens are the real screens. Retake with `scripts/ui-shot.sh <screen> <name>`.

| file | screen |
|---|---|
| 01-splash.png | the in-app splash: the owner's 3D logo large (scales in with a shine), the name, a spinner (the Android launch screen shows the same logo in the icon circle first) |
| 02-onboarding-1.png, 03-onboarding-2.png, 04-onboarding-3.png | the three onboarding pages |
| 05-connect-wallet.png, 06-connect-error.png, 06b-connect-success.png | connect wallet, its error state, the "Wallet connected" pop-up |
| 07-home-before.png, 14-explore-before.png | Home and Explore BEFORE the polish pass |
| 07-home.png, 08-home-empty.png | Home AFTER (token pill, short hero, one card per check-in) and the new-user empty state |
| 09-token-sheet.png, 09b-info-sheet.png | the bottom sheets: test tokens (from the pill) and an honesty-label explanation (info icon) |
| 15-categories.png, 14-explore.png, 17-challenge-detail.png | updated: photo tiles with a scrim, photo thumbnails, photo header |
| avatars.png | the 14 original avatars and the neutral fallback |
| 11-create-start.png, 12-create-plan.png, 13-review.png | Create challenge (plain words, plan card with proof type, trust, token, mode, stake) and the review-before-sign screen |
| 14-explore.png, 15-categories.png | Explore (filters, DEMO MODE quick challenges, SAMPLE and DEMO labels) and the categories grid |
| 17-challenge-detail.png, 18-challenge-detail-demo.png | challenge detail (hero, streak, check-in panel, players, SIMULATED yield label) |
| 19-squads.png, 20-squad-detail.png | squads list, create and join; squad detail with invite, leaderboard with Nudge, challenges, activity |
| 21-leaderboard-rewards.png, 21b-rewards-lower.png | Rewards screen (round 3): SKR balance, payout countdown, podium, my rank, two-tab leaderboard with SAMPLE rows, streak freeze, collapsed SIMULATED yield (made-up preview data) |
| 25-wallet-help.png | "Using a real wallet" help (three rules, how to switch to devnet, wrong network) |
| 22-you.png, 23-coach.png, 24-letters.png | You hub, coach, letters |
| 30-launcher-icon-light.png | the app icon in the emulator launcher (the emulator launcher did not follow dark mode, so the dark case is covered by the generated sheet below) |
| icon-shapes-light-wallpaper.png, icon-shapes-dark-wallpaper.png, icon-themed-monochrome.png | the adaptive icon under circle, squircle and rounded-square masks on light and dark backgrounds, and the themed (monochrome) layer |
| vowed-store-512.png, vowed-foreground-512.png | 512x512 store icon and the foreground layer alone (from the owner's artwork) |

Honesty labels kept word for word and restyled as badges: DEMO POOL / DEMO MODE, SAMPLE, TEST TOKENS / test USDC / test SKR, Yield: SIMULATED, trust tiers.

## Logo
The owner's glossy 3D "V" (`design/logo-3d.png`). `python scripts/gen-logo-3d.py` removes the white background (`design/logo-3d-transparent.png`) and writes the adaptive icon layers (foreground with safe-zone padding, background gradient, monochrome silhouette), the in-app mark, the store icon and the preview sheets.

## Font
Nunito (SIL Open Font License 1.1), bundled in `res/font/nunito.ttf`; license text in `docs/licenses/Nunito-OFL.txt`.

## Not done in the redesign
* The token on the Create screen is shown (test USDC) but cannot be switched to SKR: the app has no such choice today and the redesign does not change behavior.
* Real-device and dark-theme screenshots (a dark color scheme exists in `ui/theme/Theme.kt` but was not captured).
