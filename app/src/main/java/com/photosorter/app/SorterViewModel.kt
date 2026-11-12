package com.photosorter.app

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.3.1 — Scan is BACK in the ViewModel (the proven v0.2 path).
 *
 * Why the revert: v0.3.0 moved scan/move to WorkManager foreground
 * workers, and Scan then crashed on Alex's S25 Ultra (API 34) with
 * no progress bar at all, where v0.2 worked. The v0.3 worker path
 * had three crash / no-progress defects that cannot be verified
 * in this VM (no Gradle build here):
 *
 * 1. FOREGROUND SERVICE TYPE (most likely crash): SortWorker and
 *    MoveWorker called setForeground() as the FIRST line of
 *    doWork(), outside any try/catch, but the manifest declared
 *    only FOREGROUND_SERVICE — not FOREGROUND_SERVICE_DATA_SYNC.
 *    On Android 14 (targetSdk 34) a foreground service must
 *    declare its type; starting one without the dataSync type
 *    permission throws, killing the worker process — i.e. the
 *    app — before any progress was ever published. Hence:
 *    crash on Scan tap, no bar.
 * 2. STALE WorkInfo: MainActivity observed getWorkInfosByTagLiveData
 *    and took infos.firstOrNull() — that can be an OLD SUCCEEDED /
 *    FAILED run, not the new one, so the bar was driven from stale
 *    data (or never driven at all).
 * 3. UNIQUE WORK KEEP: enqueueUniqueWork(KEEP) silently drops a
 *    new scan if any previous scan work with that name exists, so
 *    tapping Scan could do nothing at all, with no error.
 *    WorkManager.getInstance() on the tap path is also a Main-thread
 *    crash vector if initialisation ever fails.
 *
 * v0.3.1 therefore runs scan/move in viewModelScope again, exactly
 * as v0.2 did (rotation-safe, progress via StateFlow, checkpoint
 * every 10 photos). The workers are NOT enqueued for scan/move in
 * this version; SortWorker/MoveWorker remain in the repo with the
 * foreground-service fix below, but are not started, and
 * BootReceiver no longer re-enqueues them (it would re-trigger
 * the same crash on boot). Background-after-exit sorting is
 * PAUSED in v0.3.1 until the worker path is verified on a real
 * device — Scan working again comes first.
 *
 * Worker hardening (for a future re-enable, not used in v0.3.1):
 * - Manifest now declares FOREGROUND_SERVICE_DATA_SYNC.
 * - SortWorker/MoveWorker createForegroundInfo() now passes
 *   FOREGROUND_SERVICE_TYPE_DATA_SYNC on API 29+, and setForeground
 *   is wrapped in try/catch so a foreground failure degrades to
 *   a normal (non-foreground) worker instead of crashing.
 */
class SorterViewModel(app: Application) : AndroidViewModel(app) {

    private val _items = MutableStateFlow<List<PhotoItem>>(emptyList())
    val items: StateFlow<List<PhotoItem>> = _items.asStateFlow()

    private val _progressLabel = MutableStateFlow("")
    val progressLabel: StateFlow<String> = _progressLabel.asStateFlow()

    private val _progressDone = MutableStateFlow(0)
    val progressDone: StateFlow<Int> = _progressDone.asStateFlow()

    private val _progressTotal = MutableStateFlow(0)
    val progressTotal: StateFlow<Int> = _progressTotal.asStateFlow()

    private val _progressVisible = MutableStateFlow(false)
    val progressVisible: StateFlow<Boolean> = _progressVisible.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isMoving = MutableStateFlow(false)
    val isMoving: StateFlow<Boolean> = _isMoving.asStateFlow()

    // v0.4 — Colab status line, shown under the two blue buttons.
    private val _colabLabel = MutableStateFlow("")
    val colabLabel: StateFlow<String> = _colabLabel.asStateFlow()

    /** Classifier owned here, closed only in onCleared() (v0.2 fix). */
    private val classifier = Classifier(getApplication())

