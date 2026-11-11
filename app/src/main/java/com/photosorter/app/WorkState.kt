package com.photosorter.app

import android.content.Context

/**
 * v0.3.0 — durable "is sorting in progress" flags.
 *
 * Why a flag, not just WorkManager's own state: after a reboot
 * WorkManager does NOT automatically re-run a OneTimeWorkRequest
 * that was interrupted by the shutdown, and the app cannot ask
 * WorkManager "was this work running when the phone died?" from a
 * BootReceiver without a record of its own. So the workers set
 * these flags in SharedPreferences when they start and clear them
 * when they finish (success or failure). BootReceiver reads them
 * on BOOT_COMPLETED and re-enqueues any work that was in flight.
 *
 * The flags are only a "was it unfinished?" signal. The actual
 * results live in plan.csv / plan.json, checkpointed every
 * 10 photos — see PlanStore / SortWorker. On resume, the user
 * taps "Resume from saved plan" to see the checkpoint, or the
 * re-enqueued worker re-scans from the start (its checkpoints
 * overwrite the plan as it goes).
 */
object WorkState {
    private const val PREFS = "photo_sorter_work"
    private const val KEY_SCAN_IN_PROGRESS = "scan_in_progress"
    private const val KEY_MOVE_IN_PROGRESS = "move_in_progress"

    fun setScanInProgress(context: Context, inProgress: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SCAN_IN_PROGRESS, inProgress).apply()
    }

    fun isScanInProgress(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SCAN_IN_PROGRESS, false)

    fun setMoveInProgress(context: Context, inProgress: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MOVE_IN_PROGRESS, inProgress).apply()
    }

    fun isMoveInProgress(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MOVE_IN_PROGRESS, false)
}
