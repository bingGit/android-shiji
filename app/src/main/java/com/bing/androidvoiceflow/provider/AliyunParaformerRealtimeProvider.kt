package com.bing.androidvoiceflow.provider

import android.util.Log
import com.bing.androidvoiceflow.core.ConnectionTestResult
import com.bing.androidvoiceflow.core.FinalTranscript
import com.bing.androidvoiceflow.core.ProviderConfig
import com.bing.androidvoiceflow.core.RealtimeSession
import com.bing.androidvoiceflow.core.RealtimeTranscriptionProvider
import com.bing.androidvoiceflow.core.TranscriptionEvent
import com.bing.androidvoiceflow.core.VoiceFlowError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val ALIYUN_PROVIDER_TAG = "AliyunParaformer"

class AliyunParaformerRealtimeProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(AliyunFallbackDns())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) : RealtimeTranscriptionProvider {
    override suspend fun startSession(config: ProviderConfig): RealtimeSession {
        validateAliyunConfig(config)
        return AliyunParaformerRealtimeSession(
            config = config,
            client = client,
            endpointUrls = config.aliyunWebSocketUrls()
        ).also { it.connect() }
    }

    override suspend fun testConnection(config: ProviderConfig): ConnectionTestResult {
        return try {
            validateAliyunConfig(config)
            val endpoints = config.aliyunWebSocketUrls()
            val dnsSummary = withContext(Dispatchers.IO) {
                endpoints.joinToString(separator = "\n") { endpoint ->
                    val host = URI(endpoint).host.orEmpty()
                    val addresses = runCatching { client.dns.lookup(host) }
                        .getOrDefault(emptyList())
                        .joinToString { it.hostAddress ?: it.hostName }
                        .ifBlank { "未解析" }
                    "WebSocket: $endpoint\nDNS: $addresses"
                }
            }
            ConnectionTestResult(
                success = true,
                summary = "阿里云 Paraformer 配置完整",
                detail = dnsSummary
            )
        } catch (error: Exception) {
            ConnectionTestResult(
                success = false,
                summary = "阿里云 Paraformer 配置不可用",
                detail = error.message
            )
        }
    }
}

