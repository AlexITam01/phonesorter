package com.photosorter.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
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
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v0.4 — "Upload to notebook" (blue button 1).
 *
 * Builds the Colab input in a WorkManager foreground worker, so it
 * survives rotation, leaving the app, and the system killing the
 * process. The zip is written to Download/PhotoSorter-Colab/
 * (thumbnails.zip) with a manifest.csv inside it:
 *   zip_name, media_id, display_name
 * Every thumbnail is max 384 px, JPEG quality 80 — the same
 * thumbnail the on-device scan uses, never the full photo.
 *
 * HONEST LIMITS — read before tapping the button:
 * - A Colab session has no inbound upload endpoint, so the phone
 *   cannot push the zip straight into Colab. This worker does the
 *   on-phone half (zip + progress) and, on success, MainActivity
 *   opens the notebook URL — you then tap Upload in Colab and pick
 *   thumbnails.zip. That last tap is in Colab's UI, not in this app.
 * - Uploading thumbnails is uploading the photos, just smaller.
 *   NSFW-suspect images are INCLUDED here on purpose (Colab's
 *   NudeNet is what sorts them) — that is the opt-in you make by
 *   tapping this button. The local Scan path stays fully on-device
 *   and is still the default; this button never runs on its own.
 * - A full power-off stops the worker while the phone is off
 *   (nothing runs with no power). WorkState records that an upload
 *   was in flight, BootReceiver re-enqueues it on boot, and the
 *   zip is rebuilt from scratch — a partial zip is never used.
 *
 * Progress: setProgress() every 10 photos ("Uploading N / total"),
 * observed by MainActivity from the WorkInfo for this unique work
 * (latest RUNNING run first — NOT firstOrNull(), which was the
 * v0.3.0 stale-bar bug).
 */
class ColabUploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    companion object {
        const val TAG_UPLOAD = "colab-upload"
        const val UNIQUE_UPLOAD = "photo-sort-colab-upload"
        const val KEY_LABEL = "label"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_COUNT = "count"
        const val KEY_ZIP_URI = "zip_uri"
        const val COLAB_URL =
            "https://colab.research.google.com/github/AlexITam01/phonesorter/blob/main/colab/PhotoSorter_Colab.ipynb"
        private const val CHANNEL_ID = "photo_sorter_work"
        private const val NOTIFICATION_ID = 1003
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
            .setSmallIcon(android.R.drawable.ic_menu_upload)
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
        runCatching { setForeground(createForegroundInfo("Building thumbnails zip…")) }
        WorkState.setColabUploadInProgress(applicationContext, true)
        return try {
            val found = queryPhotos()
            setProgress(workDataOf(
                KEY_LABEL to "Uploading 0 / ${found.size}",
                KEY_DONE to 0, KEY_TOTAL to found.size))

            // Write the zip straight into Downloads via MediaStore,
            // so the user can pick it in Colab's upload dialog.
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "thumbnails.zip")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/PhotoSorter-Colab")
            }
            val zipUri = applicationContext.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return Result.failure(workDataOf(KEY_LABEL to "Could not create thumbnails.zip in Downloads"))
            val outStream = applicationContext.contentResolver.openOutputStream(zipUri)
                ?: return Result.failure(workDataOf(KEY_LABEL to "Could not open thumbnails.zip for writing"))

            var count = 0
            val classifier = Classifier(applicationContext)
            try {
            outStream.use { raw ->
                ZipOutputStream(raw).use { zip ->
                    val manifest = StringBuilder("zip_name,media_id,display_name\n")
                    for ((index, item) in found.withIndex()) {
                        try {
                            val thumb = loadThumbnail(item) ?: continue
                            val small = classifier.thumbnail(thumb, 384)
                            val bytes = ByteArrayOutputStream().also {
                                small.compress(Bitmap.CompressFormat.JPEG, 80, it)
                            }.toByteArray()
                            if (!small.isRecycled) small.recycle()
                            if (!thumb.isRecycled) thumb.recycle()
                            val zipName = "${item.id}_${sanitise(item.displayName)}.jpg"
                            zip.putNextEntry(ZipEntry(zipName))
                            zip.write(bytes)
                            zip.closeEntry()
                            manifest.append("\"$zipName\",${item.id},\"${item.displayName.replace("\"", "\"\"")}\"\n")
                            count++
                        } catch (_: Exception) {
                            // Skip one unreadable photo; keep going.
                        }
                        val done = index + 1
                        if (done % 10 == 0 || done == found.size) {
                            setProgress(workDataOf(
                                KEY_LABEL to "Uploading $done / ${found.size}",
                                KEY_DONE to done, KEY_TOTAL to found.size))
                        }
                    }
                    zip.putNextEntry(ZipEntry("manifest.csv"))
                    zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            } finally {
                runCatching { classifier.close() }
            }
            Result.success(workDataOf(
                KEY_LABEL to "Zip ready: $count thumbnails",
                KEY_COUNT to count, KEY_ZIP_URI to zipUri.toString()))
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_LABEL to "Upload failed: ${e.message}"))
        } finally {
            WorkState.setColabUploadInProgress(applicationContext, false)
        }
    }

    private fun sanitise(name: String): String {
        val base = name.substringBeforeLast(".").ifEmpty { "photo" }
        return base.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60)
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
