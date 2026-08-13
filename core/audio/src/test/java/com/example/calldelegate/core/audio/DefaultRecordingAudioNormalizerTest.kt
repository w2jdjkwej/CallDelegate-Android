package com.example.calldelegate.core.audio

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.SESSION_RECORDING_SAMPLE_RATE_HZ
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultRecordingAudioNormalizerTest {
    @Test
    fun normalizesToTheSessionRecordingSampleRate() {
        val input = ShortArray(22_050)
        val normalized = DefaultRecordingAudioNormalizer()
            .normalize(input, 22_050)
            .successValue()

        assertThat(normalized.sampleRateHz).isEqualTo(SESSION_RECORDING_SAMPLE_RATE_HZ)
        assertThat(normalized.samples).hasLength(16_000)
    }

    @Test
    fun preservesOneSecondDurationForSupportedDownsamplingRates() {
        val normalizer = DefaultRecordingAudioNormalizer()

        assertThat(normalizer.normalize(ShortArray(24_000), 24_000).successValue().samples).hasLength(16_000)
        assertThat(normalizer.normalize(ShortArray(44_100), 44_100).successValue().samples).hasLength(16_000)
        assertThat(normalizer.normalize(ShortArray(48_000), 48_000).successValue().samples).hasLength(16_000)
    }

    @Test
    fun reusesTheOriginalArrayAtTheSessionRate() {
        val input = shortArrayOf(1, 2, 3)

        val normalized = DefaultRecordingAudioNormalizer()
            .normalize(input, SESSION_RECORDING_SAMPLE_RATE_HZ)
            .successValue()

        assertThat(normalized.samples).isSameInstanceAs(input)
    }

    @Test
    fun handlesEmptyShortAndEightKilohertzInputs() {
        val normalizer = DefaultRecordingAudioNormalizer()

        assertThat(normalizer.normalize(shortArrayOf(), 22_050).successValue().samples).isEmpty()
        assertThat(normalizer.normalize(shortArrayOf(1), 22_050).successValue().samples).hasLength(1)
        assertThat(normalizer.normalize(ShortArray(8_000), 8_000).successValue().samples).hasLength(16_000)
    }

    @Test
    fun rejectsUnsupportedRatesWithAStableErrorCode() {
        val result = DefaultRecordingAudioNormalizer().normalize(shortArrayOf(1), 12_345)

        assertThat(result.failureValue().code).isEqualTo("AUDIO_RESAMPLE_UNSUPPORTED_RATE")
        assertThat(DefaultRecordingAudioNormalizer().normalize(shortArrayOf(1), 0).failureValue().code)
            .isEqualTo("AUDIO_RESAMPLE_UNSUPPORTED_RATE")
        assertThat(DefaultRecordingAudioNormalizer().normalize(shortArrayOf(1), -1).failureValue().code)
            .isEqualTo("AUDIO_RESAMPLE_UNSUPPORTED_RATE")
    }
}

private fun <T> AppResult<T>.successValue(): T = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> error("Expected success but was ${error.code}")
}

private fun <T> AppResult<T>.failureValue() = when (this) {
    is AppResult.Success -> error("Expected failure")
    is AppResult.Failure -> error
}
