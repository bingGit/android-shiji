package com.bing.androidvoiceflow

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndroidVoiceFlowApp()
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

private enum class PostProcessAction(val label: String) {
    Summarize("总结"),
    Polish("润色"),
    Rewrite("改写")
}

private data class TranscriptHistoryItem(
    val id: Long,
    val createdAt: Long,
    val text: String,
    val model: String,
    val postProcessResult: String? = null
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
private fun AndroidVoiceFlowApp() {
    MaterialTheme(colorScheme = AppColorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            VoiceFlowScreen()
        }
    }
}

@Composable
private fun VoiceFlowScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val audioRecorder = remember { AndroidPcmAudioRecorder() }

    var providerName by remember { mutableStateOf("OpenAI-compatible Realtime") }
    var baseUrl by remember { mutableStateOf("https://api.openai.com/v1/realtime") }
    var apiKey by remember { mutableStateOf("") }
    var realtimeModel by remember { mutableStateOf("gpt-realtime") }
    var postProcessModel by remember { mutableStateOf("gpt-4o-mini") }
    var streamingEnabled by remember { mutableStateOf(true) }
    var maxRecordingSeconds by remember { mutableStateOf("120") }
    var prompt by remember { mutableStateOf("请把用户语音实时转写为简洁准确的中文文本。") }
    var hotwords by remember { mutableStateOf("VoiceFlow, Obsidian, Android") }

    var status by remember { mutableStateOf(VoiceFlowStatus.Idle) }
    var connectionStatus by remember { mutableStateOf("未连接") }
    var partialTranscript by remember { mutableStateOf("") }
    var finalTranscript by remember { mutableStateOf("") }
    var postProcessTitle by remember { mutableStateOf("") }
    var postProcessResult by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var recoveryHint by remember { mutableStateOf("") }
    var copiedNotice by remember { mutableStateOf("") }
    var amplitude by remember { mutableStateOf(0.08f) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }
    var capturedAudioBytes by remember { mutableStateOf(0L) }
    var capturedChunkCount by remember { mutableStateOf(0) }
    var history by remember { mutableStateOf<List<TranscriptHistoryItem>>(emptyList()) }

    fun config(): ProviderConfig {
        return ProviderConfig(
            providerName = providerName,
            baseUrl = baseUrl,
            apiKey = apiKey,
            realtimeModel = realtimeModel,
            postProcessModel = postProcessModel,
            streamingEnabled = streamingEnabled,
            prompt = prompt,
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
        connectionStatus = "失败"
        amplitude = 0.02f
    }

    fun finishRecording() {
        val currentConfig = config()
        recordingJob?.cancel()
        recordingJob = null
        status = VoiceFlowStatus.Finalizing
        connectionStatus = "正在生成最终文本"
        scope.launch {
            delay(650)
            val finalText = finalTranscript.trim()
            if (finalText.isBlank()) {
                val capturedSeconds = audioDurationSeconds(capturedAudioBytes, currentConfig)
                status = VoiceFlowStatus.Completed
                connectionStatus = if (capturedAudioBytes > 0) {
                    "本地音频采集完成"
                } else {
                    "未采集到音频"
                }
                partialTranscript = if (capturedAudioBytes > 0) {
                    "已采集 ${formatDuration(capturedSeconds)} PCM16 音频，${capturedChunkCount} 个分片，约 ${formatAudioBytes(capturedAudioBytes)}。实时转写 provider 尚未接入，所以本次没有 final transcript。"
                } else {
                    "没有读到麦克风音频，请检查麦克风权限或设备输入。"
                }
                copiedNotice = ""
                amplitude = 0.06f
                return@launch
            }
            postProcessTitle = ""
            postProcessResult = ""
            status = VoiceFlowStatus.Completed
            connectionStatus = "已完成"
            amplitude = 0.06f
            if (copyText("VoiceFlow transcript", finalText)) {
                copiedNotice = "最终文本已复制到剪贴板"
            }
            history = listOf(
                TranscriptHistoryItem(
                    id = System.currentTimeMillis(),
                    createdAt = System.currentTimeMillis(),
                    text = finalText,
                    model = realtimeModel
                )
            ) + history.take(19)
        }
    }

    fun startRecording() {
        val currentConfig = config()
        errorMessage = ""
        recoveryHint = ""
        copiedNotice = ""
        partialTranscript = "正在采集 ${currentConfig.audioFormat.sampleRateHz} Hz PCM16 mono 音频。实时转写 provider 下一步接入。"
        finalTranscript = ""
        postProcessTitle = ""
        postProcessResult = ""
        capturedAudioBytes = 0L
        capturedChunkCount = 0
        status = VoiceFlowStatus.Connecting
        connectionStatus = "正在启动麦克风"

        recordingJob?.cancel()
        recordingJob = scope.launch {
            try {
                audioRecorder.start(currentConfig.audioFormat)
                status = VoiceFlowStatus.Recording
                connectionStatus = if (currentConfig.apiKey.isBlank()) {
                    "本地音频采集中，provider 未连接"
                } else {
                    "本地音频采集中，等待接入 ${currentConfig.providerName}"
                }
                val levelJob = launch {
                    audioRecorder.audioLevels.collect { level ->
                        amplitude = level.coerceIn(0.02f, 1f)
                    }
                }
                while (isActive) {
                    val chunk = audioRecorder.readChunk()
                    capturedAudioBytes += chunk.size
                    capturedChunkCount += 1
                    val capturedSeconds = audioDurationSeconds(capturedAudioBytes, currentConfig)
                    connectionStatus = "已采集 ${formatDuration(capturedSeconds)}，${capturedChunkCount} 个分片"
                    partialTranscript = "麦克风采集中：${formatDuration(capturedSeconds)}，约 ${formatAudioBytes(capturedAudioBytes)}。下一步会把这些 PCM chunk 发送给实时转写 session。"
                }
                levelJob.cancel()
            } catch (_: ClosedReceiveChannelException) {
                // Stopping the recorder closes the chunk channel.
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail(
                    message = "麦克风不可用",
                    hint = error.message ?: "请确认设备麦克风可用，并重新授权后再试。"
                )
            } finally {
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
        val permission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            status = VoiceFlowStatus.RequestingPermission
            connectionStatus = "正在请求麦克风权限"
            permissionLauncher.launch(permission)
        }
    }

    fun runPostProcess(action: PostProcessAction) {
        val sourceText = finalTranscript.trim()
        if (sourceText.isBlank()) {
            fail(
                message = "没有可处理的转写文本",
                hint = "当前版本已接入本地音频采集，实时转写 provider 接入后才会生成可处理文本。"
            )
            return
        }
        status = VoiceFlowStatus.PostProcessing
        postProcessTitle = action.label
        postProcessResult = ""
        copiedNotice = ""
        scope.launch {
            delay(500)
            postProcessResult = when (action) {
                PostProcessAction.Summarize -> summarizeText(sourceText)
                PostProcessAction.Polish -> polishText(sourceText)
                PostProcessAction.Rewrite -> rewriteText(sourceText)
            }
            status = VoiceFlowStatus.Completed
            history = history.mapIndexed { index, item ->
                if (index == 0 && item.text == finalTranscript) {
                    item.copy(postProcessResult = postProcessResult)
                } else {
                    item
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Header(status = status, connectionStatus = connectionStatus)
        RecorderPanel(
            status = status,
            connectionStatus = connectionStatus,
            amplitude = amplitude,
            transcript = finalTranscript.ifBlank { partialTranscript },
            copiedNotice = copiedNotice,
            errorMessage = errorMessage,
            recoveryHint = recoveryHint,
            hasTranscript = finalTranscript.isNotBlank(),
            onPrimaryAction = {
                when (status) {
                    VoiceFlowStatus.Recording,
                    VoiceFlowStatus.Connecting -> finishRecording()
                    else -> requestStartRecording()
                }
            },
            onCopyTranscript = {
                val copied = copyText("VoiceFlow transcript", finalTranscript.ifBlank { partialTranscript })
                copiedNotice = if (copied) "文本已复制到剪贴板" else "没有可复制的文本"
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
                    copiedNotice = if (copied) "$postProcessTitle 结果已复制" else "没有可复制的后处理结果"
                }
            )
        }
        SettingsPanel(
            providerName = providerName,
            onProviderNameChange = { providerName = it },
            baseUrl = baseUrl,
            onBaseUrlChange = { baseUrl = it },
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            realtimeModel = realtimeModel,
            onRealtimeModelChange = { realtimeModel = it },
            postProcessModel = postProcessModel,
            onPostProcessModelChange = { postProcessModel = it },
            streamingEnabled = streamingEnabled,
            onStreamingEnabledChange = { streamingEnabled = it },
            maxRecordingSeconds = maxRecordingSeconds,
            onMaxRecordingSecondsChange = { maxRecordingSeconds = it.filter { char -> char.isDigit() }.take(3) },
            prompt = prompt,
            onPromptChange = { prompt = it },
            hotwords = hotwords,
            onHotwordsChange = { hotwords = it },
            onTestConnection = {
                val currentConfig = config()
                connectionStatus = when {
                    currentConfig.apiKey.isBlank() -> "连接测试失败：API Key 为空"
                    currentConfig.baseUrl.isBlank() -> "连接测试失败：Base URL 为空"
                    currentConfig.realtimeModel.isBlank() -> "连接测试失败：实时模型为空"
                    else -> "连接测试通过：配置完整，等待接入真实 provider"
                }
            }
        )
        HistoryPanel(
            history = history,
            onCopy = { item ->
                val copied = copyText("VoiceFlow history", item.text)
                copiedNotice = if (copied) "历史文本已复制" else "历史文本为空"
            },
            onDelete = { item ->
                history = history.filterNot { it.id == item.id }
            }
        )
    }
}

@Composable
private fun Header(status: VoiceFlowStatus, connectionStatus: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Android VoiceFlow",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "实时语音转文字工作台",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF5F665F)
            )
        }
        StatusBadge(status = status, connectionStatus = connectionStatus)
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
                Column {
                    Text(
                        text = status.userText(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = connectionStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF687069)
                    )
                }
                Button(
                    enabled = primaryActionEnabled,
                    onClick = onPrimaryAction
                ) {
                    Text(
                        text = if (status == VoiceFlowStatus.Recording || status == VoiceFlowStatus.Connecting) {
                            "停止"
                        } else {
                            "开始"
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
                    text = transcript.ifBlank { "点击开始说话" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (transcript.isBlank()) Color(0xFF7B827B) else Color(0xFF1F2924)
                )
            }

            if (errorMessage.isNotBlank()) {
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
                    Text("复制")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = hasTranscript && status != VoiceFlowStatus.PostProcessing,
                    onClick = { onPostProcess(PostProcessAction.Summarize) }
                ) {
                    Text("总结")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = hasTranscript && status != VoiceFlowStatus.PostProcessing,
                    onClick = { onPostProcess(PostProcessAction.Polish) }
                ) {
                    Text("润色")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = hasTranscript && status != VoiceFlowStatus.PostProcessing,
                    onClick = { onPostProcess(PostProcessAction.Rewrite) }
                ) {
                    Text("改写")
                }
            }
        }
    }
}

