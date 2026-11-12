package com.photosorter.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * v0.4 — import a plan.csv produced by the Colab notebook
 * (colab/PhotoSorter_Colab.ipynb) and turn it into PhotoItems.
 *
 * Colab columns: file, name, sha256, size_mb, nsfw_score, nsfw_parts,
 * category, subcategory, confidence, caption, needs_review,
 * media_id, display_name.
 *
 * Matching: the notebook's manifest.csv carries the phone's
 * MediaStore id for every thumbnail, so `media_id` rebuilds the
 * exact photo Uri. If media_id is missing, fall back to matching
 * by display_name against one MediaStore query.
 *
 * Subcategories: a Colab row "meme" + "anime" becomes category
 * "meme/anime" (its own row in the UI, its own folder under
 * Pictures/Sorted/ — Categories.folderName() already turns "/"
 * into " - "). For nsfw rows the subcategory replaces the last
 * segment, so "nsfw_explicit/other" + "futanari" becomes
 * "nsfw_explicit/futanari", never "nsfw_explicit/other/futanari".
 * A subcategory of "other" (or empty) keeps the top category.
 *
 * Nothing is moved or deleted by importing. The imported plan is
 * written to plan.csv / plan.json (PlanStore) and shown in the UI
 * for review, exactly like a local scan's plan.
 */
object ColabPlan {

    fun parse(context: Context, csvUri: Uri): List<PhotoItem> {
        val rows = mutableListOf<Map<String, String>>()
        context.contentResolver.openInputStream(csvUri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                val header = parseCsvLine(reader.readLine() ?: return emptyList())
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val values = parseCsvLine(line ?: continue)
                    if (values.size < header.size) continue
                    rows.add(header.zip(values).toMap())
                }
            }
        }
        if (rows.isEmpty()) return emptyList()

        // Fallback map for rows without a media_id: display_name -> id.
        val byName = mutableMapOf<String, Long>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                byName.putIfAbsent(cursor.getString(nameCol) ?: "", cursor.getLong(idCol))
            }
        }

        val out = mutableListOf<PhotoItem>()
        for (row in rows) {
            val mediaId = row["media_id"]?.toLongOrNull()
                ?: byName[row["display_name"] ?: row["file"] ?: ""]
                ?: continue
            val top = row["category"]?.trim().orEmpty().ifEmpty { "other" }
            val sub = row["subcategory"]?.trim().orEmpty()
            val category = when {
                sub.isEmpty() || sub == "other" -> top
                top.startsWith("nsfw_") -> top.substringBeforeLast("/") + "/" + sub
                top.endsWith("/$sub") -> top
                else -> "$top/$sub"
            }
            val caption = if (Categories.isExplicit(category)) "[redacted]"
                else row["caption"].orEmpty()
            out.add(PhotoItem(
                id = mediaId,
                uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId),
                displayName = row["display_name"]?.ifEmpty { row["file"] } ?: "photo_$mediaId",
                sizeBytes = ((row["size_mb"]?.toDoubleOrNull() ?: 0.0) * 1024 * 1024).toLong(),
                dateTakenMs = 0L,
                width = 0,
                height = 0,
                category = category,
                confidence = row["confidence"]?.toFloatOrNull() ?: 0f,
                caption = caption,
                needsReview = row["needs_review"]?.equals("true", ignoreCase = true) == true,
                nsfwScore = row["nsfw_score"]?.toFloatOrNull() ?: 0f
            ))
        }
        return out
    }

    /** Minimal CSV parser: handles quoted fields and "" escapes. */
    fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    cur.append('"'); i += 2
                }
                c == '"' -> { inQuotes = !inQuotes; i++ }
                c == ',' && !inQuotes -> { out.add(cur.toString()); cur.clear(); i++ }
                else -> { cur.append(c); i++ }
            }
        }
        out.add(cur.toString())
        return out
    }
}
