package com.bing.androidvoiceflow.capture.ui

import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.bing.androidvoiceflow.capture.CaptureGraph
import com.bing.androidvoiceflow.capture.domain.ReadingBlockType
import com.bing.androidvoiceflow.capture.domain.CaptureOriginType
import com.bing.androidvoiceflow.capture.data.DEFAULT_SINGLE_CAPTURE_GRACE_MILLIS
import com.bing.androidvoiceflow.capture.notification.CaptureNotificationContract
import com.bing.androidvoiceflow.capture.notification.CaptureNotificationManager
import com.bing.androidvoiceflow.capture.work.CaptureWorkScheduler
import kotlinx.coroutines.launch

private const val COMMENT_SAFETY_WINDOW_MILLIS = 5L * 60L * 1_000L
private const val MAX_COMMENT_CODE_POINTS = 20_000

internal class CaptureCommentActivity : ComponentActivity() {
    private val target: String? by lazy {
        intent.getStringExtra(CaptureNotificationContract.EXTRA_COMMENT_TARGET)
    }
    private val recordId: String? by lazy {
        intent.getStringExtra(CaptureNotificationContract.EXTRA_RECORD_ID)
    }
    private var resolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resolvedTarget = target
        val resolvedId = recordId
        if (resolvedTarget == null || resolvedId == null) {
            finish()
            return
        }

        if (resolvedTarget == CaptureNotificationContract.TARGET_SINGLE) {
            lifecycleScope.launch {
                val deadline = System.currentTimeMillis() + COMMENT_SAFETY_WINDOW_MILLIS
                if (CaptureGraph.repository(this@CaptureCommentActivity)
                        .extendSingleCaptureGrace(resolvedId, deadline)
                ) {
                    CaptureWorkScheduler.scheduleSingleFreeze(
                        this@CaptureCommentActivity,
                        resolvedId,
                        deadline
                    )
                }
            }
        }

