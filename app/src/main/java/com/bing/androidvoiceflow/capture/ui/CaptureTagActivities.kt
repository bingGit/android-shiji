package com.bing.androidvoiceflow.capture.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.core.app.RemoteInput
import com.bing.androidvoiceflow.capture.CaptureGraph
import com.bing.androidvoiceflow.capture.data.CaptureTagEntity
import com.bing.androidvoiceflow.capture.data.CaptureTagSummary
import com.bing.androidvoiceflow.capture.data.TagToggleResult
import com.bing.androidvoiceflow.capture.domain.CaptureOriginType
import com.bing.androidvoiceflow.capture.notification.CaptureNotificationManager
import com.bing.androidvoiceflow.capture.work.CaptureWorkScheduler
import kotlinx.coroutines.launch

internal class CaptureTagPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val recordId = intent.getStringExtra(EXTRA_RECORD_ID) ?: run {
            finish()
            return
        }
        val target = intent.getStringExtra(EXTRA_TARGET)
            ?: com.bing.androidvoiceflow.capture.notification.CaptureNotificationContract.TARGET_SINGLE
        val choice = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(com.bing.androidvoiceflow.capture.notification.CaptureNotificationContract.EXTRA_TAG_CHOICE)
            ?.toString()
        if (choice != null && choice != com.bing.androidvoiceflow.capture.notification.CaptureNotificationContract.CHOICE_MORE_TAGS) {
            lifecycleScope.launch {
                val repository = CaptureGraph.repository(this@CaptureTagPickerActivity)
                val tag = repository.getAvailableTags().firstOrNull { it.name == choice }
                if (tag == null) {
                    setContent { CaptureTagPickerScreen(recordId, target, ::finish) }
                    return@launch
                }
                applyNotificationTag(this@CaptureTagPickerActivity, repository, recordId, target, tag)
                finish()
            }
        } else {
            setContent { CaptureTagPickerScreen(recordId, target, ::finish) }
        }
    }

    companion object {
        private const val EXTRA_RECORD_ID = "tag_record_id"
        private const val EXTRA_TARGET = "tag_target"

        fun createIntent(context: Context, recordId: String, target: String) =
            Intent(context, CaptureTagPickerActivity::class.java)
                .putExtra(EXTRA_RECORD_ID, recordId)
                .putExtra(EXTRA_TARGET, target)

        fun createNotificationIntent(context: Context, recordId: String, target: String) =
            createIntent(context, recordId, target)
                .setAction(com.bing.androidvoiceflow.capture.notification.CaptureNotificationContract.ACTION_SELECT_TAG)
    }
}

