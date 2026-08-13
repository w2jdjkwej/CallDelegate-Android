package com.example.calldelegate.core.ai

import com.example.calldelegate.domain.api.VadDecision
import com.example.calldelegate.domain.api.VoiceActivityDetector
import com.example.calldelegate.domain.api.VoiceActivityDetectorConfiguration
import com.example.calldelegate.domain.api.VoiceActivityDetectorConfigurationSource
import kotlin.math.sqrt

class EnergyVoiceActivityDetector(
    private val rmsThreshold: Double = 650.0,
    private val endSilenceMs: Long = DEFAULT_END_SILENCE_MS,
    private val initialSilenceMs: Long = DEFAULT_INITIAL_SILENCE_MS,
) : VoiceActivityDetector, VoiceActivityDetectorConfigurationSource {
    private var seenSpeech = false
    private var silentSamples = 0L
    private var bufferedSamples = ShortArray(0)
    private var bufferedSampleCount = 0
    private var bufferedSampleRateHz = 0

    @Deprecated(
        message = "Use millisecond silence thresholds so behavior does not depend on input block size.",
    )
    constructor(
        rmsThreshold: Double,
        endSilenceFrames: Int,
        initialSilenceFrames: Int,
    ) : this(
        rmsThreshold = rmsThreshold,
        endSilenceMs = endSilenceFrames * SUBFRAME_DURATION_MS,
        initialSilenceMs = initialSilenceFrames * SUBFRAME_DURATION_MS,
    )

    override val voiceActivityDetectorConfiguration: VoiceActivityDetectorConfiguration
        get() = VoiceActivityDetectorConfiguration(
            implementationName = javaClass.simpleName,
            rmsThreshold = rmsThreshold,
            endSilenceFrames = durationToLegacyFrames(endSilenceMs),
            initialSilenceFrames = durationToLegacyFrames(initialSilenceMs),
            endSilenceMs = endSilenceMs,
            initialSilenceMs = initialSilenceMs,
            subframeDurationMs = SUBFRAME_DURATION_MS,
        )

    override fun reset() {
        seenSpeech = false
        silentSamples = 0L
        bufferedSamples = ShortArray(0)
        bufferedSampleCount = 0
        bufferedSampleRateHz = 0
    }

    override fun accept(samples: ShortArray, sampleRateHz: Int): VadDecision {
        if (samples.isEmpty()) return VadDecision(false, false, 0f)
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        prepareBuffer(sampleRateHz)

        var speechDetected = false
        var endOfSpeech = false
        var maximumProbability = 0f
        var sourceOffset = 0
        while (sourceOffset < samples.size && !endOfSpeech) {
            val copyCount = minOf(
                bufferedSamples.size - bufferedSampleCount,
                samples.size - sourceOffset,
            )
            samples.copyInto(
                destination = bufferedSamples,
                destinationOffset = bufferedSampleCount,
                startIndex = sourceOffset,
                endIndex = sourceOffset + copyCount,
            )
            bufferedSampleCount += copyCount
            sourceOffset += copyCount
            if (bufferedSampleCount == bufferedSamples.size) {
                val decision = evaluateSubframe(bufferedSamples, sampleRateHz)
                speechDetected = speechDetected || decision.speechDetected
                endOfSpeech = decision.endOfSpeech
                maximumProbability = maxOf(maximumProbability, decision.probability)
                bufferedSampleCount = 0
            }
        }
        return VadDecision(speechDetected, endOfSpeech, maximumProbability)
    }

    private fun prepareBuffer(sampleRateHz: Int) {
        if (sampleRateHz == bufferedSampleRateHz) return

        if (bufferedSampleRateHz > 0 && silentSamples > 0L) {
            silentSamples = silentSamples * sampleRateHz / bufferedSampleRateHz
        }
        bufferedSampleRateHz = sampleRateHz
        bufferedSamples = ShortArray(subframeSamples(sampleRateHz))
        bufferedSampleCount = 0
    }

    private fun evaluateSubframe(samples: ShortArray, sampleRateHz: Int): VadDecision {
        var squareSum = 0.0
        for (sample in samples) {
            squareSum += sample.toDouble() * sample
        }
        val meanSquare = squareSum / samples.size
        val rms = sqrt(meanSquare)
        val speech = rms >= rmsThreshold
        if (speech) {
            seenSpeech = true
            silentSamples = 0L
        } else {
            silentSamples += samples.size
        }
        val probability = (rms / (rmsThreshold * 2.0)).coerceIn(0.0, 1.0).toFloat()
        val silenceThresholdMs = if (seenSpeech) endSilenceMs else initialSilenceMs
        val silenceThresholdSamples = durationToSamples(silenceThresholdMs, sampleRateHz)
        val endOfSpeech = silentSamples >= silenceThresholdSamples
        return VadDecision(speech, endOfSpeech, probability)
    }

    private fun subframeSamples(sampleRateHz: Int): Int =
        (sampleRateHz * SUBFRAME_DURATION_MS / 1_000L).toInt().coerceAtLeast(1)

    private fun durationToSamples(durationMs: Long, sampleRateHz: Int): Long =
        (durationMs * sampleRateHz + 999L) / 1_000L

    private fun durationToLegacyFrames(durationMs: Long): Int =
        ((durationMs + SUBFRAME_DURATION_MS - 1L) / SUBFRAME_DURATION_MS).toInt()

    private companion object {
        const val SUBFRAME_DURATION_MS = 20L
        // Candidate endpoint is only a provisional boundary. StreamingTurnAudioInputSource
        // keeps the same recognizer alive during its grace/rollback window.
        const val DEFAULT_END_SILENCE_MS = 350L
        const val DEFAULT_INITIAL_SILENCE_MS = 8_000L
    }
}
