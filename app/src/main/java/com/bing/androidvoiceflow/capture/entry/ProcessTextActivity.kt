package com.bing.androidvoiceflow.capture.entry

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import com.bing.androidvoiceflow.capture.data.NewSingleCapture
import com.bing.androidvoiceflow.capture.data.ReadingSessionEntity
import com.bing.androidvoiceflow.capture.data.ReadingSessionFinishResult
import com.bing.androidvoiceflow.capture.domain.CaptureType
import com.bing.androidvoiceflow.capture.domain.ReadingBlockType
import com.bing.androidvoiceflow.capture.notification.CaptureNotificationManager
import com.bing.androidvoiceflow.capture.work.CaptureWorkScheduler
import com.bing.androidvoiceflow.capture.ui.CaptureButtonShape
import com.bing.androidvoiceflow.capture.ui.CaptureColors
import com.bing.androidvoiceflow.capture.ui.CaptureTheme
import kotlinx.coroutines.launch

internal class ProcessTextActivity : ComponentActivity() {
    private var resolutionStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!intent.hasTextPlainAction(Intent.ACTION_PROCESS_TEXT)) {
            finish()
            return
        }

        val parsed = CaptureIntentParser.parseProcessText(
            selectedText = intent.safeProcessTextExtra(),
            sourcePackage = resolveSourcePackage()
        )
        if (parsed !is CaptureParseResult.Valid) {
            val message = (parsed as? CaptureParseResult.Invalid)?.reason ?: "选中文字不可用"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val repository = CaptureGraph.repository(this@ProcessTextActivity)
            val currentSession = repository.getLatestEditableReadingSession()
                ?.takeIf { it.rawShareText != null || it.sourceUrl != null }
            val incomingPackage = parsed.probe.sourcePackage
            val hasSourceConflict = currentSession?.sourcePackage != null &&
                incomingPackage != null && currentSession.sourcePackage != incomingPackage

            if (hasSourceConflict) {
                showSourceConflict(
                    currentSession = requireNotNull(currentSession),
                    incomingPackage = incomingPackage,
                    selectedText = parsed.probe.rawText
                )
            } else if (currentSession != null) {
                appendAndFinish(currentSession, parsed.probe.rawText)
            } else {
                saveSingleAndFinish(incomingPackage, parsed.probe.rawText)
            }
        }
    }

    private fun showSourceConflict(
        currentSession: ReadingSessionEntity,
        incomingPackage: String,
        selectedText: String
    ) {
        setContent {
            SourceConflictScreen(
                currentSource = currentSession.sourcePackage.orEmpty(),
                incomingSource = incomingPackage,
                onAppendCurrent = {
                    if (beginResolution()) {
                        appendAndFinish(currentSession, selectedText)
                    }
                },
                onCompleteAndCreate = {
                    if (beginResolution()) lifecycleScope.launch {
                        val repository = CaptureGraph.repository(this@ProcessTextActivity)
                        runCatching {
                            when (val result = repository.finishOrAbandonReadingSession(currentSession.sessionId)) {
                                ReadingSessionFinishResult.Abandoned -> {
                                    CaptureNotificationManager.cancelSession(
                                        this@ProcessTextActivity,
                                        currentSession.sessionId
                                    )
                                }
                                is ReadingSessionFinishResult.Completed -> {
                                    CaptureWorkScheduler.scheduleOutboundSend(
                                        this@ProcessTextActivity,
                                        result.clientId
                                    )
                                    CaptureNotificationManager.showSessionCompleted(
                                        this@ProcessTextActivity,
                                        currentSession.sessionId
                                    )
                                }
                            }
                            CaptureWorkScheduler.cancelSessionReminder(
                                this@ProcessTextActivity,
                                currentSession.sessionId
                            )
                            saveSingleAndFinish(incomingPackage, selectedText)
                        }.onFailure { showFailureAndFinish() }
                    }
                },
                onCancel = ::finish
            )
        }
    }

    private fun appendAndFinish(
        currentSession: ReadingSessionEntity,
        selectedText: String
    ) {
        lifecycleScope.launch {
            runCatching {
                val repository = CaptureGraph.repository(this@ProcessTextActivity)
                val now = System.currentTimeMillis()
                val sessionId = currentSession.sessionId
                repository.appendReadingBlock(
                    sessionId = sessionId,
                    type = ReadingBlockType.Excerpt,
                    content = selectedText,
                    createdAt = now
                )
                val session = requireNotNull(repository.getReadingSession(sessionId))
                val excerptCount = repository.getReadingBlocks(sessionId)
                    .count { it.type == ReadingBlockType.Excerpt }
                CaptureWorkScheduler.scheduleSessionReminder(
                    this@ProcessTextActivity,
                    sessionId,
                    session.inactivityDeadlineAt
                )
                CaptureNotificationManager.refreshReadingSession(
                    this@ProcessTextActivity,
                    repository,
                    sessionId
                )
                excerptCount
            }.onSuccess { excerptCount ->
                Toast.makeText(
                    this@ProcessTextActivity,
                    "已加入第 $excerptCount 段",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }.onFailure { showFailureAndFinish() }
        }
    }

    private fun saveSingleAndFinish(sourcePackage: String?, selectedText: String) {
        lifecycleScope.launch {
            runCatching {
                val repository = CaptureGraph.repository(this@ProcessTextActivity)
                val captureId = repository.saveSingleCapture(
                    NewSingleCapture(
                        captureType = CaptureType.Excerpt,
                        rawText = selectedText,
                        sourceUrl = null,
                        titleHint = null,
                        sourcePackage = sourcePackage,
                        receivedAt = System.currentTimeMillis()
                    )
                )
                val capture = requireNotNull(repository.getSingleCapture(captureId))
                CaptureWorkScheduler.scheduleSingleFreeze(
                    this@ProcessTextActivity,
                    captureId,
                    capture.graceDeadlineAt
                )
                CaptureNotificationManager.showSingleGrace(
                    this@ProcessTextActivity,
                    capture,
                    quickTagNames = repository.getQuickTags().map { it.name }
                )
            }.onSuccess {
                Toast.makeText(
                    this@ProcessTextActivity,
                    "已保存为单条内容",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }.onFailure { showFailureAndFinish() }
        }
    }

    private fun showFailureAndFinish() {
        Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun beginResolution(): Boolean {
        if (resolutionStarted) return false
        resolutionStarted = true
        return true
    }
}

private fun Intent?.safeProcessTextExtra(): CharSequence? =
    runCatching { this?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) }.getOrNull()

@Composable
private fun SourceConflictScreen(
    currentSource: String,
    incomingSource: String,
    onAppendCurrent: () -> Unit,
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
                    "摘录来源发生变化",
                    color = CaptureColors.Text,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "当前会话来自 $currentSource，新摘录来自 $incomingSource。请选择这段内容应放在哪里。",
                    color = CaptureColors.Muted,
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                )
                Button(
                    onClick = onCompleteAndCreate,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = CaptureButtonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = CaptureColors.Purple)
                ) {
                    Text("完成当前会话并保存为单条")
                }
                OutlinedButton(
                    onClick = onAppendCurrent,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = CaptureButtonShape
                ) {
                    Text("仍追加到当前会话")
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
