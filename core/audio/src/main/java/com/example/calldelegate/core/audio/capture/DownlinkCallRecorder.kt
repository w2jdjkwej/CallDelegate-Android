package com.example.calldelegate.core.audio.capture

import android.content.Context
import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.RecordingAudioNormalizer
import com.example.calldelegate.domain.api.SessionRecordingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Continuous downlink (remote-party) audio recorder for call sessions.
 *
 * Uses [AudioRecordPcmReader] with the canonical 4-source fallback chain
 * (VOICE_COMMUNICATION → VOICE_CALL → VOICE_RECOGNITION → MIC) plus
 * automatic microphone mute to capture primarily remote audio.
 *
 * Captured PCM is batched, normalized via [RecordingAudioNormalizer],
 * and appended to [SessionRecordingStore] so it becomes part of the
 * session's WAV recording — alongside any turn-based ASR/TTS audio
 * already captured by [com.example.calldelegate.core.ai.DefaultCallSessionController].
 *
 * Lifecycle is strictly single-session: a second [start] while recording
 * is an error. Call [stop] or [release] before starting a new session.
 *
 * Based on the audio-source fallback strategy from CallProxyDemo's
 * [com.callproxy.demo.recorder.AudioRecordFallbackRecorder], adapted to
 * CallDelegate's coroutine/Flow architecture and Clean Architecture layering.
 */
class DownlinkCallRecorder(
    private val context: Context,
    private val recordingStore: SessionRecordingStore,
    private val recordingAudioNormalizer: RecordingAudioNormalizer,
    private val scope: CoroutineScope,
    private val captureDispatcher: CoroutineDispatcher,
    private val flushIntervalSamples: Int = 16_000, // Flush every ~1 second @ 16kHz
    private val frameBytes: Int = 3_200,            // 100 ms @ 16kHz mono 16-bit
) {

    private val mutex = Mutex()
    private var activeSessionId: String? = null
    private var captureJob: Job? = null
    private var reader: AudioRecordPcmReader? = null
    @Volatile private var isRecording: Boolean = false
    @Volatile private var lastError: String? = null

    /** The audio source that was successfully initialized, or "unknown" before start. */
    val activeSourceLabel: String get() = reader?.sourceLabel ?: "unknown"

    /** Whether recording is currently active. */
    val recording: Boolean get() = isRecording

    /** The last error encountered, or null. */
    val error: String? get() = lastError

    /**
     * Start continuous downlink recording for the given session.
     *
     * @param sessionId The call session to associate this recording with.
     * @return Success or a failure describing why recording could not start.
     */
    suspend fun start(sessionId: String): AppResult<Unit> = mutex.withLock {
        if (isRecording) {
            if (activeSessionId == sessionId) return@withLock AppResult.Success(Unit)
            return@withLock AppResult.Failure(
                AppError("DOWNLINK_BUSY", "下行录音已在其他会话中运行"),
            )
        }
        lastError = null
        val downlinkReader = AudioRecordPcmReader(
            fallbackAudioSources = AudioRecordPcmReader.DOWNLINK_FALLBACK_SOURCES,
            microphoneMuteForDownlink = true,
            context = context,
        )
        val started = withContext(captureDispatcher) {
            runCatching { downlinkReader.start() }.getOrDefault(false)
        }
        if (!started) {
            withContext(captureDispatcher) { runCatching { downlinkReader.release() } }
            return@withLock AppResult.Failure(
                AppError("DOWNLINK_INIT", "无法启动下行录音（所有音频源均不可用）"),
            )
        }
        reader = downlinkReader
        activeSessionId = sessionId
        isRecording = true
        captureJob = scope.launch(captureDispatcher) {
            captureLoop(sessionId, downlinkReader)
        }
        AppResult.Success(Unit)
    }

    /**
     * Stop recording and flush remaining buffered data.
     * Safe to call when not recording (no-op).
     */
    suspend fun stop(): AppResult<Unit> = mutex.withLock {
        if (!isRecording) return@withLock AppResult.Success(Unit)
        stopInternal()
        AppResult.Success(Unit)
    }

    /**
     * Release all resources. After calling this, the recorder cannot be reused.
     */
    suspend fun release() = mutex.withLock {
        stopInternal()
        reader?.release()
        reader = null
    }

    private fun stopInternal() {
        isRecording = false
        val activeReader = reader
        runCatching { activeReader?.stop() }
        captureJob?.cancel()
        captureJob = null
        activeSessionId = null
        lastError = null
    }

    private suspend fun captureLoop(sessionId: String, activeReader: AudioRecordPcmReader) {
        val buffer = ByteArray(frameBytes)
        // Accumulate PCM as ShortArray for batched normalization.
        val sampleRateHz = activeReader.sampleRate
        val chunkList = mutableListOf<ShortArray>()
        var accumulatedSamples = 0

        try {
            while (currentCoroutineContext().isActive && isRecording) {
                val read = activeReader.read(buffer)
                if (read < 0) {
                    lastError = "READ_ERROR_$read"
                    break
                }
                if (read == 0) continue

                // Convert byte[] to ShortArray (little-endian 16-bit PCM)
                val sampleCount = read / 2
                val shorts = ShortArray(sampleCount)
                for (i in 0 until sampleCount) {
                    val lo = buffer[i * 2].toInt() and 0xFF
                    val hi = buffer[i * 2 + 1].toInt() and 0xFF
                    shorts[i] = ((hi shl 8) or lo).toShort()
                }
                chunkList.add(shorts)
                accumulatedSamples += sampleCount

                // Flush when we have enough samples
                if (accumulatedSamples >= flushIntervalSamples) {
                    flushAccumulated(sessionId, chunkList, accumulatedSamples, sampleRateHz)
                    chunkList.clear()
                    accumulatedSamples = 0
                }
            }
            // Flush any remaining samples
            if (accumulatedSamples > 0) {
                flushAccumulated(sessionId, chunkList, accumulatedSamples, sampleRateHz)
            }
        } catch (_: CancellationException) {
            // Expected on stop — flush remaining before exiting
            if (accumulatedSamples > 0) {
                runCatching { flushAccumulated(sessionId, chunkList, accumulatedSamples, sampleRateHz) }
            }
        } catch (t: Throwable) {
            lastError = t.message ?: "CAPTURE_FAILURE"
        }
    }

    private suspend fun flushAccumulated(
        sessionId: String,
        chunks: List<ShortArray>,
        totalSamples: Int,
        sampleRateHz: Int,
    ) {
        // Concatenate all chunks into a single ShortArray
        val merged = ShortArray(totalSamples)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(merged, offset)
            offset += chunk.size
        }

        // Normalize sample rate to 16kHz (session recording standard)
        val normalizationResult = recordingAudioNormalizer.normalize(merged, sampleRateHz)
        when (normalizationResult) {
            is AppResult.Failure -> {
                lastError = normalizationResult.error.userMessage
            }
            is AppResult.Success -> {
                if (normalizationResult.value.samples.isEmpty()) return
                val appendResult = recordingStore.appendPcm(
                    sessionId = sessionId,
                    samples = normalizationResult.value.samples,
                    sampleRateHz = normalizationResult.value.sampleRateHz,
                )
                if (appendResult is AppResult.Failure) {
                    lastError = appendResult.error.userMessage
                }
            }
        }
    }
}
