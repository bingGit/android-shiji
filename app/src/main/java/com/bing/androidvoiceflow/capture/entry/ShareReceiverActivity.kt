package com.bing.androidvoiceflow.capture.entry

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.bing.androidvoiceflow.capture.CaptureGraph
import com.bing.androidvoiceflow.capture.data.NewSingleCapture
import com.bing.androidvoiceflow.capture.domain.CaptureType
import com.bing.androidvoiceflow.capture.notification.CaptureNotificationManager
import com.bing.androidvoiceflow.capture.work.CaptureWorkScheduler
import kotlinx.coroutines.launch

internal class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!intent.hasTextPlainAction(Intent.ACTION_SEND)) {
            finish()
            return
        }

        val parsed = CaptureIntentParser.parseShare(
            sharedText = intent.safeCharSequenceExtra(Intent.EXTRA_TEXT),
            titleHint = intent.safeCharSequenceExtra(Intent.EXTRA_TITLE),
            subjectHint = intent.safeStringExtra(Intent.EXTRA_SUBJECT),
            sourcePackage = resolveSourcePackage()
        )
        if (parsed !is CaptureParseResult.Valid) {
            val message = (parsed as? CaptureParseResult.Invalid)?.reason ?: "分享内容不可用"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            runCatching {
                val repository = CaptureGraph.repository(this@ShareReceiverActivity)
                val probe = parsed.probe
                val captureId = repository.saveSingleCapture(
                    NewSingleCapture(
                        captureType = if (probe.sourceUrl != null) {
                            CaptureType.Article
                        } else {
                            CaptureType.SharedText
                        },
                        rawText = probe.rawText,
                        sourceUrl = probe.sourceUrl,
                        titleHint = probe.titleHint,
                        sourcePackage = probe.sourcePackage,
                        receivedAt = probe.receivedAtMillis
                    )
                )
                val capture = requireNotNull(repository.getSingleCapture(captureId))
                CaptureWorkScheduler.scheduleSingleFreeze(
                    this@ShareReceiverActivity,
                    captureId,
                    capture.graceDeadlineAt
                )
                CaptureNotificationManager.showSingleGrace(
                    this@ShareReceiverActivity,
                    capture,
                    quickTagNames = repository.getQuickTags().map { it.name }
                )
            }.onSuccess {
                Toast.makeText(this@ShareReceiverActivity, "已暂存", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@ShareReceiverActivity, "保存失败，请重试", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }
}

private fun Intent?.safeCharSequenceExtra(name: String): CharSequence? =
    runCatching { this?.getCharSequenceExtra(name) }.getOrNull()

private fun Intent?.safeStringExtra(name: String): String? =
    runCatching { this?.getStringExtra(name) }.getOrNull()