private class AliyunParaformerRealtimeSession(
    private val config: ProviderConfig,
    private val client: OkHttpClient,
    private val endpointUrls: List<String>
) : RealtimeSession {
    private val taskId = UUID.randomUUID().toString()
    private val eventStream = MutableSharedFlow<TranscriptionEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val taskStarted = CompletableDeferred<Unit>()
    private val taskFinished = CompletableDeferred<FinalTranscript>()
    private val finalSentences = mutableListOf<String>()
    private var latestPartial = ""
    private var socket: WebSocket? = null
    private var currentEndpointIndex = 0
    private var cancelled = false

    override val events: Flow<TranscriptionEvent> = eventStream

    fun connect() {
        connectToEndpoint(0)
    }

    private fun connectToEndpoint(index: Int) {
        currentEndpointIndex = index
        val workspaceId = config.aliyunWorkspaceIdForHeader()
        val request = Request.Builder()
            .url(endpointUrls[index])
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("User-Agent", "AndroidVoiceFlow/0.1")
            .apply {
                if (workspaceId.isNotBlank()) {
                    addHeader("X-DashScope-WorkSpace", workspaceId)
                }
            }
            .build()
        socket = client.newWebSocket(request, listener(index))
    }

    override suspend fun sendAudioChunk(chunk: ByteArray) {
        taskStarted.await()
        socket?.send(chunk.toByteString())
    }

    override suspend fun commit(): FinalTranscript {
        taskStarted.await()
        sendJson(finishTaskMessage())
        return withContext(Dispatchers.IO) {
            val transcript = taskFinished.await()
            socket?.close(1000, "done")
            transcript
        }
    }

    override suspend fun cancel() {
        cancelled = true
        socket?.cancel()
    }

    private fun listener(endpointIndex: Int): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (endpointIndex != currentEndpointIndex) {
                    webSocket.cancel()
                    return
                }
                socket = webSocket
                val runTaskMessage = runTaskMessage()
                Log.d(
                    ALIYUN_PROVIDER_TAG,
                    "send run-task endpoint=${endpointUrls[endpointIndex]}, taskId=$taskId, " +
                        "model=${config.realtimeModel.ifBlank { "paraformer-realtime-v2" }}, " +
                        "sampleRate=${config.audioFormat.sampleRateHz}, workspaceSet=${config.aliyunWorkspaceIdForHeader().isNotBlank()}"
                )
                webSocket.send(runTaskMessage.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (cancelled || endpointIndex != currentEndpointIndex) return
                if (tryConnectFallback()) return

                val message = response?.toAliyunFailureMessage() ?: (t.message ?: "阿里云 Paraformer 连接失败")
                if (!taskStarted.isCompleted) taskStarted.completeExceptionally(IllegalStateException(message, t))
                if (!taskFinished.isCompleted) taskFinished.completeExceptionally(IllegalStateException(message, t))
                eventStream.tryEmit(TranscriptionEvent.Failed(VoiceFlowError.ProviderRejected(message)))
            }
        }
    }

    private fun tryConnectFallback(): Boolean {
        if (taskStarted.isCompleted) return false
        val nextEndpointIndex = currentEndpointIndex + 1
        if (nextEndpointIndex > endpointUrls.lastIndex) return false
        connectToEndpoint(nextEndpointIndex)
        return true
    }

    private fun handleMessage(text: String) {
        runCatching { JSONObject(text) }
            .onFailure {
                eventStream.tryEmit(
                    TranscriptionEvent.Failed(VoiceFlowError.ProviderRejected("阿里云返回了无法解析的事件"))
                )
            }
            .onSuccess { json ->
                val header = json.optJSONObject("header")
                when (val event = header?.optString("event")) {
                    "task-started" -> {
                        Log.d(ALIYUN_PROVIDER_TAG, "task-started taskId=$taskId")
                        if (!taskStarted.isCompleted) taskStarted.complete(Unit)
                        eventStream.tryEmit(TranscriptionEvent.Connected(config.providerName))
                    }
                    "result-generated" -> handleResultGenerated(json)
                    "task-finished" -> completeTask()
                    "task-failed" -> {
                        val code = header.optString("error_code")
                        val message = header.optString("error_message").ifBlank { "阿里云实时识别任务失败" }
                        val attributes = header.optJSONObject("attributes")
                        val requestId = attributes?.optString("request_id")
                            ?.takeIf { it.isNotBlank() }
                            ?: attributes?.optString("requestId")?.takeIf { it.isNotBlank() }
                        val detail = buildString {
                            append(if (code.isNotBlank()) "$message ($code)" else message)
                            append("\nTask ID: $taskId")
                            if (!requestId.isNullOrBlank()) {
                                append("\nRequest ID: $requestId")
                            }
                        }
                        Log.e(ALIYUN_PROVIDER_TAG, "task-failed $detail raw=$text")
                        eventStream.tryEmit(TranscriptionEvent.Failed(VoiceFlowError.ProviderRejected(detail)))
                        if (!taskStarted.isCompleted) {
                            taskStarted.completeExceptionally(IllegalStateException(detail))
                        }
                        if (!taskFinished.isCompleted) {
                            taskFinished.completeExceptionally(IllegalStateException(detail))
                        }
                    }
                    else -> Unit
                }
            }
    }

    private fun handleResultGenerated(json: JSONObject) {
        val sentence = json.optJSONObject("payload")
            ?.optJSONObject("output")
            ?.optJSONObject("sentence")
            ?: return
        if (sentence.optBoolean("heartbeat", false)) return
        val text = sentence.optString("text").trim()
        if (text.isBlank()) return
        if (sentence.optBoolean("sentence_end", false)) {
            finalSentences += text
            latestPartial = ""
            eventStream.tryEmit(TranscriptionEvent.PartialTranscript(finalSentences.joinToString("")))
        } else {
            latestPartial = text
            eventStream.tryEmit(
                TranscriptionEvent.PartialTranscript(finalSentences.joinToString("") + latestPartial)
            )
        }
    }

    private fun completeTask() {
        val text = (finalSentences.joinToString("") + latestPartial).trim()
        val transcript = FinalTranscript(
            text = text,
            providerName = config.providerName,
            model = config.realtimeModel
        )
        eventStream.tryEmit(TranscriptionEvent.FinalTranscriptReady(transcript))
        if (!taskFinished.isCompleted) taskFinished.complete(transcript)
    }

    private fun sendJson(json: JSONObject) {
        socket?.send(json.toString())
    }

    private fun runTaskMessage(): JSONObject {
        return JSONObject()
            .put(
                "header",
                JSONObject()
                    .put("action", "run-task")
                    .put("task_id", taskId)
                    .put("streaming", "duplex")
            )
            .put(
                "payload",
                JSONObject()
                    .put("task_group", "audio")
                    .put("task", "asr")
                    .put("function", "recognition")
                    .put("model", config.realtimeModel.ifBlank { "paraformer-realtime-v2" })
                    .put(
                        "parameters",
                        JSONObject()
                            .put("format", "pcm")
                            .put("sample_rate", config.audioFormat.sampleRateHz)
                            .put("disfluency_removal_enabled", false)
                            .put("language_hints", JSONArray().put("zh"))
                    )
                    .put("input", JSONObject())
            )
    }

    private fun finishTaskMessage(): JSONObject {
        return JSONObject()
            .put(
                "header",
                JSONObject()
                    .put("action", "finish-task")
                    .put("task_id", taskId)
                    .put("streaming", "duplex")
            )
            .put(
                "payload",
                JSONObject().put("input", JSONObject())
            )
    }
}

private const val ALIYUN_LEGACY_WEBSOCKET_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
private val ALIYUN_DASHSCOPE_FALLBACK_IPS = listOf(
    "8.152.159.24",
    "39.96.198.249",
    "39.96.213.166",
    "8.140.217.18"
)
private val ALIYUN_MAAS_FALLBACK_IPS = listOf(
    "101.201.58.201",
    "47.94.20.201"
)

