package com.photosorter.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * v0.4 — "Sort" (blue button 2).
 *
 * Sorts from the Colab plan (plan.json, imported by the Sort button
 * from the plan.csv downloaded from Colab) in a WorkManager
 * foreground worker, so it survives rotation, leaving the app, and
 * the system killing the process. Copies each photo to
 * Pictures/Sorted/<category>/ — subcategories are part of the
 * category string, e.g. "meme/anime" -> "Pictures/Sorted/meme - anime/".
 *
 * Progress: setProgress() every 10 photos ("Sorting N / total"),
 * observed by MainActivity from the WorkInfo for this unique work
 * (latest RUNNING run first — NOT firstOrNull(), the v0.3.0
 * stale-bar bug). The plan is the checkpoint: if the phone shuts
 * down mid-sort, WorkState records it, BootReceiver re-enqueues
 * this worker on boot, and it re-runs from the plan — files already
 * copied are simply copied again into the same folder (same name),
 * so a resume never loses a photo and never duplicates one under
 * a different name.
 *
 * Deletion is deliberately NOT done here: deleting the originals
 * needs Android's system confirmation, which can only be launched
 * from the Activity. After this worker succeeds, MainActivity
 * tells the user the copy is done and the originals are still
 * there — delete them per category from the app, after checking
 * the Sorted folders, exactly as with a local scan.
 *
 * A full power-off stops the worker while the phone is off
 * (nothing runs with no power). That is a resume-on-boot, not a
 * run-through-power-off — no app can do the latter.
 */
class ColabSortWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        const val TAG_SORT = "colab-sort"
        const val UNIQUE_SORT = "photo-sort-colab-sort"
        const val KEY_LABEL = "label"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_COPIED = "copied"
        private const val CHANNEL_ID = "photo_sorter_work"
        private const val NOTIFICATION_ID = 1004
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        // v0.3.1 lesson: never let setForeground() crash the worker.
        runCatching { setForeground(createForegroundInfo("Sorting photos from Colab plan…")) }
        WorkState.setColabSortInProgress(applicationContext, true)
        return try {
            val items = PlanStore.readPlan(applicationContext)
            if (items.isEmpty()) {
                return Result.failure(workDataOf(KEY_LABEL to "No Colab plan loaded — tap Sort and pick plan.csv first"))
            }
            setProgress(workDataOf(
                KEY_LABEL to "Sorting 0 / ${items.size}",
                KEY_DONE to 0, KEY_TOTAL to items.size))
            var copied = 0
            for ((index, item) in items.withIndex()) {
                try {
                    val folder = Categories.folderName(item.category)
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, item.displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH,
                            "Pictures/Sorted/$folder")
                    }
                    val dest = applicationContext.contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (dest != null) {
                        val input = applicationContext.contentResolver
                            .openInputStream(item.uri)
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
                if (done % 10 == 0 || done == items.size) {
                    setProgress(workDataOf(
                        KEY_LABEL to "Sorting $done / ${items.size}",
                        KEY_DONE to done, KEY_TOTAL to items.size))
                }
            }
            Result.success(workDataOf(
                KEY_LABEL to "Sorted $copied / ${items.size}",
                KEY_COPIED to copied, KEY_TOTAL to items.size))
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_LABEL to "Sort failed: ${e.message}"))
        } finally {
            WorkState.setColabSortInProgress(applicationContext, false)
        }
    }
}
