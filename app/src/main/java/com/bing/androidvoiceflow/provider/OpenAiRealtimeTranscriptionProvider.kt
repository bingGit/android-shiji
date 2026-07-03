package com.bing.androidvoiceflow.provider

import android.util.Base64
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
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenAiRealtimeTranscriptionProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) : RealtimeTranscriptionProvider {
    override suspend fun startSession(config: ProviderConfig): RealtimeSession {
        validateConfig(config)
        return OpenAiRealtimeSession(
            config = config,
            client = client
        ).also { it.connect() }
    }

    override suspend fun testConnection(config: ProviderConfig): ConnectionTestResult {
        return try {
            validateConfig(config)
            val url = config.realtimeWebSocketUrl()
            ConnectionTestResult(
                success = true,
                summary = "配置可用于实时连接",
                detail = "WebSocket: $url"
            )
        } catch (error: ConfigValidationException) {
            ConnectionTestResult(
                success = false,
                summary = error.voiceFlowError.message,
                detail = error.voiceFlowError.recoveryHint
            )
        } catch (error: Exception) {
            ConnectionTestResult(
                success = false,
                summary = "连接配置无效",
                detail = error.message
            )
        }
    }
}

private class OpenAiRealtimeSession(
    private val config: ProviderConfig,
    private val client: OkHttpClient
) : RealtimeSession {
    private val eventStream = MutableSharedFlow<TranscriptionEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val connected = CompletableDeferred<Unit>()
    private val finalTranscript = CompletableDeferred<FinalTranscript>()
    private var latestPartial = ""
    private var socket: WebSocket? = null

    override val events: Flow<TranscriptionEvent> = eventStream

    fun connect() {
        val request = Request.Builder()
            .url(config.realtimeWebSocketUrl())
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()
        socket = client.newWebSocket(request, listener())
    }

    override suspend fun sendAudioChunk(chunk: ByteArray) {
        connected.await()
        val encodedAudio = Base64.encodeToString(chunk, Base64.NO_WRAP)
        sendJson(
            JSONObject()
                .put("type", "input_audio_buffer.append")
                .put("audio", encodedAudio)
        )
    }

    override suspend fun commit(): FinalTranscript {
        connected.await()
        sendJson(JSONObject().put("type", "input_audio_buffer.commit"))
        sendJson(
            JSONObject()
                .put("type", "response.create")
                .put(
                    "response",
                    JSONObject()
                        .put("modalities", listOf("text"))
                        .put("instructions", config.prompt)
                )
        )
        return withContext(Dispatchers.IO) {
            val result = finalTranscript.await()
            socket?.close(1000, "done")
            result
        }
    }

    override suspend fun cancel() {
        socket?.cancel()
    }

    private fun listener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                sendSessionUpdate()
                connected.complete(Unit)
                eventStream.tryEmit(TranscriptionEvent.Connected(config.providerName))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val message = response?.toRealtimeFailureMessage() ?: (t.message ?: "WebSocket 连接失败")
                if (!connected.isCompleted) connected.completeExceptionally(IllegalStateException(message, t))
                if (!finalTranscript.isCompleted) finalTranscript.completeExceptionally(IllegalStateException(message, t))
                eventStream.tryEmit(
                    TranscriptionEvent.Failed(
                        VoiceFlowError.NetworkUnavailable(message)
                    )
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!finalTranscript.isCompleted && latestPartial.isNotBlank()) {
                    completeFinal(latestPartial)
                }
            }
        }
    }

    private fun sendSessionUpdate() {
        sendJson(
            JSONObject()
                .put("type", "session.update")
                .put(
                    "session",
                    JSONObject()
                        .put("modalities", listOf("text"))
                        .put("input_audio_format", "pcm16")
                        .put("turn_detection", JSONObject.NULL)
                        .put("instructions", config.prompt)
                )
        )
    }

    private fun handleMessage(text: String) {
        runCatching { JSONObject(text) }
            .onFailure {
                eventStream.tryEmit(
                    TranscriptionEvent.Failed(
                        VoiceFlowError.ProviderRejected("Provider 返回了无法解析的事件")
                    )
                )
            }
            .onSuccess { json ->
                when (val type = json.optString("type")) {
                    "error" -> handleProviderError(json)
                    "response.audio_transcript.delta",
                    "conversation.item.input_audio_transcription.delta",
                    "response.text.delta",
                    "response.output_text.delta" -> appendPartial(json.optString("delta"))
                    "response.audio_transcript.done",
                    "conversation.item.input_audio_transcription.completed" -> completeFinal(
                        json.optString("transcript").ifBlank { latestPartial }
                    )
                    "response.text.done",
                    "response.output_text.done" -> completeFinal(
                        json.optString("text").ifBlank { latestPartial }
                    )
                    "response.done" -> handleResponseDone(json)
                    else -> Unit
                }
            }
    }

    private fun handleProviderError(json: JSONObject) {
        val error = json.optJSONObject("error")
        val message = error?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json.optString("message")
            ?: "Provider 返回错误"
        eventStream.tryEmit(TranscriptionEvent.Failed(VoiceFlowError.ProviderRejected(message)))
        if (!finalTranscript.isCompleted) {
            finalTranscript.completeExceptionally(IllegalStateException(message))
        }
    }

    private fun appendPartial(delta: String) {
        if (delta.isBlank()) return
        latestPartial += delta
        eventStream.tryEmit(TranscriptionEvent.PartialTranscript(latestPartial))
    }

    private fun handleResponseDone(json: JSONObject) {
        val text = json.extractResponseText().ifBlank { latestPartial }
        if (text.isNotBlank()) completeFinal(text)
    }

    private fun completeFinal(text: String) {
        val transcript = FinalTranscript(
            text = text.trim(),
            providerName = config.providerName,
            model = config.realtimeModel
        )
        eventStream.tryEmit(TranscriptionEvent.FinalTranscriptReady(transcript))
        if (!finalTranscript.isCompleted) {
            finalTranscript.complete(transcript)
        }
    }

    private fun sendJson(json: JSONObject) {
        socket?.send(json.toString())
    }
}

