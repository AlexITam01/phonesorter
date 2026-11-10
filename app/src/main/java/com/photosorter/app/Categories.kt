package com.photosorter.app

/**
 * Categories — identical to the local PC script
 * ~/workspace/pc-folder-reorg/sort_photos_local.py
 *
 * NSFW subfolders are real categories, not free text: an image is
 * nsfw_suggestive/futanari or nsfw_explicit/futanari only when the
 * classifier (or the user, on review) says so explicitly. See
 * NsfwClassifier.kt for why on-device futanari detection is
 * approximate and always needs user review.
 */
object Categories {
    val ALL = listOf(
        "nsfw_suggestive/futanari",
        "nsfw_suggestive/other",
        "nsfw_explicit/futanari",
        "nsfw_explicit/other",
        "meme",
        "screenshot",
        "statistics_chart_document",
        "people_family_friends",
        "selfie_vtuber",
        "travel_hong_kong",
        "food",
        "school_coursework",
        "creative_3d_render",
        "game_fandom",
        "chomp_city_robotics",
        "davispedia",
        "pet_animal",
        "landscape_nature",
        "duplicate_near_duplicate",
        "blurry_low_quality",
        "other"
    )

    fun isNsfw(category: String) = category.startsWith("nsfw_")
    fun isExplicit(category: String) = category.startsWith("nsfw_explicit")

    /** Folder name under Pictures/Sorted/ — "/" becomes " - ". */
    fun folderName(category: String) = category.replace("/", " - ")
}
