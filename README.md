# Photo Sorter (Android)

Sorts the photos on your phone into categories. **Local Scan is
entirely on-device** — no account, no cloud upload. **v0.4 adds an
opt-in Colab path** (two blue buttons, see below) — that path does
upload thumbnails to a free Colab notebook, on purpose, per tap.

App name: **Photo Sorter** · Package: `com.photosorter.app`
minSdk 26 · targetSdk 34 · Kotlin 1.9.24 · AGP 8.5.2 · Gradle 8.9

## Privacy model — read this first

1. **Thumbnails only, on-device.** The app reads photos via MediaStore
   (`READ_MEDIA_IMAGES`), makes a thumbnail of at most **384 px** on the
   long edge, and runs classification on that thumbnail only. Full-resolution
   photos are never loaded for classification and never leave the phone.
2. **No internet use for local Scan.** The core sort cannot send
   anything anywhere. v0.4 adds `INTERNET` to the manifest **only**
   for the opt-in Colab buttons (opening the notebook URL) — the
   local Scan path still uses no network at all.
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

## v0.2.0 — what changed (2026-11-10)

Alex reported three v0.1 bugs; all three are fixed in this version:

1. **Green progress bar with numerals, and the crash is fixed.**
   - A green horizontal `ProgressBar` (`#2E7D32`, determinate) sits
     under the Scan button, with numerals on top in `tvProgress`:
     `Classified 12 / 48` during a scan, `Copying 12 / 48` during
     an approve-move copy. Its max/progress come from the ViewModel.
   - Crash causes found in v0.1 and fixed:
     - Scan/move ran in the Activity's `lifecycleScope` and the
       Classifier was closed in `Activity.onDestroy()` — rotating
       the phone cancelled the coroutine and closed the Classifier
       while it was still in use. Work now lives in
       `SorterViewModel` (closed in `ViewModel.onCleared()` only).
     - Per-photo grid thumbnails were set on a `RecyclerView`
       holder after an async load with no check that the holder
       still showed the same photo — wrong-photo / crash path.
       Now the holder's `tag` (photo id) is checked before
       `setImageBitmap`, and recycled bitmaps are guarded
       (`Classifier.thumbnail()` / `thumbHash()` return a safe
       placeholder / empty hash instead of throwing).
     - `AlertDialog` and the delete launcher could fire on a
       destroyed Activity — now guarded with `isFinishing` /
       `isDestroyed` checks. All progress updates go through
       `StateFlow` observed on the Main thread; no View is ever
       touched from `Dispatchers.IO`.
2. **Rotation / leaving the app no longer stops a scan or move.**
   - Scan/classify/move run in `SorterViewModel.viewModelScope`,
     which survives rotation. The Activity just re-attaches and
     re-observes progress. Selected category is saved in
     `onSaveInstanceState`.
   - The plan is checkpointed to `plan.csv` / `plan.json` **every
     10 photos** (and after every Deny/reassign). If the app is
     killed or swiped away, tap **Resume from saved plan** on next
     launch to restore the last checkpoint instead of re-scanning.
   - Honest limit: a ViewModel survives rotation, **not** a
     force-kill. Work stops if Android kills the app; the
     checkpoint + resume is the recovery path. No WorkManager is
     implemented in v0.2.0, so background work after a force-kill
     is NOT claimed.
