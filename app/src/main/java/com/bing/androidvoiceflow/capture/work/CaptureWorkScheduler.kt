package com.bing.androidvoiceflow.capture.work

import android.content.Context
import androidx.work.Data
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.math.max

internal object CaptureWorkScheduler {
    fun scheduleSingleFreeze(context: Context, captureId: String, deadlineAt: Long) {
        enqueue(
            context = context,
            uniqueName = singleFreezeWorkName(captureId),
            action = CaptureMaintenanceWorker.ACTION_FREEZE_SINGLE,
            recordId = captureId,
            runAt = deadlineAt
        )
    }

    fun cancelSingleFreeze(context: Context, captureId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(singleFreezeWorkName(captureId))
    }

    fun scheduleSessionReminder(context: Context, sessionId: String, deadlineAt: Long) {
        enqueue(
            context = context,
            uniqueName = sessionReminderWorkName(sessionId),
            action = CaptureMaintenanceWorker.ACTION_REMIND_SESSION,
            recordId = sessionId,
            runAt = deadlineAt
        )
    }

    fun cancelSessionReminder(context: Context, sessionId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(sessionReminderWorkName(sessionId))
    }

    fun scheduleOutboundSend(
        context: Context,
        clientId: String,
        replaceExisting: Boolean = false
    ) {
        val input = Data.Builder()
            .putString(CaptureMaintenanceWorker.KEY_ACTION, CaptureMaintenanceWorker.ACTION_SEND_REQUEST)
            .putString(CaptureMaintenanceWorker.KEY_RECORD_ID, clientId)
            .build()
        val request = OneTimeWorkRequestBuilder<CaptureMaintenanceWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            outboundWorkName(clientId),
            if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancelOutboundSend(context: Context, clientId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(outboundWorkName(clientId))
    }

    fun schedulePeriodicCleanup(context: Context) {
        val input = Data.Builder()
            .putString(CaptureMaintenanceWorker.KEY_ACTION, CaptureMaintenanceWorker.ACTION_CLEANUP_LOCAL)
            .build()
        val request = PeriodicWorkRequestBuilder<CaptureMaintenanceWorker>(1, TimeUnit.DAYS)
            .setInputData(input)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            LOCAL_CLEANUP_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun enqueue(
        context: Context,
        uniqueName: String,
        action: String,
        recordId: String,
        runAt: Long
    ) {
        val input = Data.Builder()
            .putString(CaptureMaintenanceWorker.KEY_ACTION, action)
            .putString(CaptureMaintenanceWorker.KEY_RECORD_ID, recordId)
            .build()
        val request = OneTimeWorkRequestBuilder<CaptureMaintenanceWorker>()
            .setInputData(input)
            .setInitialDelay(max(0L, runAt - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun singleFreezeWorkName(captureId: String) = "capture-freeze-$captureId"

    private fun sessionReminderWorkName(sessionId: String) = "capture-session-reminder-$sessionId"

    private fun outboundWorkName(clientId: String) = "capture-outbound-$clientId"

    private const val LOCAL_CLEANUP_WORK_NAME = "capture-local-retention-cleanup"
}
