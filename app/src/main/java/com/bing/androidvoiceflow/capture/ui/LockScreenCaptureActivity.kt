package com.bing.androidvoiceflow.capture.ui

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.bing.androidvoiceflow.capture.domain.CaptureOriginType
import com.bing.androidvoiceflow.capture.domain.CaptureType
import com.bing.androidvoiceflow.capture.entry.MAX_CAPTURE_CODE_POINTS
import com.bing.androidvoiceflow.capture.notification.CaptureNotificationManager
import com.bing.androidvoiceflow.capture.work.CaptureWorkScheduler
import kotlinx.coroutines.launch

internal class LockScreenCaptureActivity : ComponentActivity() {
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private var availableTags by mutableStateOf<List<CaptureTagEntity>>(emptyList())
    private var isSaving by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockScreenWindow()
        if (!keyguardManager.isDeviceLocked) {
            finishAndRemoveTask()
            return
        }
        lifecycleScope.launch {
            availableTags = CaptureGraph.repository(this@LockScreenCaptureActivity).getAvailableTags()
        }
        setContent {
            LockScreenCaptureScreen(
                availableTags = availableTags,
                isSaving = isSaving,
                errorMessage = errorMessage,
                onSave = ::saveCapture,
                onCancel = ::finishAndRemoveTask
            )
        }
    }

    override fun onStop() {
        super.onStop()
        if (!powerManager.isInteractive && !isChangingConfigurations) finishAndRemoveTask()
    }

    private fun configureLockScreenWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
    }

    private fun saveCapture(text: String, tagIds: Set<String>) {
        if (isSaving) return
        isSaving = true
        errorMessage = null
        lifecycleScope.launch {
            runCatching {
                val repository = CaptureGraph.repository(this@LockScreenCaptureActivity)
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
                    CaptureOriginType.SingleCapture,
                    captureId
                ).map { it.tagNameSnapshot }
                CaptureWorkScheduler.scheduleSingleFreeze(
                    this@LockScreenCaptureActivity,
                    captureId,
                    capture.graceDeadlineAt
                )
                CaptureNotificationManager.showSingleGrace(
                    this@LockScreenCaptureActivity,
                    capture,
                    selectedTagNames = selectedTagNames,
                    quickTagNames = repository.getQuickTags().map { it.name }
                )
            }.onSuccess {
                Toast.makeText(
                    this@LockScreenCaptureActivity,
                    "拾记已暂存，10 秒后提交",
                    Toast.LENGTH_SHORT
                ).show()
                finishAndRemoveTask()
            }.onFailure {
                errorMessage = "保存失败：${it.message ?: "请重试"}"
                isSaving = false
            }
        }
    }
}

@Composable
private fun LockScreenCaptureScreen(
    availableTags: List<CaptureTagEntity>,
    isSaving: Boolean,
    errorMessage: String?,
    onSave: (String, Set<String>) -> Unit,
    onCancel: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var selectedTagIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isInputFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val contentLength = text.codePointCount(0, text.length)
    val canSave = text.isNotBlank() && !isSaving

    BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
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
                        "快速记录",
                        color = CaptureColors.Text,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = onCancel, enabled = !isSaving) {
                        Text("取消", color = CaptureColors.PurpleSoft)
                    }
                }
                BasicTextField(
                    value = text,
                    onValueChange = { next ->
                        if (next.codePointCount(0, next.length) <= MAX_CAPTURE_CODE_POINTS) text = next
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
                        Box {
                            if (text.isEmpty()) {
                                Text("写下现在想到的内容…", color = CaptureColors.Muted, fontSize = 16.sp)
                            }
                            inner()
                        }
                    }
                )
                Text(
                    "$contentLength / $MAX_CAPTURE_CODE_POINTS",
                    color = CaptureColors.Muted,
                    fontSize = 12.sp
                )
                CaptureTagSelector(
                    availableTags = availableTags,
                    selectedTagIds = selectedTagIds,
                    enabled = !isSaving,
                    onSelectionChange = { selectedTagIds = it }
                )
                errorMessage?.let {
                    Text(it, color = Color(0xFFFFA7A2), fontSize = 13.sp, lineHeight = 20.sp)
                }
                Button(
                    onClick = { onSave(text.trim(), selectedTagIds) },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = CaptureButtonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = CaptureColors.Purple)
                ) {
                    Text(if (isSaving) "保存中…" else "保存到拾记")
                }
            }
        }
    }
}
