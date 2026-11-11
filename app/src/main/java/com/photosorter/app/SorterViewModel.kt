package com.photosorter.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.3.0 — the ViewModel no longer runs scan/move itself.
 *
 * v0.2: scan/move ran in viewModelScope. That survived rotation,
 * but NOT leaving the app — swiping it away cancelled the work.
 *
 * v0.3: startScan() / moveCategory() only ENQUEUE WorkManager
 * work (SortWorker / MoveWorker, foreground, ExistingWorkPolicy.
 * KEEP). The workers run outside the Activity/ViewModel lifecycle,
 * so exiting the app does not stop them. MainActivity observes
 * workInfos(tag) and drives the green progress bar from the
 * workers' progress Data; when a worker finishes it calls
 * onScanWorkFinished() / onMoveWorkFinished() here (guarded by
 * work id, so a rotation re-emitting the same SUCCEEDED WorkInfo
 * does not reload the plan twice).
 *
 * The Classifier is no longer owned here — each worker creates
 * and closes its own Classifier. hasNsfwModel() therefore
 * creates a short-lived Classifier just to check for a bundled
 * model file; that is cheap (it only opens assets/nsfw.tflite
 * if present, and closes it again).
 *
 * Plan persistence (unchanged from v0.2, verified in v0.3):
 * the workers checkpoint plan.csv / plan.json every 10 photos,
 * and Deny/reassign write the plan immediately. Resume from
 * saved plan restores the last checkpoint after a shutdown.
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

    /** Work ids already handled, so a rotation does not double-apply a finish. */
    private val handledScanWorkIds = HashSet<java.util.UUID>()
    private val handledMoveWorkIds = HashSet<java.util.UUID>()

    /** Pending move callback, run when MoveWorker succeeds. Set by moveCategory(). */
    private var pendingMoveCallback: ((copied: Int, total: Int, folder: String) -> Unit)? = null
    private var pendingMovePhotos: List<PhotoItem> = emptyList()

    /** Cached: creating a Classifier just to probe for a model
     *  file would spin up an ML Kit labeler on every call, and
     *  MainActivity calls this on every summary refresh. */
    private val nsfwModelPresent: Boolean by lazy {
        val c = Classifier(getApplication())
        try { c.hasNsfwModel() } finally { c.close() }
    }

    fun hasNsfwModel(): Boolean = nsfwModelPresent

    fun hasSavedPlan(): Boolean = PlanStore.planExists(getApplication())

    /** All progress updates go through here — never touch a View from IO. */
    private fun setProgress(label: String, done: Int, total: Int, visible: Boolean = true) {
        _progressLabel.value = label
        _progressDone.value = done
        _progressTotal.value = total
        _progressVisible.value = visible
    }

    /** Live WorkInfo for a tag, observed by MainActivity for the progress bar. */
    fun workInfos(tag: String): LiveData<List<WorkInfo>> =
        WorkManager.getInstance(getApplication()).getWorkInfosByTagLiveData(tag)

    /** Enqueue the scan worker. Exiting the app does not stop it. */
    fun startScan() {
        if (_isScanning.value) return
        _isScanning.value = true
        setProgress("Starting scan… (keeps running if you leave the app)", 0, 0)
        val request = OneTimeWorkRequestBuilder<SortWorker>()
            .addTag(SortWorker.TAG_SCAN)
            .build()
        WorkManager.getInstance(getApplication()).enqueueUniqueWork(
            SortWorker.UNIQUE_SCAN, ExistingWorkPolicy.KEEP, request)
    }

    /** Called once per finished scan WorkInfo (guarded by work id). */
    fun onScanWorkFinished(workId: java.util.UUID) {
        if (!handledScanWorkIds.add(workId)) return
        _isScanning.value = false
        if (resumeFromSavedPlan()) {
            setProgress("Done. Plan saved (plan.csv / plan.json).",
                _items.value.size, _items.value.size, visible = false)
        } else {
            setProgress("Scan finished, but no saved plan was found.",
                0, 0, visible = false)
        }
    }

    /** Called once per failed/cancelled scan WorkInfo (guarded by work id). */
    fun onScanWorkFailed(workId: java.util.UUID) {
        if (!handledScanWorkIds.add(workId)) return
        _isScanning.value = false
        setProgress("Scan stopped — resume from the saved plan to pick up where it left off.",
            _progressDone.value, _progressTotal.value)
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
     * Enqueue the move worker for a category. The copy runs in the
     * foreground worker even if the user leaves the app. When it
     * succeeds, MainActivity calls onMoveWorkFinished(), which runs
     * [onCopied] on the Main thread so the Activity can ask
     * Android to delete the originals — the delete launcher
     * belongs to the Activity, not the worker.
     */
    fun moveCategory(photos: List<PhotoItem>, category: String,
                     onCopied: (copied: Int, total: Int, folder: String) -> Unit) {
        if (_isMoving.value || photos.isEmpty()) return
        _isMoving.value = true
        pendingMoveCallback = onCopied
        pendingMovePhotos = photos
        setProgress("Copying 0 / ${photos.size}", 0, photos.size)
        val arr = JSONArray()
        for (item in photos) {
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("uri", item.uri.toString())
                put("display_name", item.displayName)
            })
        }
        val request = OneTimeWorkRequestBuilder<MoveWorker>()
            .addTag(MoveWorker.TAG_MOVE)
            .setInputData(workDataOf(
                MoveWorker.KEY_CATEGORY to category,
                MoveWorker.KEY_PHOTOS_JSON to arr.toString()))
            .build()
        WorkManager.getInstance(getApplication()).enqueueUniqueWork(
            MoveWorker.UNIQUE_MOVE, ExistingWorkPolicy.KEEP, request)
    }

    /** Called once per finished move WorkInfo (guarded by work id). */
    fun onMoveWorkFinished(workId: java.util.UUID, output: androidx.work.Data) {
        if (!handledMoveWorkIds.add(workId)) return
        _isMoving.value = false
        val copied = output.getInt(MoveWorker.KEY_COPIED, 0)
        val total = output.getInt(MoveWorker.KEY_TOTAL, pendingMovePhotos.size)
        val folder = output.getString(MoveWorker.KEY_FOLDER)
            ?: Categories.folderName("")
        setProgress("Copied $copied / $total to Pictures/Sorted/$folder.",
            total, total, visible = false)
        val callback = pendingMoveCallback
        pendingMoveCallback = null
        callback?.invoke(copied, total, folder)
    }

    /** Called once per failed/cancelled move WorkInfo (guarded by work id). */
    fun onMoveWorkFailed(workId: java.util.UUID) {
        if (!handledMoveWorkIds.add(workId)) return
        _isMoving.value = false
        setProgress("Copy stopped — the files already copied stay in Pictures/Sorted/.",
            _progressDone.value, _progressTotal.value)
        pendingMoveCallback = null
    }

    /** Photos for the pending move, used by MainActivity to request deletion. */
    fun pendingMoveUris(): List<android.net.Uri> = pendingMovePhotos.map { it.uri }
}
