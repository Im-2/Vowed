# Vowed UI screenshots and brand assets

Screens were captured from the debug build on the Android emulator (API 36). **Screens with data (Home, Explore, Squads, Detail, Rewards, Coach, Letters, You) use a debug-only preview activity with made-up data** (`android/app/src/debug/.../PreviewActivity.kt`, `Samples.kt`); it talks to no server or wallet and is not in release builds. The wallet-connect, splash and onboarding screens are the real screens. Retake with `scripts/ui-shot.sh <screen> <name>`.

| file | screen |
|---|---|
| 01-splash.png | Android launch screen (the in-app animated splash follows it for about 1.5 s: logo scale-in with a shine, then the name and a spinner) |
| 02-onboarding-1.png, 03-onboarding-2.png, 04-onboarding-3.png | the three onboarding pages |
| 05-connect-wallet.png, 06-connect-error.png, 06b-connect-success.png | connect wallet, its error state, the "Wallet connected" pop-up |
| 07-home.png, 07b-home-lower.png, 08-home-empty.png | Home with data (hero, test tokens, Today's check-ins, Discover, Top streaks), and the new-user empty state |
| 11-create-start.png, 12-create-plan.png, 13-review.png | Create challenge (plain words, plan card with proof type, trust, token, mode, stake) and the review-before-sign screen |
| 14-explore.png, 15-categories.png | Explore (filters, DEMO MODE quick challenges, SAMPLE and DEMO labels) and the categories grid |
| 17-challenge-detail.png, 18-challenge-detail-demo.png | challenge detail (hero, streak, check-in panel, players, SIMULATED yield label) |
| 19-squads.png, 20-squad-detail.png | squads list, create and join; squad detail with invite, leaderboard with Nudge, challenges, activity |
| 21-leaderboard-rewards.png | top streaks and weekly SKR rewards (TEST SKR), streak freeze, SIMULATED yield note |
| 22-you.png, 23-coach.png, 24-letters.png | You hub, coach, letters |
| 30-launcher-icon-light.png, 31-launcher-icon-dark.png | the app icon in the emulator launcher (the emulator's launcher stayed on its light look when night mode was switched, so the dark case is covered by the generated sheet below) |
| icon-shapes-light-wallpaper.png, icon-shapes-dark-wallpaper.png, icon-themed-monochrome.png | the adaptive icon under circle, squircle and rounded-square masks on light and dark backgrounds, and the themed (monochrome) layer |
| vowed-store-512.png, vowed-foreground-512.png, vowed-logo.svg | 512x512 store icon, the foreground layer alone, the SVG |

Honesty labels kept word for word and restyled as badges: DEMO POOL / DEMO MODE, SAMPLE, TEST TOKENS / test USDC / test SKR, Yield: SIMULATED, trust tiers.

## Logo
Original artwork: a rounded "V" of two folded faces (light violet and deep indigo) with a green check in the notch, a highlight and a soft shadow. One geometry generates every file: `python scripts/gen-logo.py` (Android vector drawables for the adaptive icon: background, foreground and monochrome layers; the in-app mark; SVG; PNGs).

## Font
Nunito (SIL Open Font License 1.1), bundled in `res/font/nunito.ttf`; license text in `docs/licenses/Nunito-OFL.txt`.

## Not done in the redesign
* The token on the Create screen is shown (test USDC) but cannot be switched to SKR: the app has no such choice today and the redesign does not change behavior.
* Real-device and dark-theme screenshots (a dark color scheme exists in `ui/theme/Theme.kt` but was not captured).