private class AliyunFallbackDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        return try {
            Dns.SYSTEM.lookup(hostname)
        } catch (error: UnknownHostException) {
            val fallbackIps = when {
                hostname.equals("dashscope.aliyuncs.com", ignoreCase = true) -> ALIYUN_DASHSCOPE_FALLBACK_IPS
                hostname.endsWith(".cn-beijing.maas.aliyuncs.com", ignoreCase = true) -> ALIYUN_MAAS_FALLBACK_IPS
                else -> throw error
            }
            fallbackIps.mapNotNull { it.toInetAddressOrNull(hostname) }.ifEmpty { throw error }
        }
    }
}

private fun validateAliyunConfig(config: ProviderConfig) {
    require(config.apiKey.isNotBlank()) { "阿里云 API Key 为空" }
    require(config.aliyunWorkspaceId.isNotBlank() || config.baseUrl.isNotBlank()) {
        "请填写 Workspace ID，或填写完整阿里云 WebSocket URL"
    }
}

private fun ProviderConfig.aliyunWebSocketUrls(): List<String> {
    return listOf(
        aliyunPrimaryWebSocketUrl(),
        ALIYUN_LEGACY_WEBSOCKET_URL
    ).distinct()
}

private fun ProviderConfig.aliyunPrimaryWebSocketUrl(): String {
    val customUrl = baseUrl.trim()
    if (customUrl.isNotBlank()) {
        if (customUrl.startsWith("wss://") || customUrl.startsWith("ws://")) {
            return customUrl.withAliyunInferencePath()
        }
        if (customUrl.startsWith("https://")) {
            return customUrl.replacePrefix("https://", "wss://").withAliyunInferencePath()
        }
        if (customUrl.startsWith("http://")) {
            return customUrl.replacePrefix("http://", "ws://").withAliyunInferencePath()
        }
        if (customUrl.contains(".") || customUrl.contains("/")) {
            return "wss://$customUrl".withAliyunInferencePath()
        }
        return customUrl.toAliyunWorkspaceUrl(aliyunRegion)
    }
    return aliyunWorkspaceId.trim().toAliyunWorkspaceUrl(aliyunRegion)
}

private fun ProviderConfig.aliyunWorkspaceIdForHeader(): String {
    val explicitWorkspaceId = aliyunWorkspaceId.trim()
    if (explicitWorkspaceId.isNotBlank()) return explicitWorkspaceId

    val customUrl = baseUrl.trim()
    if (customUrl.isBlank()) return ""
    val hostOrWorkspaceId = customUrl
        .replacePrefix("wss://", "")
        .replacePrefix("ws://", "")
        .replacePrefix("https://", "")
        .replacePrefix("http://", "")
        .substringBefore("/")
    return when {
        hostOrWorkspaceId.endsWith(".maas.aliyuncs.com") -> hostOrWorkspaceId.substringBefore(".")
        "." !in hostOrWorkspaceId -> hostOrWorkspaceId
        else -> ""
    }
}

private fun String.toAliyunWorkspaceUrl(regionValue: String): String {
    val region = regionValue.ifBlank { "cn-beijing" }.trim()
    return "wss://$this.$region.maas.aliyuncs.com/api-ws/v1/inference"
}

private fun String.withAliyunInferencePath(): String {
    val queryStart = indexOf("?")
    val urlWithoutQuery = if (queryStart >= 0) substring(0, queryStart) else this
    val query = if (queryStart >= 0) substring(queryStart) else ""
    if (urlWithoutQuery.contains("/api-ws/v1/inference")) return this
    return urlWithoutQuery.removeSuffix("/") + "/api-ws/v1/inference" + query
}

private fun String.toInetAddressOrNull(hostname: String): InetAddress? {
    return runCatching {
        val address = split(".").map { it.toInt().toByte() }.toByteArray()
        InetAddress.getByAddress(hostname, address)
    }.getOrNull()
}

private fun Response.toAliyunFailureMessage(): String {
    val detail = runCatching { peekBody(2048).string() }.getOrNull().orEmpty()
    val providerMessage = detail.extractAliyunProviderErrorMessage()
    return listOfNotNull(
        "阿里云 Paraformer 连接失败：HTTP $code $message。",
        providerMessage ?: detail.takeIf { it.isNotBlank() }
    ).joinToString("\n")
}

private fun String.extractAliyunProviderErrorMessage(): String? {
    if (isBlank()) return null
    return runCatching {
        val json = JSONObject(this)
        val message = json.optJSONObject("error")?.optString("message")
            ?.takeIf { it.isNotBlank() }
            ?: json.optString("message").takeIf { it.isNotBlank() }
            ?: json.optString("error_message").takeIf { it.isNotBlank() }
        message?.let { "服务端返回：$it" }
    }.getOrNull()
}

private fun String.replacePrefix(oldPrefix: String, newPrefix: String): String {
    return if (startsWith(oldPrefix)) newPrefix + removePrefix(oldPrefix) else this
}
