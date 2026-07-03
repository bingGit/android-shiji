package com.bing.androidvoiceflow

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bing.androidvoiceflow.audio.AndroidPcmAudioRecorder
import com.bing.androidvoiceflow.core.ProviderConfig
import com.bing.androidvoiceflow.core.RealtimeProviderProtocol
import com.bing.androidvoiceflow.core.RealtimeSession
import com.bing.androidvoiceflow.core.TranscriptionEvent
import com.bing.androidvoiceflow.provider.RealtimeProviderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private const val QUICK_RECORD_ACTION = "com.bing.androidvoiceflow.action.QUICK_RECORD"

private val Context.settingsDataStore by preferencesDataStore(name = "voiceflow_settings")

private object SettingsKeys {
    val RealtimeProtocol = stringPreferencesKey("realtime_protocol")
    val ProviderName = stringPreferencesKey("provider_name")
    val BaseUrl = stringPreferencesKey("base_url")
    val ApiKey = stringPreferencesKey("api_key")
    val RealtimeModel = stringPreferencesKey("realtime_model")
    val AliyunWorkspaceId = stringPreferencesKey("aliyun_workspace_id")
    val AliyunRegion = stringPreferencesKey("aliyun_region")
    val PostProcessProviderName = stringPreferencesKey("post_process_provider_name")
    val PostProcessBaseUrl = stringPreferencesKey("post_process_base_url")
    val PostProcessApiKey = stringPreferencesKey("post_process_api_key")
    val PostProcessModel = stringPreferencesKey("post_process_model")
    val StreamingEnabled = booleanPreferencesKey("streaming_enabled")
    val MaxRecordingSeconds = stringPreferencesKey("max_recording_seconds")
    val Prompt = stringPreferencesKey("prompt")
    val PostProcessPrompt = stringPreferencesKey("post_process_prompt")
    val Hotwords = stringPreferencesKey("hotwords")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchedFromQuickRecord = intent?.action == QUICK_RECORD_ACTION
        setContent {
            AndroidVoiceFlowApp(initialQuickRecordMode = launchedFromQuickRecord)
        }
    }
}

private enum class VoiceFlowStatus {
    Idle,
    RequestingPermission,
    Connecting,
    Recording,
    Finalizing,
    Completed,
    PostProcessing,
    Failed
}

private enum class VoiceFlowTab(val label: String) {
    Record("记录"),
    Cards("卡片"),
    Settings("设置")
}

private enum class PostProcessAction(val label: String, val resultTitle: String) {
    Summarize("提炼要点", "要点摘要"),
    Polish("润色表达", "润色版本"),
    Rewrite("整理改写", "成文版本")
}

private data class ProcessingResult(
    val id: Long,
    val type: PostProcessAction,
    val title: String,
    val content: String,
    val createdAt: Long,
    val model: String
)

private data class IdeaCard(
    val id: Long,
    val title: String,
    val originalTranscript: String,
    val createdAt: Long,
    val updatedAt: Long,
    val durationMs: Long,
    val realtimeModel: String,
    val processingResults: List<ProcessingResult> = emptyList(),
    val isFavorite: Boolean = false
)

private val AppColorScheme = lightColorScheme(
    primary = Color(0xFF1B6B63),
    onPrimary = Color.White,
    secondary = Color(0xFF765A2A),
    surface = Color(0xFFFFFCF4),
    background = Color(0xFFF7F7F2),
    error = Color(0xFFB3261E)
)

@Composable
private fun AndroidVoiceFlowApp(initialQuickRecordMode: Boolean) {
    MaterialTheme(colorScheme = AppColorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            VoiceFlowScreen(initialQuickRecordMode = initialQuickRecordMode)
        }
    }
}

