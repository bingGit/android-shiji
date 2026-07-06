package com.bing.androidvoiceflow

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bing.androidvoiceflow.audio.AndroidPcmAudioRecorder
import com.bing.androidvoiceflow.core.ProviderConfig
import com.bing.androidvoiceflow.core.RealtimeProviderProtocol
import com.bing.androidvoiceflow.core.RealtimeSession
import com.bing.androidvoiceflow.core.TranscriptionEvent
import com.bing.androidvoiceflow.provider.OpenAiCompatibleTextPostProcessProvider
import com.bing.androidvoiceflow.provider.RealtimeProviderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
    val IdeaCardsJson = stringPreferencesKey("idea_cards_json")
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

private enum class VoiceFlowTab(val label: String, val navMark: String) {
    Record("记录", "R"),
    Cards("卡片", "C"),
    Settings("设置", "S")
}

private enum class PostProcessAction(
    val label: String,
    val resultTitle: String,
    val instruction: String
) {
    Summarize(
        label = "提炼要点",
        resultTitle = "要点摘要",
        instruction = "把口述内容整理成 3 到 6 条要点，保留具体信息和判断，不添加原文没有的结论。"
    ),
    Polish(
        label = "润色表达",
        resultTitle = "润色版本",
        instruction = "保留原意，去掉口语停顿和重复，让表达更自然、清楚、有节奏。"
    ),
    Rewrite(
        label = "整理成文",
        resultTitle = "成文版本",
        instruction = "把碎片口述整理成一段可继续编辑的创作草稿，结构清楚，语气自然。"
    ),
    ExtractViewpoint(
        label = "提炼观点",
        resultTitle = "核心观点",
        instruction = "从原文中提炼一个最核心的观点，并补充 2 到 4 条支撑理由。"
    ),
    GenerateTitle(
        label = "生成标题",
        resultTitle = "标题候选",
        instruction = "基于原文生成 8 个中文标题候选，兼顾清晰、冲突感和传播性。"
    ),
    ExpandArticle(
        label = "扩写段落",
        resultTitle = "文章段落",
        instruction = "把原始灵感扩写成 2 到 4 段文章正文，保留原意，并让段落之间有递进关系。"
    ),
    Xiaohongshu(
        label = "小红书风格",
        resultTitle = "小红书版本",
        instruction = "把原文改写成小红书笔记草稿，包含吸引人的开头、分段正文和可复制的表达。"
    ),
    WeChatOpening(
        label = "公众号开头",
        resultTitle = "公众号开头",
        instruction = "把原文改写成公众号文章开头，用一个具体问题或判断切入，引出后续展开。"
    ),
    ShortVideoScript(
        label = "口播稿",
        resultTitle = "短视频口播稿",
        instruction = "把原文改写成短视频口播稿，句子短，节奏明确，适合直接朗读。"
    )
}

