# Photo credits

The category photos in the app come from files supplied by the project owner in `design/photos/`. `scripts/gen-photos.py` compresses them into `android/app/src/main/res/drawable-nodpi/photo_<category>.webp` (about 800 px wide, WebP quality 72, 209 KB for all eight; the APK grew by about 0.65 MB in this change including the SKR icon and other assets). The owner is filling in the source pages and licenses: **every row marked TODO must be completed before submission.**

| category (app key) | file supplied | used for | source page | author | license |
|---|---|---|---|---|---|
| Study (`study`) | `design/photos/study.jpg` | Categories tile, Explore and Home thumbnails, challenge header | TODO | TODO | TODO |
| Fitness (`fitness`) | `design/photos/fitness.png` | same | TODO | TODO | TODO |
| Steps (`steps`) | `design/photos/steps.png` | same | TODO | TODO | TODO |
| Sleep (`sleep`) | `design/photos/sleep.png` | same | TODO | TODO | TODO |
| Screen time (`detox`) | `design/photos/screen time.jpg` | same | TODO | TODO | TODO |
| Focus (`focus`) | `design/photos/focus.jpg` | the Focus tile in Categories (no Explore category uses it) | TODO | TODO | TODO |
| Places (`location`) | `design/photos/places.jpg` | same | TODO | TODO | TODO |
| Custom (`custom`) | `design/photos/custom.jpg` | same | TODO | TODO | TODO |

Notes
* All eight category photos are present (checked 2026-10-08). A category without a photo file falls back to the gradient tile with its icon.
* The file names differ slightly from the ones first agreed (`fitness.png`, `steps.png`, `sleep.png` are PNG; `screen time.jpg` has a space); the script accepts all of them. After changing a photo, run `python scripts/gen-photos.py` and rebuild.
* The originals are not bundled in the APK, only the compressed WebP copies. If a license requires attribution inside the app, say so and an "Image credits" line can be added to You.
* The SKR token icon (`design/brand/skr-logo.png`) and the owner's logo are not photos; see `docs/verified-facts.md`.

## Token and brand logos
| logo | file supplied | where it is used | source | licence |
|---|---|---|---|---|
| USDC logo (circle, blue with white dollar sign and brackets) | `design/brand/usdc-logo.webp` (the owner said `usdc-logo.png`; the file in the folder is a valid 300 x 300 WebP with transparent corners, so that is the file used). `scripts/gen-photos.py` resizes it to 256 px, without recolouring or cropping, into `android/app/src/main/res/drawable-nodpi/usdc_logo.png` | the stake token selector (new challenge), the pot and token lines of Explore, Home and challenge cards, the balance pill on Home, the test-token card in You, and the stake token line of "Check before you sign" | supplied by the project owner as "the official USDC logo"; the source page and author are **to be confirmed by the user** | **to be confirmed by the user** |
| SKR token icon | `design/brand/skr-logo.png` | same places, for test SKR | supplied by the project owner | to be confirmed by the user |

**Rule for the USDC logo:** tUSDC is our own TEST token on Solana devnet, not real USDC, and not issued or endorsed by Circle. The logo is never shown without a small "t" test badge (an amber circle with a white t at the logo's lower right), and the words TEST TOKENS stay on the token selector, the balance pill, the test-token card and the review screen. The logo is drawn as supplied: no tint, no shadow, no distortion.
