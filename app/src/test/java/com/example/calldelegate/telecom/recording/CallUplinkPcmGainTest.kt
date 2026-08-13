package com.example.calldelegate.telecom.recording

import com.example.calldelegate.core.audio.AdaptivePcmGain
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CallUplinkPcmGainTest {

    @Test
    fun quietSpeechUsesMaximumGain() {
        val gain = AdaptivePcmGain.calculate(shortArrayOf(-10_000, 5_000))

        assertThat(gain).isEqualTo(2f)
        assertThat(AdaptivePcmGain.apply((-10_000).toShort(), gain)).isEqualTo((-20_000).toShort())
    }

    @Test
    fun mediumSpeechTargetsSafePeak() {
        val gain = AdaptivePcmGain.calculate(shortArrayOf(-20_000, 10_000))

        assertThat(gain).isWithin(0.001f).of(1.5f)
        assertThat(AdaptivePcmGain.apply((-20_000).toShort(), gain)).isEqualTo((-30_000).toShort())
    }

    @Test
    fun loudSpeechIsNotChanged() {
        val samples = shortArrayOf(Short.MIN_VALUE, 30_000)

        val gain = AdaptivePcmGain.calculate(samples)

        assertThat(gain).isEqualTo(1f)
        assertThat(AdaptivePcmGain.apply(Short.MIN_VALUE, gain)).isEqualTo(Short.MIN_VALUE)
    }

    @Test
    fun silenceIsNotAmplified() {
        assertThat(AdaptivePcmGain.calculate(shortArrayOf(0, 0))).isEqualTo(1f)
    }
}
