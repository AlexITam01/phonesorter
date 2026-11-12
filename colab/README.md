# Photo Sorter — Colab notebook (v0.4 cloud half)

Open in Colab (after this repo is pushed — the app's **Upload to notebook**
button opens this exact link):

https://colab.research.google.com/github/AlexITam01/phonesorter/blob/main/colab/PhotoSorter_Colab.ipynb

## What it is

Free Colab, no signup beyond a Google account, private notebook.
The phone app makes `thumbnails.zip` (max 384 px JPEGs, not full photos)
+ `manifest.csv`. You upload that zip in the notebook, it runs
**NudeNet first, then Qwen2.5-VL-3B** (CLIP zero-shot fallback if Qwen
fails to load / no GPU), assigns a top category **and a subcategory**,
writes `plan.csv` / `plan.json`, you download the plan, and the last
cell wipes the thumbnails out of the runtime.

Categories are the same as the PC script, plus subcategories, e.g.
`meme/anime`, `food/meal`, `people_family_friends/family`,
`nsfw_explicit/futanari`, `game_fandom/pokemon`. In the app a
subcategory shows as its own category row, and approved moves go to
`Pictures/Sorted/<category> - <subcategory>/`.

## Privacy — honest limits

- A 384 px thumbnail still clearly shows faces, places, text and NSFW
  content. Uploading thumbnails is uploading the photos, just smaller.
- The notebook never mounts Google Drive and never saves to Drive.
  Everything lives in `/content` and the wipe cell deletes it, then
  Colab recycles the runtime. You cannot independently verify Google
  deleted it — the wipe cell only proves `/content` is empty after.
- NSFW rows have caption `[redacted]` and `needs_review=true`.
  Nothing is auto-deleted on the phone; the plan is a proposal you
  approve per category in the app.
- If you want zero images on any server, do not use this notebook —
  use the phone's on-device Scan instead.

## Flow

1. App: tap blue **Upload to notebook** → thumbnails zip is built in a
   background worker (survives rotation / leaving the app), then the
   notebook opens.
2. Colab: run cells in order, upload `thumbnails.zip`, wait.
   Checkpoints every 50 photos to `plan_partial.csv`.
3. Download `plan.csv`, **then run the wipe cell**, then close the tab.
4. App: tap blue **Sort** → pick the downloaded `plan.csv` → it sorts
   from the plan in a background worker (survives rotation / app kill /
   reboot, resumes from the plan).