@Composable
private fun VoiceFlowScreen(initialQuickRecordMode: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val audioRecorder = remember { AndroidPcmAudioRecorder() }
    val realtimeProviderFactory = remember { RealtimeProviderFactory() }

    var realtimeProtocol by remember { mutableStateOf(RealtimeProviderProtocol.AliyunParaformer) }
    var providerName by remember { mutableStateOf("阿里云 Paraformer") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var realtimeModel by remember { mutableStateOf("paraformer-realtime-v2") }
    var aliyunWorkspaceId by remember { mutableStateOf("") }
    var aliyunRegion by remember { mutableStateOf("cn-beijing") }
    var postProcessProviderName by remember { mutableStateOf("OpenAI-compatible Text") }
    var postProcessBaseUrl by remember { mutableStateOf("https://api.openai.com/v1") }
    var postProcessApiKey by remember { mutableStateOf("") }
    var postProcessModel by remember { mutableStateOf("gpt-4o-mini") }
    var streamingEnabled by remember { mutableStateOf(true) }
    var maxRecordingSeconds by remember { mutableStateOf("120") }
    var prompt by remember { mutableStateOf("请把用户语音实时转写为简洁准确的中文文本。") }
    var postProcessPrompt by remember { mutableStateOf("请保留原意，把口述内容整理为适合创作者继续使用的中文文本。") }
    var hotwords by remember { mutableStateOf("VoiceFlow, Obsidian, Android") }

    var selectedTab by remember { mutableStateOf(VoiceFlowTab.Record) }
    var quickRecordMode by remember { mutableStateOf(initialQuickRecordMode) }
    var status by remember { mutableStateOf(VoiceFlowStatus.Idle) }
    var connectionStatus by remember { mutableStateOf(if (initialQuickRecordMode) "快速记录入口已打开" else "未连接") }
    var partialTranscript by remember { mutableStateOf("") }
    var finalTranscript by remember { mutableStateOf("") }
    var postProcessTitle by remember { mutableStateOf("") }
    var postProcessResult by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var recoveryHint by remember { mutableStateOf("") }
    var copiedNotice by remember { mutableStateOf("") }
    var amplitude by remember { mutableStateOf(0.08f) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }
    var activeSession by remember { mutableStateOf<RealtimeSession?>(null) }
    var capturedAudioBytes by remember { mutableStateOf(0L) }
    var capturedChunkCount by remember { mutableStateOf(0) }
    var ideaCards by remember { mutableStateOf<List<IdeaCard>>(emptyList()) }
    var currentIdeaCardId by remember { mutableStateOf<Long?>(null) }
    var selectedIdeaCardId by remember { mutableStateOf<Long?>(null) }
    var settingsLoaded by remember { mutableStateOf(false) }

    val selectedIdeaCard = ideaCards.firstOrNull { it.id == selectedIdeaCardId }
    val activeTranscript = selectedIdeaCard?.originalTranscript ?: finalTranscript.trim()
    val recordTranscript = finalTranscript.ifBlank { partialTranscript }
    val actionableRecordTranscript = finalTranscript.trim()

    LaunchedEffect(Unit) {
        val settings = context.settingsDataStore.data.first()
        realtimeProtocol = settings[SettingsKeys.RealtimeProtocol]
            ?.let { runCatching { RealtimeProviderProtocol.valueOf(it) }.getOrNull() }
            ?: realtimeProtocol
        providerName = settings[SettingsKeys.ProviderName] ?: providerName
        baseUrl = settings[SettingsKeys.BaseUrl] ?: baseUrl
        apiKey = settings[SettingsKeys.ApiKey] ?: apiKey
        realtimeModel = settings[SettingsKeys.RealtimeModel] ?: realtimeModel
        aliyunWorkspaceId = settings[SettingsKeys.AliyunWorkspaceId] ?: aliyunWorkspaceId
        aliyunRegion = settings[SettingsKeys.AliyunRegion] ?: aliyunRegion
        postProcessProviderName = settings[SettingsKeys.PostProcessProviderName] ?: postProcessProviderName
        postProcessBaseUrl = settings[SettingsKeys.PostProcessBaseUrl] ?: postProcessBaseUrl
        postProcessApiKey = settings[SettingsKeys.PostProcessApiKey] ?: postProcessApiKey
        postProcessModel = settings[SettingsKeys.PostProcessModel] ?: postProcessModel
        streamingEnabled = settings[SettingsKeys.StreamingEnabled] ?: streamingEnabled
        maxRecordingSeconds = settings[SettingsKeys.MaxRecordingSeconds] ?: maxRecordingSeconds
        prompt = settings[SettingsKeys.Prompt] ?: prompt
        postProcessPrompt = settings[SettingsKeys.PostProcessPrompt] ?: postProcessPrompt
        hotwords = settings[SettingsKeys.Hotwords] ?: hotwords
        settingsLoaded = true
    }

    LaunchedEffect(
        settingsLoaded,
        realtimeProtocol,
        providerName,
        baseUrl,
        apiKey,
        realtimeModel,
        aliyunWorkspaceId,
        aliyunRegion,
        postProcessProviderName,
        postProcessBaseUrl,
        postProcessApiKey,
        postProcessModel,
        streamingEnabled,
        maxRecordingSeconds,
        prompt,
        postProcessPrompt,
        hotwords
    ) {
        if (!settingsLoaded) return@LaunchedEffect
        context.settingsDataStore.edit { settings ->
            settings[SettingsKeys.RealtimeProtocol] = realtimeProtocol.name
            settings[SettingsKeys.ProviderName] = providerName
            settings[SettingsKeys.BaseUrl] = baseUrl
            settings[SettingsKeys.ApiKey] = apiKey
            settings[SettingsKeys.RealtimeModel] = realtimeModel
            settings[SettingsKeys.AliyunWorkspaceId] = aliyunWorkspaceId
            settings[SettingsKeys.AliyunRegion] = aliyunRegion
            settings[SettingsKeys.PostProcessProviderName] = postProcessProviderName
            settings[SettingsKeys.PostProcessBaseUrl] = postProcessBaseUrl
            settings[SettingsKeys.PostProcessApiKey] = postProcessApiKey
            settings[SettingsKeys.PostProcessModel] = postProcessModel
            settings[SettingsKeys.StreamingEnabled] = streamingEnabled
            settings[SettingsKeys.MaxRecordingSeconds] = maxRecordingSeconds
            settings[SettingsKeys.Prompt] = prompt
            settings[SettingsKeys.PostProcessPrompt] = postProcessPrompt
            settings[SettingsKeys.Hotwords] = hotwords
        }
    }

    fun config(): ProviderConfig {
        return ProviderConfig(
            realtimeProtocol = realtimeProtocol,
            providerName = providerName,
            baseUrl = baseUrl,
            apiKey = apiKey,
            realtimeModel = realtimeModel,
            aliyunWorkspaceId = aliyunWorkspaceId,
            aliyunRegion = aliyunRegion,
            postProcessProviderName = postProcessProviderName,
            postProcessBaseUrl = postProcessBaseUrl,
            postProcessApiKey = postProcessApiKey,
            postProcessModel = postProcessModel,
            streamingEnabled = streamingEnabled,
            prompt = prompt,
            postProcessPrompt = postProcessPrompt,
            hotwords = hotwords.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            maxRecordingSeconds = maxRecordingSeconds.toIntOrNull()?.coerceIn(10, 600) ?: 120
        )
    }

    fun copyText(label: String, text: String): Boolean {
        if (text.isBlank()) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        return true
    }

    fun fail(message: String, hint: String) {
        status = VoiceFlowStatus.Failed
        errorMessage = message
        recoveryHint = hint
        connectionStatus = "需要处理"
        amplitude = 0.02f
    }

    fun createIdeaCard(finalText: String, currentConfig: ProviderConfig): IdeaCard {
        val now = System.currentTimeMillis()
        return IdeaCard(
            id = now,
            title = generateIdeaTitle(finalText),
            originalTranscript = finalText,
            createdAt = now,
            updatedAt = now,
            durationMs = (audioDurationSeconds(capturedAudioBytes, currentConfig) * 1000).toLong(),
            realtimeModel = currentConfig.realtimeModel
        )
    }

    fun finishRecording() {
        val currentConfig = config()
        val session = activeSession
        recordingJob?.cancel()
        recordingJob = null
        status = VoiceFlowStatus.Finalizing
        connectionStatus = "正在提交音频并生成最终文本"
        scope.launch {
            try {
                val transcript = session?.commit()
                val finalText = transcript?.text?.trim().orEmpty().ifBlank { finalTranscript.trim() }
                if (finalText.isBlank()) {
                    val capturedSeconds = audioDurationSeconds(capturedAudioBytes, currentConfig)
                    status = VoiceFlowStatus.Completed
                    connectionStatus = if (capturedAudioBytes > 0) {
                        "转写结果为空"
                    } else {
                        "未采集到音频"
                    }
                    partialTranscript = if (capturedAudioBytes > 0) {
                        "已采集 ${formatDuration(capturedSeconds)} PCM16 音频，${capturedChunkCount} 个分片，约 ${formatAudioBytes(capturedAudioBytes)}，但 provider 没有返回最终文本。"
                    } else {
                        "没有读到麦克风音频，请检查麦克风权限或设备输入。"
                    }
                    copiedNotice = ""
                    errorMessage = ""
                    recoveryHint = ""
                    amplitude = 0.06f
                    return@launch
                }
                finalTranscript = finalText
                postProcessTitle = ""
                postProcessResult = ""
                errorMessage = ""
                recoveryHint = ""
                status = VoiceFlowStatus.Completed
                connectionStatus = "已保存为灵感卡片"
                amplitude = 0.06f
                if (copyText("VoiceFlow idea transcript", finalText)) {
                    copiedNotice = "原始灵感已复制到剪贴板"
                }
                val ideaCard = createIdeaCard(finalText, currentConfig)
                ideaCards = listOf(ideaCard) + ideaCards.take(19)
                currentIdeaCardId = ideaCard.id
                selectedIdeaCardId = null
            } catch (error: Exception) {
                fail(
                    message = "实时转写提交失败",
                    hint = error.message ?: "请检查网络、API Key、Base URL 和实时模型是否兼容。"
                )
            } finally {
                activeSession = null
            }
        }
    }

    fun startRecording() {
        val currentConfig = config()
        selectedTab = VoiceFlowTab.Record
        quickRecordMode = true
        errorMessage = ""
        recoveryHint = ""
        copiedNotice = ""
        partialTranscript = "正在连接 ${currentConfig.providerName}，准备实时转写。"
        finalTranscript = ""
        postProcessTitle = ""
        postProcessResult = ""
        selectedIdeaCardId = null
        currentIdeaCardId = null
        capturedAudioBytes = 0L
        capturedChunkCount = 0
        status = VoiceFlowStatus.Connecting
        connectionStatus = "正在连接实时转写 provider"

        recordingJob?.cancel()
        recordingJob = scope.launch {
            var eventJob: Job? = null
            var levelJob: Job? = null
            try {
                val transcriptionProvider = realtimeProviderFactory.create(currentConfig)
                val session = transcriptionProvider.startSession(currentConfig)
                activeSession = session
                eventJob = launch {
                    session.events.collect { event ->
                        when (event) {
                            is TranscriptionEvent.Connected -> {
                                connectionStatus = "已连接 ${event.providerName}"
                            }
                            is TranscriptionEvent.AudioLevel -> {
                                amplitude = event.value.coerceIn(0.02f, 1f)
                            }
                            is TranscriptionEvent.PartialTranscript -> {
                                partialTranscript = event.text
                            }
                            is TranscriptionEvent.FinalTranscriptReady -> {
                                finalTranscript = event.transcript.text
                                partialTranscript = event.transcript.text
                            }
                            is TranscriptionEvent.Failed -> {
                                fail(event.error.message, event.error.recoveryHint)
                            }
                        }
                    }
                }
                audioRecorder.start(currentConfig.audioFormat)
                status = VoiceFlowStatus.Recording
                connectionStatus = "正在听..."
                levelJob = launch {
                    audioRecorder.audioLevels.collect { level ->
                        amplitude = level.coerceIn(0.02f, 1f)
                    }
                }
                while (isActive) {
                    val chunk = audioRecorder.readChunk()
                    capturedAudioBytes += chunk.size
                    capturedChunkCount += 1
                    session.sendAudioChunk(chunk)
                    if (partialTranscript.isBlank()) {
                        val capturedSeconds = audioDurationSeconds(capturedAudioBytes, currentConfig)
                        connectionStatus = "已发送 ${formatDuration(capturedSeconds)} 音频，等待转写"
                        partialTranscript = "正在实时转写：已发送约 ${formatAudioBytes(capturedAudioBytes)} 音频。"
                    }
                }
            } catch (_: ClosedReceiveChannelException) {
                // Stopping the recorder closes the chunk channel.
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail(
                    message = "实时转写不可用",
                    hint = error.message ?: "请确认麦克风、网络和 provider 配置可用。"
                )
            } finally {
                levelJob?.cancel()
                eventJob?.cancel()
                withContext(NonCancellable) {
                    audioRecorder.stop()
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            fail(
                message = "麦克风权限被拒绝",
                hint = "请在系统设置中允许 Android VoiceFlow 使用麦克风。"
            )
        }
    }

    fun requestStartRecording() {
        selectedTab = VoiceFlowTab.Record
        val permission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            status = VoiceFlowStatus.RequestingPermission
            connectionStatus = "正在请求麦克风权限"
            permissionLauncher.launch(permission)
        }
    }

    fun appendProcessingResult(action: PostProcessAction, result: String) {
        val targetCardId = selectedIdeaCardId ?: currentIdeaCardId ?: return
        val now = System.currentTimeMillis()
        val processingResult = ProcessingResult(
            id = now,
            type = action,
            title = action.resultTitle,
            content = result,
            createdAt = now,
            model = postProcessModel
        )
        ideaCards = ideaCards.map { card ->
            if (card.id == targetCardId) {
                card.copy(
                    updatedAt = now,
                    processingResults = listOf(processingResult) + card.processingResults
                )
            } else {
                card
            }
        }
    }

    fun runPostProcess(action: PostProcessAction) {
        val sourceText = activeTranscript.trim()
        if (sourceText.isBlank()) {
            fail(
                message = "没有可处理的转写文本",
                hint = "当前版本已接入本地音频采集，实时转写 provider 接入后才会生成可处理文本。"
            )
            return
        }
        status = VoiceFlowStatus.PostProcessing
        postProcessTitle = action.resultTitle
        postProcessResult = ""
        errorMessage = ""
        recoveryHint = ""
        copiedNotice = ""
        scope.launch {
            delay(500)
            val result = when (action) {
                PostProcessAction.Summarize -> summarizeText(sourceText)
                PostProcessAction.Polish -> polishText(sourceText)
                PostProcessAction.Rewrite -> rewriteText(sourceText)
            }
            postProcessResult = result
            errorMessage = ""
            recoveryHint = ""
            status = VoiceFlowStatus.Completed
            appendProcessingResult(action, result)
        }
    }

    LaunchedEffect(initialQuickRecordMode) {
        if (initialQuickRecordMode) {
            requestStartRecording()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Header(
            status = status,
            connectionStatus = connectionStatus,
            quickRecordMode = quickRecordMode,
            onEnterQuickRecord = { requestStartRecording() }
        )
        VoiceFlowTabs(
            selectedTab = selectedTab,
            onTabSelected = { nextTab ->
                selectedTab = nextTab
                if (nextTab == VoiceFlowTab.Record) {
                    selectedIdeaCardId = null
                }
            }
        )
        when (selectedTab) {
            VoiceFlowTab.Record -> {
                RecorderPanel(
                    status = status,
                    connectionStatus = connectionStatus,
                    amplitude = amplitude,
                    transcript = recordTranscript,
                    copiedNotice = copiedNotice,
                    errorMessage = errorMessage,
                    recoveryHint = recoveryHint,
                    hasTranscript = actionableRecordTranscript.isNotBlank(),
                    onPrimaryAction = {
                        when (status) {
                            VoiceFlowStatus.Recording,
                            VoiceFlowStatus.Connecting -> finishRecording()
                            else -> requestStartRecording()
                        }
                    },
                    onCopyTranscript = {
                        val copied = copyText("VoiceFlow idea transcript", actionableRecordTranscript)
                        copiedNotice = if (copied) "原始灵感已复制到剪贴板" else "没有可复制的文本"
                    },
                    onPostProcess = ::runPostProcess
                )
                if (postProcessTitle.isNotBlank() || status == VoiceFlowStatus.PostProcessing) {
                    PostProcessPanel(
                        title = postProcessTitle,
                        result = postProcessResult,
                        isLoading = status == VoiceFlowStatus.PostProcessing,
                        onCopy = {
                            val copied = copyText("VoiceFlow $postProcessTitle", postProcessResult)
                            copiedNotice = if (copied) "$postProcessTitle 已复制" else "没有可复制的后处理结果"
                        }
                    )
                }
            }
            VoiceFlowTab.Cards -> {
                selectedIdeaCard?.let { card ->
                    IdeaCardDetailPanel(
                        card = card,
                        onCopyOriginal = {
                            val copied = copyText("VoiceFlow idea", card.originalTranscript)
                            copiedNotice = if (copied) "灵感原文已复制" else "灵感原文为空"
                        },
                        onCopyResult = { result ->
                            val copied = copyText("VoiceFlow ${result.title}", result.content)
                            copiedNotice = if (copied) "${result.title} 已复制" else "处理结果为空"
                        },
                        onPostProcess = ::runPostProcess
                    )
                }
                IdeaCardsPanel(
                    ideaCards = ideaCards,
                    selectedIdeaCardId = selectedIdeaCardId,
                    onSelect = { selectedIdeaCardId = it.id },
                    onCopy = { item ->
                        val copied = copyText("VoiceFlow idea", item.originalTranscript)
                        copiedNotice = if (copied) "灵感原文已复制" else "灵感原文为空"
                    },
                    onDelete = { item ->
                        ideaCards = ideaCards.filterNot { it.id == item.id }
                        if (selectedIdeaCardId == item.id) selectedIdeaCardId = null
                        if (currentIdeaCardId == item.id) currentIdeaCardId = null
                    }
                )
            }
            VoiceFlowTab.Settings -> {
                SettingsPanel(
                    realtimeProtocol = realtimeProtocol,
                    onRealtimeProtocolChange = { nextProtocol ->
                        realtimeProtocol = nextProtocol
                        when (nextProtocol) {
                            RealtimeProviderProtocol.AliyunParaformer -> {
                                if (providerName.isBlank() || providerName.contains("OpenAI", ignoreCase = true)) {
                                    providerName = "阿里云 Paraformer"
                                }
                                if (realtimeModel.isBlank() || realtimeModel.startsWith("gpt", ignoreCase = true)) {
                                    realtimeModel = "paraformer-realtime-v2"
                                }
                            }
                            RealtimeProviderProtocol.OpenAiRealtime -> {
                                if (providerName.isBlank() || providerName.contains("阿里云")) {
                                    providerName = "OpenAI-compatible Realtime"
                                }
                                if (realtimeModel.isBlank() || realtimeModel.startsWith("paraformer")) {
                                    realtimeModel = "gpt-realtime"
                                }
                                if (baseUrl.isBlank()) {
                                    baseUrl = "https://api.openai.com/v1/realtime"
                                }
                            }
                        }
                    },
                    providerName = providerName,
                    onProviderNameChange = { providerName = it },
                    baseUrl = baseUrl,
                    onBaseUrlChange = { baseUrl = it },
                    apiKey = apiKey,
                    onApiKeyChange = { apiKey = it },
                    realtimeModel = realtimeModel,
                    onRealtimeModelChange = { realtimeModel = it },
                    aliyunWorkspaceId = aliyunWorkspaceId,
                    onAliyunWorkspaceIdChange = { aliyunWorkspaceId = it.trim() },
                    aliyunRegion = aliyunRegion,
                    onAliyunRegionChange = { aliyunRegion = it.trim() },
                    postProcessProviderName = postProcessProviderName,
                    onPostProcessProviderNameChange = { postProcessProviderName = it },
                    postProcessBaseUrl = postProcessBaseUrl,
                    onPostProcessBaseUrlChange = { postProcessBaseUrl = it },
                    postProcessApiKey = postProcessApiKey,
                    onPostProcessApiKeyChange = { postProcessApiKey = it },
                    postProcessModel = postProcessModel,
                    onPostProcessModelChange = { postProcessModel = it },
                    streamingEnabled = streamingEnabled,
                    onStreamingEnabledChange = { streamingEnabled = it },
                    maxRecordingSeconds = maxRecordingSeconds,
                    onMaxRecordingSecondsChange = { maxRecordingSeconds = it.filter { char -> char.isDigit() }.take(3) },
                    prompt = prompt,
                    onPromptChange = { prompt = it },
                    postProcessPrompt = postProcessPrompt,
                    onPostProcessPromptChange = { postProcessPrompt = it },
                    hotwords = hotwords,
                    onHotwordsChange = { hotwords = it },
                    onTestRealtimeConnection = {
                        scope.launch {
                            val currentConfig = config()
                            val transcriptionProvider = realtimeProviderFactory.create(currentConfig)
                            val result = transcriptionProvider.testConnection(currentConfig)
                            connectionStatus = if (result.success) {
                                result.detail ?: result.summary
                            } else {
                                listOfNotNull(result.summary, result.detail).joinToString("：")
                            }
                        }
                    },
                    onTestPostProcessConnection = {
                        connectionStatus = when {
                            postProcessApiKey.isBlank() -> "后处理连接测试失败：API Key 为空"
                            postProcessBaseUrl.isBlank() -> "后处理连接测试失败：Base URL 为空"
                            postProcessModel.isBlank() -> "后处理连接测试失败：文本模型为空"
                            else -> "后处理配置完整：${postProcessProviderName} / ${postProcessModel}"
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun Header(
    status: VoiceFlowStatus,
    connectionStatus: String,
    quickRecordMode: Boolean,
    onEnterQuickRecord: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text(
                    text = "Android VoiceFlow",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (quickRecordMode) "快速记录灵感，稍后整理成内容" else "创作者语音灵感捕捉入口",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF5F665F),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!quickRecordMode) {
                TextButton(onClick = onEnterQuickRecord) {
                    Text("快速记录")
                }
            }
        }
        if (status != VoiceFlowStatus.Failed) {
            StatusBadge(status = status, connectionStatus = connectionStatus)
        }
    }
}

@Composable
private fun VoiceFlowTabs(
    selectedTab: VoiceFlowTab,
    onTabSelected: (VoiceFlowTab) -> Unit
) {
    val tabs = VoiceFlowTab.entries
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        tonalElevation = 1.dp
    ) {
        PrimaryTabRow(
            selectedTabIndex = tabs.indexOf(selectedTab),
            containerColor = Color.White,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    text = {
                        Text(
                            text = tab.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: VoiceFlowStatus, connectionStatus: String) {
    val badgeColor = when (status) {
        VoiceFlowStatus.Recording -> Color(0xFFDDEFE8)
        VoiceFlowStatus.Failed -> Color(0xFFFFE2DF)
        VoiceFlowStatus.Completed -> Color(0xFFE5EDFF)
        else -> Color(0xFFEAE7DA)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(badgeColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(statusColor(status))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = connectionStatus,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RecorderPanel(
    status: VoiceFlowStatus,
    connectionStatus: String,
    amplitude: Float,
    transcript: String,
    copiedNotice: String,
    errorMessage: String,
    recoveryHint: String,
    hasTranscript: Boolean,
    onPrimaryAction: () -> Unit,
    onCopyTranscript: () -> Unit,
    onPostProcess: (PostProcessAction) -> Unit
) {
    val primaryActionEnabled = status != VoiceFlowStatus.RequestingPermission &&
        status != VoiceFlowStatus.Finalizing &&
        status != VoiceFlowStatus.PostProcessing

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = status.userText(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (status == VoiceFlowStatus.Failed) {
                            "查看下方错误提示"
                        } else {
                            connectionStatus
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF687069),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    enabled = primaryActionEnabled,
                    onClick = onPrimaryAction
                ) {
                    Text(
                        text = if (status == VoiceFlowStatus.Recording || status == VoiceFlowStatus.Connecting) {
                            "停止并保存"
                        } else {
                            "记录灵感"
                        }
                    )
                }
            }

            Waveform(amplitude = amplitude)

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFF8FAF7)
            ) {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = transcript.ifBlank { "点击“记录灵感”，先把想法说出来。" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (transcript.isBlank()) Color(0xFF7B827B) else Color(0xFF1F2924)
                )
            }

            if (status == VoiceFlowStatus.Failed && errorMessage.isNotBlank()) {
                ErrorPanel(message = errorMessage, hint = recoveryHint)
            }

            if (copiedNotice.isNotBlank()) {
                Text(
                    text = copiedNotice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = hasTranscript,
                    onClick = onCopyTranscript
                ) {
                    Text("复制原文")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = hasTranscript && status != VoiceFlowStatus.PostProcessing,
                    onClick = { onPostProcess(PostProcessAction.Summarize) }
                ) {
                    Text("提炼要点")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = hasTranscript && status != VoiceFlowStatus.PostProcessing,
                    onClick = { onPostProcess(PostProcessAction.Polish) }
                ) {
                    Text("润色表达")
                }
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = hasTranscript && status != VoiceFlowStatus.PostProcessing,
                onClick = { onPostProcess(PostProcessAction.Rewrite) }
            ) {
                Text("整理改写成文")
            }
        }
    }
}

@Composable
private fun ErrorPanel(message: String, hint: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFFFF6F4)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (hint.isNotBlank()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF7A3A33),
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun Waveform(amplitude: Float) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        val bars = 24
        val gap = 7.dp.toPx()
        val barWidth = max(3.dp.toPx(), (size.width - gap * (bars - 1)) / bars)
        val centerY = size.height / 2f
        drawLine(
            color = Color(0xFFE2E6DE),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round
        )
        repeat(bars) { index ->
            val distance = abs(index - bars / 2f) / (bars / 2f)
            val localLevel = (amplitude * (1f - distance * 0.55f)).coerceIn(0.05f, 1f)
            val height = (size.height * localLevel).coerceAtLeast(6.dp.toPx())
            val x = index * (barWidth + gap)
            drawRoundRect(
                color = Color(0xFF1B6B63),
                topLeft = Offset(x, centerY - height / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
            )
        }
    }
}

@Composable
private fun PostProcessPanel(
    title: String,
    result: String,
    isLoading: Boolean,
    onCopy: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title.ifBlank { "二次处理" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(
                    enabled = result.isNotBlank(),
                    onClick = onCopy
                ) {
                    Text("复制结果")
                }
            }
            Text(
                text = if (isLoading) "正在生成..." else result,
                style = MaterialTheme.typography.bodyLarge,
                color = if (result.isBlank()) Color(0xFF7B827B) else Color(0xFF1F2924)
            )
        }
    }
}

@Composable
private fun IdeaCardDetailPanel(
    card: IdeaCard,
    onCopyOriginal: () -> Unit,
    onCopyResult: (ProcessingResult) -> Unit,
    onPostProcess: (PostProcessAction) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${formatDisplayTime(card.createdAt)} · ${formatDuration(card.durationMs / 1000f)} · ${card.statusLabel()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF687069)
                    )
                }
                TextButton(onClick = onCopyOriginal) {
                    Text("复制原文")
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFF8FAF7)
            ) {
                Text(
                    modifier = Modifier.padding(14.dp),
                    text = card.originalTranscript,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF1F2924)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PostProcessAction.entries.forEach { action ->
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { onPostProcess(action) }
                    ) {
                        Text(action.label, maxLines = 1)
                    }
                }
            }
            if (card.processingResults.isNotEmpty()) {
                Text(
                    text = "处理版本",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    card.processingResults.forEach { result ->
                        ProcessingResultRow(
                            result = result,
                            onCopy = { onCopyResult(result) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessingResultRow(result: ProcessingResult, onCopy: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFFFFCF4)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(result.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${formatDisplayTime(result.createdAt)} · ${result.model}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF687069)
                    )
                }
                TextButton(onClick = onCopy) {
                    Text("复制")
                }
            }
            Text(
                text = result.content,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SettingsPanel(
    realtimeProtocol: RealtimeProviderProtocol,
    onRealtimeProtocolChange: (RealtimeProviderProtocol) -> Unit,
    providerName: String,
    onProviderNameChange: (String) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    realtimeModel: String,
    onRealtimeModelChange: (String) -> Unit,
    aliyunWorkspaceId: String,
    onAliyunWorkspaceIdChange: (String) -> Unit,
    aliyunRegion: String,
    onAliyunRegionChange: (String) -> Unit,
    postProcessProviderName: String,
    onPostProcessProviderNameChange: (String) -> Unit,
    postProcessBaseUrl: String,
    onPostProcessBaseUrlChange: (String) -> Unit,
    postProcessApiKey: String,
    onPostProcessApiKeyChange: (String) -> Unit,
    postProcessModel: String,
    onPostProcessModelChange: (String) -> Unit,
    streamingEnabled: Boolean,
    onStreamingEnabledChange: (Boolean) -> Unit,
    maxRecordingSeconds: String,
    onMaxRecordingSecondsChange: (String) -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    postProcessPrompt: String,
    onPostProcessPromptChange: (String) -> Unit,
    hotwords: String,
    onHotwordsChange: (String) -> Unit,
    onTestRealtimeConnection: () -> Unit,
    onTestPostProcessConnection: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = "Provider 设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            ProtocolSelector(
                selectedProtocol = realtimeProtocol,
                onProtocolSelected = onRealtimeProtocolChange
            )

            ProviderConfigSection(
                title = "实时语音转写",
                description = when (realtimeProtocol) {
                    RealtimeProviderProtocol.AliyunParaformer -> "接入阿里云百炼 Paraformer 实时语音识别。优先填写 Workspace ID，完整 WebSocket URL 可留空。"
                    RealtimeProviderProtocol.OpenAiRealtime -> "接入 OpenAI-compatible Realtime WebSocket，可配置官方地址或兼容中转站。"
                },
                providerName = providerName,
                onProviderNameChange = onProviderNameChange,
                baseUrl = baseUrl,
                onBaseUrlChange = onBaseUrlChange,
                baseUrlLabel = when (realtimeProtocol) {
                    RealtimeProviderProtocol.AliyunParaformer -> "完整 WebSocket URL（可选）"
                    RealtimeProviderProtocol.OpenAiRealtime -> "Base URL"
                },
                apiKey = apiKey,
                onApiKeyChange = onApiKeyChange,
                model = realtimeModel,
                onModelChange = onRealtimeModelChange,
                modelLabel = "实时转写模型",
                onTestConnection = onTestRealtimeConnection
            )

            if (realtimeProtocol == RealtimeProviderProtocol.AliyunParaformer) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFF8FAF7)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "阿里云百炼参数",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "官方端点会由 Workspace ID 自动生成：wss://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF687069)
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = aliyunWorkspaceId,
                            onValueChange = onAliyunWorkspaceIdChange,
                            singleLine = true,
                            label = { Text("Workspace ID") }
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = aliyunRegion,
                            onValueChange = onAliyunRegionChange,
                            singleLine = true,
                            label = { Text("Region") }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用流式转写", fontWeight = FontWeight.Medium)
                    Text(
                        text = "关闭后可作为录完上传 provider 的扩展入口",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF687069)
                    )
                }
                Switch(
                    checked = streamingEnabled,
                    onCheckedChange = onStreamingEnabledChange
                )
            }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = maxRecordingSeconds,
                onValueChange = onMaxRecordingSecondsChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text("最大录音时长（秒）") }
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = prompt,
                onValueChange = onPromptChange,
                minLines = 2,
                label = { Text("实时转写 prompt") }
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = hotwords,
                onValueChange = onHotwordsChange,
                singleLine = true,
                label = { Text("热词 / 术语表") }
            )

            ProviderConfigSection(
                title = "后续文本处理",
                description = "用于润色、提炼要点、整理改写，可以走另一个文本模型中转站。",
                providerName = postProcessProviderName,
                onProviderNameChange = onPostProcessProviderNameChange,
                baseUrl = postProcessBaseUrl,
                onBaseUrlChange = onPostProcessBaseUrlChange,
                apiKey = postProcessApiKey,
                onApiKeyChange = onPostProcessApiKeyChange,
                model = postProcessModel,
                onModelChange = onPostProcessModelChange,
                modelLabel = "后处理文本模型",
                onTestConnection = onTestPostProcessConnection
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = postProcessPrompt,
                onValueChange = onPostProcessPromptChange,
                minLines = 2,
                label = { Text("后处理 prompt") }
            )
        }
    }
}

