package com.example.calldelegate.core.audio.telecom

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.CaptureProvenance
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TelecomCallAudioBridgeTest {
    @Test
    fun stereo48kIsDownmixedAndResampledToCanonicalPcm() = runBlocking {
        val bridge = TelecomCallAudioBridge(nowMillis = { 1_000L })
        assertThat(bridge.start("call-1")).isInstanceOf(AppResult.Success::class.java)
        val nextFrame = async(start = CoroutineStart.UNDISPATCHED) {
            bridge.audioFrames.first()
        }

        val stereo = ShortArray(480 * 2) { index ->
            if (index % 2 == 0) 12_000 else 6_000
        }
        assertThat(
            bridge.pushDecodedPcm(
                callId = "call-1",
                samples = stereo,
                sampleRateHz = 48_000,
                channelCount = 2,
                timestampMs = 25L,
            ),
        ).isTrue()

        val frame = nextFrame.await()
        assertThat(frame.callId).isEqualTo("call-1")
        assertThat(frame.sampleRate).isEqualTo(16_000)
        assertThat(frame.channelCount).isEqualTo(1)
        assertThat(frame.timestampMs).isEqualTo(25L)
        assertThat(frame.data.size).isEqualTo(160 * 2)
    }

    @Test
    fun staleCallFramesAreRejectedAndStopReportsMixedProvenance() = runBlocking {
        var now = 1_000L
        val bridge = TelecomCallAudioBridge(nowMillis = { now })
        bridge.start("call-1")

        assertThat(
            bridge.pushDecodedPcm("other", shortArrayOf(1, 2), 16_000, 1, 0L),
        ).isFalse()
        bridge.pushDecodedPcm("call-1", shortArrayOf(100, -100), 16_000, 1, 0L)
        now = 1_100L

        val result = bridge.stop("call-1")
        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        val capture = (result as AppResult.Success).value
        assertThat(capture.totalBytes).isEqualTo(4L)
        assertThat(capture.durationMs).isEqualTo(100L)
        assertThat(capture.provenance).isEqualTo(CaptureProvenance.MIXED_UNKNOWN)
        assertThat(capture.diagnostics.audioSourceLabel)
            .isEqualTo("SHIZUKU_SCRCPY_VOICE_CALL_OPUS")
    }
}