internal class CaptureTagManagementActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CaptureTagManagementScreen(::finish) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptureTagPickerScreen(recordId: String, target: String, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { CaptureGraph.repository(context) }
    val scope = rememberCoroutineScope()
    var tags by remember { mutableStateOf<List<CaptureTagEntity>>(emptyList()) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showCreate by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val editable = when (target) {
            com.bing.androidvoiceflow.capture.notification.CaptureNotificationContract.TARGET_SINGLE ->
                repository.getSingleCapture(recordId)?.state == com.bing.androidvoiceflow.capture.domain.SingleCaptureState.LocalGrace
            com.bing.androidvoiceflow.capture.notification.CaptureNotificationContract.TARGET_SESSION ->
                repository.getReadingSession(recordId)?.state in setOf(
                    com.bing.androidvoiceflow.capture.domain.ReadingSessionState.Active,
                    com.bing.androidvoiceflow.capture.domain.ReadingSessionState.AwaitingFinish
                )
            else -> false
        }
        if (!editable) {
            onClose()
            return
        }
        tags = repository.getAvailableTags()
        selectedIds = repository.getTagSnapshots(target.toOriginType(), recordId)
            .mapTo(mutableSetOf()) { it.tagId }
    }

    LaunchedEffect(recordId, target) { refresh() }

    CaptureTheme {
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .background(Color.Black.copy(alpha = 0.48f))
                .clickable(onClick = onClose)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(min = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .background(CaptureColors.Surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .border(1.dp, CaptureColors.Border, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {})
                    }
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("为这条记录添加标签", color = CaptureColors.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text("选择后立即生效，保存倒计时重新开始", color = CaptureColors.Muted, fontSize = 12.sp)
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, "关闭", tint = CaptureColors.Muted)
                    }
                }
                val selectedTags = tags.filter { it.tagId in selectedIds }
                val commonTags = tags.take(4).filterNot { it.tagId in selectedIds }
                val otherTags = tags.drop(4).filterNot { it.tagId in selectedIds }
                if (selectedTags.isNotEmpty()) {
                    TagSection("已选择", selectedTags, selectedIds) { tag ->
                        scope.launch {
                            togglePickerTag(context, repository, recordId, target, tag, ::refresh, onClose)
                        }
                    }
                }
                if (commonTags.isNotEmpty()) {
                    TagSection("常用标签", commonTags, selectedIds) { tag ->
                        scope.launch {
                            togglePickerTag(context, repository, recordId, target, tag, ::refresh, onClose)
                        }
                    }
                }
                if (otherTags.isNotEmpty()) {
                    TagSection("所有标签", otherTags, selectedIds) { tag ->
                        scope.launch {
                            togglePickerTag(context, repository, recordId, target, tag, ::refresh, onClose)
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, CaptureColors.Purple.copy(alpha = 0.72f), RoundedCornerShape(16.dp))
                        .clickable { showCreate = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Add, null, tint = CaptureColors.PurpleSoft)
                        Spacer(Modifier.width(10.dp))
                        Text("新建标签", color = CaptureColors.PurpleSoft, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(
                    if (target == com.bing.androidvoiceflow.capture.notification.CaptureNotificationContract.TARGET_SINGLE) {
                        "点击立即生效 · 关闭后继续 10 秒缓冲"
                    } else {
                        "点击立即生效 · 完成摘录时与内容一起提交"
                    },
                    color = CaptureColors.Muted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        if (showCreate) {
            TagNameDialog("新建标签", "", { showCreate = false }) { name ->
                scope.launch {
                    val tag = runCatching { repository.createTag(name) }.getOrElse {
                        Toast.makeText(context, it.userTagError(), Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    showCreate = false
                    refresh()
                    togglePickerTag(context, repository, recordId, target, tag, ::refresh, onClose)
                }
            }
        }
    }
}

@Composable
private fun CaptureTagManagementScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { CaptureGraph.repository(context) }
    val scope = rememberCoroutineScope()
    val tags by repository.observeTagSummaries().collectAsStateWithLifecycle(initialValue = emptyList())
    var editing by remember { mutableStateOf<CaptureTagSummary?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CaptureTagSummary?>(null) }

    CaptureTheme {
        Surface(Modifier.fillMaxSize(), color = CaptureColors.Background) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = CaptureColors.Text)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("标签管理", color = CaptureColors.Text, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                            Text("置顶标签会优先出现在通知快捷选择中。", color = CaptureColors.Muted, fontSize = 12.sp)
                        }
                        IconButton(
                            onClick = { creating = true },
                            modifier = Modifier.background(CaptureColors.Purple.copy(alpha = 0.24f), CircleShape)
                        ) {
                            Icon(Icons.Outlined.Add, "新建标签", tint = CaptureColors.PurpleSoft)
                        }
                    }
                }
                item { TagGroupTitle("置顶标签") }
                items(tags.filter(CaptureTagSummary::isPinned), key = { "pinned-${it.tagId}" }) { tag ->
                    TagManagementRow(
                        tag,
                        onPin = { scope.launch { repository.setTagPinned(tag.tagId, !tag.isPinned) } },
                        onRename = { editing = tag },
                        onDelete = { deleting = tag }
                    )
                }
                item { TagGroupTitle("其他标签") }
                items(tags.filterNot(CaptureTagSummary::isPinned), key = { "other-${it.tagId}" }) { tag ->
                    TagManagementRow(
                        tag,
                        onPin = { scope.launch { repository.setTagPinned(tag.tagId, true) } },
                        onRename = { editing = tag },
                        onDelete = { deleting = tag }
                    )
                }
                item {
                    Text(
                        "删除标签不会删除任何记录；已提交内容保持原标签快照。",
                        color = CaptureColors.Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        if (creating || editing != null) {
            TagNameDialog(
                if (creating) "新建标签" else "重命名标签",
                editing?.name.orEmpty(),
                { creating = false; editing = null }
            ) { name ->
                scope.launch {
                    runCatching {
                        editing?.let { repository.renameTag(it.tagId, name) } ?: repository.createTag(name)
                    }.onSuccess {
                        creating = false
                        editing = null
                    }.onFailure { Toast.makeText(context, it.userTagError(), Toast.LENGTH_SHORT).show() }
                }
            }
        }
        deleting?.let { tag ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                containerColor = CaptureColors.CardStrong,
                title = { Text("删除“${tag.name}”？", color = CaptureColors.Text) },
                text = { Text("不会删除笔记，已提交内容中的标签也会保留。", color = CaptureColors.Muted) },
                confirmButton = {
                    TextButton(onClick = { scope.launch { repository.deleteTag(tag.tagId); deleting = null } }) {
                        Text("删除", color = Color(0xFFFFA7A2))
                    }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消", color = CaptureColors.Muted) } }
            )
        }
    }
}

@Composable
private fun TagChip(name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (selected) CaptureColors.Purple else CaptureColors.Card, RoundedCornerShape(16.dp))
            .border(1.dp, if (selected) CaptureColors.PurpleSoft else CaptureColors.Border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 10.dp)
    ) {
        Text(if (selected) "$name ×" else name, color = if (selected) Color.White else CaptureColors.Text, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagSection(
    title: String,
    tags: List<CaptureTagEntity>,
    selectedIds: Set<String>,
    onSelect: (CaptureTagEntity) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = CaptureColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            tags.forEach { tag ->
                TagChip(tag.name, tag.tagId in selectedIds) { onSelect(tag) }
            }
        }
    }
}

@Composable
private fun TagGroupTitle(title: String) {
    Text(
        title,
        color = CaptureColors.Muted,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

@Composable
private fun TagManagementRow(
    tag: CaptureTagSummary,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .background(CaptureColors.Card, RoundedCornerShape(14.dp))
            .border(1.dp, CaptureColors.Border, RoundedCornerShape(14.dp))
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(if (tag.isPinned) CaptureColors.PurpleSoft else CaptureColors.Muted, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tag.name, color = CaptureColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("已用于 ${tag.usageCount} 条内容", color = CaptureColors.Muted, fontSize = 11.sp)
        }
        IconButton(onClick = onPin) { Icon(Icons.Outlined.PushPin, if (tag.isPinned) "取消置顶" else "置顶", tint = if (tag.isPinned) CaptureColors.PurpleSoft else CaptureColors.Muted) }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Outlined.MoreVert, "更多操作", tint = CaptureColors.Muted)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(CaptureColors.CardStrong)
            ) {
                DropdownMenuItem(
                    text = { Text("重命名", color = CaptureColors.Text) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, null, tint = CaptureColors.Muted) },
                    onClick = { menuExpanded = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("删除", color = Color(0xFFFFA7A2)) },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = Color(0xFFFFA7A2)) },
                    onClick = { menuExpanded = false; onDelete() }
                )
            }
        }
    }
}