@Composable
private fun ProtocolSelector(
    selectedProtocol: RealtimeProviderProtocol,
    onProtocolSelected: (RealtimeProviderProtocol) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFF8FAF7)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "实时转写协议",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ProtocolButton(
                    modifier = Modifier.weight(1f),
                    selected = selectedProtocol == RealtimeProviderProtocol.AliyunParaformer,
                    label = "阿里云百炼",
                    onClick = { onProtocolSelected(RealtimeProviderProtocol.AliyunParaformer) }
                )
                ProtocolButton(
                    modifier = Modifier.weight(1f),
                    selected = selectedProtocol == RealtimeProviderProtocol.OpenAiRealtime,
                    label = "OpenAI Realtime",
                    onClick = { onProtocolSelected(RealtimeProviderProtocol.OpenAiRealtime) }
                )
            }
        }
    }
}

@Composable
private fun ProtocolButton(
    modifier: Modifier,
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    if (selected) {
        Button(
            modifier = modifier,
            onClick = onClick
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(
            modifier = modifier,
            onClick = onClick
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ProviderConfigSection(
    title: String,
    description: String,
    providerName: String,
    onProviderNameChange: (String) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    baseUrlLabel: String = "Base URL",
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    modelLabel: String,
    onTestConnection: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFF8FAF7)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF687069)
                )
            }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = providerName,
                onValueChange = onProviderNameChange,
                singleLine = true,
                label = { Text("Provider 名称") }
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                singleLine = true,
                label = { Text(baseUrlLabel) }
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = apiKey,
                onValueChange = onApiKeyChange,
                singleLine = true,
                label = { Text("API Key") },
                visualTransformation = PasswordVisualTransformation()
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = model,
                onValueChange = onModelChange,
                singleLine = true,
                label = { Text(modelLabel) }
            )
            OutlinedButton(onClick = onTestConnection) {
                Text("测试${title}连接")
            }
        }
    }
}

