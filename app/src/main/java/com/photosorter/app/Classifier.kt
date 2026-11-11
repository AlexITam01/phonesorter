package com.photosorter.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * On-device classification on THUMBNAILS only (max 384 px).
 *
 * Full-resolution photos are never loaded into memory for
 * classification and never leave the phone. The thumbnail is made
 * with ContentResolver.loadThumbnail / Bitmap downsampling, the
 * ML Kit on-device labeler runs on it, and the bitmap is recycled.
 *
 * ML Kit's on-device labeler cannot detect NSFW content. NSFW
 * handling is therefore:
 *   - optional TFLite model (NsfwClassifier) if the user supplied one;
 *   - otherwise heuristics + a review queue. A suggestive/explicit
 *     label is never assigned by ML Kit alone.
 */
class Classifier(context: Context) {

    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
    private val nsfw = NsfwClassifier(context)

    fun hasNsfwModel(): Boolean = nsfw.hasModel()

    /** Downscale to at most [maxPx] on the long edge. */
    fun thumbnail(source: Bitmap, maxPx: Int = 384): Bitmap {
        // v0.2.0: never operate on a recycled bitmap — that throws
        // IllegalStateException and was a v0.1 crash path. Return a
        // 1x1 placeholder; the caller treats it as unreadable.
        if (source.isRecycled) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        val longEdge = maxOf(source.width, source.height)
        if (longEdge <= maxPx) return source
        val scale = maxPx.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    fun thumbHash(thumb: Bitmap): String {
        // v0.2.0: recycled bitmap -> empty hash (no duplicate match),
        // instead of crashing in getPixels().
        if (thumb.isRecycled) return ""
        // Cheap duplicate signal: MD5 of the downscaled pixels.
        val pixels = IntArray(thumb.width * thumb.height)
        thumb.getPixels(pixels, 0, thumb.width, 0, 0, thumb.width, thumb.height)
        val bytes = ByteArray(pixels.size * 4)
        for (i in pixels.indices) {
            bytes[i * 4] = (pixels[i] shr 24).toByte()
            bytes[i * 4 + 1] = (pixels[i] shr 16).toByte()
            bytes[i * 4 + 2] = (pixels[i] shr 8).toByte()
            bytes[i * 4 + 3] = pixels[i].toByte()
        }
        return MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun labels(thumb: Bitmap): List<Pair<String, Float>> =
        suspendCancellableCoroutine { cont ->
            labeler.process(InputImage.fromBitmap(thumb, 0))
                .addOnSuccessListener { list ->
                    cont.resume(list.map { it.text.lowercase() to it.confidence })
                }
                .addOnFailureListener { cont.resume(emptyList()) }
        }

    /**
     * Classify one photo from its thumbnail + filename + dimensions.
     * Returns a PhotoItem with category/confidence/caption filled in.
     * NSFW explicit captions are always "[redacted]".
     */
    suspend fun classify(item: PhotoItem, thumb: Bitmap): PhotoItem {
        val name = item.displayName.lowercase()

        // 1) Cheap, reliable metadata rules — no model needed.
        if ("screenshot" in name || "screen shot" in name) {
            return item.copy(category = "screenshot", confidence = 0.95f,
                caption = "Screenshot (by filename)")
        }
        // Very small / low-res images are usually blurry or thumbnails.
        if (item.width > 0 && item.height > 0 &&
            item.width * item.height < 120_000) {
            return item.copy(category = "blurry_low_quality", confidence = 0.6f,
                caption = "Very low resolution", needsReview = true)
        }

        // 2) Optional on-device NSFW model (only if the user supplied one).
        val nsfwScore = when (val r = nsfw.score(thumb)) {
            is NsfwClassifier.NsfwResult.Scored -> r.score
            NsfwClassifier.NsfwResult.Unknown -> 0f
        }
        if (nsfwScore >= 0.8f) {
            // Explicit — but futanari vs other still needs a human.
            return item.copy(category = "nsfw_explicit/other", confidence = nsfwScore,
                caption = "[redacted]", needsReview = true, nsfwScore = nsfwScore)
        }

        // 3) ML Kit on-device labels on the thumbnail.
        val labs = labels(thumb)
        val texts = labs.map { it.first }.toSet()
        val top = labs.maxByOrNull { it.second }

        fun has(vararg words: String) = words.any { w ->
            texts.any { it.contains(w) }
        }

        val (category, confidence) = when {
            has("food", "dish", "meal", "cuisine") -> "food" to 0.8f
            has("dog", "cat", "bird", "animal", "pet") -> "pet_animal" to 0.8f
            has("mountain", "beach", "sunset", "landscape", "nature", "forest", "sky") ->
                "landscape_nature" to 0.75f
            has("person", "people", "face", "human") -> {
                // Selfie vs group: single front-facing person is a guess only.
                "people_family_friends" to 0.7f
            }
            has("building", "city", "street", "architecture", "tower") ->
                "travel_hong_kong" to 0.5f // weak: needs user review, location unknown
            has("text", "document", "paper", "chart", "graph") ->
                "statistics_chart_document" to 0.6f
            has("poster", "comic", "cartoon", "illustration") -> "meme" to 0.5f
            has("toy", "game", "video game") -> "game_fandom" to 0.6f
            else -> "other" to (top?.second ?: 0.3f)
        }

        val needsReview = category == "other" ||
            category == "travel_hong_kong" ||
            category == "meme" ||
            nsfwScore >= 0.5f

        return item.copy(
            category = category,
            confidence = confidence,
            caption = if (Categories.isExplicit(category)) "[redacted]"
                      else "Labels: " + texts.take(5).joinToString(", "),
            needsReview = needsReview,
            nsfwScore = nsfwScore,
            labels = texts.toList()
        )
    }

    fun close() {
        try { labeler.close() } catch (_: Exception) { }
        nsfw.close()
    }
}
