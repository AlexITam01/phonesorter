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
                put("uri", it.uri.toString())
                put("display_name", it.displayName)
                put("size_bytes", it.sizeBytes)
                put("category", it.category)
                put("confidence", it.confidence)
                put("caption", caption)
                put("needs_review", it.needsReview)
                put("nsfw_score", it.nsfwScore)
            })
        }
        csv.writeText(sb.toString())
        json.writeText(arr.toString(2))
    }
}
