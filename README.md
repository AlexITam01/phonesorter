# Photo Sorter — local only (Android)

Sorts the photos on your phone into categories, **entirely on-device**.
No account, no cloud upload, no internet permission.

App name: **Photo Sorter** · Package: `com.photosorter.app`
minSdk 26 · targetSdk 34 · Kotlin 1.9.24 · AGP 8.5.2 · Gradle 8.9

## Privacy model — read this first

1. **Thumbnails only, on-device.** The app reads photos via MediaStore
   (`READ_MEDIA_IMAGES`), makes a thumbnail of at most **384 px** on the
   long edge, and runs classification on that thumbnail only. Full-resolution
   photos are never loaded for classification and never leave the phone.
2. **No internet permission.** The manifest deliberately has no
   `INTERNET` permission. The core sort cannot send anything anywhere.
3. **Plan first, approval second.** Scanning writes `plan.csv` /
   `plan.json` (in the app's external files dir) before anything moves
   or deletes. Nothing is moved or deleted until you tap **Approve move**
   or **Approve delete** for a category, and Android itself then asks
   you to confirm deletions.
4. **NSFW captions are redacted.** Any row classified `nsfw_explicit/*`
   has caption `[redacted]` in the plan and in the UI. It is flagged,
   never described.
5. **Never auto-delete.** NSFW-suspect images, duplicates, and
   low-confidence images go to a review queue. You reassign them by
   tapping a thumbnail.

## Why "upload thumbnails, resize in the cloud, sort locally" is NOT the default

Alex's idea was to shrink photos to thumbnails, upload them, resize in
the cloud, and sort locally to save phone compute. That saves compute,
but it does **not** protect privacy:

- A 256 px thumbnail still clearly shows faces, places, text, memes,
  and NSFW content. Uploading a thumbnail is uploading the photo,
  just smaller.
- Once a thumbnail is on someone else's server you cannot verify it
  was deleted, resized only, or not used to train a model.
- NSFW content is the worst case for this: a thumbnail is enough to
  identify a person and the nature of the image.

So the default path is fully on-device. The code has an **opt-in,
off-by-default** stub for this instead — see `CloudUpload.kt`:

- `enabled = false`, no server configured, no UI toggle turns it on.
- If a future version ever enables it, it must be to a server **you**
  control (e.g. your own home server / self-hosted Ollama box), with
  thumbnails downscaled to **256 px**, and **NSFW-suspect images
  excluded from any upload, always**.
- Enabling it would also require adding `INTERNET` to the manifest
  by hand — a deliberate, visible step, not a silent default.

## Categories

Same as the PC script `~/workspace/pc-folder-reorg/sort_photos_local.py`:

```
nsfw_suggestive/futanari, nsfw_suggestive/other,
nsfw_explicit/futanari,   nsfw_explicit/other,
meme, screenshot, statistics_chart_document,
people_family_friends, selfie_vtuber, travel_hong_kong, food,
school_coursework, creative_3d_render, game_fandom,
chomp_city_robotics, davispedia, pet_animal, landscape_nature,
duplicate_near_duplicate, blurry_low_quality, other
```

Approved moves go to `Pictures/Sorted/<category>/` on device storage
(`nsfw_explicit/futanari` becomes `Pictures/Sorted/nsfw_explicit - futanari/`).

## How classification works (and its limits)

- **General categories:** Google ML Kit **on-device** Image Labeling
  (`com.google.mlkit:image-labeling`). This is the on-device labeler;
  it does not send images to Google Cloud. Its labels are coarse
  (food, animal, landscape, person, text, …), so many photos land in
  `other` / `needs_review` and need a human tap. `travel_hong_kong`
  and `meme` are weak guesses from labels alone — review them.
- **Screenshots:** by filename (`screenshot` in the name), 95% confidence.
- **Duplicates:** by MD5 of the thumbnail pixels, not the full file.
  Near-duplicates with different crops/filters will be missed.
- **Blurry / low quality:** very low-resolution images are flagged.
- **NSFW — honest limitation:** ML Kit cannot detect NSFW content,
  and there is **no small, reliable, openly-licensed TFLite model
  for futanari / explicit-vs-suggestive detection bundled in this
  repo**. NudeNet (used by the PC script) is PyTorch/ONNX, not TFLite.
  - The app includes a TFLite loader (`NsfwClassifier.kt`). If you
    place a compatible model at `app/src/main/assets/nsfw.tflite`
    (single float output 0.0 = safe, 1.0 = explicit, 224×224 RGB
    input, e.g. a converted OpenNSFW2 / nsfwjs-style classifier),
    it will run on-device. **No model is bundled in v0.1.0.**
  - Without a model, the app falls back to heuristics + a user
    review queue. It will **never** label an image
    `nsfw_explicit/*` on heuristics alone — that needs the model
    or a human tap. Futanari subcategories always need user review.
  - Do not treat a "not NSFW" result as proof an image is safe.
    It is a triage signal only.

## How to use

1. Install the APK (see Build below) on the phone.
2. Open **Photo Sorter**, tap **Scan photos**, grant photo permission.
3. Wait — thumbnails are classified on-device; a few thousand photos
   take a while (ML Kit is ~0.1–1 s/photo on a modern phone).
4. Review the category list with counts. Tap a category to see its
   thumbnails. Tap a thumbnail to reassign its category by hand.
5. When a category looks right, tap **Approve move** (copies to
   `Pictures/Sorted/<category>/`, then Android asks to delete the
   originals) or **Approve delete** (Android asks to delete).
6. `plan.csv` / `plan.json` are saved in the app's files dir first,
   so you can check the plan before approving anything.

## Build

### In Android Studio (recommended)
1. Open `~/workspace/phone-photo-sorter/` in Android Studio (Hedgehog or later).
2. Let it download Gradle 8.9 / AGP 8.5.2 and SDK 34.
3. Build → Build App Bundle(s) / APK(s) → Build APK(s).
   Or run `./gradlew assembleDebug` in a terminal.

### From a terminal (needs JDK 17 + Android SDK 34)
```bash
cd phone-photo-sorter
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

**Build status in this workspace — attempted 2026-11-10, NOT built here:**
`./gradlew assembleDebug` (and the installed Gradle 8.9 binary) fail
in this VM with:
`FAILURE: Build failed with an exception. Could not receive a message from the daemon.`
`Caused by: java.lang.IllegalArgumentException: Unexpected type tag 109 found.`
Root cause, verified with a minimal Java localhost-socket test: this
VM blocks Java TCP connections to localhost. The socket returns:
`muse: Other TCP connections is turned off for this assistant. To allow
it, ask the user to open Muse settings -> Permissions -> Direct network
protocols and switch other_tcp from Deny to Ask.`
Gradle's daemon communicates over localhost TCP, so no Gradle build
can run here until that permission is switched to Ask. (Python
localhost sockets work; Java's do not, in this sandbox.)
What was installed here to try: Temurin JDK 17.0.21 and Android SDK
cmdline-tools + platform-34 (ext12) + build-tools 34.0.0, in
`~/workspace/.sdk/` — Gradle itself downloaded fine, the daemon
handshake is what fails. The project is otherwise complete: all XML
parses, Kotlin files are brace/paren balanced, and the Gradle wrapper
jar sha256 is `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`
(same verified wrapper as Shield-Browser).
**To build:** either (a) switch `other_tcp` to Ask in Muse settings
and re-run `./gradlew assembleDebug` here, or (b) open the project
in Android Studio on a PC (Hedgehog or later) and build there —
no code changes needed.

**Update 2026-11-10 ~14:15 PST — no-daemon also fails, GitHub Actions fallback prepared:**
- Method 1 tried: `org.gradle.daemon=false` added to `gradle.properties`,
  `./gradlew --no-daemon assembleDebug` — failed before Gradle even
  started: the wrapper tried to re-download Gradle 8.9 and got
  `javax.net.ssl.SSLException: Unsupported or unrecognized SSL message`
  (the wrapper's downloader ignores the egress proxy/truststore in
  `~/.gradle/gradle.properties`; no wrapper distribution was cached).
- Method 2 tried: installed Gradle 8.9 binary
  (`~/workspace/.sdk/gradle-8.9/bin/gradle --no-daemon assembleDebug`)
  — failed with the same daemon error:
  `FAILURE: Build failed with an exception. Could not receive a message
  from the daemon.` Gradle 8.9 has no true in-process mode: even
  `--no-daemon` forks a single-use daemon that talks over localhost
  TCP, which this VM blocks for Java. So **no Gradle build can run in
  this VM at all** until `other_tcp` is allowed.
- A fully manual build (kotlinc + d8 + aapt2, no Gradle) was not
  attempted: kotlinc is not installed, none of the 7 dependencies
  (AndroidX, Material, ML Kit, TFLite + transitives) are cached, and
  resolving them by hand is fragile — the GitHub Actions route below
  is the reliable fallback.
- **Fallback (c) — GitHub Actions (prepared, NOT pushed):**
  `.github/workflows/build-apk.yml` is in the project (same pattern as
  Shield-Browser: setup-java Temurin 17, accept SDK licenses,
  `./gradlew assembleDebug --no-daemon`, upload-artifact
  `PhotoSorter-debug-apk`). To use it: create a new GitHub repo
  (e.g. `AlexITam01/Photo-Sorter`), push this project, and download
  the APK from the Actions run's artifacts. Nothing has been pushed
  and no repo has been created — that needs Alex's go-ahead.
  Note: pushing to GitHub Actions means the *source code* goes to
  GitHub; no photos are involved, the app has no INTERNET permission,
  and the build runner never sees any images.

## Files

```
settings.gradle.kts / build.gradle.kts / gradle.properties
app/build.gradle.kts
app/src/main/AndroidManifest.xml          # no INTERNET permission
app/src/main/java/com/photosorter/app/
  MainActivity.kt     # scan, grouped results, per-category approval
  Classifier.kt       # thumbnail-only ML Kit classification
  NsfwClassifier.kt   # optional TFLite model, fallback = review queue
  Categories.kt       # category list (same as PC script)
  PhotoItem.kt        # one photo + its classification
  PlanStore.kt        # plan.csv / plan.json, written before any move
  CloudUpload.kt      # opt-in cloud stub — OFF, no server, NSFW excluded
app/src/main/res/layout/  activity_main.xml, item_category.xml, item_photo.xml
```

## What this app does NOT do

- No cloud upload by default, no account, no analytics.
- No write-back to Google Photos albums (the Photos API no longer
  allows full-library write-back). Use the plan + `Pictures/Sorted/`
  folders, then recreate albums in Google Photos if you want them there.
- No reliable automatic futanari detection — that needs human review.
- No video classification (videos are listed as `other` / review).

## Repo creation status (2026-11-10)
- The GitHub connector cannot create repositories: `create_repository` returns
  `403 Resource not accessible by integration`. Alex must create the empty
  `Photo-Sorter` repo on github.com (private recommended, no README/.gitignore/
  license — leave it empty), then add it to the Muse GitHub App installation
  (https://github.com/apps/meta-muse-ai/installations/new) so Muse can push.
- Ready to push once the repo exists (28 text files, manifest at
  /tmp/photo-sorter-push-paths): everything except the two web-UI files below
  and local.properties (now gitignored — it holds this machine's SDK path).
- Web-UI uploads still needed after the push (same as Shield-Browser):
  1. `.github/workflows/build-apk.yml` (API 403, App lacks Workflows permission)
  2. `gradle/wrapper/gradle-wrapper.jar` (binary; sha256 498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17)
  Once both are in, the Actions workflow builds the APK automatically on push.
