Optional NSFW model — NOT bundled.

To enable on-device NSFW scoring, place a compatible TFLite model here
as nsfw.tflite:
  - Input: 224x224 RGB, float, 0..1
  - Output: single float, 0.0 = safe, 1.0 = explicit
  - Example source: a converted OpenNSFW2 / nsfwjs-style classifier.

If this file is absent (the normal case in v0.1.0), the app falls back
to heuristics + a user review queue, and never labels an image
nsfw_explicit on heuristics alone. See README.md / NsfwClassifier.kt.
