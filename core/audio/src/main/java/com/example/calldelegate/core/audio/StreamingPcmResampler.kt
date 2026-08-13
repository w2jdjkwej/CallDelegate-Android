package com.example.calldelegate.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Lightweight CPU resampler for one continuous PCM fragment.
 *
 * Downsampling uses two RBJ low-pass biquads in series. Their Q values are the
 * two second-order sections of a fourth-order Butterworth filter. The 6800Hz
 * cutoff leaves a transition band before the 8000Hz Nyquist limit of the
 * 16000Hz session recording. Linear interpolation then follows a rational
 * phase accumulator. This is an MVP path, not a replacement for a polyphase
 * FIR resampler when device listening and frequency tests require higher quality.
 */
internal class StreamingPcmResampler(
    private val sourceRateHz: Int,
    private val targetRateHz: Int,
) {
    private val filters = if (sourceRateHz > targetRateHz) {
        arrayOf(
            Biquad.lowPass(sourceRateHz, CUTOFF_HZ, FIRST_SECTION_Q),
            Biquad.lowPass(sourceRateHz, CUTOFF_HZ, SECOND_SECTION_Q),
        )
    } else {
        emptyArray()
    }

    private var totalInputSamples = 0L
    private var nextOutputIndex = 0L
    private var previousFilteredSample = 0.0
    private var hasPreviousSample = false
    private var ended = false

    fun process(samples: ShortArray, endOfInput: Boolean = false): ShortArray {
        if (ended) return shortArrayOf()

        val estimatedSize = ((samples.size.toLong() * targetRateHz + sourceRateHz - 1L) / sourceRateHz + 2L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val output = ShortArrayBuilder(estimatedSize)

        samples.forEach { sample ->
            var filtered = sample.toDouble()
            filters.forEach { filter -> filtered = filter.process(filtered) }
            val currentInputIndex = totalInputSamples

            if (!hasPreviousSample) {
                previousFilteredSample = filtered
                hasPreviousSample = true
                if (nextOutputIndex == 0L) {
                    output.add(filtered.toPcm16())
                    nextOutputIndex = 1L
                }
            } else {
                emitAvailableSamples(
                    currentInputIndex = currentInputIndex,
                    currentFilteredSample = filtered,
                    output = output,
                )
                previousFilteredSample = filtered
            }
            totalInputSamples += 1L
        }

        if (endOfInput) {
            val expectedOutputCount = (
                totalInputSamples * targetRateHz + sourceRateHz / 2L
            ) / sourceRateHz
            while (nextOutputIndex < expectedOutputCount && hasPreviousSample) {
                output.add(previousFilteredSample.toPcm16())
                nextOutputIndex += 1L
            }
            ended = true
        }

        return output.toArray()
    }

    private fun emitAvailableSamples(
        currentInputIndex: Long,
        currentFilteredSample: Double,
        output: ShortArrayBuilder,
    ) {
        while (true) {
            val positionNumerator = nextOutputIndex * sourceRateHz.toLong()
            val leftInputIndex = positionNumerator / targetRateHz
            if (leftInputIndex > currentInputIndex) return

            val remainder = positionNumerator % targetRateHz
            val value = when {
                leftInputIndex == currentInputIndex && remainder == 0L -> currentFilteredSample
                leftInputIndex == currentInputIndex - 1L -> {
                    val fraction = remainder.toDouble() / targetRateHz
                    previousFilteredSample + (currentFilteredSample - previousFilteredSample) * fraction
                }
                else -> return
            }
            output.add(value.toPcm16())
            nextOutputIndex += 1L
        }
    }

    private fun Double.toPcm16(): Short = roundToInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        .toShort()

    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(input: Double): Double {
            val output = b0 * input + z1
            z1 = b1 * input - a1 * output + z2
            z2 = b2 * input - a2 * output
            return output
        }

        companion object {
            fun lowPass(sampleRateHz: Int, cutoffHz: Double, q: Double): Biquad {
                require(sampleRateHz > cutoffHz * 2.0)
                val omega = 2.0 * PI * cutoffHz / sampleRateHz
                val cosine = cos(omega)
                val alpha = sin(omega) / (2.0 * q)
                val a0 = 1.0 + alpha
                return Biquad(
                    b0 = ((1.0 - cosine) / 2.0) / a0,
                    b1 = (1.0 - cosine) / a0,
                    b2 = ((1.0 - cosine) / 2.0) / a0,
                    a1 = (-2.0 * cosine) / a0,
                    a2 = (1.0 - alpha) / a0,
                )
            }
        }
    }

    private class ShortArrayBuilder(initialCapacity: Int) {
        private var values = ShortArray(initialCapacity.coerceAtLeast(1))
        private var size = 0

        fun add(value: Short) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size] = value
            size += 1
        }

        fun toArray(): ShortArray = values.copyOf(size)
    }

    private companion object {
        const val CUTOFF_HZ = 6_800.0
        const val FIRST_SECTION_Q = 0.5411961
        const val SECOND_SECTION_Q = 1.3065630
    }
}
