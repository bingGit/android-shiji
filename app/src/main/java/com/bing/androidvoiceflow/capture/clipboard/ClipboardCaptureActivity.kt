package com.bing.androidvoiceflow.capture.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.bing.androidvoiceflow.capture.CaptureGraph
import com.bing.androidvoiceflow.capture.data.NewReadingSession
import com.bing.androidvoiceflow.capture.domain.ReadingBlockType
import com.bing.androidvoiceflow.capture.notification.CaptureNotificationManager
import com.bing.androidvoiceflow.capture.work.CaptureWorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

internal class ClipboardCaptureActivity : ComponentActivity() {
    private var captureStarted = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || captureStarted) return

        captureStarted = true
        lifecycleScope.launch {
            val message = captureMutex.withLock { captureClipboard() }
            Toast.makeText(this@ClipboardCaptureActivity, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private suspend fun captureClipboard(): String = withContext(Dispatchers.IO) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val description = clipboard.primaryClipDescription
        val snapshot = ClipboardSnapshot(
            text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text,
            timestampMillis = description?.timestamp ?: 0L
        )
        val now = System.currentTimeMillis()
        val store = ClipboardImportStore(this@ClipboardCaptureActivity)
        when (val decision = ClipboardImportPolicy.evaluate(snapshot, store.load(), now)) {
            is ClipboardImportDecision.Reject -> decision.reason.userMessage()
            is ClipboardImportDecision.Accept -> runCatching {
                val repository = CaptureGraph.repository(this@ClipboardCaptureActivity)
                val existingSession = repository.getLatestEditableReadingSession()
                val sessionId = existingSession?.sessionId ?: repository.startReadingSession(
                    NewReadingSession(
                        sourceUrl = null,
                        titleHint = null,
                        sourcePackage = null,
                        rawShareText = null,
                        startedAt = now
                    )
                )
                repository.appendReadingBlock(
                    sessionId = sessionId,
                    type = ReadingBlockType.Excerpt,
                    content = decision.text,
                    createdAt = now
                )
                store.save(decision, now)

                val session = requireNotNull(repository.getReadingSession(sessionId))
                CaptureWorkScheduler.scheduleSessionReminder(
                    this@ClipboardCaptureActivity,
                    sessionId,
                    session.inactivityDeadlineAt
                )
                CaptureNotificationManager.refreshReadingSession(
                    this@ClipboardCaptureActivity,
                    repository,
                    sessionId
                )
                "已摘录：${decision.text.lineSequence().first().take(48)}"
            }.getOrElse { "保存失败，请重试" }
        }
    }

    private fun ClipboardRejectReason.userMessage(): String = when (this) {
        ClipboardRejectReason.Empty -> "剪贴板中没有可保存的文字"
        ClipboardRejectReason.TimestampUnavailable -> "无法确认剪贴板时间，请重新复制后再试"
        ClipboardRejectReason.Stale -> "剪贴板内容已过期，请重新复制后再试"
        ClipboardRejectReason.TooLong -> "剪贴板文字过长，未保存"
        ClipboardRejectReason.Duplicate -> "这段内容刚刚已经保存"
    }

    private companion object {
        val captureMutex = Mutex()
    }
}
