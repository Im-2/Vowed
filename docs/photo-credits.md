# Photo credits

The category photos in the app come from files supplied by the project owner in `design/photos/`. `scripts/gen-photos.py` compresses them into `android/app/src/main/res/drawable-nodpi/photo_<category>.webp` (about 800 px wide, WebP quality 72, 209 KB for all seven; the APK grew by about 0.65 MB in this change including the SKR icon and other assets). The owner is filling in the source pages and licenses: **every row marked TODO must be completed before submission.**

| category (app key) | file supplied | used for | source page | author | license |
|---|---|---|---|---|---|
| Study (`study`) | **not supplied yet** (the gradient tile with the book icon is used) | Categories tile, Explore and Home thumbnails, challenge header | TODO | TODO | TODO |
| Fitness (`fitness`) | `design/photos/fitness.png` | same | TODO | TODO | TODO |
| Steps (`steps`) | `design/photos/steps.png` | same | TODO | TODO | TODO |
| Sleep (`sleep`) | `design/photos/sleep.png` | same | TODO | TODO | TODO |
| Screen time (`detox`) | `design/photos/screen time.jpg` | same | TODO | TODO | TODO |
| Focus (`focus`) | `design/photos/focus.jpg` | the Focus tile in Categories (no Explore category uses it) | TODO | TODO | TODO |
| Places (`location`) | `design/photos/places.jpg` | same | TODO | TODO | TODO |
| Custom (`custom`) | `design/photos/custom.jpg` | same | TODO | TODO | TODO |

Notes
* The file names differ slightly from the ones first agreed (`fitness.png`, `steps.png`, `sleep.png` are PNG; `screen time.jpg` has a space); the script accepts all of them. When `study.jpg` is added, run `python scripts/gen-photos.py` and rebuild: the Study tile switches from the gradient to the photo by itself.
* The originals are not bundled in the APK, only the compressed WebP copies. If a license requires attribution inside the app, say so and an "Image credits" line can be added to You.
* The SKR token icon (`design/brand/skr-logo.png`) and the owner's logo are not photos; see `docs/verified-facts.md`.