@Composable
private fun IdeaCardsPanel(
    ideaCards: List<IdeaCard>,
    selectedIdeaCardId: Long?,
    onSelect: (IdeaCard) -> Unit,
    onCopy: (IdeaCard) -> Unit,
    onDelete: (IdeaCard) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "最近灵感卡片",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (ideaCards.isEmpty()) {
                Text(
                    text = "暂无灵感卡片。完成一次真实转写后会自动保存到这里。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF687069)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(ideaCards, key = { it.id }) { item ->
                        IdeaCardRow(
                            item = item,
                            isSelected = selectedIdeaCardId == item.id,
                            onSelect = { onSelect(item) },
                            onCopy = { onCopy(item) },
                            onDelete = { onDelete(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IdeaCardRow(
    item: IdeaCard,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val background = if (isSelected) Color(0xFFEAF4EF) else Color.Transparent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatDisplayTime(item.createdAt)} · ${item.statusLabel()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF687069)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSelect) {
                    Text(if (isSelected) "已打开" else "打开")
                }
                TextButton(onClick = onCopy) {
                    Text("复制")
                }
                TextButton(onClick = onDelete) {
                    Text("删除")
                }
            }
        }
        Text(
            text = item.originalTranscript,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
        if (item.processingResults.isNotEmpty()) {
            Text(
                text = item.processingResults.joinToString(" / ") { it.title },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF4F5F58)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFFE7E8E2))
        )
    }
}