private fun validateConfig(config: ProviderConfig) {
    when {
        config.apiKey.isBlank() -> throw ConfigValidationException(VoiceFlowError.ApiKeyMissing)
        config.baseUrl.isBlank() -> throw ConfigValidationException(
            VoiceFlowError.NetworkUnavailable("Base URL 为空")
        )
        config.realtimeModel.isBlank() -> throw ConfigValidationException(
            VoiceFlowError.ProviderRejected("实时模型为空")
        )
    }
}

private class ConfigValidationException(
    val voiceFlowError: VoiceFlowError
) : IllegalArgumentException(voiceFlowError.message)

private fun Response.toRealtimeFailureMessage(): String {
    val detail = runCatching { peekBody(2048).string() }.getOrNull().orEmpty()
    val providerMessage = detail.extractProviderErrorMessage()
    return when (code) {
        401 -> listOfNotNull(
            "实时转写鉴权失败：请检查实时语音转写 API Key 是否填写正确。",
            providerMessage
        ).joinToString("\n")
        403 -> listOfNotNull(
            "实时转写连接被拒绝：服务端拒绝 WebSocket 升级。请检查 API Key 是否有 realtime 权限、模型是否支持 realtime，或中转站是否允许 /v1/realtime。",
            providerMessage
        ).joinToString("\n")
        404 -> listOfNotNull(
            "实时转写地址不存在：请检查 Base URL 是否为 /v1/realtime。",
            providerMessage
        ).joinToString("\n")
        else -> listOfNotNull(
            "实时转写连接失败：HTTP $code $message。",
            providerMessage ?: detail.takeIf { it.isNotBlank() }
        ).joinToString("\n")
    }.trim()
}

private fun String.extractProviderErrorMessage(): String? {
    if (isBlank()) return null
    return runCatching {
        val json = JSONObject(this)
        val message = json.optJSONObject("error")?.optString("message")
            ?.takeIf { it.isNotBlank() }
            ?: json.optString("message").takeIf { it.isNotBlank() }
        message?.let { "服务端返回：$it" }
    }.getOrNull()
}

private fun ProviderConfig.realtimeWebSocketUrl(): String {
    val trimmed = baseUrl.trim().removeSuffix("/")
    val websocketBase = when {
        trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
        trimmed.startsWith("https://") -> "wss://${trimmed.removePrefix("https://")}"
        trimmed.startsWith("http://") -> "ws://${trimmed.removePrefix("http://")}"
        else -> "wss://$trimmed"
    }.withRealtimePath()
    val separator = if (websocketBase.contains("?")) "&" else "?"
    return "$websocketBase${separator}model=$realtimeModel"
}

private fun String.withRealtimePath(): String {
    val queryStart = indexOf('?')
    val urlWithoutQuery = if (queryStart >= 0) substring(0, queryStart) else this
    val query = if (queryStart >= 0) substring(queryStart) else ""
    val schemeEnd = urlWithoutQuery.indexOf("://")
    if (schemeEnd < 0) return urlWithoutQuery + query
    val pathStart = urlWithoutQuery.indexOf('/', startIndex = schemeEnd + 3)
    val hasPath = pathStart >= 0 && pathStart < urlWithoutQuery.lastIndex
    return if (hasPath) {
        urlWithoutQuery + query
    } else {
        "$urlWithoutQuery/v1/realtime$query"
    }
}

private fun JSONObject.extractResponseText(): String {
    val response = optJSONObject("response") ?: return ""
    val output = response.optJSONArray("output") ?: return ""
    val chunks = mutableListOf<String>()
    for (outputIndex in 0 until output.length()) {
        val item = output.optJSONObject(outputIndex) ?: continue
        val content = item.optJSONArray("content") ?: continue
        for (contentIndex in 0 until content.length()) {
            val part = content.optJSONObject(contentIndex) ?: continue
            val text = part.optString("text")
                .ifBlank { part.optString("transcript") }
            if (text.isNotBlank()) chunks += text
        }
    }
    return chunks.joinToString("")
}
