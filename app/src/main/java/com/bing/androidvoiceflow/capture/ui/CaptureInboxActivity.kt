package com.bing.androidvoiceflow.capture.ui

import android.Manifest
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.VolumeDown
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.bing.androidvoiceflow.R
import com.bing.androidvoiceflow.capture.CaptureGraph
import com.bing.androidvoiceflow.capture.accessibility.VolumeQuickCaptureService
import com.bing.androidvoiceflow.capture.clipboard.CaptureTileService
import com.bing.androidvoiceflow.capture.clipboard.ClipboardCaptureActivity
import com.bing.androidvoiceflow.capture.data.CaptureRecordDao
import com.bing.androidvoiceflow.capture.data.OutboundCaptureRequestEntity
import com.bing.androidvoiceflow.capture.data.ReadingBlockEntity
import com.bing.androidvoiceflow.capture.data.SingleCaptureEntity
import com.bing.androidvoiceflow.capture.domain.OutboundRequestState
import com.bing.androidvoiceflow.capture.domain.ReadingBlockType
import com.bing.androidvoiceflow.capture.domain.ReadingSessionState
import com.bing.androidvoiceflow.capture.domain.SingleCaptureState
import com.bing.androidvoiceflow.capture.network.CaptureHealthResult
import com.bing.androidvoiceflow.capture.notification.CaptureNotificationContract
import com.bing.androidvoiceflow.capture.notification.CaptureNotificationManager
import com.bing.androidvoiceflow.capture.work.CaptureWorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class CaptureInboxActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CaptureInboxScreen(onClose = ::finish) }
    }
}

private data class InboxSnapshot(
    val singles: List<SingleCaptureEntity> = emptyList(),
    val sessions: List<CaptureRecordSession> = emptyList(),
    val requests: List<OutboundCaptureRequestEntity> = emptyList(),
    val totalRecordCount: Int = 0,
    val totalRequestCount: Int = 0
)

private data class InboxContentSnapshot(
    val singles: List<SingleCaptureEntity>,
    val sessions: List<CaptureRecordSession>,
    val totalRecordCount: Int
)

private data class InboxSyncSnapshot(
    val requests: List<OutboundCaptureRequestEntity>,
    val totalRequestCount: Int
)

private enum class CaptureTab(val label: String, val icon: ImageVector) {
    Capture("捕获", Icons.AutoMirrored.Outlined.MenuBook),
    Records("记录", Icons.Outlined.Description),
    Settings("设置", Icons.Outlined.Settings)
}

private enum class RecordsPage {
    List,
    SyncManagement
}

private sealed interface Confirmation {
    data class AbandonSession(val sessionId: String) : Confirmation
    data class DeleteRequest(val clientId: String) : Confirmation
    data object ClearConfig : Confirmation
}