private fun VoiceFlowStatus.userText(): String {
    return when (this) {
        VoiceFlowStatus.Idle -> "准备记录灵感"
        VoiceFlowStatus.RequestingPermission -> "等待麦克风权限"
        VoiceFlowStatus.Connecting -> "正在启动记录"
        VoiceFlowStatus.Recording -> "正在听..."
        VoiceFlowStatus.Finalizing -> "正在生成最终文本"
        VoiceFlowStatus.Completed -> "记录完成"
        VoiceFlowStatus.PostProcessing -> "正在整理文本"
        VoiceFlowStatus.Failed -> "需要处理错误"
    }
}

private fun statusColor(status: VoiceFlowStatus): Color {
    return when (status) {
        VoiceFlowStatus.Recording -> Color(0xFF1B6B63)
        VoiceFlowStatus.Failed -> Color(0xFFB3261E)
        VoiceFlowStatus.Completed -> Color(0xFF3157A6)
        VoiceFlowStatus.Connecting,
        VoiceFlowStatus.Finalizing,
        VoiceFlowStatus.PostProcessing -> Color(0xFF9A6A15)
        else -> Color(0xFF7B827B)
    }
}

private fun IdeaCard.statusLabel(): String {
    if (processingResults.isEmpty()) return "未处理"
    val labels = processingResults.map { it.type.label }.distinct()
    return labels.joinToString(" / ")
}

