package com.photosorter.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * v0.3.0 — the scan/classify work, run by WorkManager.
 *
 * Why a Worker, not the ViewModel (v0.2): a ViewModel survives
 * rotation, but NOT leaving the app. When Alex swiped the app away
 * or the system killed it, viewModelScope was cancelled and the
 * scan stopped. WorkManager runs outside the Activity/ViewModel
 * lifecycle: once enqueued (ExistingWorkPolicy.KEEP, unique name
 * "photo-sort-scan"), the scan keeps running after the app is
 * exited, and Android will restart it if the process is killed
 * (subject to the honest limits in the README: a force-stop, or
 * aggressive battery optimisation, can still pause it).
 *
 * Foreground work: the worker calls setForeground() with a
 * low-importance notification ("Sorting photos… N / total") so
 * Android treats it as user-visible work and does not kill it
 * immediately. No internet permission is used; everything is
 * on-device, thumbnails only, exactly as in v0.2.
 *
 * Progress: the worker publishes setProgress() with keys
 * KEY_LABEL / KEY_DONE / KEY_TOTAL. MainActivity observes the
 * WorkInfo for tag TAG_SCAN and drives the green progress bar
 * from that data — the same "Classified N / total" numerals as
 * v0.2, now fed by the worker instead of the ViewModel.
 *
 * Persistence: the plan is checkpointed to plan.csv / plan.json
 * every 10 photos (and at the end), exactly as in v0.2. If the
 * phone shuts down mid-scan, WorkState.scan_in_progress stays
 * true, BootReceiver re-enqueues this worker on boot, and the
 * user can also tap "Resume from saved plan" to see the last
 * checkpoint without waiting for a re-scan.
 */
class SortWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        const val TAG_SCAN = "sort-scan"
        const val UNIQUE_SCAN = "photo-sort-scan"
        const val KEY_LABEL = "label"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_CLASSIFIED = "classified"
        private const val CHANNEL_ID = "photo_sorter_work"
        private const val NOTIFICATION_ID = 1001
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
        // v0.3.1 fix: Android 14 (targetSdk 34) REQUIRES a foreground
        // service type. v0.3.0 used the no-type ForegroundInfo and the
        // manifest declared only FOREGROUND_SERVICE, so setForeground()
        // threw and killed the worker process on Scan tap. Pass
        // FOREGROUND_SERVICE_TYPE_DATA_SYNC explicitly (manifest now
        // declares FOREGROUND_SERVICE_DATA_SYNC too).
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        // v0.3.1 fix: setForeground() was the FIRST line of doWork(),
        // outside any try/catch — if it threw (see above), the whole
        // worker/app died before any progress was published. Degrade
        // to a normal worker instead of crashing.
        runCatching { setForeground(createForegroundInfo("Sorting photos…")) }
        WorkState.setScanInProgress(applicationContext, true)
        val classifier = Classifier(applicationContext)
        return try {
            val found = queryPhotos()
            setProgress(workDataOf(
                KEY_LABEL to "Classified 0 / ${found.size}",
                KEY_DONE to 0, KEY_TOTAL to found.size))
            val classified = mutableListOf<PhotoItem>()
            val seenHashes = HashSet<String>()
            for ((index, item) in found.withIndex()) {
                val done = index + 1
                try {
                    val thumb = loadThumbnail(item)
                    if (thumb != null && !thumb.isRecycled) {
                        val small = classifier.thumbnail(thumb, 384)
                        if (!small.isRecycled) {
                            val hash = classifier.thumbHash(small)
                            if (hash.isNotEmpty() && !seenHashes.add(hash)) {
                                classified.add(item.copy(
                                    category = "duplicate_near_duplicate",
                                    confidence = 0.6f,
                                    caption = "Same thumbnail as an earlier photo",
                                    needsReview = true, thumbHash = hash))
                            } else {
                                classified.add(
                                    classifier.classify(item.copy(thumbHash = hash), small))
                            }
                        }
                        if (small !== thumb && !small.isRecycled) small.recycle()
                        if (!thumb.isRecycled) thumb.recycle()
                    } else {
                        classified.add(item.copy(category = "other", confidence = 0f,
                            caption = "Thumbnail could not be read",
                            needsReview = true))
                    }
                } catch (e: Exception) {
                    classified.add(item.copy(category = "other", confidence = 0f,
                        caption = "Classification failed: ${e.message}",
                        needsReview = true))
                }
                // Publish + checkpoint every 10, so a shutdown never
                // loses more than 10 photos of work.
                if (done % 10 == 0 || done == found.size) {
                    val snapshot = classified.toList()
                    setProgress(workDataOf(
                        KEY_LABEL to "Classified $done / ${found.size}",
                        KEY_DONE to done, KEY_TOTAL to found.size))
                    runCatching { PlanStore.writePlan(applicationContext, snapshot) }
                }
            }
            runCatching { PlanStore.writePlan(applicationContext, classified.toList()) }
            Result.success(workDataOf(KEY_CLASSIFIED to classified.size))
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_LABEL to "Scan failed: ${e.message}"))
        } finally {
            WorkState.setScanInProgress(applicationContext, false)
            classifier.close()
        }
    }

    private fun queryPhotos(): List<PhotoItem> {
        val out = mutableListOf<PhotoItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
        applicationContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val wCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val hCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                out.add(PhotoItem(
                    id = id,
                    uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    displayName = cursor.getString(nameCol) ?: "photo_$id",
                    sizeBytes = cursor.getLong(sizeCol),
                    dateTakenMs = cursor.getLong(dateCol),
                    width = cursor.getInt(wCol),
                    height = cursor.getInt(hCol)
                ))
            }
        }
        return out
    }

    private fun loadThumbnail(item: PhotoItem): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                applicationContext.contentResolver
                    .loadThumbnail(item.uri, Size(384, 384), null)
            } else {
                applicationContext.contentResolver
                    .openInputStream(item.uri)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
            }
        } catch (_: Exception) { null }
    }
}
