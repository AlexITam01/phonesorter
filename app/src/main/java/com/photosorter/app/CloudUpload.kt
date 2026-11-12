package com.photosorter.app

/**
 * OPT-IN cloud path — OFF by default, NOT wired to any server.
 *
 * Alex's idea was: shrink photos to thumbnails, upload them, resize
 * in the cloud, sort locally. That does reduce compute on the phone,
 * but it does NOT protect privacy: a 256 px thumbnail still clearly
 * shows what a photo is — faces, places, text, and NSFW content
 * are all recognisable at that size. Uploading thumbnails to a
 * third-party server is uploading the photos, just smaller.
 *
 * So the default path in this app is fully on-device (see
 * Classifier.kt) and this file is only a documented, disabled stub
 * for a direct-to-server upload:
 *
 *  - [enabled] is false unless the user explicitly turns it on in
 *    Settings and types in their OWN server URL (e.g. a home server
 *    or a self-hosted Ollama box they control). There is no default
 *    server, no analytics endpoint, and no account.
 *  - Images flagged NSFW-suspect are NEVER uploaded, even when
 *    enabled (see [isUploadable]).
 *  - Uploads, if ever enabled, are downscaled to 256 px first.
 *
 * v0.4 — the Colab path is DIFFERENT from this stub, and Alex
 * explicitly asked for it: the two blue buttons build a thumbnails
 * zip (max 384 px) and open a free Colab notebook, where NudeNet +
 * a vision model sort it and the user downloads plan.csv. That
 * path DOES include NSFW-suspect thumbnails, on purpose — Colab's
 * NudeNet is what sorts them — and it is opt-in per tap, never
 * automatic. See ColabUploadWorker.kt / colab/README.md for the
 * full honest limits. This stub stays disabled.
 *
 * This class intentionally does no network I/O. It exists so the
 * opt-in design is explicit in code, not just in the README.
 */
object CloudUpload {
    /** Always false in v0.1.0. No UI toggle turns this on yet. */
    const val enabled: Boolean = false

    /** User-supplied server URL. Empty = no server configured. */
    var serverUrl: String = ""

    const val UPLOAD_MAX_PX = 256

    /** NSFW-suspect images are never uploadable, even if enabled. */
    fun isUploadable(item: PhotoItem): Boolean {
        if (!enabled) return false
        if (serverUrl.isBlank()) return false
        if (Categories.isNsfw(item.category)) return false
        if (item.nsfwScore >= 0.2f) return false
        if (item.needsReview && item.category.startsWith("nsfw_")) return false
        return true
    }
}
