package com.bing.androidvoiceflow.core

import kotlinx.coroutines.flow.Flow

data class AudioFormatConfig(
    val encoding: String = "PCM16",
    val channelCount: Int = 1,
    val sampleRateHz: Int = 24_000,
    val chunkDurationMs: Int = 400
)

data class ProviderConfig(
    val providerName: String,
    val baseUrl: String,
    val apiKey: String,
    val realtimeModel: String,
    val postProcessModel: String,
    val streamingEnabled: Boolean,
    val prompt: String,
    val hotwords: List<String>,
    val maxRecordingSeconds: Int,
    val audioFormat: AudioFormatConfig = AudioFormatConfig()
)

data class FinalTranscript(
    val text: String,
    val providerName: String,
    val model: String
)

sealed interface TranscriptionEvent {
    data class Connected(val providerName: String) : TranscriptionEvent
    data class AudioLevel(val value: Float) : TranscriptionEvent
    data class PartialTranscript(val text: String) : TranscriptionEvent
    data class FinalTranscriptReady(val transcript: FinalTranscript) : TranscriptionEvent
    data class Failed(val error: VoiceFlowError) : TranscriptionEvent
}

sealed interface VoiceFlowError {
    val message: String
    val recoveryHint: String

    data object MicrophonePermissionDenied : VoiceFlowError {
        override val message = "麦克风权限被拒绝"
        override val recoveryHint = "请在系统设置中允许 Android VoiceFlow 使用麦克风。"
    }

    data object ApiKeyMissing : VoiceFlowError {
        override val message = "API Key 为空"
        override val recoveryHint = "请在设置里填写 provider 的 API Key。"
    }

    data class NetworkUnavailable(override val message: String) : VoiceFlowError {
        override val recoveryHint = "请检查网络、Base URL 和中转站可用性。"
    }

    data class ProviderRejected(override val message: String) : VoiceFlowError {
        override val recoveryHint = "请检查 API Key 权限、模型名和 provider 协议兼容性。"
    }

    data object EmptyFinalTranscript : VoiceFlowError {
        override val message = "最终转写为空"
        override val recoveryHint = "请确认录音中有人声，或重试一次。"
    }

    data class PostProcessFailed(override val message: String) : VoiceFlowError {
        override val recoveryHint = "原始转写已保留，请稍后重试后处理。"
    }
}

interface AudioRecorder {
    val audioLevels: Flow<Float>
    suspend fun start(config: AudioFormatConfig)
    suspend fun readChunk(): ByteArray
    suspend fun stop()
}

interface RealtimeTranscriptionProvider {
    suspend fun startSession(config: ProviderConfig): RealtimeSession
    suspend fun testConnection(config: ProviderConfig): ConnectionTestResult
}

interface RealtimeSession {
    val events: Flow<TranscriptionEvent>
    suspend fun sendAudioChunk(chunk: ByteArray)
    suspend fun commit(): FinalTranscript
    suspend fun cancel()
}

interface TextPostProcessProvider {
    suspend fun summarize(text: String, config: ProviderConfig): String
    suspend fun polish(text: String, config: ProviderConfig): String
    suspend fun rewrite(text: String, config: ProviderConfig): String
}

data class ConnectionTestResult(
    val success: Boolean,
    val summary: String,
    val detail: String? = null
)