@Composable
private fun CaptureInboxScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val database = remember { CaptureGraph.database(context) }
    val repository = remember { CaptureGraph.repository(context) }
    val configStore = remember { CaptureGraph.configStore(context) }
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(CaptureTab.Capture) }
    var recordsPage by rememberSaveable { mutableStateOf(RecordsPage.List) }
    var recordsFilter by rememberSaveable { mutableStateOf(CaptureRecordFilter.All) }
    var isBusy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("正在读取本地内容") }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    var captureBaseUrl by remember { mutableStateOf("") }
    var captureUsername by remember { mutableStateOf("") }
    var capturePassword by remember { mutableStateOf("") }
    var captureServiceConfigured by remember { mutableStateOf(false) }
    var resumeTick by remember { mutableIntStateOf(0) }
    var notificationAllowed by remember {
        mutableStateOf(CaptureNotificationManager.canNotify(context))
    }
    var volumeQuickCaptureEnabled by remember {
        mutableStateOf(VolumeQuickCaptureService.isEnabled(context))
    }
    val captureTileLabel = stringResource(R.string.capture_tile_label)
    val contentSnapshotFlow = remember(database) {
        combine(
            database.singleCaptureDao().observeInbox(),
            database.readingSessionDao().observeInboxSessions(),
            database.readingSessionDao().observeInboxBlocks(),
            database.singleCaptureDao().observeCount(),
            database.readingSessionDao().observeCount()
        ) { singles, sessions, blocks, singleCount, sessionCount ->
            val blocksBySession = blocks.groupBy(ReadingBlockEntity::sessionId)
            InboxContentSnapshot(
                singles = singles,
                sessions = sessions.map { session ->
                    CaptureRecordSession(session, blocksBySession[session.sessionId].orEmpty())
                },
                totalRecordCount = singleCount + sessionCount
            )
        }
    }
    val syncSnapshotFlow = remember(database) {
        combine(
            database.outboundCaptureRequestDao().observeActionable(),
            database.outboundCaptureRequestDao().observeCount()
        ) { requests, count ->
            InboxSyncSnapshot(requests, count)
        }
    }
    val snapshotFlow = remember(contentSnapshotFlow, syncSnapshotFlow) {
        combine(contentSnapshotFlow, syncSnapshotFlow) { content, sync ->
            InboxSnapshot(
                singles = content.singles,
                sessions = content.sessions,
                requests = sync.requests,
                totalRecordCount = content.totalRecordCount,
                totalRequestCount = sync.totalRequestCount
            )
        }
    }
    val liveSnapshot by snapshotFlow.collectAsStateWithLifecycle(initialValue = null)
    val snapshot = liveSnapshot ?: InboxSnapshot()
    val isLoading = liveSnapshot == null
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationAllowed = granted || CaptureNotificationManager.canNotify(context)
        if (notificationAllowed) CaptureNotificationManager.ensureChannels(context)
        showToast(context, if (notificationAllowed) "通知已启用" else "通知未启用，操作仍可在 Inbox 完成")
    }
    BackHandler {
        if (selectedTab == CaptureTab.Records && recordsPage == RecordsPage.SyncManagement) {
            recordsPage = RecordsPage.List
        } else {
            onClose()
        }
    }

    fun runAction(success: String, block: suspend () -> Unit) {
        if (isBusy) return
        scope.launch {
            isBusy = true
            statusMessage = "处理中…"
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess {
                    statusMessage = success
                    showToast(context, success)
                }
                .onFailure {
                    statusMessage = "操作失败"
                    showToast(context, "操作失败：${it.message ?: "请重试"}", true)
                }
            isBusy = false
        }
    }

    fun saveConfig() {
        if (isBusy) return
        scope.launch {
            isBusy = true
            statusMessage = "处理中…"
            runCatching {
                withContext(Dispatchers.IO) {
                    val saved = configStore.save(captureBaseUrl, captureUsername, capturePassword)
                    val health = CaptureGraph.captureApi(context).checkHealth()
                    val pending = if (health is CaptureHealthResult.Healthy) {
                        repository.resetAuthRequiredRequests()
                        database.outboundCaptureRequestDao().getAll().filter {
                            it.state == OutboundRequestState.Pending || it.state == OutboundRequestState.RetryWait
                        }
                    } else {
                        emptyList()
                    }
                    Triple(saved, health, pending)
                }
            }.onSuccess { (saved, health, pending) ->
                captureBaseUrl = saved.baseUrl
                captureUsername = saved.username
                capturePassword = saved.password
                captureServiceConfigured = true
                pending.forEach { CaptureWorkScheduler.scheduleOutboundSend(context, it.clientId, true) }
                val message = when (health) {
                    CaptureHealthResult.Healthy -> "配置已保存，连接正常，队列已恢复"
                    CaptureHealthResult.AuthRequired -> "配置已保存，但认证失败，队列未恢复"
                    is CaptureHealthResult.Failed -> "配置已保存，但连接失败：${health.reason}"
                    CaptureHealthResult.NotConfigured -> "配置未生效，请检查输入"
                }
                statusMessage = message
                showToast(context, message, health !is CaptureHealthResult.Healthy)
            }.onFailure {
                statusMessage = "配置保存失败"
                showToast(context, "配置保存失败：${it.message ?: "请重试"}", true)
            }
            isBusy = false
        }
    }

    fun testConnection() {
        if (isBusy || !captureServiceConfigured) return
        scope.launch {
            isBusy = true
            val result = withContext(Dispatchers.IO) { CaptureGraph.captureApi(context).checkHealth() }
            val message = when (result) {
                CaptureHealthResult.Healthy -> "Capture Service 连接正常"
                CaptureHealthResult.AuthRequired -> "认证失败，请检查用户名和密码"
                is CaptureHealthResult.Failed -> "连接测试失败：${result.reason}"
                CaptureHealthResult.NotConfigured -> "请先保存同步配置"
            }
            statusMessage = message
            showToast(context, message, result !is CaptureHealthResult.Healthy)
            isBusy = false
        }
    }

    fun requestTile() {
        if (Build.VERSION.SDK_INT < 33) {
            showToast(context, "请在快捷设置编辑页手动添加“保存到拾记”")
            return
        }
        context.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(context, CaptureTileService::class.java),
            captureTileLabel,
            Icon.createWithResource(context, R.drawable.ic_capture_tile),
            context.mainExecutor
        ) { result ->
            showToast(
                context,
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "快捷设置按钮已添加"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "快捷设置按钮已经存在"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "未添加快捷设置按钮"
                    else -> "系统未完成快捷设置按钮添加"
                }
            )
        }
    }

    fun openNotificationSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        )
    }

    fun openVolumeQuickCaptureSettings() {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun retryRequest(request: OutboundCaptureRequestEntity) {
        runAction("已重新开始同步") {
            check(repository.restartFailedOutboundRequest(request.clientId))
            CaptureWorkScheduler.scheduleOutboundSend(context, request.clientId, true)
        }
    }

    fun copyRequest(request: OutboundCaptureRequestEntity) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("阅读捕获", request.content))
        showToast(context, "内容已复制")
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(resumeTick) {
        withContext(Dispatchers.IO) { configStore.load() }?.let { config ->
            captureBaseUrl = config.baseUrl
            captureUsername = config.username
            capturePassword = config.password
            captureServiceConfigured = true
        }
        notificationAllowed = CaptureNotificationManager.canNotify(context)
        volumeQuickCaptureEnabled = VolumeQuickCaptureService.isEnabled(context)
        statusMessage = "本地内容已更新"
    }

    confirmation?.let { pending ->
        val (title, body, confirmLabel) = when (pending) {
            is Confirmation.AbandonSession -> Triple(
                "放弃这次阅读摘录？",
                "摘录及其本地内容会被永久删除，此操作无法撤销。",
                "放弃摘录"
            )
            is Confirmation.DeleteRequest -> Triple(
                "删除这个同步任务？",
                "同步会停止，本地内容从删除时起保留 30 天，之后自动清理。",
                "删除任务"
            )
            Confirmation.ClearConfig -> Triple(
                "清除同步配置？",
                "地址和认证信息会被移除，未完成任务仍保留在本机。",
                "清除配置"
            )
        }
        AlertDialog(
            onDismissRequest = { if (!isBusy) confirmation = null },
            containerColor = CaptureColors.CardStrong,
            titleContentColor = CaptureColors.Text,
            textContentColor = CaptureColors.Muted,
            title = { Text(title) },
            text = { Text(body) },
            dismissButton = {
                TextButton(onClick = { confirmation = null }, enabled = !isBusy) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    enabled = !isBusy,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFFA7A2)),
                    onClick = {
                        confirmation = null
                        when (pending) {
                            is Confirmation.AbandonSession -> runAction("阅读摘录已放弃") {
                                check(repository.abandonReadingSession(pending.sessionId))
                                CaptureWorkScheduler.cancelSessionReminder(context, pending.sessionId)
                                CaptureNotificationManager.cancelSession(context, pending.sessionId)
                            }
                            is Confirmation.DeleteRequest -> runAction("同步任务已删除，本地内容保留 30 天") {
                                check(repository.deleteFailedOutboundRequest(pending.clientId))
                                CaptureWorkScheduler.cancelOutboundSend(context, pending.clientId)
                            }
                            Confirmation.ClearConfig -> {
                                configStore.clear()
                                captureBaseUrl = ""
                                captureUsername = ""
                                capturePassword = ""
                                captureServiceConfigured = false
                                statusMessage = "同步配置已清除"
                                showToast(context, "同步配置已清除")
                            }
                        }
                    }
                ) { Text(confirmLabel) }
            }
        )
    }

    CaptureTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = CaptureColors.Background) {
            Scaffold(
                containerColor = CaptureColors.Background,
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    if (!(selectedTab == CaptureTab.Records && recordsPage == RecordsPage.SyncManagement)) {
                        CaptureBottomNavigation(selectedTab) { tab ->
                            selectedTab = tab
                            recordsPage = RecordsPage.List
                        }
                    }
                }
            ) { padding ->
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    when (selectedTab) {
                        CaptureTab.Capture -> CaptureHomeTab(
                            snapshot = snapshot,
                            notificationAllowed = notificationAllowed,
                            isBusy = isBusy,
                            statusMessage = statusMessage,
                            isLoading = isLoading,
                            onEnableNotifications = {
                                if (
                                    Build.VERSION.SDK_INT >= 33 &&
                                    androidx.core.content.ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    openNotificationSettings()
                                }
                            },
                            onManual = { context.startActivity(Intent(context, ManualCaptureActivity::class.java)) },
                            onClipboard = {
                                context.startActivity(Intent(context, ClipboardCaptureActivity::class.java))
                            },
                            onStartSingle = { context.startActivity(StartReadingActivity.createIntent(context, it.captureId)) },
                            onCommentSingle = {
                                context.startActivity(CaptureCommentActivity.createIntent(context, CaptureNotificationContract.TARGET_SINGLE, it.captureId))
                            },
                            onUndoSingle = { capture ->
                                runAction("单次捕获已撤销") {
                                    check(repository.undoSingleCapture(capture.captureId))
                                    CaptureWorkScheduler.cancelSingleFreeze(context, capture.captureId)
                                    CaptureNotificationManager.cancelSingle(context, capture.captureId)
                                }
                            },
                            onCommentSession = { session ->
                                context.startActivity(CaptureCommentActivity.createIntent(context, CaptureNotificationContract.TARGET_SESSION, session.session.sessionId))
                            },
                            onUndoSession = { session ->
                                runAction("已撤销上一段") {
                                    check(repository.undoLastReadingBlock(session.session.sessionId))
                                    val refreshed = requireNotNull(repository.getReadingSession(session.session.sessionId))
                                    CaptureWorkScheduler.scheduleSessionReminder(context, refreshed.sessionId, refreshed.inactivityDeadlineAt)
                                    CaptureNotificationManager.refreshReadingSession(
                                        context,
                                        repository,
                                        refreshed.sessionId
                                    )
                                }
                            },
                            onCompleteSession = { session ->
                                runAction("阅读摘录已完成，已进入同步队列") {
                                    val clientId = repository.completeReadingSession(session.session.sessionId)
                                    CaptureWorkScheduler.scheduleOutboundSend(context, clientId)
                                    CaptureWorkScheduler.cancelSessionReminder(context, session.session.sessionId)
                                    CaptureNotificationManager.showSessionCompleted(context, session.session.sessionId)
                                }
                            }
                        )
                        CaptureTab.Records -> when (recordsPage) {
                            RecordsPage.List -> CaptureRecordsTab(
                                recordDao = database.captureRecordDao(),
                                requests = snapshot.requests,
                                totalRequestCount = snapshot.totalRequestCount,
                                isLoading = isLoading,
                                isBusy = isBusy,
                                filter = recordsFilter,
                                onFilterChange = { recordsFilter = it },
                                onOpenSync = { recordsPage = RecordsPage.SyncManagement },
                                onOpenTags = {
                                    context.startActivity(Intent(context, CaptureTagManagementActivity::class.java))
                                },
                                onAbandon = { confirmation = Confirmation.AbandonSession(it) }
                            )
                            RecordsPage.SyncManagement -> CaptureSyncManagementPage(
                                requests = snapshot.requests,
                                totalRequestCount = snapshot.totalRequestCount,
                                isLoading = isLoading,
                                isBusy = isBusy,
                                onBack = { recordsPage = RecordsPage.List },
                                onOpenSettings = {
                                    recordsPage = RecordsPage.List
                                    selectedTab = CaptureTab.Settings
                                },
                                onRetry = ::retryRequest,
                                onCopy = ::copyRequest,
                                onDelete = { confirmation = Confirmation.DeleteRequest(it.clientId) }
                            )
                        }
                        CaptureTab.Settings -> CaptureSettingsTab(
                            baseUrl = captureBaseUrl,
                            username = captureUsername,
                            password = capturePassword,
                            configured = captureServiceConfigured,
                            isBusy = isBusy,
                            onBaseUrlChange = { captureBaseUrl = it },
                            onUsernameChange = { captureUsername = it },
                            onPasswordChange = { capturePassword = it },
                            onSave = ::saveConfig,
                            onTest = ::testConnection,
                            onClear = { confirmation = Confirmation.ClearConfig },
                            onRequestTile = ::requestTile,
                            onOpenNotifications = ::openNotificationSettings,
                            volumeQuickCaptureEnabled = volumeQuickCaptureEnabled,
                            onOpenVolumeQuickCapture = ::openVolumeQuickCaptureSettings
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureBottomNavigation(selected: CaptureTab, onSelect: (CaptureTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .background(CaptureColors.Surface, RoundedCornerShape(32.dp))
            .border(1.dp, CaptureColors.Border, RoundedCornerShape(32.dp))
            .padding(horizontal = 5.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        CaptureTab.entries.forEach { tab ->
            val active = selected == tab
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .background(
                        if (active) CaptureColors.Purple.copy(alpha = 0.24f) else Color.Transparent,
                        RoundedCornerShape(26.dp)
                    )
                    .clickable { onSelect(tab) },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    tab.icon,
                    tab.label,
                    modifier = Modifier.size(21.dp),
                    tint = if (active) CaptureColors.PurpleSoft else CaptureColors.Muted
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    tab.label,
                    color = if (active) CaptureColors.Text else CaptureColors.Muted,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun CapturePageHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = CaptureColors.Text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = CaptureColors.Muted, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun CaptureHomeTab(
    snapshot: InboxSnapshot,
    notificationAllowed: Boolean,
    isBusy: Boolean,
    statusMessage: String,
    isLoading: Boolean,
    onEnableNotifications: () -> Unit,
    onManual: () -> Unit,
    onClipboard: () -> Unit,
    onStartSingle: (SingleCaptureEntity) -> Unit,
    onCommentSingle: (SingleCaptureEntity) -> Unit,
    onUndoSingle: (SingleCaptureEntity) -> Unit,
    onCommentSession: (CaptureRecordSession) -> Unit,
    onUndoSession: (CaptureRecordSession) -> Unit,
    onCompleteSession: (CaptureRecordSession) -> Unit
) {
    val pendingSingle = snapshot.singles.firstOrNull { it.state == SingleCaptureState.LocalGrace }
    val activeSession = snapshot.sessions.firstOrNull {
        it.session.state == ReadingSessionState.Active || it.session.state == ReadingSessionState.AwaitingFinish
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            CapturePageHeading(
                "阅读捕获",
                if (notificationAllowed) "把阅读中的片段，安静地留在这里。" else "通知已关闭 · 当前会话仍可继续"
            )
        }
        if (isLoading || isBusy) {
            item { CaptureStatusBand(if (isLoading) "正在读取本地内容…" else "处理中…") }
        }
        if (pendingSingle != null) {
            item {
                CaptureSectionTitle("等待处理的内容")
                Spacer(Modifier.height(10.dp))
                CaptureCard {
                    Text(pendingSingle.titleHint ?: "刚刚保存的内容", color = CaptureColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(pendingSingle.rawText.take(180), color = CaptureColors.Muted, fontSize = 14.sp, lineHeight = 21.sp)
                    CapturePrimaryButton("开始摘录", Icons.Outlined.PlayArrow, !isBusy) { onStartSingle(pendingSingle) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CaptureSecondaryButton("补一句", Icons.Outlined.AddComment, !isBusy) { onCommentSingle(pendingSingle) }
                        CaptureSecondaryButton("撤销", Icons.AutoMirrored.Outlined.Undo, !isBusy, danger = true) { onUndoSingle(pendingSingle) }
                    }
                }
            }
        }
        if (activeSession != null) {
            item {
                CaptureSectionTitle("当前摘录", "${activeSession.blocks.count { it.type == ReadingBlockType.Excerpt }} 段")
                Spacer(Modifier.height(10.dp))
                CaptureCard {
                    Text("当前阅读摘录正在收集片段。", color = CaptureColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(activeSession.session.titleHint ?: "未命名阅读摘录", color = CaptureColors.Muted, fontSize = 13.sp)
                    CapturePrimaryButton("开始摘录", Icons.Outlined.EditNote, !isBusy, onClick = onClipboard)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CaptureSecondaryButton("补想法", Icons.Outlined.EditNote, !isBusy) { onCommentSession(activeSession) }
                        CaptureSecondaryButton("撤销上一段", Icons.AutoMirrored.Outlined.Undo, !isBusy && activeSession.blocks.isNotEmpty()) { onUndoSession(activeSession) }
                    }
                    CapturePrimaryButton("完成阅读", Icons.Outlined.Save, !isBusy && activeSession.blocks.isNotEmpty()) { onCompleteSession(activeSession) }
                }
            }
        }
        if (!isLoading && pendingSingle == null && activeSession == null) {
            item {
                CaptureCard {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("❞", color = CaptureColors.PurpleSoft, fontSize = 28.sp)
                        Text("本机已保存 ${snapshot.totalRecordCount} 条", color = CaptureColors.PurpleSoft, fontSize = 12.sp)
                    }
                    Text(
                        "下一段值得留下的文字，可能就在你正在看的页面里。",
                        color = CaptureColors.Text,
                        fontSize = 20.sp,
                        lineHeight = 29.sp
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CaptureSecondaryButton("手动输入", Icons.Outlined.EditNote, !isBusy, onClick = onManual)
                CaptureSecondaryButton("读取剪贴板", Icons.Outlined.ContentCopy, !isBusy, onClick = onClipboard)
            }
        }
        if (snapshot.singles.isNotEmpty() || snapshot.sessions.isNotEmpty()) {
            item { CaptureSectionTitle("最近记录", "查看全部") }
            items(snapshot.singles.take(2), key = { "recent-${it.captureId}" }) { capture ->
                CaptureCard {
                    Text(
                        capture.rawText,
                        color = CaptureColors.Text,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(capture.titleHint ?: capture.captureType.storageValue, color = CaptureColors.Muted, fontSize = 12.sp)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CaptureRecordsTab(
    recordDao: CaptureRecordDao,
    requests: List<OutboundCaptureRequestEntity>,
    totalRequestCount: Int,
    isLoading: Boolean,
    isBusy: Boolean,
    filter: CaptureRecordFilter,
    onFilterChange: (CaptureRecordFilter) -> Unit,
    onOpenSync: () -> Unit,
    onOpenTags: () -> Unit,
    onAbandon: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val pagingFlow = remember(recordDao, filter) {
        Pager(
            config = PagingConfig(
                pageSize = 20,
                initialLoadSize = 20,
                prefetchDistance = 4,
                enablePlaceholders = false
            )
        ) {
            recordDao.pagingSource(filter.queryKind)
        }.flow
    }
    val records = pagingFlow.collectAsLazyPagingItems()

    LaunchedEffect(filter) {
        listState.scrollToItem(0)
    }

    val compactHeader = listState.firstVisibleItemIndex >= 2

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "records-heading") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                CapturePageHeading("记录", "最近保存的内容")
                IconButton(onClick = onOpenTags) {
                    Icon(Icons.Outlined.Tag, "标签管理", tint = CaptureColors.PurpleSoft)
                }
            }
        }
        item(key = "sync-status") {
            CaptureSyncStatusEntry(requests, totalRequestCount, onOpenSync)
        }
        stickyHeader {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CaptureColors.Background)
                    .padding(top = if (compactHeader) 4.dp else 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (compactHeader) {
                    Text(
                        "记录",
                        color = CaptureColors.Text,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                CaptureRecordFilterBar(filter) { selected ->
                    if (selected != filter) onFilterChange(selected)
                }
            }
        }
        if (isLoading || records.loadState.refresh is LoadState.Loading) {
            item { CaptureStatusBand("正在读取本地内容…") }
        } else if (records.loadState.refresh is LoadState.Error) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CaptureStatusBand("记录加载失败，请稍后重试", warning = true)
                    TextButton(onClick = records::retry, modifier = Modifier.fillMaxWidth()) {
                        Text("重新加载", color = CaptureColors.Warning)
                    }
                }
            }
        } else if (records.itemCount == 0) {
            item {
                CaptureStatusBand(
                    when (filter) {
                        CaptureRecordFilter.All -> "还没有保存的记录"
                        CaptureRecordFilter.Single -> "还没有单条记录"
                        CaptureRecordFilter.Reading -> "还没有阅读摘录"
                    }
                )
            }
        } else {
            items(
                count = records.itemCount,
                key = records.itemKey { it.stableId }
            ) { index ->
                records[index]?.toListItem()?.let { item ->
                    val group = captureRecordDateGroup(item.occurredAt)
                    val previousGroup = if (index > 0) {
                        records.peek(index - 1)?.let { captureRecordDateGroup(it.occurredAt) }
                    } else {
                        null
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (index == 0 || group != previousGroup) {
                            Text(
                                group.label,
                                color = CaptureColors.Text.copy(alpha = 0.78f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 5.dp)
                            )
                        }
                        CaptureRecordCard(item, isBusy, onAbandon)
                    }
                }
            }
            when (records.loadState.append) {
                is LoadState.Loading -> item(key = "load-more") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(3) {
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .size(8.dp)
                                    .background(CaptureColors.Muted, RoundedCornerShape(4.dp))
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("正在加载更多…", color = CaptureColors.Muted, fontSize = 12.sp)
                    }
                }
                is LoadState.Error -> item(key = "load-more-error") {
                    TextButton(onClick = records::retry, modifier = Modifier.fillMaxWidth()) {
                        Text("加载失败，点击重试", color = CaptureColors.Warning)
                    }
                }
                is LoadState.NotLoading -> if (
                    records.loadState.append.endOfPaginationReached && records.itemCount > 20
                ) {
                    item(key = "records-end") {
                        Text(
                            "已经到底了",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            color = CaptureColors.Muted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun CaptureSyncStatusEntry(
    requests: List<OutboundCaptureRequestEntity>,
    totalRequestCount: Int,
    onOpen: () -> Unit
) {
    val failedCount = requests.count { it.state == OutboundRequestState.Failed }
    val authCount = requests.count { it.state == OutboundRequestState.AuthRequired }
    val activeCount = (totalRequestCount - failedCount - authCount).coerceAtLeast(0)
    val warning = failedCount > 0 || authCount > 0
    val title = when {
        failedCount > 0 && authCount > 0 -> "${failedCount + authCount} 条同步异常"
        failedCount > 0 -> "$failedCount 条同步失败"
        authCount > 0 -> "$authCount 条需要重新认证"
        activeCount > 0 -> "$activeCount 条等待同步"
        else -> "已全部同步"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (warning) CaptureColors.Warning.copy(alpha = 0.10f) else CaptureColors.Card.copy(alpha = 0.72f),
                RoundedCornerShape(14.dp)
            )
            .border(
                1.dp,
                if (warning) CaptureColors.Warning.copy(alpha = 0.28f) else CaptureColors.Border.copy(alpha = 0.72f),
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    if (warning) CaptureColors.Warning else CaptureColors.Success,
                    RoundedCornerShape(5.dp)
                )
        )
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = if (warning) Color(0xFFFFF0C2) else CaptureColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            if (warning) "立即处理" else "查看状态",
            color = if (warning) CaptureColors.Warning else CaptureColors.Muted,
            fontSize = 12.sp
        )
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (warning) CaptureColors.Warning else CaptureColors.Muted
        )
    }
}

@Composable
private fun CaptureRecordFilterBar(
    selected: CaptureRecordFilter,
    onSelect: (CaptureRecordFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CaptureColors.Surface, RoundedCornerShape(13.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        CaptureRecordFilter.entries.forEach { filter ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(
                        if (selected == filter) CaptureColors.Purple else Color.Transparent,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelect(filter) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    filter.label,
                    color = if (selected == filter) Color.White else CaptureColors.Muted,
                    fontSize = 13.sp,
                    fontWeight = if (selected == filter) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun CaptureRecordCard(
    item: CaptureRecordListItem,
    isBusy: Boolean,
    onAbandon: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CaptureColors.Card.copy(alpha = 0.82f), RoundedCornerShape(14.dp))
            .border(1.dp, CaptureColors.Border.copy(alpha = 0.68f), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (item.kind == CaptureRecordKind.Reading) {
                    "阅读摘录 · ${item.excerptCount} 段"
                } else {
                    "单条记录"
                },
                color = if (item.kind == CaptureRecordKind.Reading) CaptureColors.Reading else CaptureColors.PurpleSoft,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                captureRecordTimeLabel(item.occurredAt),
                color = CaptureColors.Muted.copy(alpha = 0.82f),
                fontSize = 11.sp
            )
        }
        Text(
            item.title,
            color = CaptureColors.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            item.summary,
            color = CaptureColors.Muted.copy(alpha = 0.92f),
            fontSize = 13.sp,
            lineHeight = 19.sp,
            maxLines = if (item.kind == CaptureRecordKind.Reading) 3 else 2,
            overflow = TextOverflow.Ellipsis
        )
        if (item.source != null || item.activeSessionId != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    item.source ?: "正在摘录",
                    color = CaptureColors.Muted.copy(alpha = 0.76f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.activeSessionId?.let { sessionId ->
                    TextButton(
                        onClick = { onAbandon(sessionId) },
                        enabled = !isBusy,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("放弃", color = Color(0xFFFFA7A2), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureSyncManagementPage(
    requests: List<OutboundCaptureRequestEntity>,
    totalRequestCount: Int,
    isLoading: Boolean,
    isBusy: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: (OutboundCaptureRequestEntity) -> Unit,
    onCopy: (OutboundCaptureRequestEntity) -> Unit,
    onDelete: (OutboundCaptureRequestEntity) -> Unit
) {
    val failedRequests = requests.filter { it.state == OutboundRequestState.Failed }
    val authRequests = requests.filter { it.state == OutboundRequestState.AuthRequired }
    val activeCount = (totalRequestCount - failedRequests.size - authRequests.size).coerceAtLeast(0)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回记录",
                        tint = CaptureColors.Text
                    )
                }
                Spacer(Modifier.width(2.dp))
                Column(modifier = Modifier.padding(top = 3.dp)) {
                    Text("同步管理", color = CaptureColors.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Text("这里只显示需要你处理的异常", color = CaptureColors.Muted, fontSize = 13.sp)
                }
            }
        }
        if (isLoading) {
            item { CaptureStatusBand("正在读取同步状态…") }
        } else if (failedRequests.isEmpty() && authRequests.isEmpty()) {
            item {
                CaptureStatusBand(
                    if (activeCount == 0) "没有需要处理的同步任务" else "$activeCount 条任务正在后台自动同步"
                )
            }
        } else {
            item {
                CaptureSyncOverviewCard(
                    title = when {
                        failedRequests.isNotEmpty() && authRequests.isNotEmpty() ->
                            "${failedRequests.size + authRequests.size} 条同步异常"
                        failedRequests.isNotEmpty() -> "同步永久失败"
                        else -> "同步认证已失效"
                    },
                    description = when {
                        failedRequests.isNotEmpty() && authRequests.isNotEmpty() ->
                            "${failedRequests.size} 条永久失败，${authRequests.size} 条需要重新认证。本地内容保持完整。"
                        failedRequests.isNotEmpty() -> "多次重试仍未成功，本地内容保持完整。"
                        else -> "更新同步配置后即可继续，本地内容保持完整。"
                    }
                )
            }
            if (authRequests.isNotEmpty()) {
                item {
                    CapturePrimaryButton("前往设置", Icons.Outlined.Settings, !isBusy, onClick = onOpenSettings)
                }
            }
            if (failedRequests.isNotEmpty()) {
                item { CaptureSectionTitle("需要处理", "${failedRequests.size} 条") }
                items(failedRequests, key = { "failed-${it.clientId}" }) { request ->
                    val taskNumber = failedRequests.indexOf(request) + 1
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CaptureColors.Card.copy(alpha = 0.82f), RoundedCornerShape(14.dp))
                                .border(1.dp, CaptureColors.Border, RoundedCornerShape(14.dp))
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(CaptureColors.Purple.copy(alpha = 0.18f), RoundedCornerShape(13.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    taskNumber.toString().padStart(2, '0'),
                                    color = CaptureColors.PurpleSoft,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    request.content,
                                    color = CaptureColors.Text,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text("永久失败 · 原文保存在本机", color = CaptureColors.Muted, fontSize = 12.sp)
                                request.lastError?.let {
                                    Text(
                                        it,
                                        color = CaptureColors.Warning,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        CapturePrimaryButton("重新同步", Icons.Outlined.Sync, !isBusy) { onRetry(request) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CaptureSecondaryButton("复制内容", Icons.Outlined.ContentCopy, !isBusy) { onCopy(request) }
                            CaptureSecondaryButton("删除任务", Icons.Outlined.DeleteOutline, !isBusy, danger = true) { onDelete(request) }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun CaptureSyncOverviewCard(title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CaptureColors.Warning.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .border(1.dp, CaptureColors.Warning.copy(alpha = 0.34f), RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(CaptureColors.Warning.copy(alpha = 0.16f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                tint = CaptureColors.Warning,
                modifier = Modifier.size(25.dp)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Color(0xFFFFF0C2), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = CaptureColors.Warning.copy(alpha = 0.72f), fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun CaptureSettingsTab(
    baseUrl: String,
    username: String,
    password: String,
    configured: Boolean,
    isBusy: Boolean,
    onBaseUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onClear: () -> Unit,
    onRequestTile: () -> Unit,
    onOpenNotifications: () -> Unit,
    volumeQuickCaptureEnabled: Boolean,
    onOpenVolumeQuickCapture: () -> Unit
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = CaptureColors.Text,
        unfocusedTextColor = CaptureColors.Text,
        focusedBorderColor = CaptureColors.PurpleSoft,
        unfocusedBorderColor = CaptureColors.Border,
        focusedLabelColor = CaptureColors.PurpleSoft,
        unfocusedLabelColor = CaptureColors.Muted,
        cursorColor = CaptureColors.PurpleSoft
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { CapturePageHeading("阅读捕获设置", "认证信息只保存在本机") }
        item {
            CaptureStatusBand(
                if (configured) "认证信息只保存在本机。保存后会检查服务连接，再恢复队列同步。" else "当前未配置同步服务，新内容只保存在本机。"
            )
        }
        item {
            OutlinedTextField(baseUrl, onBaseUrlChange, Modifier.fillMaxWidth(), !isBusy, label = { Text("服务地址") }, singleLine = true, shape = RoundedCornerShape(16.dp), colors = fieldColors)
        }
        item {
            OutlinedTextField(username, onUsernameChange, Modifier.fillMaxWidth(), !isBusy, label = { Text("用户名") }, singleLine = true, shape = RoundedCornerShape(16.dp), colors = fieldColors)
        }
        item {
            OutlinedTextField(password, onPasswordChange, Modifier.fillMaxWidth(), !isBusy, label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(16.dp), colors = fieldColors)
        }
        if (configured) {
            item { CapturePrimaryButton("清除同步配置", Icons.Outlined.DeleteOutline, !isBusy, danger = true, onClick = onClear) }
            item {
                Row { CaptureSecondaryButton("测试连接", Icons.Outlined.WifiTethering, !isBusy, onClick = onTest) }
            }
        }
        item {
            Row { CaptureSecondaryButton("添加快捷设置按钮", Icons.Outlined.AddComment, !isBusy, onClick = onRequestTile) }
        }
        item {
            Row { CaptureSecondaryButton("通知设置", Icons.Outlined.NotificationsOff, !isBusy, onClick = onOpenNotifications) }
        }
        item {
            CaptureStatusBand(
                if (volumeQuickCaptureEnabled) {
                    "音量减三击已开启。亮屏锁定时也可新增内容，拾记不读取屏幕内容。"
                } else {
                    "开启后，解锁或亮屏锁定时连续三次按音量减可快速记录。"
                }
            )
        }
        item {
            Row {
                CaptureSecondaryButton(
                    if (volumeQuickCaptureEnabled) "音量键快速记录 · 已开启" else "开启音量键快速记录",
                    Icons.Outlined.VolumeDown,
                    !isBusy,
                    onClick = onOpenVolumeQuickCapture
                )
            }
        }
        item {
            CapturePrimaryButton(
                "保存配置",
                Icons.Outlined.Save,
                !isBusy && baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                onClick = onSave
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun showToast(context: Context, message: String, long: Boolean = false) {
    Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

private fun SingleCaptureState.userLabel(): String = when (this) {
    SingleCaptureState.LocalGrace -> "可撤销"
    SingleCaptureState.Frozen -> "已保存"
    SingleCaptureState.Abandoned -> "已撤销"
}

private fun ReadingSessionState.userLabel(): String = when (this) {
    ReadingSessionState.Active -> "摘录中"
    ReadingSessionState.AwaitingFinish -> "待完成"
    ReadingSessionState.Frozen -> "已完成"
    ReadingSessionState.Abandoned -> "已放弃"
}

private fun OutboundRequestState.userLabel(): String = when (this) {
    OutboundRequestState.Pending -> "待同步"
    OutboundRequestState.Sending -> "同步中"
    OutboundRequestState.RetryWait -> "等待重试"
    OutboundRequestState.AuthRequired -> "需要认证"
    OutboundRequestState.Failed -> "同步永久失败"
}
