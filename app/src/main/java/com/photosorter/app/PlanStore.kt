package com.photosorter.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The plan is written BEFORE anything is moved or deleted.
 * plan.csv / plan.json live in the app's external files dir
 * (Android/data/com.photosorter.app/files/) and are the only
 * output the app produces. Approved moves happen only after
 * the user taps Approve for a category in MainActivity.
 * NSFW explicit rows have caption "[redacted]" in the plan.
 */
object PlanStore {

    fun planDir(context: Context): File =
        File(context.getExternalFilesDir(null), "plans").apply { mkdirs() }

    fun planExists(context: Context): Boolean {
        val dir = File(context.getExternalFilesDir(null), "plans")
        return File(dir, "plan.json").exists() || File(dir, "plan.csv").exists()
    }

    /**
     * v0.2.0 — resume support. Reads the last checkpointed plan.json
     * (written every 10 photos during a scan, and after every
     * Deny/reassign). Returns an empty list if there is no saved plan
     * or it cannot be parsed — the caller then scans from scratch.
     * PhotoItem fields not stored in the JSON default sensibly.
     */
    fun readPlan(context: Context): List<PhotoItem> {
        val file = File(File(context.getExternalFilesDir(null), "plans"), "plan.json")
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        val out = mutableListOf<PhotoItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val uri = android.net.Uri.parse(o.optString("uri", ""))
            // Skip rows with no usable id/uri — they cannot be moved.
            val id = o.optLong("id", -1L)
            if (uri.toString().isEmpty() || id < 0) continue
            out.add(PhotoItem(
                id = id,
                uri = uri,
                displayName = o.optString("display_name", "photo_$id"),
                sizeBytes = o.optLong("size_bytes", 0L),
                dateTakenMs = o.optLong("date_taken_ms", 0L),
                width = o.optInt("width", 0),
                height = o.optInt("height", 0),
                category = o.optString("category", "other"),
                confidence = o.optDouble("confidence", 0.0).toFloat(),
                caption = o.optString("caption", ""),
                needsReview = o.optBoolean("needs_review", false),
                nsfwScore = o.optDouble("nsfw_score", 0.0).toFloat(),
                labels = o.optString("labels", "").split(";").filter { it.isNotEmpty() }
            ))
        }
        return out
    }

    fun writePlan(context: Context, items: List<PhotoItem>) {
        val dir = planDir(context)
        val csv = File(dir, "plan.csv")
        val json = File(dir, "plan.json")
        val header = "file,display_name,size_bytes,date_taken_ms,category," +
            "confidence,caption,needs_review,nsfw_score,labels\n"
        val sb = StringBuilder(header)
        val arr = JSONArray()
        for (it in items) {
            val caption = if (Categories.isExplicit(it.category)) "[redacted]" else it.caption
            fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
            sb.append(listOf(
                q(it.uri.toString()), q(it.displayName), it.sizeBytes.toString(),
                it.dateTakenMs.toString(), q(it.category), it.confidence.toString(),
                q(caption), it.needsReview.toString(), it.nsfwScore.toString(),
                q(it.labels.joinToString(";"))
            ).joinToString(",")).append("\n")
            arr.put(JSONObject().apply {
                // v0.2.0: store everything readPlan() needs to rebuild
                // the item — id, dimensions and labels were missing
                // in v0.1, which would have made resume drop every row.
                put("id", it.id)
                put("uri", it.uri.toString())
                put("display_name", it.displayName)
                put("size_bytes", it.sizeBytes)
                put("date_taken_ms", it.dateTakenMs)
                put("width", it.width)
                put("height", it.height)
                put("category", it.category)
                put("confidence", it.confidence)
                put("caption", caption)
                put("needs_review", it.needsReview)
                put("nsfw_score", it.nsfwScore)
                put("labels", it.labels.joinToString(";"))
            })
        }
        csv.writeText(sb.toString())
        json.writeText(arr.toString(2))
    }
}