3. **Per-photo Deny button.**
   - Each needs-review photo now has a **Deny** button under its
     thumbnail (hidden for photos that don't need review).
     Tapping Deny reassigns that photo to category `other`,
     sets `needsReview=false`, caption
     `Denied by user — moved to other`, writes the updated plan
     immediately, and refreshes the category counts.
     Deny does **NOT** delete or move the file — it only changes
     its category to `other` in the plan.

## v0.3.0 — what changed (2026-11-10)

Alex asked for three things; all three are in this version:

1. **New launcher logo: 3×3 grid of blue squares on white.**
   - `ic_launcher_foreground.xml` is now nine blue (`#1565C0`)
     24×24 squares in a 3×3 grid (6 dp gaps, 12 dp margin) on the
     white background (`ic_launcher_background.xml`, `#FFFFFF`).
     `ic_launcher.xml` / `ic_launcher_round.xml` (adaptive icons)
     are unchanged — they already point at that foreground +
     background, so the new grid shows on both square and round
     launcher shapes.
2. **Sorting keeps running even if you exit the app.**
   - Scan and approve-move copy now run as **WorkManager
     foreground workers** (`SortWorker.kt`, `MoveWorker.kt`,
     `androidx.work:work-runtime-ktx:2.9.0`, unique work with
     `ExistingWorkPolicy.KEEP`). The workers run outside the
     Activity/ViewModel lifecycle, show a "Sorting photos…"
     notification while they work, and keep going after the app
     is swiped away or the system kills the process. The green
     progress bar is now driven by the workers' progress Data
     (`Classified N / total`, `Copying N / total`), observed by
     MainActivity from WorkInfo. Deletion of originals is still
     Activity-only: when MoveWorker succeeds, MainActivity asks
     Android to delete the originals, as in v0.2.
   - Honest limits: WorkManager survives app exit and most
     kills, but **not a force-stop** (Settings → Force stop
     clears scheduled work) and aggressive battery optimisation
     can delay it. If that happens, the checkpoint + resume
     below is the recovery path — nothing is lost, at most the
     in-flight batch is re-run.
3. **The file sort is saved / resumed after shutdown.**
   - The plan is still checkpointed to `plan.csv` / `plan.json`
     **every 10 photos** (and after every Deny/reassign) — verified
     kept in v0.3. `WorkState.kt` records a durable
     "scan in progress" flag in SharedPreferences; a new
     `BootReceiver` (registered in the manifest with
     `RECEIVE_BOOT_COMPLETED`) re-enqueues an unfinished scan
     on reboot. The re-enqueued scan starts from the top (it
     does not resume mid-list), but the checkpointed plan is
     still there — tap **Resume from saved plan** to see the
     last checkpoint without waiting for the re-scan. A move
     interrupted by shutdown is not auto-re-enqueued (its
     delete step needs the Activity); the files already copied
     stay in `Pictures/Sorted/<category>/` and the category
     can simply be re-approved.

## v0.4.0 — Colab path: two blue buttons, NudeNet + vision model, subcategories (2026-11-12)

Alex: the on-device categories were bad and the phone sort took
forever. The extreme option: sort in a free Colab notebook instead.

**The two blue buttons** sit directly below the green progress bar,
in the fourth row, split side by side — each is a little less than
half a full-width button, blue `#1565C0`, white text:

1. **Upload to notebook** — builds `thumbnails.zip` (max 384 px
   JPEGs + `manifest.csv` with MediaStore ids) in
   `Download/PhotoSorter-Colab/` in a WorkManager foreground worker
   (`ColabUploadWorker.kt`), with the green bar showing
   `Uploading N / total`. On success it opens the Colab notebook
   (`colab/PhotoSorter_Colab.ipynb`); you tap Upload in Colab and
   pick `thumbnails.zip`.
   - Honest limit: Colab has **no inbound endpoint**, so the phone
     cannot push the zip straight into a Colab session. The button
     does the on-phone half (zip + progress) and opens the notebook;
     the last upload tap is in Colab's UI.
   - The zip **includes NSFW-suspect thumbnails on purpose** —
     Colab's NudeNet is what sorts them. That is the opt-in you
     make by tapping this button, per tap, never automatic. The
     local Scan path stays fully on-device and is still the default.
2. **Sort** — if no plan is loaded, it opens a file picker for the
   `plan.csv` you downloaded from Colab (`ColabPlan.kt` imports it,
   matching by MediaStore id, subcategories become `top/sub`
   categories). If a plan is loaded, it sorts from it in a
   WorkManager foreground worker (`ColabSortWorker.kt`), copying
   to `Pictures/Sorted/<category> - <subcategory>/`, bar showing
   `Sorting N / total`. Originals stay until you delete them per
   category, after checking the Sorted folders.

**Surviving rotation / shutdown:** both workers run in WorkManager
(foreground, `FOREGROUND_SERVICE_TYPE_DATA_SYNC`, `setForeground()`
in `runCatching` — the v0.3.1 lessons), with progress driven from
the **latest RUNNING** WorkInfo, not `firstOrNull()` (the v0.3.0
stale-bar bug), and `REPLACE` on a user tap (v0.3.0's `KEEP`
silently dropped new work). `WorkState` records in-flight Colab
work and `BootReceiver` re-enqueues it on boot. A full power-off
stops work while the phone is off — nothing runs with no power —
but it resumes on boot from the plan / rebuilds the zip. That is
a resume, not a run-through-power-off.

**The Colab notebook** (`colab/PhotoSorter_Colab.ipynb`, also
`colab/README.md`): free, private, no Drive mount, no Drive save.
NudeNet first (≥0.5 explicit, skip vision, caption `[redacted]`;
≥0.2 needs review), then **Qwen2.5-VL-3B** (CLIP zero-shot fallback
if Qwen fails / no GPU), top category **+ subcategory**
(e.g. `meme/anime`, `food/meal`, `nsfw_explicit/futanari`),
checkpoints every 50 photos to `plan_partial.csv`, writes
`plan.csv` / `plan.json`, you download the plan, then the **wipe
cell deletes every thumbnail from the runtime**. A 384 px thumbnail
still shows what a photo is, and deletion is verified only by
listing `/content` afterwards — that is the honest privacy limit.

**If it still crashes, Alex should report:** which blue button,
whether the bar appeared, and whether it was instant or after a
few seconds — that distinguishes a worker crash from a zip /
plan-import issue, same as the v0.3.1 Scan crash report.

## v0.3.1 — URGENT FIX: Scan crashed in v0.3.0, no progress bar (2026-11-10)

Alex reported: tapping **Scan photos** crashed the app in v0.3.0
(it worked in v0.2), and no progress bar showed. Root causes
found by reading the v0.3.0 code (no crash log was available —
the exact on-device exception could not be confirmed from code
alone, so all three defects below were fixed, not just one):

1. **Foreground service type — most likely crash.** SortWorker
   and MoveWorker called `setForeground()` as the FIRST line of
   `doWork()`, outside any `try/catch`, but the manifest declared
   only `FOREGROUND_SERVICE` — not `FOREGROUND_SERVICE_DATA_SYNC`.
   On Android 14 (targetSdk 34, Alex's S25 Ultra) a foreground
   service must declare its type; starting one without it throws
   and kills the worker process — i.e. the app — before any
   progress was ever published. Hence: crash on Scan tap, no bar.
   Fixed two ways: the manifest now declares
   `FOREGROUND_SERVICE_DATA_SYNC`, the workers pass
   `FOREGROUND_SERVICE_TYPE_DATA_SYNC` explicitly, AND
   `setForeground()` is wrapped in `runCatching` so a foreground
   failure degrades to a normal worker instead of crashing.
2. **Stale WorkInfo — why no bar showed.** MainActivity observed
   `getWorkInfosByTagLiveData()` and took `infos.firstOrNull()`,
   which can be an OLD SUCCEEDED/FAILED run, not the new one —
   so the bar was driven from stale data, or never driven.
3. **Unique-work KEEP — why Scan could do nothing.** 
   `enqueueUniqueWork(KEEP)` silently drops a new scan if any
   previous scan work with that name exists. Plus
   `WorkManager.getInstance()` on the tap path is a Main-thread
   crash vector if initialisation ever fails.

**The fix (v0.3.1): Scan and approve-move are BACK in the
ViewModel** — the proven v0.2 path (`viewModelScope`, progress
via StateFlow, checkpoint every 10 photos, Classifier closed
only in `onCleared()`). The green bar is shown immediately on
Scan tap (`"Starting scan…"`, then `"Classified 0 / N"`,
`"Classified N / total"` from the first photo) — it is driven
by the ViewModel, not by WorkInfo, and the WorkInfo observers
were removed from MainActivity. The workers are NOT enqueued
for scan/move in v0.3.1, and BootReceiver no longer re-enqueues
them on boot (that would re-trigger the same crash). 
**Honest trade-off:** background sorting after exiting the app
(the v0.3.0 feature) is PAUSED in v0.3.1 until the worker path
is verified on a real device — Scan working again comes first.
Rotation still survives (ViewModel), and Resume from saved plan
still recovers after a shutdown.

**If it still crashes in v0.3.1, Alex should report:** exactly
what was tapped (Scan / Approve move / Resume), whether the
crash was instant or after a few seconds, and whether the green
bar appeared at all — that distinguishes a remaining worker
issue from a classifier / thumbnail issue.

## How to use

1. Install the APK (see Build below) on the phone.
2. Open **Photo Sorter**, tap **Scan photos**, grant photo permission.
3. Wait — thumbnails are classified on-device; a few thousand photos
   take a while (ML Kit is ~0.1–1 s/photo on a modern phone). The
   green progress bar shows `Classified N / total` as it goes.
   Rotating the phone no longer restarts or stops the scan, and
   neither does leaving the app — the scan keeps running in the
   background (v0.3.0, WorkManager) and you can come back to it.
   If the phone shuts down mid-scan, the scan is re-enqueued on
   boot and the last checkpoint is recoverable via Resume.
4. Review the category list with counts. Tap a category to see its
   thumbnails. Tap a thumbnail to reassign its category by hand.
   Tap **Deny** on a needs-review photo to send it to `other`.
   If the app was killed mid-scan, tap **Resume from saved plan**.
5. When a category looks right, tap **Approve move** (copies to
   `Pictures/Sorted/<category>/`, then Android asks to delete the
   originals) or **Approve delete** (Android asks to delete).
   The green bar shows `Copying N / total` during the copy.
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
  MainActivity.kt     # UI only: observes ViewModel + WorkInfo, dialogs, approvals
  SorterViewModel.kt  # v0.3.0: enqueues workers, plan, deny/reassign
  SortWorker.kt       # v0.3.0: scan/classify in a foreground WorkManager worker
  MoveWorker.kt       # v0.3.0: approve-move copy in a foreground WorkManager worker
  BootReceiver.kt     # v0.3.0: re-enqueue unfinished scan after reboot
  WorkState.kt        # v0.3.0: durable "sorting in progress" flags
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
