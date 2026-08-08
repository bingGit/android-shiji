package com.bing.androidvoiceflow.capture.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bing.androidvoiceflow.capture.CaptureGraph
import com.bing.androidvoiceflow.capture.work.CaptureWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class CaptureActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val recordId = intent.getStringExtra(CaptureNotificationContract.EXTRA_RECORD_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = CaptureGraph.repository(context)
                when (intent.action) {
                    CaptureNotificationContract.ACTION_UNDO_SINGLE -> {
                        if (repository.undoSingleCapture(recordId)) {
                            CaptureWorkScheduler.cancelSingleFreeze(context, recordId)
                            CaptureNotificationManager.cancelSingle(context, recordId)
                        }
                    }

                    CaptureNotificationContract.ACTION_UNDO_LAST_BLOCK -> {
                        repository.undoLastReadingBlock(recordId)
                        val session = repository.getReadingSession(recordId) ?: return@launch
                        CaptureWorkScheduler.scheduleSessionReminder(
                            context,
                            recordId,
                            session.inactivityDeadlineAt
                        )
                        CaptureNotificationManager.refreshReadingSession(context, repository, recordId)
                    }

                    CaptureNotificationContract.ACTION_COMPLETE_SESSION -> {
                        if (
                            repository.getReadingSession(recordId)?.state ==
                            com.bing.androidvoiceflow.capture.domain.ReadingSessionState.Frozen
                        ) {
                            CaptureWorkScheduler.cancelSessionReminder(context, recordId)
                            CaptureNotificationManager.showSessionCompleted(context, recordId)
                            return@launch
                        }
                        val clientId = runCatching {
                            repository.completeReadingSession(recordId)
                        }.getOrElse { error ->
                            if (
                                repository.getReadingSession(recordId)?.state ==
                                com.bing.androidvoiceflow.capture.domain.ReadingSessionState.Frozen
                            ) {
                                CaptureWorkScheduler.cancelSessionReminder(context, recordId)
                                CaptureNotificationManager.showSessionCompleted(context, recordId)
                                return@launch
                            }
                            throw error
                        }
                        CaptureWorkScheduler.scheduleOutboundSend(context, clientId)
                        CaptureWorkScheduler.cancelSessionReminder(context, recordId)
                        CaptureNotificationManager.showSessionCompleted(context, recordId)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
