package com.photosorter.app

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.2.0 — work lives here, NOT in MainActivity.
 *
 * Why: in v0.1 scan/move ran in the Activity's lifecycleScope, so
 * rotating the phone (or leaving the app) cancelled the coroutine
 * mid-scan, and onDestroy() closed the Classifier while it was
 * still in use — the crash Alex reported. A ViewModel survives
 * rotation, so scan/move keep running and the UI just re-attaches.
 *
 * Honest limit: a ViewModel survives rotation, NOT a force-kill.
 * If Android kills the app / the user swipes it away, the work
 * stops. That is why the plan is checkpointed to disk every
 * 10 photos (and after every Deny/reassign): on next launch
 * "Resume from saved plan" restores the last checkpoint instead
 * of starting over. No WorkManager is implemented in v0.2.0 —
 * background work after a force-kill is NOT claimed.
 */
class SorterViewModel(app: Application) : AndroidViewModel(app) {

    private val classifier = Classifier(app.applicationContext)

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

    fun hasNsfwModel(): Boolean = classifier.hasNsfwModel()
    fun hasSavedPlan(): Boolean = PlanStore.planExists(getApplication())

    /** All progress updates go through here — never touch a View from IO. */
    private fun setProgress(label: String, done: Int, total: Int, visible: Boolean = true) {
        _progressLabel.value = label
        _progressDone.value = done
        _progressTotal.value = total
        _progressVisible.value = visible
    }

    fun startScan() {
        if (_isScanning.value) return
        _isScanning.value = true
        viewModelScope.launch {
            setProgress("Scanning MediaStore…", 0, 0)
            try {
                val found = withContext(Dispatchers.IO) { queryPhotos() }
                setProgress("Classified 0 / ${found.size}", 0, found.size)
                val classified = mutableListOf<PhotoItem>()
                val seenHashes = HashSet<String>()
                for ((index, item) in found.withIndex()) {
                    val done = index + 1
                    try {
                        val thumb = withContext(Dispatchers.IO) { loadThumbnail(item) }
                        if (thumb != null && !thumb.isRecycled) {
                            val small = classifier.thumbnail(thumb, 384)
                            if (!small.isRecycled) {
                                val hash = classifier.thumbHash(small)
                                // Empty hash = unreadable/recycled thumbnail:
                                // never call it a duplicate.
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
                            // Recycle only bitmaps we own and only once.
                            // classifier.thumbnail() returns `thumb` itself
                            // when it is already small — never recycle twice.
                            if (small !== thumb && !small.isRecycled) small.recycle()
                            if (!thumb.isRecycled) thumb.recycle()
                        } else {
                            // Thumbnail unreadable: keep the photo, flag it.
                            classified.add(item.copy(category = "other", confidence = 0f,
                                caption = "Thumbnail could not be read",
                                needsReview = true))
                        }
                    } catch (e: Exception) {
                        classified.add(item.copy(category = "other", confidence = 0f,
                            caption = "Classification failed: ${e.message}",
                            needsReview = true))
                    }
                    // Publish + checkpoint every 10, so rotation / a kill
                    // never loses more than 10 photos of work.
                    if (done % 10 == 0 || done == found.size) {
                        val snapshot = classified.toList()
                        _items.value = snapshot
                        setProgress("Classified $done / ${found.size}", done, found.size)
                        withContext(Dispatchers.IO) {
                            runCatching { PlanStore.writePlan(getApplication(), snapshot) }
                        }
                    }
                }
                _items.value = classified.toList()
                setProgress("Done: ${classified.size} photos. Plan saved (plan.csv / plan.json).",
                    classified.size, classified.size, visible = false)
            } catch (e: Exception) {
                setProgress("Scan failed: ${e.message}", _progressDone.value,
                    _progressTotal.value)
            } finally {
                _isScanning.value = false
            }
        }
    }

    /** Restore the last checkpointed plan after the app was killed. */
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
     * Copy a category to Pictures/Sorted/<category>/ with progress.
     * Calls [onCopied] on the Main thread when copying is done, so the
     * Activity (not the ViewModel) can ask Android to delete the
     * originals — the delete launcher belongs to the Activity.
     * Returns via callback; never touches a View directly.
     */
    fun moveCategory(photos: List<PhotoItem>, category: String,
                     onCopied: (copied: Int, total: Int, folder: String) -> Unit) {
        if (_isMoving.value || photos.isEmpty()) return
        _isMoving.value = true
        viewModelScope.launch {
            val folder = Categories.folderName(category)
            setProgress("Copying 0 / ${photos.size}", 0, photos.size)
            var copied = 0
            withContext(Dispatchers.IO) {
                for ((index, item) in photos.withIndex()) {
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, item.displayName)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            put(MediaStore.Images.Media.RELATIVE_PATH,
                                "Pictures/Sorted/$folder")
                        }
                        val dest = getApplication<Application>().contentResolver
                            .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
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
                                // No input/output stream: count as failed,
                                // remove the empty destination entry.
                                input?.close()
                                output?.close()
                                getApplication<Application>().contentResolver
                                    .delete(dest, null, null)
                            }
                        }
                    } catch (_: Exception) {
                        // Keep going; the toast reports copied/total.
                    }
                    val done = index + 1
                    if (done % 10 == 0 || done == photos.size) {
                        setProgress("Copying $done / ${photos.size}", done, photos.size)
                    }
                }
            }
            val doneCount = copied
            setProgress("Copied $doneCount / ${photos.size} to Pictures/Sorted/$folder.",
                photos.size, photos.size, visible = false)
            _isMoving.value = false
            onCopied(doneCount, photos.size, folder)
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
            if (android.os.Build.VERSION.SDK_INT >= 29) {
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

    override fun onCleared() {
        // Classifier is closed here, not in Activity.onDestroy() —
        // closing it on rotation was one of the v0.1 crash causes.
        classifier.close()
        super.onCleared()
    }
}