private suspend fun togglePickerTag(
    context: Context,
    repository: com.bing.androidvoiceflow.capture.data.CaptureRepository,
    recordId: String,
    target: String,
    tag: CaptureTagEntity,
    refresh: suspend () -> Unit,
    onClose: () -> Unit
) {
    when (val result = repository.toggleTargetTag(recordId, target, tag.tagId)) {
        is TagToggleResult.Updated -> {
            if (target == com.bing.androidvoiceflow.capture.notification.CaptureNotificationContract.TARGET_SINGLE) {
                CaptureWorkScheduler.scheduleSingleFreeze(context, recordId, result.deadlineAt)
            }
            refresh()
            refreshTargetNotification(context, repository, recordId, target)
        }
        TagToggleResult.LimitReached -> Toast.makeText(context, "一条内容最多添加 10 个标签", Toast.LENGTH_SHORT).show()
        else -> onClose()
    }
}

private suspend fun applyNotificationTag(
    context: Context,
    repository: com.bing.androidvoiceflow.capture.data.CaptureRepository,
    recordId: String,
    target: String,
    tag: CaptureTagEntity
) {
    when (val result = repository.toggleTargetTag(recordId, target, tag.tagId)) {
        is TagToggleResult.Updated -> {
            if (target == com.bing.androidvoiceflow.capture.notification.CaptureNotificationContract.TARGET_SINGLE) {
                CaptureWorkScheduler.scheduleSingleFreeze(context, recordId, result.deadlineAt)
            }
            refreshTargetNotification(context, repository, recordId, target)
        }
        TagToggleResult.LimitReached -> Toast.makeText(context, "一条内容最多添加 10 个标签", Toast.LENGTH_SHORT).show()
        else -> Unit
    }
}

