package com.example.calldelegate.core.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/** Raises quiet PCM speech towards a useful peak while leaving already-loud audio unchanged. */
object AdaptivePcmGain {
    private const val TARGET_PEAK = 30_000
    private const val MAX_GAIN = 2f

    fun calculate(samples: ShortArray): Float {
        var peak = 0
        for (sample in samples) {
            val magnitude = if (sample == Short.MIN_VALUE) {
                Short.MAX_VALUE.toInt() + 1
            } else {
                abs(sample.toInt())
            }
            if (magnitude > peak) {
                peak = magnitude
            }
        }
        if (peak == 0 || peak >= TARGET_PEAK) {
            return 1f
        }
        return minOf(MAX_GAIN, TARGET_PEAK.toFloat() / peak)
    }

    fun apply(sample: Short, gain: Float): Short {
        if (gain <= 1f) {
            return sample
        }
        return (sample.toInt() * gain)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}