private fun generateIdeaTitle(text: String): String {
    val cleaned = text
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .trim()
    if (cleaned.isBlank()) return "未命名灵感"
    val firstSentence = cleaned.split(Regex("[。！？!?\\n]+"))
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        .orEmpty()
    val candidate = if (firstSentence.length in 6..28) firstSentence else cleaned
    return candidate.take(28)
}

private fun summarizeText(text: String): String {
    val sentences = text
        .split(Regex("[。！？!?\\n]+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .take(4)
    if (sentences.isEmpty()) return text.take(120)
    return sentences.joinToString(separator = "\n") { "- $it" }
}

private fun polishText(text: String): String {
    return text
        .replace("然后", "随后")
        .replace("就是", "")
        .replace("这个", "这项")
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(separator = "\n")
}

private fun rewriteText(text: String): String {
    val cleaned = polishText(text)
    return "创作草稿：\n$cleaned\n\n下一步：补充一个具体例子，再提炼一个更有冲突感的标题。"
}

private fun audioDurationSeconds(bytes: Long, config: ProviderConfig): Float {
    val bytesPerSample = 2
    val bytesPerSecond = config.audioFormat.sampleRateHz *
        config.audioFormat.channelCount *
        bytesPerSample
    if (bytesPerSecond <= 0) return 0f
    return bytes / bytesPerSecond.toFloat()
}

private fun formatDuration(seconds: Float): String {
    return if (seconds < 10f) {
        String.format(Locale.getDefault(), "%.1f 秒", seconds)
    } else {
        "${seconds.toInt()} 秒"
    }
}

private fun formatAudioBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kib = bytes / 1024f
    if (kib < 1024f) return String.format(Locale.getDefault(), "%.1f KB", kib)
    return String.format(Locale.getDefault(), "%.2f MB", kib / 1024f)
}

private fun formatDisplayTime(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}
