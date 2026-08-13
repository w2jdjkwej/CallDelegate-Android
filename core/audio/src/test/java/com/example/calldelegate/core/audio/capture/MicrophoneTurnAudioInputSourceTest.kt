package com.example.calldelegate.core.audio.capture

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.CaptureProvenance
import com.example.calldelegate.domain.api.VadDecision
import com.example.calldelegate.domain.api.VoiceActivityDetector
import com.example.calldelegate.domain.model.CaptureRequest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Frames are 20 ms (320 samples at 16 kHz) and drive exactly one VAD subframe each, so a frame
 * index doubles as a timeline in 20 ms steps.
 */
class MicrophoneTurnAudioInputSourceTest {

    /**
     * The defect this class exists to prevent: the previous microphone input broke out of its read
     * loop on the first end-of-speech decision, so a pause between clauses ended the turn and
     * everything said after it was lost.
     */
    @Test
    fun capture_keepsRecordingWhenSpeechResumesAfterAPause() {
        val reader = FakePcmReader(frameCount = 60)
        var observation: TurnCaptureObservation? = null
        val source = MicrophoneTurnAudioInputSource(
            hasRecordAudioPermission = { true },
            readerFactory = { reader },
            // 100 ms pause (frames 10..14) mid-sentence, then speech again, then a real ending.
            vad = ScriptedVad { frame -> frame in 10..14 || frame >= 30 },
            captureDispatcher = Dispatchers.Unconfined,
            frameProcessingDispatcher = Dispatchers.Unconfined,
            endpointGraceMs = 500L,
            onTurnCaptured = { observation = it },
        )

        val captured = runBlocking { source.capture(CaptureRequest("pause", maxDurationMillis = 0L)) }

        assertThat(captured).isInstanceOf(AppResult.Success::class.java)
        val audio = (captured as AppResult.Success).value
        // The short pause raised a candidate endpoint that was rolled back, not committed.
        assertThat(observation?.candidateEndpointRollbackCount).isEqualTo(1)
        // The turn ran on to the real ending at frame 30 plus one 500 ms grace window, keeping the
        // speech that follows the pause. Breaking at the first end-of-speech would have kept 11.
        assertThat(audio.pcm16.size).isEqualTo(56 * FRAME_SAMPLES)
        assertThat(audio.speechDetected).isTrue()
    }

    @Test
    fun capture_commitsWhenSilenceOutlastsTheGraceWindow() {
        val reader = FakePcmReader(frameCount = 60)
        var observation: TurnCaptureObservation? = null
        val source = MicrophoneTurnAudioInputSource(
            hasRecordAudioPermission = { true },
            readerFactory = { reader },
            vad = ScriptedVad { frame -> frame >= 10 },
            captureDispatcher = Dispatchers.Unconfined,
            frameProcessingDispatcher = Dispatchers.Unconfined,
            endpointGraceMs = 500L,
            onTurnCaptured = { observation = it },
        )

        val captured = runBlocking { source.capture(CaptureRequest("commit", maxDurationMillis = 0L)) }

        assertThat(captured).isInstanceOf(AppResult.Success::class.java)
        assertThat(observation?.endReason).isEqualTo(TurnCaptureEndReason.VAD_ENDPOINT)
        assertThat(observation?.candidateEndpointRollbackCount).isEqualTo(0)
        assertThat(observation?.candidateEndpointAtMs).containsExactly(220L)
        // Candidate at 220 ms plus the 500 ms grace window.
        assertThat(observation?.endpointCommittedAtMs).isEqualTo(720L)
    }

    @Test
    fun capture_reportsRecorderOpenFailureInsteadOfASilentTurn() {
        val reader = FakePcmReader(frameCount = 10, startSucceeds = false)
        val source = MicrophoneTurnAudioInputSource(
            hasRecordAudioPermission = { true },
            readerFactory = { reader },
            vad = ScriptedVad { false },
            captureDispatcher = Dispatchers.Unconfined,
            frameProcessingDispatcher = Dispatchers.Unconfined,
        )

        val captured = runBlocking { source.capture(CaptureRequest("busy", maxDurationMillis = 0L)) }

        assertThat(captured).isInstanceOf(AppResult.Failure::class.java)
        assertThat((captured as AppResult.Failure).error.code).isEqualTo("AUDIO_INIT")
        assertThat(reader.released).isTrue()
    }

    @Test
    fun capture_rejectsMissingPermissionWithoutOpeningTheRecorder() {
        val reader = FakePcmReader(frameCount = 10)
        val source = MicrophoneTurnAudioInputSource(
            hasRecordAudioPermission = { false },
            readerFactory = { reader },
            vad = ScriptedVad { false },
            captureDispatcher = Dispatchers.Unconfined,
            frameProcessingDispatcher = Dispatchers.Unconfined,
        )

        val captured = runBlocking { source.capture(CaptureRequest("denied", maxDurationMillis = 0L)) }

        assertThat(captured).isInstanceOf(AppResult.Failure::class.java)
        assertThat((captured as AppResult.Failure).error.code).isEqualTo("MIC_PERMISSION")
        assertThat(reader.started).isFalse()
    }

    /** Reports end-of-speech for every frame [isSilent] accepts, and speech for the rest. */
    private class ScriptedVad(private val isSilent: (Int) -> Boolean) : VoiceActivityDetector {
        private var frameIndex = -1
        override fun reset() { frameIndex = -1 }
        override fun accept(samples: ShortArray, sampleRateHz: Int): VadDecision {
            frameIndex += 1
            val silent = isSilent(frameIndex)
            return VadDecision(
                speechDetected = !silent,
                endOfSpeech = silent,
                probability = if (silent) 0f else 1f,
            )
        }
    }

    private class FakePcmReader(
        private val frameCount: Int,
        private val startSucceeds: Boolean = true,
    ) : PcmReader {
        override val sampleRate: Int = 16_000
        override val channelCount: Int = 1
        override val sourceLabel: String = "FAKE"
        override val declaredProvenance: CaptureProvenance = CaptureProvenance.LOCAL_MIC
        var started = false
            private set
        var released = false
            private set
        private var emitted = 0

        override fun start(): Boolean {
            started = startSucceeds
            return startSucceeds
        }

        override fun read(buffer: ByteArray): Int {
            if (emitted >= frameCount) return -1
            emitted += 1
            // Non-zero payload so captured PCM is distinguishable from an unwritten buffer.
            buffer.fill(0x11)
            return buffer.size
        }

        override fun stop() = Unit
        override fun release() { released = true }
    }

    private companion object {
        const val FRAME_SAMPLES = 320
    }
}
