package com.example.calldelegate.core.audio.capture

import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.AudioCaptureResult
import com.example.calldelegate.domain.api.CallAudioSource
import com.example.calldelegate.domain.api.CaptureDiagnostics
import com.example.calldelegate.domain.api.CaptureProvenance
import com.example.calldelegate.domain.api.PcmAudioFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.sqrt

/** Input cadence used by the debug-only WAV call test source. */
enum class WavInjectionMode {
    REAL_TIME,
    AS_FAST_AS_POSSIBLE,
}

/**
 * Source-side facts observed while injecting one WAV file.
 *
 * [originalAudioSamples] is derived from the WAV `data` chunk. It never includes the optional
 * tail silence or frame padding, so callers can use it as an RTF denominator.
 */
data class WavInjectionMetrics(
    val originalAudioSamples: Long,
    val originalAudioDurationMillis: Long,
    val tailSilenceMs: Long,
    val injectedTailSamples: Long,
    val framePaddingSamples: Long,
    val framePaddingDurationMillis: Long,
    val emittedFrames: Long,
    val emittedSamples: Long,
    val completed: Boolean,
    val cancelled: Boolean,
    val consumerStoppedEarly: Boolean,
)

/**
 * Debug-only [CallAudioSource] for a single, strict PCM WAV caller turn.
 *
 * It validates and streams the WAV data before VAD. It deliberately does not know about VAD,
 * ASR, dialogue, TTS, or playback. The regular [StreamingTurnAudioInputSource] remains the only
 * component that converts the continuous frames into a turn-level [CapturedAudio].
 */
