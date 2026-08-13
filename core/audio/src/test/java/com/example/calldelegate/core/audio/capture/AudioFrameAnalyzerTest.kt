package com.example.calldelegate.core.audio.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioFrameAnalyzerTest {

    private fun bytesOf(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = (s and 0xff).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun constant(sample: Int, count: Int): ByteArray =
        bytesOf(*IntArray(count) { sample })

    @Test
    fun silenceIsDetectedAsEffectivelySilent() {
        val analyzer = AudioFrameAnalyzer(sampleRate = 16_000, channelCount = 1)
        val silence = constant(0, 1_600) // 100 ms of zeros

        analyzer.accept(silence, silence.size)

        assertThat(analyzer.isEffectivelySilent()).isTrue()
        assertThat(analyzer.audioDurationMs()).isEqualTo(100)
        val d = analyzer.toDiagnostics("MIC", true, wallClockMs = 100, droppedFrames = 0, error = null)
        assertThat(d.maxAbsAmplitude).isEqualTo(0)
        assertThat(d.meanRms).isEqualTo(0.0)
        assertThat(d.silenceRatio).isEqualTo(1.0)
        assertThat(d.longestSilenceMs).isEqualTo(100)
    }

    @Test
    fun loudSignalIsNotSilentAndReportsAmplitude() {
        val analyzer = AudioFrameAnalyzer(sampleRate = 16_000, channelCount = 1)
        val loud = constant(10_000, 1_600)

        analyzer.accept(loud, loud.size)

        assertThat(analyzer.isEffectivelySilent()).isFalse()
        val d = analyzer.toDiagnostics("VOICE_RECOGNITION", true, wallClockMs = 100, droppedFrames = 3, error = null)
        assertThat(d.maxAbsAmplitude).isEqualTo(10_000)
        assertThat(d.meanRms).isWithin(1.0).of(10_000.0)
        assertThat(d.silenceRatio).isEqualTo(0.0)
        assertThat(d.longestSilenceMs).isEqualTo(0)
        assertThat(d.droppedFrames).isEqualTo(3)
        assertThat(d.audioSourceLabel).isEqualTo("VOICE_RECOGNITION")
    }

    @Test
    fun bytesPerSecondUsesWallClock() {
        val analyzer = AudioFrameAnalyzer(sampleRate = 16_000, channelCount = 1)
        val loud = constant(5_000, 8_000) // 16000 bytes = 500 ms of audio

        analyzer.accept(loud, loud.size)

        // Captured 16000 bytes; if wall clock was 1000 ms, throughput is 16000 B/s.
        val d = analyzer.toDiagnostics("MIC", true, wallClockMs = 1_000, droppedFrames = 0, error = null)
        assertThat(analyzer.bytesCaptured).isEqualTo(16_000)
        assertThat(d.bytesPerSecond).isEqualTo(16_000)
    }

    @Test
    fun longestSilenceRunTracksContiguousSilenceOnly() {
        val analyzer = AudioFrameAnalyzer(sampleRate = 16_000, channelCount = 1)
        // 800 loud, 1600 silent (contiguous), 800 loud -> longest silence = 100 ms
        analyzer.accept(constant(9_000, 800), 1_600)
        analyzer.accept(constant(0, 1_600), 3_200)
        analyzer.accept(constant(9_000, 800), 1_600)

        val d = analyzer.toDiagnostics("MIC", true, wallClockMs = 200, droppedFrames = 0, error = null)
        assertThat(d.longestSilenceMs).isEqualTo(100)
        assertThat(d.silenceRatio).isWithin(0.01).of(0.5)
        assertThat(analyzer.isEffectivelySilent()).isFalse()
    }
}