    fun hasNsfwModel(): Boolean = classifier.hasNsfwModel()

    fun hasSavedPlan(): Boolean = PlanStore.planExists(getApplication())

    /** All progress updates go through here — never touch a View from IO. */
    private fun setProgress(label: String, done: Int, total: Int, visible: Boolean = true) {
        _progressLabel.value = label
        _progressDone.value = done
        _progressTotal.value = total
        _progressVisible.value = visible
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { classifier.close() }
    }

    /** v0.2 scan path, restored: runs in viewModelScope (rotation-safe). */
    fun startScan() {
        if (_isScanning.value) return
        _isScanning.value = true
        // Show the bar IMMEDIATELY, before any photo is read, so the
        // user sees "Classified 0 / …" from the first moment.
        setProgress("Starting scan…", 0, 0, visible = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val found = queryPhotos()
                withContext(Dispatchers.Main) {
                    setProgress("Classified 0 / ${found.size}", 0, found.size, visible = true)
                }
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
                    if (done % 10 == 0 || done == found.size) {
                        val snapshot = classified.toList()
                        runCatching { PlanStore.writePlan(getApplication(), snapshot) }
                        withContext(Dispatchers.Main) {
                            setProgress("Classified $done / ${found.size}",
                                done, found.size, visible = true)
                        }
                    }
                }
                runCatching { PlanStore.writePlan(getApplication(), classified.toList()) }
                withContext(Dispatchers.Main) {
                    _items.value = classified
                    _isScanning.value = false
                    setProgress("Done: ${classified.size} photos. Plan saved (plan.csv / plan.json).",
                        classified.size, classified.size, visible = false)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isScanning.value = false
                    setProgress("Scan failed: ${e.message}",
                        _progressDone.value, _progressTotal.value)
                }
            }
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
        getApplication<Application>().contentResolver.query(
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
                getApplication<Application>().contentResolver
                    .loadThumbnail(item.uri, Size(384, 384), null)
            } else {
                getApplication<Application>().contentResolver
                    .openInputStream(item.uri)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
            }
        } catch (_: Exception) { null }
    }

    /** Restore the last checkpointed plan after the app was killed / shut down. */
    fun resumeFromSavedPlan(): Boolean {
        val saved = runCatching { PlanStore.readPlan(getApplication()) }.getOrNull()
            ?: return false
        if (saved.isEmpty()) return false
        _items.value = saved
        setProgress("Resumed from saved plan: ${saved.size} photos.",
            saved.size, saved.size, visible = false)
        return true
    }

    fun exportPlan() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { PlanStore.writePlan(getApplication(), _items.value) }
        }
    }

    // ---- v0.4 Colab path ----
    // Upload and Sort run as WorkManager workers, not in
    // viewModelScope, so they survive rotation AND leaving the
    // app AND the system killing the process. REPLACE (not KEEP)
    // on a user tap: v0.3.0's KEEP silently dropped a new scan
    // when old work with the same name existed. BootReceiver
    // re-enqueues with KEEP, so a boot never duplicates a run.

    fun enqueueColabUpload() {
        _colabLabel.value = "Building thumbnails zip in the background…"
        runCatching {
            WorkManager.getInstance(getApplication()).enqueueUniqueWork(
                ColabUploadWorker.UNIQUE_UPLOAD,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ColabUploadWorker>()
                    .addTag(ColabUploadWorker.TAG_UPLOAD).build())
        }.onFailure { _colabLabel.value = "Upload could not start: ${it.message}" }
    }

    fun enqueueColabSort() {
        if (_items.value.isEmpty()) {
            _colabLabel.value = "No plan loaded — tap Sort and pick the Colab plan.csv first."
            return
        }
        _colabLabel.value = "Sorting from Colab plan in the background…"
        runCatching {
            WorkManager.getInstance(getApplication()).enqueueUniqueWork(
                ColabSortWorker.UNIQUE_SORT,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ColabSortWorker>()
                    .addTag(ColabSortWorker.TAG_SORT).build())
        }.onFailure { _colabLabel.value = "Sort could not start: ${it.message}" }
    }

    /** Import a plan.csv downloaded from Colab. Returns true if any rows loaded. */
    fun importColabPlan(uri: Uri, onDone: (Boolean, Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val items = runCatching { ColabPlan.parse(getApplication(), uri) }.getOrDefault(emptyList())
            if (items.isNotEmpty()) {
                runCatching { PlanStore.writePlan(getApplication(), items) }
            }
            withContext(Dispatchers.Main) {
                if (items.isNotEmpty()) {
                    _items.value = items
                    _colabLabel.value = "Colab plan loaded: ${items.size} photos. Tap Sort to sort from it."
                } else {
                    _colabLabel.value = "That plan.csv had no photos I could match on this phone."
                }
                onDone(items.isNotEmpty(), items.size)
            }
        }
    }

    /** Per-photo Deny: needsReview photo -> category "other", nothing deleted/moved. */
    fun deny(item: PhotoItem) {
        val updated = _items.value.map {
            if (it.id == item.id) it.copy(category = "other", needsReview = false,
                caption = "Denied by user — moved to other") else it
        }
        applyPlanUpdate(updated)
    }

    fun reassign(item: PhotoItem, category: String) {
        val updated = _items.value.map {
            if (it.id == item.id) it.copy(category = category, needsReview = false,
                caption = if (Categories.isExplicit(category)) "[redacted]"
                    else "Reassigned by user") else it
        }
        applyPlanUpdate(updated)
    }

    private fun applyPlanUpdate(updated: List<PhotoItem>) {
        _items.value = updated
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { PlanStore.writePlan(getApplication(), updated) }
        }
    }

    /**
     * v0.2 move path, restored: copy runs in viewModelScope.
     * When it succeeds, [onCopied] runs on the Main thread so the
     * Activity can ask Android to delete the originals.
     */
    fun moveCategory(photos: List<PhotoItem>, category: String,
                     onCopied: (copied: Int, total: Int, folder: String) -> Unit) {
        if (_isMoving.value || photos.isEmpty()) return
        _isMoving.value = true
        val folder = Categories.folderName(category)
        setProgress("Copying 0 / ${photos.size}", 0, photos.size, visible = true)
        viewModelScope.launch(Dispatchers.IO) {
            var copied = 0
            for ((index, item) in photos.withIndex()) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, item.displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH,
                            "Pictures/Sorted/$folder")
                    }
                    val dest = getApplication<Application>().contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (dest != null) {
                        val input = getApplication<Application>().contentResolver
                            .openInputStream(item.uri)
                        val output = if (input != null) {
                            getApplication<Application>().contentResolver
                                .openOutputStream(dest)
                        } else null
                        if (input != null && output != null) {
                            input.use { input.copyTo(output) }
                            output.close()
                            copied++
                        } else {
                            input?.close()
                            output?.close()
                            getApplication<Application>().contentResolver
                                .delete(dest, null, null)
                        }
                    }
                } catch (_: Exception) {
                    // Keep going; the result reports copied/total.
                }
                val done = index + 1
                if (done % 10 == 0 || done == photos.size) {
                    withContext(Dispatchers.Main) {
                        setProgress("Copying $done / ${photos.size}",
                            done, photos.size, visible = true)
                    }
                }
            }
            val finalCopied = copied
            withContext(Dispatchers.Main) {
                _isMoving.value = false
                setProgress("Copied $finalCopied / ${photos.size} to Pictures/Sorted/$folder.",
                    photos.size, photos.size, visible = false)
                onCopied(finalCopied, photos.size, folder)
            }
        }
    }

    /** Photos for the pending move, used by MainActivity to request deletion. */
    fun pendingMoveUris(photos: List<PhotoItem>): List<android.net.Uri> =
        photos.map { it.uri }
}
