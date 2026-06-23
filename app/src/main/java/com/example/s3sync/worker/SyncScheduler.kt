package com.example.s3sync.worker

import android.content.Context
import androidx.work.*

object SyncScheduler {
    /**
     * Triggers an immediate, one-time synchronization for a specific month.
     *
     * @param context The application context used to access [WorkManager].
     * @param month The target month to synchronize (e.g., in "yyyy-MM" format).
     */
    fun triggerManualSync(context: Context, month: String) {
        val inputData = Data.Builder()
            .putString("TARGET_MONTH", month)
            .putString("WORK_MODE", SyncWorker.MODE_SYNC)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(inputData)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag("MANUAL")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "MANUAL_SYNC_$month",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    /**
     * Triggers an immediate, one-time verification for a specific month.
     *
     * @param context The application context used to access [WorkManager].
     * @param month The target month to verify (e.g., in "yyyy-MM" format).
     */
    fun triggerVerify(context: Context, month: String) {
        val inputData = Data.Builder()
            .putString("TARGET_MONTH", month)
            .putString("WORK_MODE", SyncWorker.MODE_VERIFY)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(inputData)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag("MANUAL")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "VERIFY_$month",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun stopAllSyncs(context: Context) {
        val workManager = WorkManager.getInstance(context)
        // Cancel all manual syncs by tag
        workManager.cancelAllWorkByTag("MANUAL")
    }
}