@Composable
private fun ErrorPanel(message: String, hint: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFFFF1EE)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF7A3A33)
            )
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
                    text = title.ifBlank { "后处理" },
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
private fun SettingsPanel(
    providerName: String,
    onProviderNameChange: (String) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    realtimeModel: String,
    onRealtimeModelChange: (String) -> Unit,
    postProcessModel: String,
    onPostProcessModelChange: (String) -> Unit,
    streamingEnabled: Boolean,
    onStreamingEnabledChange: (Boolean) -> Unit,
    maxRecordingSeconds: String,
    onMaxRecordingSecondsChange: (String) -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    hotwords: String,
    onHotwordsChange: (String) -> Unit,
    onTestConnection: () -> Unit
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
                text = "Provider 设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
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
                label = { Text("Base URL") }
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = apiKey,
                onValueChange = onApiKeyChange,
                singleLine = true,
                label = { Text("API Key") },
                visualTransformation = PasswordVisualTransformation()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = realtimeModel,
                    onValueChange = onRealtimeModelChange,
                    singleLine = true,
                    label = { Text("实时转写模型") }
                )
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = postProcessModel,
                    onValueChange = onPostProcessModelChange,
                    singleLine = true,
                    label = { Text("后处理模型") }
                )
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
                label = { Text("转写 prompt") }
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = hotwords,
                onValueChange = onHotwordsChange,
                singleLine = true,
                label = { Text("热词 / 术语表") }
            )
            OutlinedButton(onClick = onTestConnection) {
                Text("测试连接")
            }
        }
    }
}

