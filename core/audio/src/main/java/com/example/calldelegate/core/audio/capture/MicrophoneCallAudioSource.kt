package com.example.calldelegate.core.audio.capture

import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.AudioCaptureResult
import com.example.calldelegate.domain.api.CallAudioSource
import com.example.calldelegate.domain.api.CaptureDiagnostics
import com.example.calldelegate.domain.api.CaptureProvenance
import com.example.calldelegate.domain.api.PcmAudioFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The local microphone as a continuous [CallAudioSource], carrying NO endpoint decision of its own.
 *
 * Turn segmentation belongs to [StreamingTurnAudioInputSource], which can hold an endpoint as a
 * reversible candidate and roll it back when the caller resumes. This source used to be fused with
 * a VAD that broke out of its read loop the moment [com.example.calldelegate.domain.api.VadDecision]
 * reported end-of-speech, so an ordinary mid-sentence pause ended the turn with no way back. Keeping
 * the microphone a dumb frame producer is what lets both transports share one segmentation policy.
 *
 * [audioFrames] is cold and owns one recorder per collection: the reader opens when collection
 * starts and is always released when it ends, including cancellation by the turn segmenter.
 */
class MicrophoneCallAudioSource(
    private val readerFactory: () -> PcmReader,
    private val captureDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : CallAudioSource {
    private val lock = Any()
    private val stopping = AtomicBoolean(false)
    @Volatile private var activeReader: PcmReader? = null

    /**
     * Set when the recorder could not be opened or read. The turn segmenter reports an empty stream
     * as an ordinary silent turn, so without this a busy microphone would look like a caller who
     * said nothing. Cleared by [clearFailure] at the start of each turn.
     */
    @Volatile var latestFailure: AppError? = null
        private set

    private var activeCallId: String? = null
    private var startedAtMillis = 0L
    private var emittedBytes = 0L
    private var emittedSamples = 0L
    private var sumSquares = 0.0
    private var maxAmplitude = 0
    private var silentSamples = 0L
    private var zeroByteReads = 0L
    private var readErrorCount = 0L
    private var sourceLabel = "unknown"
    private var declaredProvenance = CaptureProvenance.LOCAL_MIC

    override val audioFrames: Flow<PcmAudioFrame> = flow {
        val reader = readerFactory()
        val callId = synchronized(lock) { activeCallId } ?: DEFAULT_CALL_ID
        if (!reader.start()) {
            reader.release()
            latestFailure = AppError("AUDIO_INIT", "麦克风打开失败，可能被其他应用占用")
            return@flow
        }
        synchronized(lock) {
            activeReader = reader
            sourceLabel = reader.sourceLabel
            declaredProvenance = reader.declaredProvenance
            if (startedAtMillis == 0L) startedAtMillis = nowMillis()
        }
        val buffer = ByteArray(frameBytes(reader.sampleRate))
        try {
            while (true) {
                val readBytes = reader.read(buffer)
                if (readBytes < 0) {
                    // A negative read after cancel() is the recorder being torn down, not a fault.
                    if (!stopping.get()) {
                        synchronized(lock) { readErrorCount += 1L }
                        latestFailure = AppError("AUDIO_CAPTURE", "麦克风读取失败", "read=$readBytes")
                    }
                    return@flow
                }
                if (readBytes == 0) {
                    synchronized(lock) { zeroByteReads += 1L }
                    continue
                }
                // PCM16 frames are always sample-aligned; a trailing odd byte would shift every
                // later sample by one byte, so it is dropped rather than carried forward.
                val alignedBytes = readBytes - (readBytes % Short.SIZE_BYTES)
                if (alignedBytes <= 0) continue
                val data = buffer.copyOf(alignedBytes)
                val timestampMs = synchronized(lock) {
                    val startSample = emittedSamples
                    updateDiagnostics(data)
                    startSample * 1_000L / reader.sampleRate
                }
                emit(
                    PcmAudioFrame(
                        callId = callId,
                        data = data,
                        sampleRate = reader.sampleRate,
                        channelCount = reader.channelCount,
                        timestampMs = timestampMs,
                        emittedAtElapsedRealtimeNanos = monotonicNanos(),
                    ),
                )
            }
        } finally {
            synchronized(lock) { if (activeReader === reader) activeReader = null }
            reader.release()
        }
    }.flowOn(captureDispatcher)

    override suspend fun start(callId: String): AppResult<Unit> = synchronized(lock) {
        val current = activeCallId
        if (current != null && current != callId) {
            return@synchronized AppResult.Failure(
                AppError("MIC_AUDIO_BUSY", "已有麦克风采集正在使用"),
            )
        }
        if (current == callId) return@synchronized AppResult.Success(Unit)

        activeCallId = callId
        startedAtMillis = nowMillis()
        emittedBytes = 0L
        emittedSamples = 0L
        sumSquares = 0.0
        maxAmplitude = 0
        silentSamples = 0L
        zeroByteReads = 0L
        readErrorCount = 0L
        AppResult.Success(Unit)
    }

    override suspend fun stop(callId: String): AppResult<AudioCaptureResult> = synchronized(lock) {
        if (activeCallId != callId) {
            return@synchronized AppResult.Failure(
                AppError("MIC_AUDIO_MISMATCH", "停止的麦克风采集与当前会话不一致"),
            )
        }
        activeCallId = null
        val durationMs = (nowMillis() - startedAtMillis).coerceAtLeast(0L)
        val meanRms = if (emittedSamples > 0L) sqrt(sumSquares / emittedSamples) else 0.0
        AppResult.Success(
            AudioCaptureResult(
                callId = callId,
                wavPath = null,
                durationMs = durationMs,
                totalBytes = emittedBytes,
                provenance = declaredProvenance,
                diagnostics = CaptureDiagnostics(
                    audioSourceLabel = sourceLabel,
                    initialized = emittedBytes > 0L,
                    bytesPerSecond = if (durationMs > 0L) emittedBytes * 1_000L / durationMs else 0L,
                    meanRms = meanRms,
                    maxAbsAmplitude = maxAmplitude,
                    silenceRatio = if (emittedSamples > 0L) {
                        silentSamples.toDouble() / emittedSamples
                    } else {
                        1.0
                    },
                    longestSilenceMs = 0L,
                    zeroByteReads = zeroByteReads,
                    readErrorCount = readErrorCount,
                    error = latestFailure?.userMessage,
                ),
            ),
        )
    }

    fun clearFailure() {
        latestFailure = null
        stopping.set(false)
    }

    /**
     * Unblocks a reader that is parked in a blocking read. Collection still terminates through the
     * ordinary flow cancellation path, which is what releases the recorder.
     */
    fun cancel() {
        stopping.set(true)
        activeReader?.let { reader -> runCatching { reader.stop() } }
    }

    private fun updateDiagnostics(data: ByteArray) {
        var index = 0
        while (index + 1 < data.size) {
            val low = data[index].toInt() and 0xff
            val high = data[index + 1].toInt()
            val value = (high shl 8) or low
            val amplitude = abs(value)
            sumSquares += value.toDouble() * value
            maxAmplitude = maxOf(maxAmplitude, amplitude)
            if (amplitude <= SILENCE_AMPLITUDE) silentSamples += 1L
            index += 2
        }
        emittedSamples += data.size / Short.SIZE_BYTES
        emittedBytes += data.size
    }

    private fun frameBytes(sampleRateHz: Int): Int =
        (sampleRateHz * FRAME_DURATION_MS / 1_000L).toInt().coerceAtLeast(1) * Short.SIZE_BYTES

    private companion object {
        const val DEFAULT_CALL_ID = "microphone"

        /** Matches the segmenter's VAD subframe, so no frame ever straddles a decision boundary. */
        const val FRAME_DURATION_MS = 20L
        const val SILENCE_AMPLITUDE = 32
    }
}
