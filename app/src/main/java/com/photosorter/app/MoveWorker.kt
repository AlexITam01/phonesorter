package com.photosorter.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONArray

/**
 * v0.3.0 — the approve-move copy, run by WorkManager.
 *
 * Same reason as SortWorker: in v0.2 the copy ran in
 * viewModelScope, so leaving the app mid-copy stopped it.
 * MoveWorker copies one approved category to
 * Pictures/Sorted/<category>/ in a foreground worker, so
 * exiting the app does not stop the copy.
 *
 * Input (Data): KEY_CATEGORY (String) and KEY_PHOTOS_JSON — a
 * JSON array of {"id": Long, "uri": String, "display_name":
 * String} for the photos in that category. Output (Data):
 * KEY_COPIED / KEY_TOTAL / KEY_FOLDER.
 *
 * Deletion is deliberately NOT done here: deleting the
 * originals needs Android's system confirmation, which can
 * only be launched from the Activity. When this worker
 * succeeds, MainActivity observes the WorkInfo, shows
 * "Copied N / total", and asks Android to delete the
 * originals — exactly as in v0.2, only the copy now survives
 * leaving the app.
 */
class MoveWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        const val TAG_MOVE = "sort-move"
        const val UNIQUE_MOVE = "photo-sort-move"
        const val KEY_CATEGORY = "category"
        const val KEY_PHOTOS_JSON = "photos_json"
        const val KEY_LABEL = "label"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_COPIED = "copied"
        const val KEY_FOLDER = "folder"
        private const val CHANNEL_ID = "photo_sorter_work"
        private const val NOTIFICATION_ID = 1002
    }

    private fun createForegroundInfo(label: String): ForegroundInfo {
        val manager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Photo Sorter sorting",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows while Photo Sorter is sorting photos." }
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Photo Sorter")
            .setContentText(label)
            .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result {
        val category = inputData.getString(KEY_CATEGORY) ?: return Result.failure()
        val folder = Categories.folderName(category)
        val photosJson = inputData.getString(KEY_PHOTOS_JSON) ?: "[]"
        val photos = runCatching {
            val arr = JSONArray(photosJson)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Triple(o.optLong("id", -1L),
                    Uri.parse(o.optString("uri", "")),
                    o.optString("display_name", "photo"))
            }.filter { it.first >= 0 && it.second.toString().isNotEmpty() }
        }.getOrElse { emptyList() }
        if (photos.isEmpty()) return Result.failure()

        setForeground(createForegroundInfo("Copying photos to $folder…"))
        WorkState.setMoveInProgress(applicationContext, true)
        return try {
            var copied = 0
            for ((index, item) in photos.withIndex()) {
                val (id, uri, displayName) = item
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH,
                            "Pictures/Sorted/$folder")
                    }
                    val dest = applicationContext.contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (dest != null) {
                        val input = applicationContext.contentResolver
                            .openInputStream(uri)
                        val output = if (input != null) {
                            applicationContext.contentResolver.openOutputStream(dest)
                        } else null
                        if (input != null && output != null) {
                            input.use { input.copyTo(output) }
                            output.close()
                            copied++
                        } else {
                            input?.close()
                            output?.close()
                            applicationContext.contentResolver.delete(dest, null, null)
                        }
                    }
                } catch (_: Exception) {
                    // Keep going; the result reports copied/total.
                }
                val done = index + 1
                if (done % 10 == 0 || done == photos.size) {
                    setProgress(workDataOf(
                        KEY_LABEL to "Copying $done / ${photos.size}",
                        KEY_DONE to done, KEY_TOTAL to photos.size))
                }
            }
            Result.success(workDataOf(
                KEY_COPIED to copied, KEY_TOTAL to photos.size,
                KEY_FOLDER to folder))
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_LABEL to "Copy failed: ${e.message}"))
        } finally {
            WorkState.setMoveInProgress(applicationContext, false)
        }
    }
}
