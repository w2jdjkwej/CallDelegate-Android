package com.example.calldelegate.core.audio.telecom

import com.example.calldelegate.core.audio.StreamingPcmResampler
import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.AudioCaptureResult
import com.example.calldelegate.domain.api.CallAudioSource
import com.example.calldelegate.domain.api.CaptureDiagnostics
import com.example.calldelegate.domain.api.CaptureProvenance
import com.example.calldelegate.domain.api.PcmAudioFrame
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Bounded in-process bridge between the privileged scrcpy decoder and the turn-based AI input.
 *
 * scrcpy currently captures `voice-call`, which may contain both sides of the call. The bridge
 * therefore reports [CaptureProvenance.MIXED_UNKNOWN] and never claims that channel separation is
 * guaranteed. During AI takeover the local user is expected to remain silent, and capture
 * subscribers are paused while TTS is injected.
 */
class TelecomCallAudioBridge(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val monotonicNanos: () -> Long = System::nanoTime,
) : CallAudioSource {
    private val lock = Any()
    private val mutableFrames = MutableSharedFlow<PcmAudioFrame>(
        replay = 0,
        extraBufferCapacity = FRAME_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val audioFrames: Flow<PcmAudioFrame> = mutableFrames

    private var activeCallId: String? = null
    private var sourceSampleRateHz = 0
    private var resampler: StreamingPcmResampler? = null
    private var startedAtMillis = 0L
    private var emittedBytes = 0L
    private var emittedSamples = 0L
    private var sumSquares = 0.0
    private var maxAmplitude = 0
    private var silentSamples = 0L
    private var droppedFrames = 0L

    override suspend fun start(callId: String): AppResult<Unit> = synchronized(lock) {
        val current = activeCallId
        if (current != null && current != callId) {
            return@synchronized AppResult.Failure(
                AppError("TELECOM_AUDIO_BUSY", "已有真实通话音频流正在使用"),
            )
        }
        if (current == callId) return@synchronized AppResult.Success(Unit)

        activeCallId = callId
        sourceSampleRateHz = 0
        resampler = null
        startedAtMillis = nowMillis()
        emittedBytes = 0L
        emittedSamples = 0L
        sumSquares = 0.0
        maxAmplitude = 0
        silentSamples = 0L
        droppedFrames = 0L
        AppResult.Success(Unit)
    }

    /**
     * Accepts interleaved PCM16 from MediaCodec, downmixes all channels, and emits canonical
     * 16000 Hz mono PCM16 little-endian frames. Invalid or stale frames are rejected explicitly.
     */
    fun pushDecodedPcm(
        callId: String,
        samples: ShortArray,
        sampleRateHz: Int,
        channelCount: Int,
        timestampMs: Long,
    ): Boolean {
        val frame = synchronized(lock) {
            if (activeCallId != callId || samples.isEmpty()) return@synchronized null
            if (sampleRateHz !in SUPPORTED_SAMPLE_RATES_HZ || channelCount !in 1..MAX_CHANNEL_COUNT) {
                return@synchronized null
            }

            val mono = downmix(samples, channelCount)
            if (mono.isEmpty()) return@synchronized null
            if (sourceSampleRateHz != sampleRateHz || resampler == null) {
                sourceSampleRateHz = sampleRateHz
                resampler = if (sampleRateHz == TARGET_SAMPLE_RATE_HZ) {
                    null
                } else {
                    StreamingPcmResampler(sampleRateHz, TARGET_SAMPLE_RATE_HZ)
                }
            }
            val normalized = resampler?.process(mono, endOfInput = false) ?: mono
            if (normalized.isEmpty()) return@synchronized null

            updateDiagnostics(normalized)
            val bytes = normalized.toLittleEndianBytes()
            emittedBytes += bytes.size
            PcmAudioFrame(
                callId = callId,
                data = bytes,
                sampleRate = TARGET_SAMPLE_RATE_HZ,
                channelCount = 1,
                timestampMs = timestampMs,
                emittedAtElapsedRealtimeNanos = monotonicNanos(),
            )
        } ?: return false

        val accepted = mutableFrames.tryEmit(frame)
        if (!accepted) synchronized(lock) { droppedFrames += 1L }
        return accepted
    }

    override suspend fun stop(callId: String): AppResult<AudioCaptureResult> = synchronized(lock) {
        if (activeCallId != callId) {
            return@synchronized AppResult.Failure(
                AppError("TELECOM_AUDIO_MISMATCH", "停止的真实通话音频流与当前通话不一致"),
            )
        }
        activeCallId = null
        resampler?.process(shortArrayOf(), endOfInput = true)
        resampler = null

        val durationMs = (nowMillis() - startedAtMillis).coerceAtLeast(0L)
        val meanRms = if (emittedSamples > 0L) sqrt(sumSquares / emittedSamples) else 0.0
        AppResult.Success(
            AudioCaptureResult(
                callId = callId,
                wavPath = null,
                durationMs = durationMs,
                totalBytes = emittedBytes,
                provenance = CaptureProvenance.MIXED_UNKNOWN,
                diagnostics = CaptureDiagnostics(
                    audioSourceLabel = "SHIZUKU_SCRCPY_VOICE_CALL_OPUS",
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
                    droppedFrames = droppedFrames,
                ),
            ),
        )
    }

    private fun downmix(interleaved: ShortArray, channelCount: Int): ShortArray {
        if (channelCount == 1) return interleaved.copyOf()
        val frameCount = interleaved.size / channelCount
        val mono = ShortArray(frameCount)
        var frameIndex = 0
        while (frameIndex < frameCount) {
            var sum = 0
            var channel = 0
            while (channel < channelCount) {
                sum += interleaved[frameIndex * channelCount + channel].toInt()
                channel += 1
            }
            mono[frameIndex] = (sum / channelCount).toShort()
            frameIndex += 1
        }
        return mono
    }

    private fun updateDiagnostics(samples: ShortArray) {
        samples.forEach { sample ->
            val value = sample.toInt()
            val amplitude = abs(value).coerceAtMost(Short.MAX_VALUE.toInt() + 1)
            sumSquares += value.toDouble() * value
            maxAmplitude = maxOf(maxAmplitude, amplitude)
            if (amplitude <= SILENCE_AMPLITUDE) silentSamples += 1L
        }
        emittedSamples += samples.size
    }

    private fun ShortArray.toLittleEndianBytes(): ByteArray {
        val output = ByteArray(size * 2)
        var index = 0
        while (index < size) {
            val value = this[index].toInt()
            output[index * 2] = (value and 0xff).toByte()
            output[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
            index += 1
        }
        return output
    }

    private companion object {
        const val TARGET_SAMPLE_RATE_HZ = 16_000
        const val FRAME_BUFFER_CAPACITY = 50
        const val MAX_CHANNEL_COUNT = 8
        const val SILENCE_AMPLITUDE = 32
        val SUPPORTED_SAMPLE_RATES_HZ = setOf(8_000, 16_000, 22_050, 24_000, 44_100, 48_000)
    }
}
