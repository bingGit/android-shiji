package com.bing.androidvoiceflow.capture.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.bing.androidvoiceflow.capture.CaptureGraph
import com.bing.androidvoiceflow.capture.data.ReadingSessionEntity
import com.bing.androidvoiceflow.capture.domain.ReadingBlockType
import com.bing.androidvoiceflow.capture.notification.CaptureNotificationContract
import com.bing.androidvoiceflow.capture.notification.CaptureNotificationManager
import com.bing.androidvoiceflow.capture.work.CaptureWorkScheduler
import kotlinx.coroutines.launch

internal class StartReadingActivity : ComponentActivity() {
    private var resolutionStarted = false

    private val captureId: String? by lazy {
        intent.getStringExtra(CaptureNotificationContract.EXTRA_RECORD_ID)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resolvedCaptureId = captureId ?: run {
            finish()
            return
        }
        lifecycleScope.launch {
            val repository = CaptureGraph.repository(this@StartReadingActivity)
            val currentSession = repository.getLatestEditableReadingSession()
            if (currentSession == null) {
                convertToNewSession(resolvedCaptureId)
            } else {
                showChoice(resolvedCaptureId, currentSession)
            }
        }
    }

    private fun showChoice(captureId: String, currentSession: ReadingSessionEntity) {
        setContent {
            StartReadingChoiceScreen(
                canBindCurrent = currentSession.sourceUrl == null && currentSession.rawShareText == null,
                currentLabel = currentSession.titleHint ?: currentSession.sourcePackage ?: "无来源会话",
                onBindCurrent = {
                    if (beginResolution()) lifecycleScope.launch {
                        runCatching {
                            val repository = CaptureGraph.repository(this@StartReadingActivity)
                            check(
                                repository.bindSingleCaptureToReadingSession(
                                    captureId,
                                    currentSession.sessionId,
                                    System.currentTimeMillis()
                                )
                            )
                            updateSessionNotification(currentSession.sessionId)
                            CaptureWorkScheduler.cancelSingleFreeze(this@StartReadingActivity, captureId)
                            CaptureNotificationManager.cancelSingle(this@StartReadingActivity, captureId)
                        }.onSuccess { finishWithMessage("文章已绑定到当前会话") }
                            .onFailure { finishWithMessage("绑定失败，请在阅读捕获中处理") }
                    }
                },
                onCompleteAndCreate = {
                    if (beginResolution()) lifecycleScope.launch {
                        runCatching {
                            val repository = CaptureGraph.repository(this@StartReadingActivity)
                            if (repository.getReadingBlocks(currentSession.sessionId).isEmpty()) {
                                repository.abandonReadingSession(currentSession.sessionId)
                                CaptureNotificationManager.cancelSession(
                                    this@StartReadingActivity,
                                    currentSession.sessionId
                                )
                            } else {
                                val clientId = repository.completeReadingSession(
                                    currentSession.sessionId
                                )
                                CaptureWorkScheduler.scheduleOutboundSend(
                                    this@StartReadingActivity,
                                    clientId
                                )
                                CaptureNotificationManager.showSessionCompleted(
                                    this@StartReadingActivity,
                                    currentSession.sessionId
                                )
                            }
                            CaptureWorkScheduler.cancelSessionReminder(
                                this@StartReadingActivity,
                                currentSession.sessionId
                            )
                            convertToNewSession(captureId)
                        }.onFailure { finishWithMessage("操作失败，请在阅读捕获中处理") }
                    }
                },
                onCancel = ::finish
            )
        }
    }

    private fun convertToNewSession(captureId: String) {
        lifecycleScope.launch {
            runCatching {
                val repository = CaptureGraph.repository(this@StartReadingActivity)
                val sessionId = repository.convertSingleCaptureToReadingSession(
                    captureId,
                    System.currentTimeMillis()
                )
                updateSessionNotification(sessionId)
                CaptureWorkScheduler.cancelSingleFreeze(this@StartReadingActivity, captureId)
                CaptureNotificationManager.cancelSingle(this@StartReadingActivity, captureId)
            }.onSuccess { finishWithMessage("已开始阅读摘录") }
                .onFailure { finishWithMessage("操作失败，请在阅读捕获中处理") }
        }
    }

    private suspend fun updateSessionNotification(sessionId: String) {
        val repository = CaptureGraph.repository(this)
        val session = requireNotNull(repository.getReadingSession(sessionId))
        CaptureWorkScheduler.scheduleSessionReminder(this, sessionId, session.inactivityDeadlineAt)
        CaptureNotificationManager.refreshReadingSession(this, repository, sessionId)
    }

    private fun finishWithMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun beginResolution(): Boolean {
        if (resolutionStarted) return false
        resolutionStarted = true
        return true
    }

    companion object {
        fun createIntent(context: Context, captureId: String): Intent =
            Intent(context, StartReadingActivity::class.java)
                .putExtra(CaptureNotificationContract.EXTRA_RECORD_ID, captureId)
    }
}

@Composable
private fun StartReadingChoiceScreen(
    canBindCurrent: Boolean,
    currentLabel: String,
    onBindCurrent: () -> Unit,
    onCompleteAndCreate: () -> Unit,
    onCancel: () -> Unit
) {
    CaptureTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = CaptureColors.Background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "已有阅读摘录",
                    color = CaptureColors.Text,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "当前：$currentLabel",
                    color = CaptureColors.Muted,
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                )
                if (canBindCurrent) {
                    Button(
                        onClick = onBindCurrent,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = CaptureButtonShape,
                        colors = ButtonDefaults.buttonColors(containerColor = CaptureColors.Purple)
                    ) {
                        Text("将文章绑定到当前会话")
                    }
                }
                OutlinedButton(
                    onClick = onCompleteAndCreate,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = CaptureButtonShape
                ) {
                    Text("完成当前并开始新会话")
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = CaptureButtonShape
                ) {
                    Text("取消")
                }
            }
        }
    }
}
