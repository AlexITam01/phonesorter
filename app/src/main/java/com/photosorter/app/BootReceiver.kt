package com.photosorter.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * v0.3.0 — resume sorting after a shutdown / reboot.
 *
 * Registered in AndroidManifest with RECEIVE_BOOT_COMPLETED.
 * On BOOT_COMPLETED (and on MY_PACKAGE_REPLACED, i.e. after an
 * app update), it checks WorkState: if a scan was in progress
 * when the phone shut down, it re-enqueues SortWorker with
 * ExistingWorkPolicy.KEEP. The re-enqueued worker re-scans
 * from the start — it does not resume mid-list — but the
 * checkpointed plan (plan.csv / plan.json, written every
 * 10 photos) is still there, so the user can tap "Resume
 * from saved plan" in the app to see the last checkpoint
 * without waiting for the re-scan.
 *
 * If nothing was in progress at shutdown, this receiver does
 * nothing — it never starts a scan the user did not ask for.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val wm = WorkManager.getInstance(context)
        if (WorkState.isScanInProgress(context)) {
            val request = OneTimeWorkRequestBuilder<SortWorker>()
                .addTag(SortWorker.TAG_SCAN)
                .build()
            wm.enqueueUniqueWork(
                SortWorker.UNIQUE_SCAN, ExistingWorkPolicy.KEEP, request)
        }
        // A move interrupted by shutdown is not re-enqueued here:
        // moves are short, user-approved, and their delete step
        // needs the Activity. The copied files stay in
        // Pictures/Sorted/<category>/ and the plan still lists
        // the category, so the user can re-approve the move.
    }
}
