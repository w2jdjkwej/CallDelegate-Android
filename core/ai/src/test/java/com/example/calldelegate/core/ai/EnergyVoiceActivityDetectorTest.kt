package com.example.calldelegate.core.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EnergyVoiceActivityDetectorTest {
    @Test fun stopsAfterEightSecondsWhenSpeechNeverStarts() {
        val detector = EnergyVoiceActivityDetector()
        val silentFrame = ShortArray(320)

        repeat(399) {
            assertThat(detector.accept(silentFrame, 16_000).endOfSpeech).isFalse()
        }

        assertThat(detector.accept(silentFrame, 16_000).endOfSpeech).isTrue()
    }

    @Test fun waitsForThreeHundredFiftyMillisecondsOfSilenceAfterSpeech() {
        val detector = EnergyVoiceActivityDetector()
        val speechFrame = ShortArray(320) { 2_000 }
        val silentFrame = ShortArray(320)

        detector.accept(speechFrame, 16_000)
        repeat(17) {
            assertThat(detector.accept(silentFrame, 16_000).endOfSpeech).isFalse()
        }

        assertThat(detector.accept(silentFrame, 16_000).endOfSpeech).isTrue()
    }

    @Test fun evaluatesLongInputBlocksAsTwentyMillisecondSubframes() {
        val detector = EnergyVoiceActivityDetector()
        val mixedBlock = ShortArray(1_600) { index -> if (index < 320) 1_000 else 0 }

        val firstDecision = detector.accept(mixedBlock, 16_000)

        assertThat(firstDecision.speechDetected).isTrue()
        assertThat(firstDecision.endOfSpeech).isFalse()
        repeat(2) {
            assertThat(detector.accept(ShortArray(1_600), 16_000).endOfSpeech).isFalse()
        }
        assertThat(detector.accept(ShortArray(1_600), 16_000).endOfSpeech).isTrue()
    }

    @Test fun accumulatesTenMillisecondBlocksBeforeMakingVadDecision() {
        val detector = EnergyVoiceActivityDetector()
        val halfSpeechFrame = ShortArray(160) { 2_000 }

        assertThat(detector.accept(halfSpeechFrame, 16_000).speechDetected).isFalse()
        assertThat(detector.accept(halfSpeechFrame, 16_000).speechDetected).isTrue()
    }

    @Test fun reportsTimeBasedConfiguration() {
        val configuration = EnergyVoiceActivityDetector().voiceActivityDetectorConfiguration

        assertThat(configuration.endSilenceMs).isEqualTo(350L)
        assertThat(configuration.initialSilenceMs).isEqualTo(8_000L)
        assertThat(configuration.subframeDurationMs).isEqualTo(20L)
        assertThat(configuration.endSilenceFrames).isEqualTo(18)
        assertThat(configuration.initialSilenceFrames).isEqualTo(400)
    }

    @Suppress("DEPRECATION")
    @Test fun legacyFrameConstructorKeepsTheOriginalThresholds() {
        val detector = EnergyVoiceActivityDetector(
            rmsThreshold = 650.0,
            endSilenceFrames = 30,
            initialSilenceFrames = 400,
        )

        assertThat(detector.voiceActivityDetectorConfiguration.endSilenceMs).isEqualTo(600L)
        assertThat(detector.voiceActivityDetectorConfiguration.initialSilenceMs).isEqualTo(8_000L)
    }
}