        setContent {
            CaptureTheme {
                CaptureCommentScreen(
                    target = resolvedTarget,
                    onSave = { comment -> saveComment(resolvedTarget, resolvedId, comment) },
                    onCancel = { cancelComment(resolvedTarget, resolvedId) }
                )
            }
        }
    }

    private fun saveComment(target: String, recordId: String, comment: String) {
        if (resolved) return
        resolved = true
        lifecycleScope.launch {
            runCatching {
                val repository = CaptureGraph.repository(this@CaptureCommentActivity)
                when (target) {
                    CaptureNotificationContract.TARGET_SINGLE -> {
                        check(repository.updateSingleCaptureComment(recordId, comment))
                        val capture = requireNotNull(repository.getSingleCapture(recordId))
                        val tagNames = repository.getTagSnapshots(
                            CaptureOriginType.SingleCapture,
                            recordId
                        ).map { it.tagNameSnapshot }
                        val clientId = repository.freezeSingleCapture(recordId)
                        CaptureWorkScheduler.scheduleOutboundSend(
                            this@CaptureCommentActivity,
                            clientId
                        )
                        CaptureWorkScheduler.cancelSingleFreeze(this@CaptureCommentActivity, recordId)
                        CaptureNotificationManager.showSingleFrozen(
                            this@CaptureCommentActivity,
                            capture,
                            tagNames
                        )
                    }

                    CaptureNotificationContract.TARGET_SESSION -> {
                        repository.appendReadingBlock(
                            sessionId = recordId,
                            type = ReadingBlockType.Comment,
                            content = comment,
                            createdAt = System.currentTimeMillis()
                        )
                        val session = requireNotNull(repository.getReadingSession(recordId))
                        CaptureWorkScheduler.scheduleSessionReminder(
                            this@CaptureCommentActivity,
                            recordId,
                            session.inactivityDeadlineAt
                        )
                        CaptureNotificationManager.refreshReadingSession(
                            this@CaptureCommentActivity,
                            repository,
                            recordId
                        )
                    }

                    else -> error("未知补充目标")
                }
            }.onSuccess {
                Toast.makeText(this@CaptureCommentActivity, "想法已保存", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@CaptureCommentActivity, "保存失败：${it.message}", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    private fun cancelComment(target: String, recordId: String) {
        if (resolved) return
        resolved = true
        if (target != CaptureNotificationContract.TARGET_SINGLE) {
            finish()
            return
        }
        lifecycleScope.launch {
            runCatching {
                val repository = CaptureGraph.repository(this@CaptureCommentActivity)
                val deadlineAt = System.currentTimeMillis() + DEFAULT_SINGLE_CAPTURE_GRACE_MILLIS
                check(repository.extendSingleCaptureGrace(recordId, deadlineAt))
                CaptureWorkScheduler.scheduleSingleFreeze(this@CaptureCommentActivity, recordId, deadlineAt)
                val capture = requireNotNull(repository.getSingleCapture(recordId))
                CaptureNotificationManager.showSingleGrace(
                    this@CaptureCommentActivity,
                    capture,
                    repository.getTagSnapshots(CaptureOriginType.SingleCapture, recordId).map { it.tagNameSnapshot },
                    repository.getQuickTags().map { it.name }
                )
            }.onSuccess {
                Toast.makeText(this@CaptureCommentActivity, "已返回 10 秒缓冲", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@CaptureCommentActivity, "保存失败：${it.message}", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    companion object {
        fun createIntent(context: Context, target: String, recordId: String): Intent =
            Intent(context, CaptureCommentActivity::class.java)
                .putExtra(CaptureNotificationContract.EXTRA_COMMENT_TARGET, target)
                .putExtra(CaptureNotificationContract.EXTRA_RECORD_ID, recordId)
    }
}

@Composable
private fun CaptureCommentScreen(
    target: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    var comment by remember { mutableStateOf("") }
    var isInputFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val valid = comment.isNotBlank() &&
        comment.codePointCount(0, comment.length) <= MAX_COMMENT_CODE_POINTS
    BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = CaptureColors.Background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .background(CaptureColors.Card, RoundedCornerShape(22.dp))
                            .border(1.dp, CaptureColors.Border, RoundedCornerShape(22.dp))
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = CaptureColors.Text)
                    }
                    Text(
                        if (target == CaptureNotificationContract.TARGET_SINGLE) "补一句" else "补充想法",
                        color = CaptureColors.Text,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                TextButton(onClick = onCancel) { Text("取消", color = CaptureColors.PurpleSoft) }
            }
            Text(
                if (target == CaptureNotificationContract.TARGET_SINGLE) {
                    "补充你的判断；取消后立即保存原材料并进入同步"
                } else {
                    "写下此刻的理解；取消后继续剩余缓冲"
                },
                color = CaptureColors.Muted,
                fontSize = 14.sp,
                lineHeight = 21.sp
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(CaptureColors.Card, CaptureCardShape)
                    .border(
                        1.dp,
                        if (isInputFocused) CaptureColors.PurpleSoft else CaptureColors.Border,
                        CaptureCardShape
                    )
                    .padding(18.dp)
            ) {
                BasicTextField(
                    value = comment,
                    onValueChange = {
                        if (it.codePointCount(0, it.length) <= MAX_COMMENT_CODE_POINTS) comment = it
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .onFocusChanged { isInputFocused = it.isFocused }
                        .focusRequester(focusRequester),
                    cursorBrush = SolidColor(CaptureColors.PurpleSoft),
                    textStyle = TextStyle(
                        color = CaptureColors.Text,
                        fontSize = 16.sp,
                        lineHeight = 25.sp
                    ),
                    decorationBox = { inner ->
                        if (comment.isEmpty()) {
                            Text("写下此刻的判断…", color = CaptureColors.Muted, fontSize = 16.sp)
                        }
                        inner()
                    }
                )
            }
            Text(
                "${comment.codePointCount(0, comment.length)} 字",
                modifier = Modifier.fillMaxWidth(),
                color = CaptureColors.Muted,
                fontSize = 12.sp
            )
            Button(
                onClick = { onSave(comment.trim()) },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = CaptureButtonShape,
                colors = ButtonDefaults.buttonColors(containerColor = CaptureColors.Purple)
            ) {
                Text(if (target == CaptureNotificationContract.TARGET_SINGLE) "保存并同步" else "保存想法", fontSize = 16.sp)
            }
        }
    }
}
