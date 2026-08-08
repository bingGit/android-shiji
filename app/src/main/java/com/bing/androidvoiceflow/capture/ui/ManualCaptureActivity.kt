package com.bing.androidvoiceflow.capture.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.bing.androidvoiceflow.capture.CaptureGraph
import com.bing.androidvoiceflow.capture.data.CaptureTagEntity
import com.bing.androidvoiceflow.capture.data.NewSingleCapture
import com.bing.androidvoiceflow.capture.data.ReadingSessionEntity
import com.bing.androidvoiceflow.capture.domain.CaptureType
import com.bing.androidvoiceflow.capture.domain.ReadingBlockType
import com.bing.androidvoiceflow.capture.entry.MAX_CAPTURE_CODE_POINTS
import com.bing.androidvoiceflow.capture.notification.CaptureNotificationManager
import com.bing.androidvoiceflow.capture.work.CaptureWorkScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ManualCaptureActivity : ComponentActivity() {
    private val draftStore by lazy { ManualCaptureDraftStore(this) }
    private var activeSession by mutableStateOf<ReadingSessionEntity?>(null)
    private var availableTags by mutableStateOf<List<CaptureTagEntity>>(emptyList())
    private var isSaving by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialDraft = draftStore.load()
        lifecycleScope.launch {
            val repository = CaptureGraph.repository(this@ManualCaptureActivity)
            activeSession = repository.getLatestEditableReadingSession()
            availableTags = repository.getAvailableTags()
        }
        setContent {
            ManualCaptureScreen(
                initialText = initialDraft,
                activeSession = activeSession,
                availableTags = availableTags,
                isSaving = isSaving,
                errorMessage = errorMessage,
                onTextChange = draftStore::save,
                onSaveSingle = ::saveAsSingleCapture,
                onAppendSession = ::appendToReadingSession,
                onCancel = ::discardDraftAndFinish
            )
        }
    }

    private fun saveAsSingleCapture(text: String, tagIds: Set<String>) {
        if (isSaving) return
        isSaving = true
        errorMessage = null
        lifecycleScope.launch {
            runCatching {
                val repository = CaptureGraph.repository(this@ManualCaptureActivity)
                val now = System.currentTimeMillis()
                val captureId = repository.saveSingleCapture(
                    NewSingleCapture(
                        captureType = CaptureType.ManualText,
                        rawText = text,
                        sourceUrl = null,
                        titleHint = null,
                        sourcePackage = null,
                        receivedAt = now
                    ),
                    tagIds = tagIds
                )
                val capture = requireNotNull(repository.getSingleCapture(captureId))
                val selectedTagNames = repository.getTagSnapshots(
                    com.bing.androidvoiceflow.capture.domain.CaptureOriginType.SingleCapture,
                    captureId
                ).map { it.tagNameSnapshot }
                CaptureWorkScheduler.scheduleSingleFreeze(
                    this@ManualCaptureActivity,
                    captureId,
                    capture.graceDeadlineAt
                )
                CaptureNotificationManager.showSingleGrace(
                    this@ManualCaptureActivity,
                    capture,
                    selectedTagNames = selectedTagNames,
                    quickTagNames = repository.getQuickTags().map { it.name }
                )
            }.onSuccess {
                draftStore.clear()
                Toast.makeText(
                    this@ManualCaptureActivity,
                    "拾记已暂存，10 秒后提交",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }.onFailure {
                errorMessage = "保存失败：${it.message ?: "请重试"}"
                isSaving = false
            }
        }
    }

    private fun appendToReadingSession(text: String, tagIds: Set<String>) {
        if (isSaving) return
        val targetSession = activeSession ?: return
        isSaving = true
        errorMessage = null
        lifecycleScope.launch {
            runCatching {
                val repository = CaptureGraph.repository(this@ManualCaptureActivity)
                val now = System.currentTimeMillis()
                repository.appendReadingBlock(
                    sessionId = targetSession.sessionId,
                    type = ReadingBlockType.Comment,
                    content = text,
                    createdAt = now,
                    tagIds = tagIds
                )
                val session = requireNotNull(repository.getReadingSession(targetSession.sessionId))
                CaptureWorkScheduler.scheduleSessionReminder(
                    this@ManualCaptureActivity,
                    targetSession.sessionId,
                    session.inactivityDeadlineAt
                )
                CaptureNotificationManager.refreshReadingSession(
                    this@ManualCaptureActivity,
                    repository,
                    targetSession.sessionId
                )
            }.onSuccess {
                draftStore.clear()
                Toast.makeText(
                    this@ManualCaptureActivity,
                    "已加入当前摘录",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }.onFailure {
                errorMessage = "当前摘录已变化，请保存为独立拾记"
                activeSession = null
                isSaving = false
            }
        }
    }

    private fun discardDraftAndFinish() {
        if (isSaving) return
        draftStore.clear()
        finish()
    }
}

@Composable
private fun ManualCaptureScreen(
    initialText: String,
    activeSession: ReadingSessionEntity?,
    availableTags: List<CaptureTagEntity>,
    isSaving: Boolean,
    errorMessage: String?,
    onTextChange: (String) -> Unit,
    onSaveSingle: (String, Set<String>) -> Unit,
    onAppendSession: (String, Set<String>) -> Unit,
    onCancel: () -> Unit
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    var clearedText by rememberSaveable { mutableStateOf<String?>(null) }
    var isInputFocused by remember { mutableStateOf(false) }
    var selectedTagIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val contentLength = text.codePointCount(0, text.length)
    val canSave = text.isNotBlank() && contentLength <= MAX_CAPTURE_CODE_POINTS && !isSaving

    BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    LaunchedEffect(clearedText) {
        if (clearedText != null) {
            delay(CLEAR_UNDO_WINDOW_MILLIS)
            clearedText = null
        }
    }

    CaptureTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = CaptureColors.Background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "手动记录",
                        color = CaptureColors.Text,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = onCancel, enabled = !isSaving) {
                        Text("取消", color = CaptureColors.PurpleSoft)
                    }
                }
                Text(
                    "内容会先可靠保存在本机，再按现有规则同步。",
                    color = CaptureColors.Muted,
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                )
                BasicTextField(
                    value = text,
                    onValueChange = { next ->
                        val bounded = next.limitCodePoints(MAX_CAPTURE_CODE_POINTS + 1)
                        clearedText = null
                        text = bounded
                        onTextChange(bounded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .background(CaptureColors.Card, CaptureCardShape)
                        .border(
                            1.dp,
                            if (isInputFocused) CaptureColors.PurpleSoft else CaptureColors.Border,
                            CaptureCardShape
                        )
                        .padding(16.dp)
                        .onFocusChanged { isInputFocused = it.isFocused }
                        .focusRequester(focusRequester),
                    enabled = !isSaving,
                    cursorBrush = SolidColor(CaptureColors.PurpleSoft),
                    textStyle = TextStyle(
                        color = CaptureColors.Text,
                        fontSize = 16.sp,
                        lineHeight = 25.sp
                    ),
                    decorationBox = { inner ->
                        if (text.isEmpty()) {
                            Text("写下现在想到的内容…", color = CaptureColors.Muted, fontSize = 16.sp)
                        }
                        inner()
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        when {
                            clearedText != null -> "已清空"
                            contentLength > MAX_CAPTURE_CODE_POINTS -> "超过 80,000 字"
                            else -> "$contentLength / $MAX_CAPTURE_CODE_POINTS"
                        },
                        modifier = Modifier.height(40.dp).padding(top = 12.dp),
                        color = if (contentLength > MAX_CAPTURE_CODE_POINTS) Color(0xFFFFA7A2) else CaptureColors.Muted,
                        fontSize = 12.sp
                    )
                    Box(modifier = Modifier.width(64.dp).height(40.dp)) {
                        when {
                            clearedText != null -> TextButton(
                                onClick = {
                                    val restored = clearedText.orEmpty()
                                    text = restored
                                    onTextChange(restored)
                                    clearedText = null
                                },
                                enabled = !isSaving
                            ) {
                                Text("撤销", color = CaptureColors.PurpleSoft, fontSize = 12.sp)
                            }

                            text.isNotEmpty() -> TextButton(
                                onClick = {
                                    clearedText = text
                                    text = ""
                                    onTextChange("")
                                },
                                enabled = !isSaving
                            ) {
                                Text("清空", color = Color(0xFFFFA7A2), fontSize = 12.sp)
                            }
                        }
                    }
                }
                CaptureTagSelector(
                    availableTags = availableTags,
                    selectedTagIds = selectedTagIds,
                    enabled = !isSaving,
                    onSelectionChange = { selectedTagIds = it }
                )
                errorMessage?.let { message ->
                    Text(message, color = Color(0xFFFFA7A2), fontSize = 13.sp, lineHeight = 20.sp)
                }
                Button(
                    onClick = { onSaveSingle(text.trim(), selectedTagIds) },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = CaptureButtonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = CaptureColors.Purple)
                ) {
                    Text(if (isSaving) "保存中…" else "保存到拾记")
                }
                activeSession?.let {
                    Text(
                        "当前有一条进行中的阅读摘录",
                        color = CaptureColors.Muted,
                        fontSize = 13.sp
                    )
                    OutlinedButton(
                        onClick = { onAppendSession(text.trim(), selectedTagIds) },
                        enabled = canSave,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = CaptureButtonShape
                    ) {
                        Text("加入当前摘录", color = CaptureColors.PurpleSoft)
                    }
                }
            }
        }
    }
}

private fun String.limitCodePoints(limit: Int): String {
    val count = codePointCount(0, length)
    return if (count <= limit) this else substring(0, offsetByCodePoints(0, limit))
}

private const val CLEAR_UNDO_WINDOW_MILLIS = 5_000L
