package com.example.calldelegate.core.audio.capture

import com.example.calldelegate.domain.api.CaptureDiagnostics
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure-JVM running analysis over captured 16-bit little-endian PCM. Answers the "uncertain audio
 * source" problem from the Demo: instead of assuming the stream contains the remote party, we
 * measure signal energy and silence so the caller can decide the real [com.example.calldelegate
 * .domain.api.CaptureProvenance].
 *
 * Deliberately holds no Android types and never retains raw PCM, only aggregate statistics.
 */
class AudioFrameAnalyzer(
    private val sampleRate: Int,
    private val channelCount: Int,
    /** |amplitude| below this (out of 32767) counts as silence. ~ -44 dBFS by default. */
    private val silenceAmplitudeThreshold: Int = 200,
) {
    private var totalBytes: Long = 0
    private var totalSamples: Long = 0
    private var sumSquares: Double = 0.0
    private var maxAbs: Int = 0
    private var silentSamples: Long = 0
    private var currentSilenceRun: Long = 0
    private var longestSilenceRun: Long = 0

    /** Feed [length] bytes of interleaved 16-bit LE PCM from [data]. */
    fun accept(data: ByteArray, length: Int) {
        val usable = length.coerceAtMost(data.size)
        var i = 0
        while (i + 1 < usable) {
            val lo = data[i].toInt() and 0xff
            val hi = data[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val amp = abs(sample)
            if (amp > maxAbs) maxAbs = amp
            sumSquares += sample.toDouble() * sample.toDouble()
            totalSamples++
            if (amp < silenceAmplitudeThreshold) {
                silentSamples++
                currentSilenceRun++
                if (currentSilenceRun > longestSilenceRun) longestSilenceRun = currentSilenceRun
            } else {
                currentSilenceRun = 0
            }
            i += 2
        }
        totalBytes += usable
    }

    val bytesCaptured: Long get() = totalBytes

    /** Ideal captured audio duration from sample count (not wall-clock throughput). */
    fun audioDurationMs(): Long {
        val framesPerChannel = if (channelCount > 0) totalSamples / channelCount else totalSamples
        return if (sampleRate > 0) framesPerChannel * 1_000L / sampleRate else 0L
    }

    /**
     * @param wallClockMs actual elapsed capture time, used for real throughput (stall detection).
     */
    fun toDiagnostics(
        audioSourceLabel: String,
        initialized: Boolean,
        wallClockMs: Long,
        droppedFrames: Long,
        zeroByteReads: Long = 0,
        readErrorCount: Long = 0,
        error: String?,
    ): CaptureDiagnostics {
        val meanRms = if (totalSamples > 0) sqrt(sumSquares / totalSamples) else 0.0
        val silenceRatio = if (totalSamples > 0) silentSamples.toDouble() / totalSamples else 1.0
        val bytesPerSecond = if (wallClockMs > 0) totalBytes * 1_000L / wallClockMs else 0L
        return CaptureDiagnostics(
            audioSourceLabel = audioSourceLabel,
            initialized = initialized,
            bytesPerSecond = bytesPerSecond,
            meanRms = meanRms,
            maxAbsAmplitude = maxAbs,
            silenceRatio = silenceRatio,
            longestSilenceMs = samplesToMs(longestSilenceRun),
            droppedFrames = droppedFrames,
            zeroByteReads = zeroByteReads,
            readErrorCount = readErrorCount,
            error = error,
        )
    }

    /** True when effectively no signal was captured (all silence / empty). */
    fun isEffectivelySilent(): Boolean =
        totalSamples == 0L || silentSamples.toDouble() / totalSamples >= EFFECTIVE_SILENCE_RATIO

    private fun samplesToMs(samples: Long): Long {
        val framesPerChannel = if (channelCount > 0) samples / channelCount else samples
        return if (sampleRate > 0) framesPerChannel * 1_000L / sampleRate else 0L
    }

    private companion object {
        const val EFFECTIVE_SILENCE_RATIO = 0.98
    }
}
