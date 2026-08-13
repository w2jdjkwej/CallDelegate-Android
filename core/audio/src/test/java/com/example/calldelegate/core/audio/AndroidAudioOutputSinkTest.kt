package com.example.calldelegate.core.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AndroidAudioOutputSinkTest {
    @Test fun streamBufferUsesAboutOneHundredMillisecondsOfPcm() {
        assertThat(streamBufferSizeBytes(minBufferSizeBytes = 2_048, sampleRateHz = 22_050))
            .isEqualTo(4_410)
        assertThat(streamWriteChunkSamples(streamBufferSizeBytes = 4_410)).isEqualTo(2_205)
    }

    @Test fun streamBufferKeepsLargerPlatformMinimum() {
        assertThat(streamBufferSizeBytes(minBufferSizeBytes = 8_192, sampleRateHz = 22_050))
            .isEqualTo(8_192)
    }

    @Test fun playbackWaitOnlyIncludesAudioNotYetPlayedWhileWriting() {
        assertThat(remainingPlaybackWaitMillis(durationMillis = 8_772L, elapsedPlaybackMillis = 2_000L))
            .isEqualTo(6_772L)
        assertThat(remainingPlaybackWaitMillis(durationMillis = 1_000L, elapsedPlaybackMillis = 1_500L))
            .isEqualTo(0L)
    }
}