class WavCallAudioSource(
    private val wavFile: File,
    private val injectionMode: WavInjectionMode,
    private val tailSilenceMs: Long = DEFAULT_TAIL_SILENCE_MS,
    private val frameSamples: Int = VAD_FRAME_SAMPLES,
    private val delayForMillis: suspend (Long) -> Unit = { delay(it) },
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val onFrameInjected: ((PcmAudioFrame, Long) -> Unit)? = null,
) : CallAudioSource {

    private val lifecycleMutex = Mutex()
    private var activeSession: Session? = null
    private var mostRecentStoppedSession: Session? = null

    @Volatile
    private var latestMetricsValue: WavInjectionMetrics? = null

    /** Returns the latest source-side injection metrics without exposing raw PCM. */
    fun latestInjectionMetrics(): WavInjectionMetrics? = latestMetricsValue

    override val audioFrames: Flow<PcmAudioFrame> = flow {
        val session = acquireCollectorSession()
        if (session == null) return@flow

        try {
            streamSession(session) { frame -> emit(frame) }
            session.completed = !session.stopRequested
        } catch (cancellation: CancellationException) {
            if (session.stopRequested) {
                session.cancelled = true
            } else {
                // A VAD endpoint cancels the upstream Flow to finish its turn. This is normal and
                // differs from an explicit source stop.
                session.consumerStoppedEarly = true
            }
            throw cancellation
        } finally {
            publishMetrics(session)
        }
    }

    override suspend fun start(callId: String): AppResult<Unit> {
        if (callId.isBlank()) {
            return AppResult.Failure(AppError("WAV_CALL_ID", "WAV 测试通话标识不能为空"))
        }
        if (tailSilenceMs < 0L) {
            return AppResult.Failure(AppError("WAV_TAIL_CONFIG", "尾部静音时长不能小于零"))
        }
        if (frameSamples != VAD_FRAME_SAMPLES) {
            return AppResult.Failure(
                AppError(
                    "WAV_FRAME_CONFIG",
                    "WAV 注入帧大小必须与现有 VAD 输入帧一致",
                    "expected=$VAD_FRAME_SAMPLES actual=$frameSamples",
                ),
            )
        }

        lifecycleMutex.withLock {
            val current = activeSession
            if (current != null) {
                return if (current.callId == callId) {
                    AppResult.Success(Unit)
                } else {
                    AppResult.Failure(AppError("WAV_SOURCE_BUSY", "已有 WAV 测试音频正在注入"))
                }
            }
        }

        val metadata = try {
            WavPcm16Parser.parse(wavFile)
        } catch (error: WavFormatException) {
            return AppResult.Failure(AppError("WAV_FORMAT", "WAV 格式无效", error.message, recoverable = false))
        } catch (error: Throwable) {
            return AppResult.Failure(AppError("WAV_OPEN", "无法打开 WAV 测试音频", error.message))
        }

        val tailSamples = try {
            checkedMultiply(tailSilenceMs, REQUIRED_SAMPLE_RATE) / MILLIS_PER_SECOND
        } catch (error: WavFormatException) {
            return AppResult.Failure(AppError("WAV_TAIL_CONFIG", "尾部静音时长超出可支持范围", error.message))
        }
        val session = Session(callId, metadata, tailSilenceMs, tailSamples)
        lifecycleMutex.withLock {
            val current = activeSession
            if (current != null) {
                return if (current.callId == callId) {
                    AppResult.Success(Unit)
                } else {
                    AppResult.Failure(AppError("WAV_SOURCE_BUSY", "已有 WAV 测试音频正在注入"))
                }
            }
            activeSession = session
            mostRecentStoppedSession = null
            latestMetricsValue = session.toMetrics()
        }
        return AppResult.Success(Unit)
    }

    override suspend fun stop(callId: String): AppResult<AudioCaptureResult> {
        val session = lifecycleMutex.withLock {
            val current = activeSession
                ?: return@withLock stoppedSessionFor(callId)
            if (current.callId != callId) {
                return@withLock SessionLookup.Mismatched
            }
            current.stopRequested = true
            current.stopSignal.complete(Unit)
            activeSession = null
            mostRecentStoppedSession = current
            SessionLookup.Found(current)
        }

        when (session) {
            null -> return AppResult.Failure(AppError("WAV_SOURCE_NOT_STARTED", "WAV 音频源尚未启动"))
            SessionLookup.Mismatched -> {
                return AppResult.Failure(AppError("WAV_CALL_ID_MISMATCH", "停止请求与当前 WAV 通话不匹配"))
            }
            is SessionLookup.Found -> {
                val active = session.value
                active.cancelled = !active.completed
                publishMetrics(active)
                return AppResult.Success(active.toAudioCaptureResult())
            }
        }
    }

    private suspend fun acquireCollectorSession(): Session? {
        val acquired = lifecycleMutex.withLock {
            val current = activeSession
                ?: throw IllegalStateException("WAV_SOURCE_NOT_STARTED")
            if (!current.firstCollectorClaimed) {
                current.firstCollectorClaimed = true
                return@withLock CollectorSession.First(current)
            }
            CollectorSession.Later(current)
        }
        if (acquired is CollectorSession.Later) {
            // The controller may ask for a second turn after the one WAV caller utterance. Keep
            // that turn waiting until the runner ends the session instead of replaying the WAV or
            // returning an artificial empty capture.
            acquired.value.stopSignal.await()
            return null
        }
        return (acquired as CollectorSession.First).value
    }

    private fun stoppedSessionFor(callId: String): SessionLookup? {
        val prior = mostRecentStoppedSession
        return if (prior?.callId == callId) SessionLookup.Found(prior) else null
    }

    private suspend fun streamSession(
        session: Session,
        emitFrame: suspend (PcmAudioFrame) -> Unit,
    ) {
        val metadata = session.metadata
        val frameBytes = frameSamples * BYTES_PER_SAMPLE
        val pacingStartedAtNanos = monotonicNanos()
        var emittedSamplesBeforeFrame = 0L

        FileInputStream(wavFile).use { input ->
            skipFully(input, metadata.dataOffset)
            var remainingDataBytes = metadata.dataSizeBytes
            while (remainingDataBytes > 0L && !session.stopRequested) {
                val dataBytesInFrame = minOf(frameBytes.toLong(), remainingDataBytes).toInt()
                val frameData = ByteArray(frameBytes)
                readFully(input, frameData, dataBytesInFrame)
                remainingDataBytes -= dataBytesInFrame
                val dataSamplesInFrame = dataBytesInFrame / BYTES_PER_SAMPLE
                if (dataSamplesInFrame < frameSamples) {
                    session.framePaddingSamples += frameSamples - dataSamplesInFrame
                }
                emitPacedFrame(session, frameData, emittedSamplesBeforeFrame, pacingStartedAtNanos, emitFrame)
                emittedSamplesBeforeFrame += frameSamples
            }
        }

        var remainingTailSamples = session.tailSamples
        while (remainingTailSamples > 0L && !session.stopRequested) {
            val tailSamplesInFrame = minOf(frameSamples.toLong(), remainingTailSamples).toInt()
            val frameData = ByteArray(frameBytes)
            if (tailSamplesInFrame < frameSamples) {
                session.framePaddingSamples += frameSamples - tailSamplesInFrame
            }
            session.injectedTailSamples += tailSamplesInFrame
            emitPacedFrame(session, frameData, emittedSamplesBeforeFrame, pacingStartedAtNanos, emitFrame)
            emittedSamplesBeforeFrame += frameSamples
            remainingTailSamples -= tailSamplesInFrame
        }
    }

    private suspend fun emitPacedFrame(
        session: Session,
        frameData: ByteArray,
        emittedSamplesBeforeFrame: Long,
        pacingStartedAtNanos: Long,
        emitFrame: suspend (PcmAudioFrame) -> Unit,
    ) {
        if (injectionMode == WavInjectionMode.REAL_TIME) {
            val targetNanos = checkedAdd(
                pacingStartedAtNanos,
                emittedSamplesBeforeFrame * NANOS_PER_SECOND / REQUIRED_SAMPLE_RATE,
            )
            val remainingNanos = targetNanos - monotonicNanos()
            if (remainingNanos > 0L) {
                delayForMillis((remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND)
            }
        }
        if (session.stopRequested) return

        updateSignalDiagnostics(session, frameData)
        val emittedAtNanos = monotonicNanos()
        val frame = PcmAudioFrame(
            callId = session.callId,
            data = frameData,
            sampleRate = REQUIRED_SAMPLE_RATE.toInt(),
            channelCount = REQUIRED_CHANNEL_COUNT,
            timestampMs = emittedSamplesBeforeFrame * MILLIS_PER_SECOND / REQUIRED_SAMPLE_RATE,
            emittedAtElapsedRealtimeNanos = emittedAtNanos,
        )
        onFrameInjected?.invoke(frame, emittedAtNanos)
        emitFrame(frame)
        session.emittedFrames += 1L
        session.emittedSamples += frameSamples
        publishMetrics(session)
    }

    private fun updateSignalDiagnostics(session: Session, frameData: ByteArray) {
        var offset = 0
        while (offset < frameData.size) {
            val low = frameData[offset].toInt() and 0xff
            val high = frameData[offset + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            val magnitude = abs(sample)
            session.sumSquares += sample.toDouble() * sample
            session.signalSamples += 1L
            if (magnitude == 0) session.silentSamples += 1L
            if (magnitude > session.maxAbsAmplitude) session.maxAbsAmplitude = magnitude
            offset += BYTES_PER_SAMPLE
        }
    }

    private fun publishMetrics(session: Session) {
        latestMetricsValue = session.toMetrics()
    }

    private sealed interface SessionLookup {
        data object Mismatched : SessionLookup
        data class Found(val value: Session) : SessionLookup
    }

    private sealed interface CollectorSession {
        data class First(val value: Session) : CollectorSession
        data class Later(val value: Session) : CollectorSession
    }

    private class Session(
        val callId: String,
        val metadata: WavPcm16Metadata,
        private val configuredTailSilenceMs: Long,
        val tailSamples: Long,
    ) {
        val stopSignal = CompletableDeferred<Unit>()
        var firstCollectorClaimed = false
        var stopRequested = false
        var completed = false
        var cancelled = false
        var consumerStoppedEarly = false
        var injectedTailSamples = 0L
        var framePaddingSamples = 0L
        var emittedFrames = 0L
        var emittedSamples = 0L
        var sumSquares = 0.0
        var signalSamples = 0L
        var silentSamples = 0L
        var maxAbsAmplitude = 0

        fun toMetrics(): WavInjectionMetrics = WavInjectionMetrics(
            originalAudioSamples = metadata.originalSamples,
            originalAudioDurationMillis = metadata.originalDurationMillis,
            tailSilenceMs = configuredTailSilenceMs,
            injectedTailSamples = injectedTailSamples,
            framePaddingSamples = framePaddingSamples,
            framePaddingDurationMillis = framePaddingSamples * MILLIS_PER_SECOND / REQUIRED_SAMPLE_RATE,
            emittedFrames = emittedFrames,
            emittedSamples = emittedSamples,
            completed = completed,
            cancelled = cancelled,
            consumerStoppedEarly = consumerStoppedEarly,
        )

        fun toAudioCaptureResult(): AudioCaptureResult {
            val durationMillis = emittedSamples * MILLIS_PER_SECOND / REQUIRED_SAMPLE_RATE
            val rms = if (signalSamples == 0L) 0.0 else sqrt(sumSquares / signalSamples)
            val silenceRatio = if (signalSamples == 0L) 1.0 else silentSamples.toDouble() / signalSamples
            return AudioCaptureResult(
                callId = callId,
                wavPath = null,
                durationMs = durationMillis,
                totalBytes = emittedSamples * BYTES_PER_SAMPLE,
                provenance = CaptureProvenance.UNKNOWN,
                diagnostics = CaptureDiagnostics(
                    audioSourceLabel = "WAV_TEST",
                    initialized = true,
                    bytesPerSecond = REQUIRED_BYTE_RATE.toLong(),
                    meanRms = rms,
                    maxAbsAmplitude = maxAbsAmplitude,
                    silenceRatio = silenceRatio,
                    longestSilenceMs = 0L,
                ),
            )
        }
    }

    private companion object {
        const val REQUIRED_SAMPLE_RATE = 16_000L
        const val REQUIRED_CHANNEL_COUNT = 1
        const val BYTES_PER_SAMPLE = 2
        const val REQUIRED_BYTE_RATE = 32_000
        const val VAD_FRAME_SAMPLES = 320
        const val DEFAULT_TAIL_SILENCE_MS = 800L
        const val MILLIS_PER_SECOND = 1_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

private data class WavPcm16Metadata(
    val dataOffset: Long,
    val dataSizeBytes: Long,
    val originalSamples: Long,
    val originalDurationMillis: Long,
)

private object WavPcm16Parser {
    private const val RIFF_HEADER_BYTES = 12
    private const val CHUNK_HEADER_BYTES = 8
    private const val PCM_FORMAT_BYTES = 16L
    private const val PCM_AUDIO_FORMAT = 1
    private const val REQUIRED_CHANNEL_COUNT = 1
    private const val REQUIRED_SAMPLE_RATE = 16_000L
    private const val REQUIRED_BYTE_RATE = 32_000L
    private const val REQUIRED_BLOCK_ALIGN = 2
    private const val REQUIRED_BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = 2L
    private const val MILLIS_PER_SECOND = 1_000L

    fun parse(file: File): WavPcm16Metadata {
        if (!file.isFile) throw WavFormatException("file does not exist or is not a regular file")
        val fileLength = file.length()
        if (fileLength < RIFF_HEADER_BYTES.toLong()) throw WavFormatException("truncated RIFF header")

        RandomAccessFile(file, "r").use { input ->
            val riff = readExact(input, 4)
            if (!riff.matchesAscii("RIFF")) throw WavFormatException("missing RIFF signature")
            val declaredSize = readUnsignedIntLittleEndian(input)
            val wave = readExact(input, 4)
            if (!wave.matchesAscii("WAVE")) throw WavFormatException("missing WAVE signature")
            if (declaredSize != fileLength - 8L) {
                throw WavFormatException("RIFF size does not match file length")
            }

            var cursor = RIFF_HEADER_BYTES.toLong()
            var fmt: WavFormatFields? = null
            var dataOffset: Long? = null
            var dataSize: Long? = null
            while (cursor < fileLength) {
                val remaining = fileLength - cursor
                if (remaining < CHUNK_HEADER_BYTES.toLong()) throw WavFormatException("truncated chunk header")
                input.seek(cursor)
                val chunkId = readExact(input, 4)
                val chunkSize = readUnsignedIntLittleEndian(input)
                val payloadOffset = checkedAdd(cursor, CHUNK_HEADER_BYTES.toLong())
                val payloadEnd = checkedAdd(payloadOffset, chunkSize)
                if (payloadEnd > fileLength) throw WavFormatException("chunk exceeds RIFF boundary")

                when {
                    chunkId.matchesAscii("fmt ") -> {
                        if (fmt != null) throw WavFormatException("multiple fmt chunks are not supported")
                        if (chunkSize < PCM_FORMAT_BYTES) throw WavFormatException("fmt chunk is shorter than PCM header")
                        input.seek(payloadOffset)
                        fmt = WavFormatFields(
                            audioFormat = readUnsignedShortLittleEndian(input),
                            channelCount = readUnsignedShortLittleEndian(input),
                            sampleRate = readUnsignedIntLittleEndian(input),
                            byteRate = readUnsignedIntLittleEndian(input),
                            blockAlign = readUnsignedShortLittleEndian(input),
                            bitsPerSample = readUnsignedShortLittleEndian(input),
                        )
                    }
                    chunkId.matchesAscii("data") -> {
                        if (dataOffset != null) throw WavFormatException("multiple data chunks are not supported")
                        dataOffset = payloadOffset
                        dataSize = chunkSize
                    }
                }

                cursor = checkedAdd(payloadEnd, chunkSize and 1L)
                if (cursor > fileLength) throw WavFormatException("missing odd-sized chunk padding")
            }

            val format = fmt ?: throw WavFormatException("missing fmt chunk")
            val pcmDataOffset = dataOffset ?: throw WavFormatException("missing data chunk")
            val pcmDataSize = dataSize ?: throw WavFormatException("missing data chunk size")
            validatePcm16Format(format, pcmDataSize)
            return WavPcm16Metadata(
                dataOffset = pcmDataOffset,
                dataSizeBytes = pcmDataSize,
                originalSamples = pcmDataSize / BYTES_PER_SAMPLE,
                originalDurationMillis = pcmDataSize / REQUIRED_BYTE_RATE * MILLIS_PER_SECOND +
                    pcmDataSize % REQUIRED_BYTE_RATE * MILLIS_PER_SECOND / REQUIRED_BYTE_RATE,
            )
        }
    }

    private fun validatePcm16Format(format: WavFormatFields, dataSize: Long) {
        if (format.audioFormat != PCM_AUDIO_FORMAT) throw WavFormatException("audioFormat must be PCM (1)")
        if (format.channelCount != REQUIRED_CHANNEL_COUNT) throw WavFormatException("channel count must be 1")
        if (format.sampleRate != REQUIRED_SAMPLE_RATE) throw WavFormatException("sample rate must be 16000 Hz")
        if (format.bitsPerSample != REQUIRED_BITS_PER_SAMPLE) throw WavFormatException("bits per sample must be 16")
        if (format.blockAlign != REQUIRED_BLOCK_ALIGN) throw WavFormatException("block align must be 2")
        if (format.byteRate != REQUIRED_BYTE_RATE) throw WavFormatException("byte rate must be 32000")
        if (dataSize % REQUIRED_BLOCK_ALIGN.toLong() != 0L) {
            throw WavFormatException("data chunk is not aligned to PCM16 samples")
        }
    }

    private fun readExact(input: RandomAccessFile, count: Int): ByteArray = ByteArray(count).also(input::readFully)

    private fun readUnsignedShortLittleEndian(input: RandomAccessFile): Int {
        val low = input.readUnsignedByte()
        val high = input.readUnsignedByte()
        return low or (high shl 8)
    }

    private fun readUnsignedIntLittleEndian(input: RandomAccessFile): Long {
        val b0 = input.readUnsignedByte().toLong()
        val b1 = input.readUnsignedByte().toLong()
        val b2 = input.readUnsignedByte().toLong()
        val b3 = input.readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

}

private data class WavFormatFields(
    val audioFormat: Int,
    val channelCount: Int,
    val sampleRate: Long,
    val byteRate: Long,
    val blockAlign: Int,
    val bitsPerSample: Int,
)

private class WavFormatException(message: String) : IllegalArgumentException(message)

private fun ByteArray.matchesAscii(text: String): Boolean =
    size == text.length && indices.all { index -> this[index].toInt() == text[index].code }

private fun checkedAdd(left: Long, right: Long): Long {
    if (right < 0L || left > Long.MAX_VALUE - right) throw WavFormatException("integer overflow")
    return left + right
}

private fun checkedMultiply(left: Long, right: Long): Long {
    if (left < 0L || right < 0L || (left != 0L && right > Long.MAX_VALUE / left)) {
        throw WavFormatException("integer overflow")
    }
    return left * right
}

private fun skipFully(input: FileInputStream, byteCount: Long) {
    var remaining = byteCount
    while (remaining > 0L) {
        val skipped = input.skip(remaining)
        if (skipped > 0L) {
            remaining -= skipped
        } else if (input.read() >= 0) {
            remaining -= 1L
        } else {
            throw WavFormatException("truncated WAV data offset")
        }
    }
}

private fun readFully(input: FileInputStream, target: ByteArray, byteCount: Int) {
    var offset = 0
    while (offset < byteCount) {
        val read = input.read(target, offset, byteCount - offset)
        if (read < 0) throw WavFormatException("truncated WAV data")
        if (read > 0) offset += read
    }
}
