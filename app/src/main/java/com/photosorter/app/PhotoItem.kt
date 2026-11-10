package com.photosorter.app

import android.net.Uri

data class PhotoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val dateTakenMs: Long,
    val width: Int,
    val height: Int,
    /** MD5 of the thumbnail bytes — used only for duplicate spotting. */
    val thumbHash: String = "",
    var category: String = "other",
    var confidence: Float = 0f,
    /** Short neutral caption. Always "[redacted]" for nsfw_explicit. */
    var caption: String = "",
    var needsReview: Boolean = false,
    var nsfwScore: Float = 0f,
    var labels: List<String> = emptyList()
)