private data class ProcessingResult(
    val id: Long,
    val type: PostProcessAction,
    val title: String,
    val content: String,
    val createdAt: Long,
    val model: String,
    val isEdited: Boolean = false
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

private class CaptureStats {
    var audioBytes: Long = 0L
    var chunkCount: Int = 0

    fun reset() {
        audioBytes = 0L
        chunkCount = 0
    }
}

private val AppColorScheme = lightColorScheme(
    primary = Color(0xFF376854),
    onPrimary = Color.White,
    secondary = Color(0xFF7B6F63),
    surface = Color(0xFFF8F5EF),
    background = Color(0xFFF8F5EF),
    error = Color(0xFFB85B48)
)

private object V3Color {
    val Background = Color(0xFFF8F5EF)
    val Surface = Color(0x00FFFFFF)
    val SoftSurface = Color(0x66FFFFFF)
    val TextPrimary = Color(0xFF23302A)
    val TextSecondary = Color(0xFF68756E)
    val TextMuted = Color(0xFF8A948E)
    val Green = Color(0xFF376854)
    val GreenSoft = Color(0xFFE9F2EA)
    val Warm = Color(0xFFD8664F)
    val WarmSoft = Color(0xFFF8EDE8)
    val Secondary = Color(0xFF7B6F63)
    val Sand = Color(0xFFF3F1EA)
    val Line = Color(0xFFE8E2D7)
}

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
    val textPostProcessProvider = remember { OpenAiCompatibleTextPostProcessProvider() }

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
    var runningPostProcessAction by remember { mutableStateOf<PostProcessAction?>(null) }
    var morePostProcessActionsExpanded by remember { mutableStateOf(false) }
    val captureStats = remember { CaptureStats() }
    var ideaCards by remember { mutableStateOf<List<IdeaCard>>(emptyList()) }
    var currentIdeaCardId by remember { mutableStateOf<Long?>(null) }
    var selectedIdeaCardId by remember { mutableStateOf<Long?>(null) }
    var pendingDeleteCard by remember { mutableStateOf<IdeaCard?>(null) }
    var pendingDeleteResult by remember { mutableStateOf<ProcessingResult?>(null) }
    var settingsLoaded by remember { mutableStateOf(false) }

    val selectedIdeaCard = ideaCards.firstOrNull { it.id == selectedIdeaCardId }
    val currentIdeaCard = ideaCards.firstOrNull { it.id == currentIdeaCardId }
    val recordProcessingResults = currentIdeaCard?.processingResults.orEmpty()
    val activeTranscript = selectedIdeaCard?.originalTranscript ?: finalTranscript.trim()
    val recordTranscript = finalTranscript.ifBlank { partialTranscript }
    val actionableRecordTranscript = finalTranscript.trim()
    val canUsePostProcessDock = when (selectedTab) {
        VoiceFlowTab.Record -> actionableRecordTranscript.isNotBlank()
        VoiceFlowTab.Cards -> false
        VoiceFlowTab.Settings -> false
    }

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
        val savedCards = settings[SettingsKeys.IdeaCardsJson]
            ?.let(::decodeIdeaCards)
            .orEmpty()
        ideaCards = savedCards
        currentIdeaCardId = savedCards.firstOrNull()?.id
        settingsLoaded = true
    }

    LaunchedEffect(settingsLoaded, ideaCards) {
        if (!settingsLoaded) return@LaunchedEffect
        context.settingsDataStore.edit { settings ->
            settings[SettingsKeys.IdeaCardsJson] = encodeIdeaCards(ideaCards)
        }
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
            durationMs = (audioDurationSeconds(captureStats.audioBytes, currentConfig) * 1000).toLong(),
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
                    partialTranscript = ""
                    finalTranscript = ""
                    copiedNotice = ""
                    if (captureStats.audioBytes > 0L) {
                        fail(
                            message = "没有生成转写文本",
                            hint = "这次已经收到声音，但没有识别出可保存的文字。可以再试一次，或检查实时转写配置。"
                        )
                    } else {
                        fail(
                            message = "没有收到声音",
                            hint = "请确认麦克风权限和设备输入正常，然后再试一次。"
                        )
                    }
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
        captureStats.reset()
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
                    captureStats.audioBytes += chunk.size
                    captureStats.chunkCount += 1
                    session.sendAudioChunk(chunk)
                    if (partialTranscript.isBlank()) {
                        val capturedSeconds = audioDurationSeconds(captureStats.audioBytes, currentConfig)
                        connectionStatus = "已发送 ${formatDuration(capturedSeconds)} 音频，等待转写"
                        partialTranscript = "正在听你说话..."
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

    fun updateProcessingResult(resultId: Long, nextContent: String) {
        ideaCards = ideaCards.map { card ->
            val nextResults = card.processingResults.map { result ->
                if (result.id == resultId) {
                    result.copy(content = nextContent, isEdited = true)
                } else {
                    result
                }
            }
            if (nextResults == card.processingResults) {
                card
            } else {
                card.copy(updatedAt = System.currentTimeMillis(), processingResults = nextResults)
            }
        }
    }

    fun updateIdeaCardOriginal(cardId: Long, nextContent: String) {
        val normalized = nextContent.trim()
        if (normalized.isBlank()) return
        val now = System.currentTimeMillis()
        ideaCards = ideaCards.map { card ->
            if (card.id == cardId) {
                card.copy(
                    title = generateIdeaTitle(normalized),
                    originalTranscript = normalized,
                    updatedAt = now
                )
            } else {
                card
            }
        }
        if (currentIdeaCardId == cardId) {
            finalTranscript = normalized
        }
        copiedNotice = "原文已保存"
    }


    fun deleteProcessingResult(resultId: Long) {
        ideaCards = ideaCards.map { card ->
            val nextResults = card.processingResults.filterNot { it.id == resultId }
            if (nextResults.size == card.processingResults.size) {
                card
            } else {
                card.copy(updatedAt = System.currentTimeMillis(), processingResults = nextResults)
            }
        }
        if (postProcessResult.isNotBlank()) {
            postProcessTitle = ""
            postProcessResult = ""
        }
    }

    fun deleteIdeaCard(cardId: Long) {
        val nextCards = ideaCards.filterNot { it.id == cardId }
        ideaCards = nextCards
        if (selectedIdeaCardId == cardId) selectedIdeaCardId = null
        if (currentIdeaCardId == cardId) currentIdeaCardId = nextCards.firstOrNull()?.id
        copiedNotice = "灵感卡片已删除"
    }

    fun runPostProcess(action: PostProcessAction) {
        if (runningPostProcessAction != null) return
        val sourceText = activeTranscript.trim()
        if (sourceText.isBlank()) {
            fail(
                message = "还没有可处理的记录",
                hint = "先完成一条有文字内容的记录，再进行润色或提炼。"
            )
            return
        }
        status = VoiceFlowStatus.PostProcessing
        postProcessTitle = action.resultTitle
        postProcessResult = ""
        errorMessage = ""
        recoveryHint = ""
        copiedNotice = ""
        runningPostProcessAction = action
        morePostProcessActionsExpanded = false
        scope.launch {
            try {
                val result = textPostProcessProvider.process(
                    text = sourceText,
                    config = config(),
                    actionTitle = action.resultTitle,
                    actionInstruction = action.instruction
                )
                postProcessResult = result
                errorMessage = ""
                recoveryHint = ""
                status = VoiceFlowStatus.Completed
                appendProcessingResult(action, result)
            } catch (error: Exception) {
                postProcessResult = ""
                fail(
                    message = "后处理生成失败",
                    hint = error.message ?: "请检查后处理 API Key、Base URL 和文本模型是否可用。"
                )
            } finally {
                runningPostProcessAction = null
            }
        }
    }

    fun selectTab(nextTab: VoiceFlowTab) {
        selectedTab = nextTab
        morePostProcessActionsExpanded = false
        when (nextTab) {
            VoiceFlowTab.Record -> selectedIdeaCardId = null
            VoiceFlowTab.Cards -> Unit
            VoiceFlowTab.Settings -> Unit
        }
    }

    LaunchedEffect(initialQuickRecordMode) {
        if (initialQuickRecordMode) {
            requestStartRecording()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(V3Color.Background)
    ) {
        when (selectedTab) {
            VoiceFlowTab.Record -> {
                PrototypeRecordPage(
                    status = status,
                    connectionStatus = connectionStatus,
                    amplitude = amplitude,
                    transcript = recordTranscript,
                    copiedNotice = copiedNotice,
                    errorMessage = errorMessage,
                    recoveryHint = recoveryHint,
                    onPrimaryAction = {
                        when (status) {
                            VoiceFlowStatus.Recording,
                            VoiceFlowStatus.Connecting -> finishRecording()
                            else -> requestStartRecording()
                        }
                    },
                    onPolish = { runPostProcess(PostProcessAction.Polish) },
                    onSummarize = { runPostProcess(PostProcessAction.Summarize) }
                )
            }
            VoiceFlowTab.Cards -> {
                selectedIdeaCard?.let { card ->
                    PrototypeIdeaDetailPage(
                        card = card,
                        runningAction = runningPostProcessAction,
                        onBack = { selectedIdeaCardId = null },
                        onCopyOriginal = {
                            val copied = copyText("VoiceFlow idea", card.originalTranscript)
                            copiedNotice = if (copied) "灵感原文已复制" else "灵感原文为空"
                        },
                        onCopyResult = { result ->
                            val copied = copyText("VoiceFlow ${result.title}", result.content)
                            copiedNotice = if (copied) "${result.title} 已复制" else "处理结果为空"
                        },
                        onOriginalChange = { updateIdeaCardOriginal(card.id, it) },
                        onPostProcess = ::runPostProcess,
                        onContentChange = ::updateProcessingResult,
                        onDeleteResult = { pendingDeleteResult = it },
                        onDeleteCard = { pendingDeleteCard = card }
                    )
                } ?: run {
                    PrototypeIdeaListPage(
                        ideaCards = ideaCards,
                        selectedIdeaCardId = selectedIdeaCardId,
                        onSelect = { selectedIdeaCardId = it.id },
                        onCopy = { item ->
                            val copied = copyText("VoiceFlow idea", item.originalTranscript)
                            copiedNotice = if (copied) "灵感原文已复制" else "灵感原文为空"
                        },
                        onDelete = { item -> pendingDeleteCard = item },
                        onNewRecord = { selectTab(VoiceFlowTab.Record) }
                    )
                }
            }
            VoiceFlowTab.Settings -> {
                PrototypeSettingsPage(
                    realtimeProtocol = realtimeProtocol,
                    providerName = providerName,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    realtimeModel = realtimeModel,
                    postProcessProviderName = postProcessProviderName,
                    postProcessBaseUrl = postProcessBaseUrl,
                    postProcessApiKey = postProcessApiKey,
                    postProcessModel = postProcessModel,
                    prompt = prompt,
                    hotwords = hotwords,
                    streamingEnabled = streamingEnabled,
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
                    onProviderNameChange = { providerName = it },
                    onBaseUrlChange = { baseUrl = it },
                    onApiKeyChange = { apiKey = it },
                    onRealtimeModelChange = { realtimeModel = it },
                    onPostProcessBaseUrlChange = { postProcessBaseUrl = it },
                    onPostProcessApiKeyChange = { postProcessApiKey = it },
                    onPostProcessModelChange = { postProcessModel = it },
                    onPromptChange = { prompt = it },
                    onHotwordsChange = { hotwords = it },
                    onStreamingEnabledChange = { streamingEnabled = it },
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
                        scope.launch {
                            connectionStatus = "正在测试后处理模型连接"
                            val result = textPostProcessProvider.testConnection(config())
                            connectionStatus = if (result.success) {
                                result.detail ?: result.summary
                            } else {
                                listOfNotNull(result.summary, result.detail).joinToString("：")
                            }
                        }
                    }
                )
            }
        }
        PrototypeBottomNavigation(
            selectedTab = selectedTab,
            onTabSelected = ::selectTab
        )
    }

    pendingDeleteCard?.let { card ->
        ConfirmDeleteDialog(
            title = "删除这条灵感？",
            message = "“${card.title}”及其所有处理版本会被删除。",
            onDismiss = { pendingDeleteCard = null },
            onConfirm = {
                deleteIdeaCard(card.id)
                pendingDeleteCard = null
            }
        )
    }

    pendingDeleteResult?.let { result ->
        ConfirmDeleteDialog(
            title = "删除这个处理版本？",
            message = "“${result.title}”会从当前灵感中移除。",
            onDismiss = { pendingDeleteResult = null },
            onConfirm = {
                deleteProcessingResult(result.id)
                pendingDeleteResult = null
                copiedNotice = "处理版本已删除"
            }
        )
    }
}

@Composable
private fun Header(
    status: VoiceFlowStatus,
    connectionStatus: String,
    quickRecordMode: Boolean
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
        }
        if (status != VoiceFlowStatus.Failed) {
            StatusBadge(status = status, connectionStatus = connectionStatus)
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private object V3Spec {
    const val BaseWidth = 390f
    const val ContentX = 22f
    const val ContentWidth = 346f
    const val HeaderEyebrowY = 78f
    const val HeaderTitleY = 104f
    const val HeaderDescriptionY = 134f
    const val NavWidth = 342f
    const val NavHeight = 58f
    const val NavBottom = 16f
}

private class PrototypeMetrics(val scale: Float) {
    fun dp(value: Int): Dp = (value * scale).dp
    fun dp(value: Float): Dp = (value * scale).dp
    fun sp(value: Int) = (value * scale).sp
    fun sp(value: Float) = (value * scale).sp
}

@Composable
private fun PrototypePage(content: @Composable BoxScope.(PrototypeMetrics) -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(V3Color.Background)
    ) {
        val metrics = PrototypeMetrics(maxWidth / V3Spec.BaseWidth.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            content = { content(metrics) }
        )
    }
}

@Composable
private fun PrototypeHeader(
    metrics: PrototypeMetrics,
    eyebrow: String,
    title: String,
    description: String
) {
    Text(
        modifier = Modifier
            .offset(metrics.dp(V3Spec.ContentX), metrics.dp(V3Spec.HeaderEyebrowY))
            .width(metrics.dp(V3Spec.ContentWidth)),
        text = eyebrow,
        fontSize = metrics.sp(12),
        lineHeight = metrics.sp(16),
        color = V3Color.TextMuted,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Text(
        modifier = Modifier
            .offset(metrics.dp(V3Spec.ContentX), metrics.dp(V3Spec.HeaderTitleY))
            .width(metrics.dp(V3Spec.ContentWidth)),
        text = title,
        fontSize = metrics.sp(22),
        lineHeight = metrics.sp(26),
        color = V3Color.TextPrimary,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Text(
        modifier = Modifier
            .offset(metrics.dp(V3Spec.ContentX), metrics.dp(V3Spec.HeaderDescriptionY))
            .width(metrics.dp(316)),
        text = description,
        fontSize = metrics.sp(13),
        lineHeight = metrics.sp(19),
        color = V3Color.TextMuted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun PrototypeChip(
    metrics: PrototypeMetrics,
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit = {}
) {
    val bg = when {
        danger -> V3Color.WarmSoft
        selected -> Color(0xFFEAF1EA)
        else -> V3Color.Sand
    }
    val fg = when {
        danger -> V3Color.Warm
        selected -> V3Color.Green
        else -> V3Color.Secondary
    }
    Surface(
        modifier = modifier
            .height(metrics.dp(32))
            .clip(RoundedCornerShape(metrics.dp(16)))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(metrics.dp(16)),
        color = if (enabled) bg else bg.copy(alpha = 0.48f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = metrics.dp(10)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.dp(5))
        ) {
            Text(
                text = text,
                fontSize = metrics.sp(12),
                color = if (enabled) fg else fg.copy(alpha = 0.52f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PrototypeBottomNavigation(
    selectedTab: VoiceFlowTab,
    onTabSelected: (VoiceFlowTab) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        val metrics = PrototypeMetrics(maxWidth / V3Spec.BaseWidth.dp)
        Surface(
            modifier = Modifier
                .padding(bottom = metrics.dp(V3Spec.NavBottom))
                .size(metrics.dp(V3Spec.NavWidth), metrics.dp(V3Spec.NavHeight)),
            shape = RoundedCornerShape(metrics.dp(29)),
            color = Color.White.copy(alpha = 0.78f),
            shadowElevation = metrics.dp(6)
        ) {
            Row(
                modifier = Modifier.padding(metrics.dp(6)),
                horizontalArrangement = Arrangement.spacedBy(metrics.dp(4)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VoiceFlowTab.entries.forEach { tab ->
                    val selected = selectedTab == tab
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(metrics.dp(46))
                            .clip(RoundedCornerShape(metrics.dp(23)))
                            .background(if (selected) Color(0xCCE8F0E8) else Color.Transparent)
                            .clickable { onTabSelected(tab) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        PrototypeNavIcon(metrics = metrics, tab = tab, selected = selected)
                        Text(
                            text = tab.label,
                            fontSize = metrics.sp(10),
                            color = if (selected) V3Color.Green else Color(0xFF8A9790),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrototypeNavIcon(metrics: PrototypeMetrics, tab: VoiceFlowTab, selected: Boolean) {
    val color = if (selected) V3Color.Green else Color(0xFF8A9790)
    Canvas(modifier = Modifier.size(metrics.dp(18))) {
        val stroke = Stroke(width = metrics.dp(1.7f).toPx(), cap = StrokeCap.Round)
        when (tab) {
            VoiceFlowTab.Record -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.35f, size.height * 0.12f),
                    size = Size(size.width * 0.3f, size.height * 0.52f),
                    cornerRadius = CornerRadius(metrics.dp(6).toPx(), metrics.dp(6).toPx()),
                    style = stroke
                )
                drawLine(color, Offset(size.width * 0.2f, size.height * 0.48f), Offset(size.width * 0.2f, size.height * 0.55f), strokeWidth = metrics.dp(1.7f).toPx(), cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.8f, size.height * 0.48f), Offset(size.width * 0.8f, size.height * 0.55f), strokeWidth = metrics.dp(1.7f).toPx(), cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.5f, size.height * 0.72f), Offset(size.width * 0.5f, size.height * 0.9f), strokeWidth = metrics.dp(1.7f).toPx(), cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.34f, size.height * 0.9f), Offset(size.width * 0.66f, size.height * 0.9f), strokeWidth = metrics.dp(1.7f).toPx(), cap = StrokeCap.Round)
            }
            VoiceFlowTab.Cards -> {
                drawRoundRect(color, Offset(size.width * 0.22f, size.height * 0.2f), Size(size.width * 0.56f, size.height * 0.22f), CornerRadius(metrics.dp(3).toPx(), metrics.dp(3).toPx()), style = stroke)
                drawRoundRect(color, Offset(size.width * 0.16f, size.height * 0.42f), Size(size.width * 0.68f, size.height * 0.22f), CornerRadius(metrics.dp(3).toPx(), metrics.dp(3).toPx()), style = stroke)
                drawRoundRect(color, Offset(size.width * 0.1f, size.height * 0.64f), Size(size.width * 0.8f, size.height * 0.22f), CornerRadius(metrics.dp(3).toPx(), metrics.dp(3).toPx()), style = stroke)
            }
            VoiceFlowTab.Settings -> {
                drawCircle(color = color, radius = size.minDimension * 0.16f, center = Offset(size.width / 2f, size.height / 2f), style = stroke)
                repeat(8) { index ->
                    val angle = Math.toRadians((index * 45).toDouble())
                    val inner = size.minDimension * 0.34f
                    val outer = size.minDimension * 0.43f
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    drawLine(
                        color = color,
                        start = Offset((cx + kotlin.math.cos(angle) * inner).toFloat(), (cy + kotlin.math.sin(angle) * inner).toFloat()),
                        end = Offset((cx + kotlin.math.cos(angle) * outer).toFloat(), (cy + kotlin.math.sin(angle) * outer).toFloat()),
                        strokeWidth = metrics.dp(1.5f).toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}

@Composable
private fun PrototypeRecordPage(
    status: VoiceFlowStatus,
    connectionStatus: String,
    amplitude: Float,
    transcript: String,
    copiedNotice: String,
    errorMessage: String,
    recoveryHint: String,
    onPrimaryAction: () -> Unit,
    onPolish: () -> Unit,
    onSummarize: () -> Unit
) {
    val isRecording = status == VoiceFlowStatus.Recording || status == VoiceFlowStatus.Connecting
    val isCompleted = status == VoiceFlowStatus.Completed
    val isInitialIdle = status == VoiceFlowStatus.Idle && transcript.isBlank()
    val displayText = transcript.ifBlank {
        if (isRecording) "正在听你说话..." else "按下记录按钮，把刚冒出来的想法说出来。"
    }
    val stageTop = if (displayText.length > 70) 398 else 372
    PrototypePage { metrics ->
        if (isInitialIdle) {
            PrototypeRecordIdlePage(metrics = metrics, onPrimaryAction = onPrimaryAction)
            return@PrototypePage
        }

        PrototypeHeader(
            metrics = metrics,
            eyebrow = "VOICE IDEA",
            title = if (isCompleted) "刚刚这条灵感" else if (isRecording) "正在记录" else "刚刚这条灵感",
            description = "轻按开始记录，结束后可以润色、提炼或继续整理。"
        )

        Box(
            modifier = Modifier
                .offset(metrics.dp(36), metrics.dp(166))
                .size(metrics.dp(318), metrics.dp(if (displayText.length > 70) 220 else 130))
        ) {
            PrototypeStatusLine(
                metrics = metrics,
                status = status,
                label = if (status == VoiceFlowStatus.Failed) "记录失败" else connectionStatus
            )
            Text(
                modifier = Modifier
                    .offset(metrics.dp(0), metrics.dp(48))
                    .width(metrics.dp(318)),
                text = displayText,
                fontSize = if (displayText.length > 45) metrics.sp(19) else metrics.sp(24),
                lineHeight = if (displayText.length > 45) metrics.sp(29) else metrics.sp(33),
                color = V3Color.TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = if (displayText.length > 70) 5 else 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                modifier = Modifier
                    .offset(metrics.dp(260), metrics.dp(18))
                    .width(metrics.dp(58)),
                text = "${displayText.length} 字",
                fontSize = metrics.sp(13),
                color = V3Color.TextMuted,
                maxLines = 1
            )
        }

        if (status == VoiceFlowStatus.Failed && errorMessage.isNotBlank()) {
            Text(
                modifier = Modifier
                    .offset(metrics.dp(36), metrics.dp(304))
                    .width(metrics.dp(318)),
                text = listOf(errorMessage, recoveryHint).filter { it.isNotBlank() }.joinToString("："),
                fontSize = metrics.sp(12),
                lineHeight = metrics.sp(17),
                color = V3Color.Warm,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isCompleted && transcript.isNotBlank()) {
            Box(
                modifier = Modifier
                    .offset(metrics.dp(22), metrics.dp(584))
                    .size(metrics.dp(346), metrics.dp(1))
                    .background(V3Color.Line)
            )
            Text(
                modifier = Modifier
                    .offset(metrics.dp(22), metrics.dp(616))
                    .width(metrics.dp(120)),
                text = "已保存为卡片",
                fontSize = metrics.sp(12),
                color = V3Color.Green,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.offset(metrics.dp(152), metrics.dp(604)),
                horizontalArrangement = Arrangement.spacedBy(metrics.dp(8))
            ) {
                PrototypeChip(metrics = metrics, text = "润色", selected = true, onClick = onPolish)
                PrototypeChip(metrics = metrics, text = "要点", onClick = onSummarize)
            }
        }

        PrototypeRecordStage(
            metrics = metrics,
            modifier = Modifier.offset(metrics.dp(0), metrics.dp(stageTop)),
            amplitude = amplitude,
            isRecording = isRecording,
            onClick = onPrimaryAction
        )

        if (copiedNotice.isNotBlank()) {
            Text(
                modifier = Modifier
                    .offset(metrics.dp(22), metrics.dp(612))
                    .width(metrics.dp(346)),
                text = copiedNotice,
                fontSize = metrics.sp(12),
                color = V3Color.Green,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PrototypeRecordIdlePage(
    metrics: PrototypeMetrics,
    onPrimaryAction: () -> Unit
) {
    PrototypeHeader(
        metrics = metrics,
        eyebrow = "记录灵感",
        title = "准备记录",
        description = "按下按钮，说出刚冒出来的想法。"
    )

    Box(
        modifier = Modifier
            .offset(metrics.dp(36), metrics.dp(176))
            .size(metrics.dp(318), metrics.dp(116))
    ) {
        Box(
            modifier = Modifier
                .offset(metrics.dp(0), metrics.dp(8.5f))
                .size(metrics.dp(7))
                .clip(CircleShape)
                .background(Color(0xFFD9CBB8))
        )
        Text(
            modifier = Modifier.offset(metrics.dp(15), metrics.dp(3.5f)),
            text = "未开始",
            fontSize = metrics.sp(12),
            lineHeight = metrics.sp(17),
            color = V3Color.TextMuted,
            maxLines = 1
        )
        Text(
            modifier = Modifier.offset(metrics.dp(60), metrics.dp(3.5f)),
            text = "等待你的想法",
            fontSize = metrics.sp(12),
            lineHeight = metrics.sp(17),
            color = V3Color.TextMuted,
            maxLines = 1
        )
        Text(
            modifier = Modifier
                .offset(metrics.dp(0), metrics.dp(36))
                .width(metrics.dp(318)),
            text = "有想法时，按下按钮开始记录。",
            fontSize = metrics.sp(14),
            lineHeight = metrics.sp(20),
            color = V3Color.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .offset(metrics.dp(0), metrics.dp(66))
                .size(metrics.dp(292), metrics.dp(38))
                .clip(RoundedCornerShape(metrics.dp(19)))
                .background(Color(0x1AE5EFE5))
        ) {
            Text(
                modifier = Modifier.offset(metrics.dp(10), metrics.dp(9)),
                text = "按下开始记录",
                fontSize = metrics.sp(14),
                lineHeight = metrics.sp(20),
                color = V3Color.Green,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }

    PrototypeRecordIdleStage(
        metrics = metrics,
        modifier = Modifier.offset(metrics.dp(0), metrics.dp(396)),
        onClick = onPrimaryAction
    )
}

@Composable
private fun PrototypeRecordIdleStage(
    metrics: PrototypeMetrics,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier.size(metrics.dp(390), metrics.dp(224)),
        contentAlignment = Alignment.TopStart
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(metrics.dp(195).toPx(), metrics.dp(126).toPx())
            fun soundCircle(radius: Float, color: Color, strokeColor: Color? = null, strokeWidth: Float = 1f) {
                drawCircle(
                    color = color,
                    radius = metrics.dp(radius).toPx(),
                    center = center
                )
                if (strokeColor != null) {
                    drawCircle(
                        color = strokeColor,
                        radius = metrics.dp(radius).toPx(),
                        center = center,
                        style = Stroke(metrics.dp(strokeWidth).toPx())
                    )
                }
            }

            soundCircle(94f, Color(0x1AE5EFE5), Color(0xFFDCE8DF), 1f)
            soundCircle(89f, Color(0x26E5EFE5), Color(0xFFCFE0D6), 1f)
            soundCircle(65f, Color(0x40DCEBE1), Color(0xFFBDD4C7), 1f)
            soundCircle(53f, Color(0x59D5E7DC))
        }
        Box(
            modifier = Modifier
                .offset(metrics.dp(161), metrics.dp(92))
                .size(metrics.dp(68))
                .clip(CircleShape)
                .background(V3Color.Green)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(metrics.dp(28))) {
                val stroke = Stroke(width = metrics.dp(2).toPx(), cap = StrokeCap.Round)
                val color = Color.White
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.39f, size.height * 0.18f),
                    size = Size(size.width * 0.22f, size.height * 0.44f),
                    cornerRadius = CornerRadius(metrics.dp(6).toPx(), metrics.dp(6).toPx()),
                    style = stroke
                )
                drawLine(color, Offset(size.width * 0.28f, size.height * 0.46f), Offset(size.width * 0.28f, size.height * 0.58f), strokeWidth = metrics.dp(2).toPx(), cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.72f, size.height * 0.46f), Offset(size.width * 0.72f, size.height * 0.58f), strokeWidth = metrics.dp(2).toPx(), cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.5f, size.height * 0.7f), Offset(size.width * 0.5f, size.height * 0.88f), strokeWidth = metrics.dp(2).toPx(), cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.34f, size.height * 0.88f), Offset(size.width * 0.66f, size.height * 0.88f), strokeWidth = metrics.dp(2).toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun PrototypeStatusLine(
    metrics: PrototypeMetrics,
    status: VoiceFlowStatus,
    label: String
) {
    Row(
        modifier = Modifier
            .height(metrics.dp(24))
            .width(metrics.dp(282)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.dp(8))
    ) {
        Box(
            modifier = Modifier
                .size(metrics.dp(8))
                .clip(CircleShape)
                .background(statusColor(status))
        )
        Text(
            text = label,
            fontSize = metrics.sp(12),
            color = V3Color.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PrototypeRecordStage(
    metrics: PrototypeMetrics,
    modifier: Modifier,
    amplitude: Float,
    isRecording: Boolean,
    onClick: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val pulseTransition = rememberInfiniteTransition(label = "recordingPulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1650),
            repeatMode = RepeatMode.Restart
        ),
        label = "recordingPulseProgress"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.91f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "recordButtonPressScale"
    )
    val recordingEmphasis by animateFloatAsState(
        targetValue = if (isRecording) 1f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "recordingEmphasis"
    )
    val buttonTone = if (isRecording) V3Color.Warm else Color.White.copy(alpha = 0.9f)
    val primaryTextColor = if (isRecording) Color.White else V3Color.Green
    val secondaryTextColor = if (isRecording) Color.White.copy(alpha = 0.74f) else V3Color.TextMuted

    Box(
        modifier = modifier.size(metrics.dp(390), metrics.dp(232)),
        contentAlignment = Alignment.TopCenter
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, metrics.dp(96).toPx())
            val activePulse = if (isRecording) pulse else 0f
            val secondPulse = (activePulse + 0.46f) % 1f
            val pulseBase = metrics.dp(76).toPx()
            val pulseRange = metrics.dp(42).toPx()
            drawCircle(Color(0x14E5EFE5), radius = metrics.dp(120).toPx(), center = center)
            if (isRecording) {
                drawCircle(
                    color = V3Color.Warm.copy(alpha = 0.11f * (1f - activePulse)),
                    radius = pulseBase + activePulse * pulseRange,
                    center = center,
                    style = Stroke(metrics.dp(1.2f).toPx())
                )
                drawCircle(
                    color = V3Color.Warm.copy(alpha = 0.08f * (1f - secondPulse)),
                    radius = pulseBase + secondPulse * pulseRange,
                    center = center,
                    style = Stroke(metrics.dp(1f).toPx())
                )
            }
            drawCircle(
                Color.White.copy(alpha = 0.32f + recordingEmphasis * 0.08f),
                radius = metrics.dp(95).toPx(),
                center = center,
                style = Stroke(metrics.dp(1).toPx())
            )
            drawCircle(
                Color(0xBFE5E0D6).copy(alpha = 0.75f + recordingEmphasis * 0.16f),
                radius = metrics.dp(76).toPx(),
                center = center,
                style = Stroke(metrics.dp(1.2f).toPx())
            )
            val bars = 9
            repeat(bars) { index ->
                val x = metrics.dp(125).toPx() + index * metrics.dp(16).toPx()
                val level = (0.3f + amplitude * (1f - abs(index - 4) / 4.8f)).coerceIn(0.25f, 1f)
                val barHeight = (metrics.dp(16).toPx() + level * metrics.dp(24).toPx())
                drawRoundRect(
                    color = if (isRecording) V3Color.Warm.copy(alpha = 0.72f) else Color(0xFF7C9890),
                    topLeft = Offset(x, metrics.dp(204).toPx() - barHeight),
                    size = Size(metrics.dp(5).toPx(), barHeight),
                    cornerRadius = CornerRadius(metrics.dp(3).toPx(), metrics.dp(3).toPx())
                )
            }
        }
        Surface(
            modifier = Modifier
                .offset(y = metrics.dp(58))
                .size(metrics.dp(74))
                .clip(CircleShape)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                    alpha = if (isPressed) 0.96f else 1f
                }
                .pointerInput(onClick) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            val released = tryAwaitRelease()
                            isPressed = false
                            if (released) onClick()
                        }
                    )
                },
            shape = CircleShape,
            color = buttonTone,
            border = BorderStroke(
                metrics.dp(1),
                if (isRecording) V3Color.Warm.copy(alpha = 0.32f) else V3Color.Line
            ),
            shadowElevation = if (isRecording) metrics.dp(6) else 0.dp
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (isRecording) "停止" else "按下",
                    fontSize = metrics.sp(16),
                    lineHeight = metrics.sp(20),
                    color = primaryTextColor,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isRecording) "保存" else "记录",
                    fontSize = metrics.sp(12),
                    color = secondaryTextColor
                )
            }
        }
    }
}

@Composable
private fun PrototypeIdeaListPage(
    ideaCards: List<IdeaCard>,
    selectedIdeaCardId: Long?,
    onSelect: (IdeaCard) -> Unit,
    onCopy: (IdeaCard) -> Unit,
    onDelete: (IdeaCard) -> Unit,
    onNewRecord: () -> Unit
) {
    PrototypePage { metrics ->
        PrototypeHeader(
            metrics = metrics,
            eyebrow = "${ideaCards.size} NOTES",
            title = "灵感卡片",
            description = "每一条记录都是一张可继续加工的笔记。"
        )
        Box(
            modifier = Modifier
                .offset(metrics.dp(52), metrics.dp(150))
                .size(metrics.dp(286), metrics.dp(320))
                .clip(CircleShape)
                .background(Color(0x1AE5EFE5))
        )
        if (ideaCards.isEmpty()) {
            Surface(
                modifier = Modifier
                    .offset(metrics.dp(22), metrics.dp(174))
                    .size(metrics.dp(346), metrics.dp(76)),
                shape = RoundedCornerShape(metrics.dp(18)),
                color = Color.White.copy(alpha = 0.32f),
                border = BorderStroke(metrics.dp(1), V3Color.Line)
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = metrics.dp(22), vertical = metrics.dp(17)),
                    text = "暂无灵感卡片。回到记录页说出第一条想法，它会自动保存到这里。",
                    fontSize = metrics.sp(13),
                    lineHeight = metrics.sp(19),
                    color = V3Color.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Row(
                modifier = Modifier.offset(metrics.dp(22), metrics.dp(174)),
                horizontalArrangement = Arrangement.spacedBy(metrics.dp(15))
            ) {
                PrototypeChip(metrics = metrics, text = "全部", selected = true)
                PrototypeChip(metrics = metrics, text = "待整理")
                PrototypeChip(metrics = metrics, text = "已润色")
            }
            ideaCards.take(3).forEachIndexed { index, item ->
                PrototypeIdeaRow(
                    metrics = metrics,
                    modifier = Modifier.offset(metrics.dp(22), metrics.dp(218 + index * 104)),
                    item = item,
                    selected = selectedIdeaCardId == item.id,
                    onSelect = { onSelect(item) },
                    onCopy = { onCopy(item) },
                    onDelete = { onDelete(item) }
                )
            }
        }
        Surface(
            modifier = Modifier
                .offset(metrics.dp(302), metrics.dp(560))
                .size(metrics.dp(52))
                .clip(CircleShape)
                .clickable(onClick = onNewRecord),
            shape = CircleShape,
            color = V3Color.Green,
            shadowElevation = metrics.dp(6)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("+", fontSize = metrics.sp(26), color = Color.White, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun PrototypeIdeaRow(
    metrics: PrototypeMetrics,
    modifier: Modifier,
    item: IdeaCard,
    selected: Boolean,
    onSelect: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Box(
        modifier = modifier
            .size(metrics.dp(346), metrics.dp(92))
            .clip(RoundedCornerShape(metrics.dp(12)))
            .background(if (selected) Color(0x33E8F0E8) else Color.Transparent)
            .clickable(onClick = onSelect)
    ) {
        Box(
            modifier = Modifier
                .offset(metrics.dp(0), metrics.dp(10))
                .size(metrics.dp(7))
                .clip(CircleShape)
                .background(if (item.processingResults.isEmpty()) V3Color.Warm else V3Color.Green)
        )
        Text(
            modifier = Modifier.offset(metrics.dp(18), metrics.dp(4)),
            text = formatDisplayTime(item.createdAt),
            fontSize = metrics.sp(11),
            color = Color(0xFF929D96),
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        Text(
            modifier = Modifier
                .offset(metrics.dp(18), metrics.dp(24))
                .width(metrics.dp(280)),
            text = item.title,
            fontSize = metrics.sp(15),
            lineHeight = metrics.sp(19),
            color = Color(0xFF28342E),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            modifier = Modifier
                .offset(metrics.dp(18), metrics.dp(50))
                .width(metrics.dp(304)),
            text = item.originalTranscript,
            fontSize = metrics.sp(13),
            lineHeight = metrics.sp(18),
            color = V3Color.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            modifier = Modifier
                .offset(metrics.dp(312), metrics.dp(16))
                .size(metrics.dp(28))
                .clickable(onClick = onCopy),
            text = "复制",
            fontSize = metrics.sp(11),
            color = Color(0xFF9AA49E)
        )
        Box(
            modifier = Modifier
                .offset(metrics.dp(0), metrics.dp(90))
                .size(metrics.dp(346), metrics.dp(1))
                .background(V3Color.Line)
        )
        Box(
            modifier = Modifier
                .offset(metrics.dp(286), metrics.dp(50))
                .size(metrics.dp(54), metrics.dp(28))
                .clickable(onClick = onDelete)
        )
    }
}

@Composable
private fun PrototypeIdeaDetailPage(
    card: IdeaCard,
    runningAction: PostProcessAction?,
    onBack: () -> Unit,
    onCopyOriginal: () -> Unit,
    onCopyResult: (ProcessingResult) -> Unit,
    onOriginalChange: (String) -> Unit,
    onPostProcess: (PostProcessAction) -> Unit,
    onContentChange: (Long, String) -> Unit,
    onDeleteResult: (ProcessingResult) -> Unit,
    onDeleteCard: () -> Unit
) {
    var draftText by remember(card.id, card.originalTranscript) { mutableStateOf(card.originalTranscript) }
    val result = card.processingResults.firstOrNull()
    var resultDraft by remember(result?.id, result?.content) { mutableStateOf(result?.content.orEmpty()) }
    PrototypePage { metrics ->
        PrototypeHeader(
            metrics = metrics,
            eyebrow = "${formatDisplayTime(card.createdAt)} · ${formatDuration(card.durationMs / 1000f)}",
            title = card.title,
            description = "打开一条灵感，在同一个空间里编辑、润色和提炼。"
        )
        Text(
            modifier = Modifier.offset(metrics.dp(22), metrics.dp(174)).width(metrics.dp(316)),
            text = "原文",
            fontSize = metrics.sp(12),
            lineHeight = metrics.sp(16),
            color = V3Color.TextMuted,
            fontWeight = FontWeight.Bold
        )
        BasicTextField(
            modifier = Modifier.offset(metrics.dp(22), metrics.dp(202)).size(metrics.dp(346), metrics.dp(96)),
            value = draftText,
            onValueChange = { draftText = it },
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = metrics.sp(14),
                lineHeight = metrics.sp(23),
                color = V3Color.TextPrimary
            )
        )
        Box(modifier = Modifier.offset(metrics.dp(22), metrics.dp(328)).size(metrics.dp(346), metrics.dp(1)).background(V3Color.Line))
        PrototypeChip(
            metrics = metrics,
            modifier = Modifier.offset(metrics.dp(22), metrics.dp(352)).width(metrics.dp(64)),
            text = "编辑",
            selected = true,
            onClick = { onOriginalChange(draftText) }
        )
        PrototypeChip(
            metrics = metrics,
            modifier = Modifier.offset(metrics.dp(92), metrics.dp(352)).width(metrics.dp(64)),
            text = if (runningAction == PostProcessAction.Polish) "生成" else "润色",
            onClick = { onPostProcess(PostProcessAction.Polish) },
            enabled = runningAction == null
        )
        PrototypeChip(
            metrics = metrics,
            modifier = Modifier.offset(metrics.dp(164), metrics.dp(352)).width(metrics.dp(64)),
            text = if (runningAction == PostProcessAction.Summarize) "生成" else "要点",
            onClick = { onPostProcess(PostProcessAction.Summarize) },
            enabled = runningAction == null
        )
        PrototypeChip(
            metrics = metrics,
            modifier = Modifier.offset(metrics.dp(236), metrics.dp(352)).width(metrics.dp(64)),
            text = "删除",
            danger = true,
            onClick = onDeleteCard
        )
        Text(
            modifier = Modifier.offset(metrics.dp(22), metrics.dp(414)).width(metrics.dp(316)),
            text = result?.title ?: runningAction?.resultTitle ?: "处理结果",
            fontSize = metrics.sp(12),
            lineHeight = metrics.sp(16),
            color = V3Color.Secondary,
            fontWeight = FontWeight.Bold
        )
        Box(modifier = Modifier.offset(metrics.dp(22), metrics.dp(446)).size(metrics.dp(3), metrics.dp(84)).clip(RoundedCornerShape(metrics.dp(2))).background(Color(0xFFD9CBB8)))
        BasicTextField(
            modifier = Modifier.offset(metrics.dp(36), metrics.dp(442)).size(metrics.dp(326), metrics.dp(76)),
            value = if (runningAction != null) "${runningAction.label}生成中..." else resultDraft.ifBlank { "点击润色或要点后，处理结果会作为独立版本显示在这里。" },
            onValueChange = { nextContent ->
                resultDraft = nextContent
                result?.let { current -> onContentChange(current.id, nextContent) }
            },
            enabled = result != null && runningAction == null,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = metrics.sp(14),
                lineHeight = metrics.sp(21),
                color = V3Color.TextSecondary
            )
        )
        PrototypeChip(
            metrics = metrics,
            modifier = Modifier.offset(metrics.dp(36), metrics.dp(544)).width(metrics.dp(64)),
            text = "复制",
            onClick = { result?.let(onCopyResult) ?: onCopyOriginal() }
        )
        PrototypeChip(
            metrics = metrics,
            modifier = Modifier.offset(metrics.dp(106), metrics.dp(544)).width(metrics.dp(88)),
            text = "替换原文",
            selected = true,
            enabled = result != null,
            onClick = { if (resultDraft.isNotBlank()) onOriginalChange(resultDraft) }
        )
        PrototypeChip(
            metrics = metrics,
            modifier = Modifier.offset(metrics.dp(22), metrics.dp(604)).width(metrics.dp(64)),
            text = "返回",
            onClick = onBack
        )
        if (result != null) {
            PrototypeChip(
                metrics = metrics,
                modifier = Modifier.offset(metrics.dp(92), metrics.dp(604)).width(metrics.dp(64)),
                text = "删版本",
                danger = true,
                onClick = { onDeleteResult(result) }
            )
        }
    }
}

@Composable
private fun PrototypeSettingsPage(
    realtimeProtocol: RealtimeProviderProtocol,
    providerName: String,
    baseUrl: String,
    apiKey: String,
    realtimeModel: String,
    postProcessProviderName: String,
    postProcessBaseUrl: String,
    postProcessApiKey: String,
    postProcessModel: String,
    prompt: String,
    hotwords: String,
    streamingEnabled: Boolean,
    onRealtimeProtocolChange: (RealtimeProviderProtocol) -> Unit,
    onProviderNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onRealtimeModelChange: (String) -> Unit,
    onPostProcessBaseUrlChange: (String) -> Unit,
    onPostProcessApiKeyChange: (String) -> Unit,
    onPostProcessModelChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onHotwordsChange: (String) -> Unit,
    onStreamingEnabledChange: (Boolean) -> Unit,
    onTestRealtimeConnection: () -> Unit,
    onTestPostProcessConnection: () -> Unit
) {
    val realtimeReady = apiKey.isNotBlank() && (
        realtimeProtocol == RealtimeProviderProtocol.AliyunParaformer ||
            baseUrl.isNotBlank()
        )
    val postReady = postProcessApiKey.isNotBlank() && postProcessBaseUrl.isNotBlank()
    PrototypePage { metrics ->
        PrototypeHeader(
            metrics = metrics,
            eyebrow = "SETTINGS",
            title = "设置",
            description = "认证、模型和中转站配置保持清楚，但视觉不过重。"
        )
        Surface(
            modifier = Modifier.offset(metrics.dp(22), metrics.dp(174)).size(metrics.dp(346), metrics.dp(70)),
            shape = RoundedCornerShape(metrics.dp(18)),
            color = Color.White.copy(alpha = 0.34f),
            border = BorderStroke(metrics.dp(1), V3Color.Line)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = metrics.dp(18), vertical = metrics.dp(14)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metrics.dp(12))
            ) {
                Box(modifier = Modifier.size(metrics.dp(20)).clip(CircleShape).background(if (realtimeReady && postReady) V3Color.Green else V3Color.Warm))
                Column(modifier = Modifier.weight(1f)) {
                    Text("API 认证", fontSize = metrics.sp(15), color = V3Color.TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(
                        if (realtimeReady && postReady) "实时转写和文本处理已配置" else "请补全中转站 API 配置",
                        fontSize = metrics.sp(12),
                        lineHeight = metrics.sp(17),
                        color = V3Color.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        PrototypeEditableSettingRow(metrics, 266, "实时模型", realtimeModel, onRealtimeModelChange)
        PrototypeEditableSettingRow(metrics, 334, "文本模型", postProcessModel, onPostProcessModelChange)
        PrototypeEditableSettingRow(metrics, 402, "中转站", postProcessBaseUrl.ifBlank { baseUrl }, {
            onPostProcessBaseUrlChange(it)
            onBaseUrlChange(it)
        })
        PrototypeEditableSettingRow(metrics, 470, "API Key", postProcessApiKey.ifBlank { apiKey }, {
            onPostProcessApiKeyChange(it)
            onApiKeyChange(it)
        })
        PrototypeEditableSettingRow(metrics, 538, "热词", hotwords, onHotwordsChange)
        Row(
            modifier = Modifier.offset(metrics.dp(22), metrics.dp(604)),
            horizontalArrangement = Arrangement.spacedBy(metrics.dp(8))
        ) {
            PrototypeChip(
                metrics = metrics,
                text = if (realtimeProtocol == RealtimeProviderProtocol.AliyunParaformer) "阿里云" else "OpenAI",
                selected = true,
                onClick = {
                    val next = if (realtimeProtocol == RealtimeProviderProtocol.AliyunParaformer) {
                        RealtimeProviderProtocol.OpenAiRealtime
                    } else {
                        RealtimeProviderProtocol.AliyunParaformer
                    }
                    onRealtimeProtocolChange(next)
                }
            )
            PrototypeChip(metrics = metrics, text = if (streamingEnabled) "流式开" else "流式关", onClick = { onStreamingEnabledChange(!streamingEnabled) })
            PrototypeChip(metrics = metrics, text = "测转写", onClick = onTestRealtimeConnection)
            PrototypeChip(metrics = metrics, text = "测文本", onClick = onTestPostProcessConnection)
        }
    }
}

@Composable
private fun PrototypeEditableSettingRow(
    metrics: PrototypeMetrics,
    y: Int,
    title: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Box(modifier = Modifier.offset(metrics.dp(22), metrics.dp(y)).size(metrics.dp(346), metrics.dp(56))) {
        Text(
            modifier = Modifier.offset(metrics.dp(32), metrics.dp(9)).width(metrics.dp(110)),
            text = title,
            fontSize = metrics.sp(14),
            lineHeight = metrics.sp(20),
            color = V3Color.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        BasicTextField(
            modifier = Modifier.offset(metrics.dp(32), metrics.dp(31)).width(metrics.dp(282)),
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = metrics.sp(12),
                lineHeight = metrics.sp(17),
                color = V3Color.TextSecondary
            )
        )
        Text(
            modifier = Modifier.offset(metrics.dp(328), metrics.dp(18)),
            text = ">",
            fontSize = metrics.sp(16),
            color = V3Color.TextMuted
        )
        Box(modifier = Modifier.offset(metrics.dp(0), metrics.dp(55)).size(metrics.dp(346), metrics.dp(1)).background(V3Color.Line))
    }
}

@Composable
private fun VoiceFlowBottomNavigation(
    selectedTab: VoiceFlowTab,
    onTabSelected: (VoiceFlowTab) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        color = Color.Transparent
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(999.dp),
            color = Color.White.copy(alpha = 0.78f),
            shadowElevation = 10.dp
        ) {
            Row(
                modifier = Modifier.padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VoiceFlowTab.entries.forEach { tab ->
                    val selected = selectedTab == tab
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (selected) V3Color.GreenSoft else Color.Transparent)
                            .clickable { onTabSelected(tab) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = tab.navMark,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) V3Color.Green else V3Color.TextMuted,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) V3Color.Green else V3Color.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun V3PageHeader(
    eyebrow: String,
    title: String,
    description: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = eyebrow,
            style = MaterialTheme.typography.labelSmall,
            color = V3Color.TextMuted,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = V3Color.TextPrimary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = V3Color.TextSecondary
        )
    }
}

@Composable
private fun V3ActionChip(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val bg = when {
        danger -> V3Color.WarmSoft
        selected -> V3Color.GreenSoft
        else -> V3Color.Sand
    }
    val fg = when {
        danger -> V3Color.Warm
        selected -> V3Color.Green
        else -> V3Color.Secondary
    }
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (enabled) bg else bg.copy(alpha = 0.5f)
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) fg else fg.copy(alpha = 0.55f),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun V3Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(V3Color.Line)
    )
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
    onPrimaryAction: () -> Unit
) {
    val primaryActionEnabled = status != VoiceFlowStatus.RequestingPermission &&
        status != VoiceFlowStatus.Finalizing &&
        status != VoiceFlowStatus.PostProcessing
    val isActivelyRecording = status == VoiceFlowStatus.Recording || status == VoiceFlowStatus.Connecting
    val hasTranscript = transcript.isNotBlank()
    val pulseTransition = rememberInfiniteTransition(label = "recordingPulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 760),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recordingPulseAlpha"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        V3PageHeader(
            eyebrow = "VOICE IDEA",
            title = when {
                isActivelyRecording -> "正在记录"
                status == VoiceFlowStatus.Completed -> "刚刚这条灵感"
                else -> "把灵感说出来"
            },
            description = if (isActivelyRecording) {
                "实时转写会自然长在页面里，松开后自动保存为一条灵感。"
            } else {
                "轻按开始记录，结束后可以润色、提炼或继续整理。"
            }
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 250.dp)
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    V3StatusPill(
                        status = status,
                        connectionStatus = connectionStatus,
                        pulseAlpha = pulseAlpha
                    )
                    Text(
                        text = if (hasTranscript) "${transcript.length} 字" else "等待输入",
                        style = MaterialTheme.typography.labelMedium,
                        color = V3Color.TextMuted,
                        maxLines = 1
                    )
                }

                TranscriptCanvasText(
                    transcript = transcript,
                    isActivelyRecording = isActivelyRecording
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Waveform(amplitude = amplitude, active = isActivelyRecording)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier
                            .size(116.dp)
                            .clip(CircleShape)
                            .clickable(enabled = primaryActionEnabled, onClick = onPrimaryAction),
                        shape = CircleShape,
                        color = if (isActivelyRecording) V3Color.Green else Color.White.copy(alpha = 0.72f),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isActivelyRecording) V3Color.Green.copy(alpha = pulseAlpha) else V3Color.Line
                        ),
                        shadowElevation = if (isActivelyRecording) 0.dp else 8.dp
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = if (isActivelyRecording) "停止" else "按下",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isActivelyRecording) Color.White else V3Color.Green,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isActivelyRecording) "保存灵感" else "记录",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isActivelyRecording) Color.White.copy(alpha = 0.78f) else V3Color.TextMuted
                            )
                        }
                    }
                }
            }
        }

        if (status == VoiceFlowStatus.Completed && hasTranscript) {
            V3Divider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "已保存为卡片",
                    style = MaterialTheme.typography.labelMedium,
                    color = V3Color.Green,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = transcript,
                    style = MaterialTheme.typography.bodyMedium,
                    color = V3Color.TextSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (status == VoiceFlowStatus.Failed && errorMessage.isNotBlank()) {
            ErrorPanel(message = errorMessage, hint = recoveryHint)
        }

        if (copiedNotice.isNotBlank()) {
            Text(
                text = copiedNotice,
                style = MaterialTheme.typography.bodySmall,
                color = V3Color.Green
            )
        }
    }
}

@Composable
private fun V3StatusPill(
    status: VoiceFlowStatus,
    connectionStatus: String,
    pulseAlpha: Float
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = when (status) {
            VoiceFlowStatus.Recording -> V3Color.GreenSoft
            VoiceFlowStatus.Failed -> V3Color.WarmSoft
            VoiceFlowStatus.Completed -> Color.White.copy(alpha = 0.54f)
            else -> V3Color.Sand
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor(status).copy(alpha = if (status == VoiceFlowStatus.Recording) pulseAlpha else 1f))
            )
            Text(
                text = if (status == VoiceFlowStatus.Failed) status.userText() else connectionStatus,
                style = MaterialTheme.typography.labelSmall,
                color = if (status == VoiceFlowStatus.Failed) V3Color.Warm else V3Color.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TranscriptCanvasText(
    transcript: String,
    isActivelyRecording: Boolean
) {
    val text = transcript.ifBlank {
        if (isActivelyRecording) "正在听你说话..." else "这里会实时浮现你的想法。"
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = text,
            style = if (transcript.isBlank()) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
            color = if (transcript.isBlank()) V3Color.TextMuted else V3Color.TextPrimary,
            fontWeight = if (transcript.isBlank()) FontWeight.Medium else FontWeight.SemiBold,
            maxLines = if (isActivelyRecording) 8 else 10,
            overflow = TextOverflow.Ellipsis
        )
        if (isActivelyRecording && transcript.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Color.White.copy(alpha = 0.52f)
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    text = "当前句正在确认",
                    style = MaterialTheme.typography.labelSmall,
                    color = V3Color.Green,
                    fontWeight = FontWeight.SemiBold
                )
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
private fun Waveform(amplitude: Float, active: Boolean) {
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
            color = if (active) Color(0xFFC7DDD5) else Color(0xFFE2E6DE),
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
                color = if (active) Color(0xFF1B6B63) else Color(0xFF769087),
                topLeft = Offset(x, centerY - height / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
            )
        }
    }
}

@Composable
private fun IdeaCardDetailPanel(
    card: IdeaCard,
    runningAction: PostProcessAction?,
    onBack: () -> Unit,
    onCopyOriginal: () -> Unit,
    onCopyResult: (ProcessingResult) -> Unit,
    onOriginalChange: (String) -> Unit,
    onPostProcess: (PostProcessAction) -> Unit,
    onContentChange: (Long, String) -> Unit,
    onDeleteResult: (ProcessingResult) -> Unit,
    onDeleteCard: () -> Unit
) {
    var draftText by remember(card.id, card.originalTranscript) { mutableStateOf(card.originalTranscript) }
    val originalChanged = draftText.trim() != card.originalTranscript.trim()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            V3ActionChip(label = "返回", onClick = onBack)
            V3ActionChip(label = "删除", danger = true, onClick = onDeleteCard)
        }

        V3PageHeader(
            eyebrow = "${formatDisplayTime(card.createdAt)} · ${formatDuration(card.durationMs / 1000f)}",
            title = card.title,
            description = "这是一条灵感的工作空间：先保护原文，再生成和管理不同处理版本。"
        )

        V3Divider()

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "原文",
                    style = MaterialTheme.typography.titleSmall,
                    color = V3Color.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = card.statusLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = V3Color.TextMuted,
                    maxLines = 1
                )
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draftText,
                onValueChange = { draftText = it },
                minLines = 6,
                label = { Text("可编辑原文") }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                V3ActionChip(label = "保存原文", selected = true, enabled = originalChanged, onClick = { onOriginalChange(draftText) })
                V3ActionChip(label = "复制", onClick = onCopyOriginal)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "处理",
                style = MaterialTheme.typography.titleSmall,
                color = V3Color.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            PostProcessActionGrid(
                actions = listOf(PostProcessAction.Polish, PostProcessAction.Summarize),
                runningAction = runningAction,
                onPostProcess = onPostProcess
            )
        }

        ProcessingResultsPanel(
            results = card.processingResults,
            runningAction = runningAction,
            onCopyResult = onCopyResult,
            onContentChange = onContentChange,
            onDeleteResult = onDeleteResult
        )
    }
}

@Composable
private fun ProcessingResultsPanel(
    results: List<ProcessingResult>,
    runningAction: PostProcessAction?,
    onCopyResult: (ProcessingResult) -> Unit,
    onContentChange: (Long, String) -> Unit,
    onDeleteResult: (ProcessingResult) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (runningAction != null) {
            ProcessingPlaceholder(action = runningAction)
        }
        if (results.isEmpty() && runningAction == null) {
            Text(
                text = "还没有处理版本。选择润色或提炼后，会在这里保留独立草稿。",
                style = MaterialTheme.typography.bodySmall,
                color = V3Color.TextMuted
            )
        } else if (results.isNotEmpty()) {
            Text(
                text = "处理版本",
                style = MaterialTheme.typography.titleSmall,
                color = V3Color.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }
        results.forEach { result ->
            ProcessingResultRow(
                result = result,
                onCopy = { onCopyResult(result) },
                onContentChange = { onContentChange(result.id, it) },
                onDelete = { onDeleteResult(result) }
            )
        }
    }
}

@Composable
private fun ProcessingPlaceholder(action: PostProcessAction) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = V3Color.GreenSoft
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(V3Color.Green)
            )
            Text(
                text = "${action.label}生成中...",
                style = MaterialTheme.typography.bodyMedium,
                color = V3Color.Green,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PostProcessDock(
    expanded: Boolean,
    runningAction: PostProcessAction?,
    onToggleExpanded: () -> Unit,
    onCopyOriginal: () -> Unit,
    onPostProcess: (PostProcessAction) -> Unit
) {
    val primaryActions = listOf(PostProcessAction.Polish, PostProcessAction.Summarize)
    val moreActions = PostProcessAction.entries.filterNot { it in primaryActions }
    val actionsEnabled = runningAction == null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = V3Color.Background
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (expanded) {
                PostProcessActionGrid(
                    actions = moreActions,
                    runningAction = runningAction,
                    onPostProcess = onPostProcess
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PostProcessActionButton(
                    modifier = Modifier.weight(1f),
                    action = PostProcessAction.Polish,
                    runningAction = runningAction,
                    onPostProcess = onPostProcess
                )
                PostProcessActionButton(
                    modifier = Modifier.weight(1f),
                    action = PostProcessAction.Summarize,
                    runningAction = runningAction,
                    onPostProcess = onPostProcess
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                V3ActionChip(
                    modifier = Modifier.weight(1f),
                    label = "复制原文",
                    enabled = actionsEnabled,
                    onClick = onCopyOriginal
                )
                V3ActionChip(
                    modifier = Modifier.weight(1f),
                    label = if (expanded) "收起更多" else "更多操作",
                    enabled = actionsEnabled,
                    onClick = onToggleExpanded
                )
            }
        }
    }
}

@Composable
private fun PostProcessActionGrid(
    actions: List<PostProcessAction>,
    runningAction: PostProcessAction?,
    onPostProcess: (PostProcessAction) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.chunked(2).forEach { rowActions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowActions.forEach { action ->
                    PostProcessActionButton(
                        modifier = Modifier.weight(1f),
                        action = action,
                        runningAction = runningAction,
                        onPostProcess = onPostProcess
                    )
                }
                if (rowActions.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PostProcessActionButton(
    modifier: Modifier,
    action: PostProcessAction,
    runningAction: PostProcessAction?,
    onPostProcess: (PostProcessAction) -> Unit
) {
    val isRunning = runningAction == action
    V3ActionChip(
        modifier = modifier,
        label = if (isRunning) "生成中..." else action.label,
        selected = action == PostProcessAction.Polish || action == PostProcessAction.Summarize,
        enabled = runningAction == null,
        onClick = { onPostProcess(action) }
    )
}

@Composable
private fun ProcessingResultRow(
    result: ProcessingResult,
    onCopy: () -> Unit,
    onContentChange: (String) -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.54f),
        border = BorderStroke(1.dp, V3Color.Line)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(result.title, color = V3Color.TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${formatDisplayTime(result.createdAt)} · ${result.model} · ${if (result.isEdited) "已编辑" else "AI 原始结果"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = V3Color.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    V3ActionChip(label = "复制", onClick = onCopy)
                    V3ActionChip(label = "删除", danger = true, onClick = onDelete)
                }
            }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = result.content,
                onValueChange = onContentChange,
                minLines = 4,
                label = { Text("可编辑草稿") }
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
    val realtimeReady = apiKey.isNotBlank() && (
        realtimeProtocol == RealtimeProviderProtocol.AliyunParaformer && aliyunWorkspaceId.isNotBlank() ||
            realtimeProtocol == RealtimeProviderProtocol.OpenAiRealtime && baseUrl.isNotBlank()
        )
    val postProcessReady = postProcessApiKey.isNotBlank() && postProcessBaseUrl.isNotBlank() && postProcessModel.isNotBlank()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        V3PageHeader(
            eyebrow = "SETTINGS",
            title = "配置",
            description = "只保留必要技术参数，让实时转写和后续文本处理都可检查、可切换。"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            V3StatusSummary(
                modifier = Modifier.weight(1f),
                title = "实时转写",
                ready = realtimeReady,
                detail = if (realtimeReady) "已认证" else "待配置"
            )
            V3StatusSummary(
                modifier = Modifier.weight(1f),
                title = "文本处理",
                ready = postProcessReady,
                detail = if (postProcessReady) "已认证" else "待配置"
            )
        }

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
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.44f),
                border = BorderStroke(1.dp, V3Color.Line)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "阿里云百炼参数",
                        style = MaterialTheme.typography.titleSmall,
                        color = V3Color.TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "官方端点会由 Workspace ID 自动生成：wss://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference",
                        style = MaterialTheme.typography.bodySmall,
                        color = V3Color.TextSecondary
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

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color.White.copy(alpha = 0.36f),
            border = BorderStroke(1.dp, V3Color.Line)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用流式转写", color = V3Color.TextPrimary, fontWeight = FontWeight.Medium)
                        Text(
                            text = "关闭后可作为录完上传 provider 的扩展入口",
                            style = MaterialTheme.typography.bodySmall,
                            color = V3Color.TextSecondary
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
            }
        }

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

@Composable
private fun V3StatusSummary(
    modifier: Modifier,
    title: String,
    ready: Boolean,
    detail: String
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = if (ready) V3Color.GreenSoft else Color.White.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, if (ready) V3Color.Green.copy(alpha = 0.22f) else V3Color.Line)
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
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = V3Color.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (ready) V3Color.Green else V3Color.Warm)
                )
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (ready) V3Color.Green else V3Color.TextMuted
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
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.36f),
        border = BorderStroke(1.dp, V3Color.Line)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "实时转写协议",
                style = MaterialTheme.typography.titleSmall,
                color = V3Color.TextPrimary,
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
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, V3Color.Line)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = V3Color.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = V3Color.TextSecondary
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        V3PageHeader(
            eyebrow = "${ideaCards.size} NOTES",
            title = "灵感卡片",
            description = "每一条记录都是一张可继续加工的笔记。"
        )
        V3Divider()
        if (ideaCards.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.42f),
                border = BorderStroke(1.dp, V3Color.Line)
            ) {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = "暂无灵感卡片。回到记录页说出第一条想法，它会自动保存到这里。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = V3Color.TextSecondary
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                ideaCards.forEach { item ->
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

@Composable
private fun IdeaCardRow(
    item: IdeaCard,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) V3Color.GreenSoft.copy(alpha = 0.56f) else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(vertical = 12.dp, horizontal = 2.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                    color = V3Color.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatDisplayTime(item.createdAt)} · ${item.statusLabel()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = V3Color.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                V3ActionChip(label = "复制", onClick = onCopy)
                V3ActionChip(label = "删除", danger = true, onClick = onDelete)
            }
        }
        Text(
            text = item.originalTranscript,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = V3Color.TextSecondary
        )
        if (item.processingResults.isNotEmpty()) {
            Text(
                text = item.processingResults.joinToString(" / ") { it.title },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = V3Color.Green
            )
        }
        V3Divider()
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

private fun encodeIdeaCards(cards: List<IdeaCard>): String {
    val array = JSONArray()
    cards.forEach { card ->
        val resultsArray = JSONArray()
        card.processingResults.forEach { result ->
            resultsArray.put(
                JSONObject()
                    .put("id", result.id)
                    .put("type", result.type.name)
                    .put("title", result.title)
                    .put("content", result.content)
                    .put("createdAt", result.createdAt)
                    .put("model", result.model)
                    .put("isEdited", result.isEdited)
            )
        }
        array.put(
            JSONObject()
                .put("id", card.id)
                .put("title", card.title)
                .put("originalTranscript", card.originalTranscript)
                .put("createdAt", card.createdAt)
                .put("updatedAt", card.updatedAt)
                .put("durationMs", card.durationMs)
                .put("realtimeModel", card.realtimeModel)
                .put("isFavorite", card.isFavorite)
                .put("processingResults", resultsArray)
        )
    }
    return array.toString()
}

private fun decodeIdeaCards(raw: String): List<IdeaCard> {
    return runCatching {
        val array = JSONArray(raw)
        val cards = mutableListOf<IdeaCard>()
        for (index in 0 until array.length()) {
            val cardObject = array.getJSONObject(index)
            val resultsArray = cardObject.optJSONArray("processingResults") ?: JSONArray()
            val results = mutableListOf<ProcessingResult>()
            for (resultIndex in 0 until resultsArray.length()) {
                val resultObject = resultsArray.getJSONObject(resultIndex)
                val action = runCatching {
                    PostProcessAction.valueOf(resultObject.optString("type", PostProcessAction.Summarize.name))
                }.getOrDefault(PostProcessAction.Summarize)
                results.add(
                    ProcessingResult(
                        id = resultObject.optLong("id", 0L),
                        type = action,
                        title = resultObject.optString("title", action.resultTitle),
                        content = resultObject.optString("content"),
                        createdAt = resultObject.optLong("createdAt", 0L),
                        model = resultObject.optString("model"),
                        isEdited = resultObject.optBoolean("isEdited", false)
                    )
                )
            }
            cards.add(
                IdeaCard(
                    id = cardObject.optLong("id", 0L),
                    title = cardObject.optString("title", "未命名灵感"),
                    originalTranscript = cardObject.optString("originalTranscript"),
                    createdAt = cardObject.optLong("createdAt", 0L),
                    updatedAt = cardObject.optLong("updatedAt", 0L),
                    durationMs = cardObject.optLong("durationMs", 0L),
                    realtimeModel = cardObject.optString("realtimeModel"),
                    processingResults = results,
                    isFavorite = cardObject.optBoolean("isFavorite", false)
                )
            )
        }
        cards
    }.getOrDefault(emptyList())
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

private fun formatDisplayTime(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}
