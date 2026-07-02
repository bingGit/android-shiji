package com.bing.androidvoiceflow.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.bing.androidvoiceflow.core.AudioFormatConfig
import com.bing.androidvoiceflow.core.AudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

class AndroidPcmAudioRecorder : AudioRecorder {
    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val levelEvents = MutableSharedFlow<Float>(replay = 1)
    private var audioRecord: AudioRecord? = null
    private var readJob: Job? = null
    private var chunks = Channel<ByteArray>(Channel.BUFFERED)

    override val audioLevels: Flow<Float> = levelEvents

    @SuppressLint("MissingPermission")
    override suspend fun start(config: AudioFormatConfig) {
        stop()
        require(config.encoding == "PCM16") { "Only PCM16 audio is supported in MVP." }
        require(config.channelCount == 1) { "Only mono audio is supported in MVP." }

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            channelConfig,
            encoding
        )
        require(minBufferSize > 0) {
            "AudioRecord does not support ${config.sampleRateHz} Hz PCM16 mono on this device."
        }

        val chunkSizeBytes = config.chunkSizeBytes()
        val bufferSize = max(minBufferSize, chunkSizeBytes * 2)
        val nextAudioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            config.sampleRateHz,
            channelConfig,
            encoding,
            bufferSize
        )
        require(nextAudioRecord.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize."
        }

        chunks = Channel(Channel.BUFFERED)
        audioRecord = nextAudioRecord
        nextAudioRecord.startRecording()

        readJob = recorderScope.launch {
            val buffer = ByteArray(chunkSizeBytes)
            while (nextAudioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val bytesRead = nextAudioRecord.read(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    val chunk = buffer.copyOf(bytesRead)
                    levelEvents.emit(chunk.rmsLevel())
                    chunks.send(chunk)
                }
            }
        }
    }

    override suspend fun readChunk(): ByteArray {
        return chunks.receive()
    }

    override suspend fun stop() {
        withContext(Dispatchers.IO) {
            readJob?.cancel()
            readJob = null
            chunks.close()
            val currentRecord = audioRecord
            audioRecord = null
            if (currentRecord != null) {
                runCatching {
                    if (currentRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        currentRecord.stop()
                    }
                }
                currentRecord.release()
            }
            levelEvents.emit(0f)
        }
    }
}

private fun AudioFormatConfig.chunkSizeBytes(): Int {
    val bytesPerSample = 2
    val samplesPerChunk = sampleRateHz * chunkDurationMs / 1000
    return max(bytesPerSample * channelCount, samplesPerChunk * channelCount * bytesPerSample)
}

private fun ByteArray.rmsLevel(): Float {
    if (size < 2) return 0f
    var sumSquares = 0.0
    var sampleCount = 0
    var index = 0
    while (index + 1 < size) {
        val low = this[index].toInt() and 0xFF
        val high = this[index + 1].toInt()
        val sample = (high shl 8) or low
        val normalized = sample / Short.MAX_VALUE.toDouble()
        sumSquares += normalized * normalized
        sampleCount += 1
        index += 2
    }
    if (sampleCount == 0) return 0f
    return sqrt(sumSquares / sampleCount).toFloat().coerceIn(0f, 1f)
}