@Composable
private fun HistoryPanel(
    history: List<TranscriptHistoryItem>,
    onCopy: (TranscriptHistoryItem) -> Unit,
    onDelete: (TranscriptHistoryItem) -> Unit
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
                text = "最近历史",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (history.isEmpty()) {
                Text(
                    text = "暂无历史记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF687069)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(history, key = { it.id }) { item ->
                        HistoryItemRow(
                            item = item,
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
private fun HistoryItemRow(
    item: TranscriptHistoryItem,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatDisplayTime(item.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF687069)
                )
                Text(
                    text = item.model,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF7A6B45)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCopy) {
                    Text("复制")
                }
                TextButton(onClick = onDelete) {
                    Text("删除")
                }
            }
        }
        Text(
            text = item.text,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
        item.postProcessResult?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                maxLines = 2,
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
        VoiceFlowStatus.Idle -> "点击开始说话"
        VoiceFlowStatus.RequestingPermission -> "等待麦克风权限"
        VoiceFlowStatus.Connecting -> "正在连接"
        VoiceFlowStatus.Recording -> "正在听..."
        VoiceFlowStatus.Finalizing -> "正在生成最终文本"
        VoiceFlowStatus.Completed -> "转写完成"
        VoiceFlowStatus.PostProcessing -> "正在处理文本"
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
    return "记录：\n$cleaned\n\n下一步：确认真实 Realtime provider、音频采样配置和后处理 prompt。"
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