private suspend fun com.bing.androidvoiceflow.capture.data.CaptureRepository.toggleTargetTag(
    recordId: String,
    target: String,
    tagId: String
): TagToggleResult = when (target) {
    com.bing.androidvoiceflow.capture.notification.CaptureNotificationContract.TARGET_SINGLE ->
        toggleTagForSingleCapture(recordId, tagId, System.currentTimeMillis())
    com.bing.androidvoiceflow.capture.notification.CaptureNotificationContract.TARGET_SESSION ->
        toggleTagForReadingSession(recordId, tagId, System.currentTimeMillis())
    else -> TagToggleResult.NotEditable
}

private fun String.toOriginType(): CaptureOriginType = when (this) {
    com.bing.androidvoiceflow.capture.notification.CaptureNotificationContract.TARGET_SESSION ->
        CaptureOriginType.ReadingSession
    else -> CaptureOriginType.SingleCapture
}

private suspend fun refreshTargetNotification(
    context: Context,
    repository: com.bing.androidvoiceflow.capture.data.CaptureRepository,
    recordId: String,
    target: String
) {
    val originType = target.toOriginType()
    val selected = repository.getTagSnapshots(originType, recordId).map { it.tagNameSnapshot }
    val quick = repository.getQuickTags().map { it.name }
    when (target) {
        com.bing.androidvoiceflow.capture.notification.CaptureNotificationContract.TARGET_SINGLE -> {
            val capture = repository.getSingleCapture(recordId) ?: return
            CaptureNotificationManager.showSingleGrace(context, capture, selected, quick)
        }
        com.bing.androidvoiceflow.capture.notification.CaptureNotificationContract.TARGET_SESSION -> {
            CaptureNotificationManager.refreshReadingSession(context, repository, recordId)
        }
    }
}

@Composable
private fun TagNameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CaptureColors.CardStrong,
        title = { Text(title, color = CaptureColors.Text) },
        text = {
            OutlinedTextField(
                value,
                { value = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("标签名称") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = CaptureColors.Text,
                    unfocusedTextColor = CaptureColors.Text,
                    focusedBorderColor = CaptureColors.PurpleSoft,
                    unfocusedBorderColor = CaptureColors.Border,
                    cursorColor = CaptureColors.PurpleSoft
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value) }, enabled = value.isNotBlank()) {
                Text("确定", color = CaptureColors.PurpleSoft)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = CaptureColors.Muted) } }
    )
}

private fun Throwable.userTagError(): String = when {
    message?.contains("UNIQUE", ignoreCase = true) == true -> "标签名称已存在"
    message.isNullOrBlank() -> "操作失败，请重试"
    else -> message.orEmpty()
}
