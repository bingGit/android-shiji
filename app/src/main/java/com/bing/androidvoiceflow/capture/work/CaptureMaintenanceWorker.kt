package com.bing.androidvoiceflow.capture.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bing.androidvoiceflow.capture.CaptureGraph
import com.bing.androidvoiceflow.capture.domain.ReadingSessionState
import com.bing.androidvoiceflow.capture.data.SingleFreezeResult
import com.bing.androidvoiceflow.capture.notification.CaptureNotificationManager
import com.bing.androidvoiceflow.capture.network.SubmissionRunResult

internal class CaptureMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val action = inputData.getString(KEY_ACTION) ?: return Result.failure()
        val recordId = inputData.getString(KEY_RECORD_ID)
        val repository = CaptureGraph.repository(applicationContext)

        return runCatching {
            when (action) {
                ACTION_FREEZE_SINGLE -> {
                    recordId ?: return Result.failure()
                    when (val freeze = repository.freezeSingleCaptureIfDue(recordId, System.currentTimeMillis())) {
                        is SingleFreezeResult.Frozen -> {
                            CaptureWorkScheduler.scheduleOutboundSend(applicationContext, freeze.clientId)
                            CaptureNotificationManager.showSingleFrozen(
                                applicationContext,
                                freeze.capture,
                                freeze.tagNames
                            )
                        }
                        is SingleFreezeResult.NotDue -> CaptureWorkScheduler.scheduleSingleFreeze(
                            applicationContext,
                            recordId,
                            freeze.deadlineAt
                        )
                        SingleFreezeResult.Noop -> Unit
                    }
                }

                ACTION_REMIND_SESSION -> {
                    recordId ?: return Result.failure()
                    val session = repository.getReadingSession(recordId) ?: return Result.success()
                    if (session.state != ReadingSessionState.Active) return Result.success()
                    val now = System.currentTimeMillis()
                    if (session.inactivityDeadlineAt > now) {
                        CaptureWorkScheduler.scheduleSessionReminder(
                            applicationContext,
                            recordId,
                            session.inactivityDeadlineAt
                        )
                        return Result.success()
                    }
                    val markedAwaiting = repository.markReadingSessionAwaitingFinishIfDue(recordId, now)
                    CaptureNotificationManager.refreshReadingSession(
                        applicationContext,
                        repository,
                        recordId,
                        awaitingFinish = markedAwaiting
                    )
                }

                ACTION_SEND_REQUEST -> {
                    recordId ?: return Result.failure()
                    return when (CaptureGraph.submissionRunner(applicationContext).run(recordId)) {
                        SubmissionRunResult.Finished,
                        SubmissionRunResult.NotConfigured -> Result.success()
                        SubmissionRunResult.Retry -> Result.retry()
                    }
                }

                ACTION_CLEANUP_LOCAL -> {
                    repository.cleanupExpiredLocalContent(System.currentTimeMillis())
                }

                else -> return Result.failure()
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        const val KEY_ACTION = "capture_action"
        const val KEY_RECORD_ID = "capture_record_id"
        const val ACTION_FREEZE_SINGLE = "freeze_single"
        const val ACTION_REMIND_SESSION = "remind_session"
        const val ACTION_SEND_REQUEST = "send_request"
        const val ACTION_CLEANUP_LOCAL = "cleanup_local"
    }
}
