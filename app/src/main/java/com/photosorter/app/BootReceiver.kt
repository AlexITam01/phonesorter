package com.photosorter.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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
        // v0.3.1: do NOT re-enqueue SortWorker on boot. v0.3.0 did,
        // and the worker's setForeground() crash would then fire
        // on every boot / app update while a scan flag was set.
        // The scan_in_progress flag and the checkpointed plan are
        // still there — the user taps "Resume from saved plan" in
        // the app to pick up where the scan left off. Re-enqueueing
        // the worker is paused until the worker path is verified
        // on a real device.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Intentionally no WorkManager call here in v0.3.1.
    }
}
