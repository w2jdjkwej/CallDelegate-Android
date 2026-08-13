package com.example.calldelegate.core.ai.coordination

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.AudioCaptureResult
import com.example.calldelegate.domain.api.AudioInputSource
import com.example.calldelegate.domain.api.AudioState
import com.example.calldelegate.domain.api.CallAudioSource
import com.example.calldelegate.domain.api.CallControlGateway
import com.example.calldelegate.domain.api.CallResponseAudioSink
import com.example.calldelegate.domain.api.CallResponseResult
import com.example.calldelegate.domain.api.CaptureDiagnostics
import com.example.calldelegate.domain.api.CaptureProvenance
import com.example.calldelegate.domain.api.CallTransport
import com.example.calldelegate.domain.api.ExternalCallAdapter
import com.example.calldelegate.domain.api.ExternalCallSnapshot
import com.example.calldelegate.domain.api.ExternalCallState
import com.example.calldelegate.domain.api.PcmAudioFrame
import com.example.calldelegate.domain.model.CaptureRequest
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.SynthesizedSpeech
import com.example.calldelegate.domain.coordination.CallTransportRouter
import com.example.calldelegate.domain.coordination.CoordinatedPhase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalCallCoordinatorTest {

    private class FakeCallAudioSource : CallAudioSource {
        var starts = 0
        var stops = 0
        var lastStartId: String? = null
        var lastStopId: String? = null
        override val audioFrames: Flow<PcmAudioFrame> = emptyFlow()
        override suspend fun start(callId: String): AppResult<Unit> {
            starts++
            lastStartId = callId
            return AppResult.Success(Unit)
        }

        override suspend fun stop(callId: String): AppResult<AudioCaptureResult> {
            stops++
            lastStopId = callId
            return AppResult.Success(
                AudioCaptureResult(
                    callId = callId,
                    wavPath = null,
                    durationMs = 0,
                    totalBytes = 0,
                    provenance = CaptureProvenance.UNKNOWN,
                    diagnostics = CaptureDiagnostics(
                        audioSourceLabel = "fake",
                        initialized = true,
                        bytesPerSecond = 0,
                        meanRms = 0.0,
                        maxAbsAmplitude = 0,
                        silenceRatio = 1.0,
                        longestSilenceMs = 0,
                    ),
                ),
            )
        }
    }

    private class NoopAudioInput : AudioInputSource {
        override val mode = com.example.calldelegate.domain.model.InputMode.MICROPHONE
        override val state: StateFlow<AudioState> = MutableStateFlow(AudioState.Idle)
        override suspend fun capture(request: CaptureRequest): AppResult<CapturedAudio> =
            AppResult.Success(CapturedAudio(ShortArray(0), 16_000, 0, null))
        override suspend fun cancel() = Unit
        override suspend fun release() = Unit
    }

    private class FakeAdapter(
        override val transport: CallTransport,
        override val callAudioSource: CallAudioSource?,
        override val audioInput: AudioInputSource? = null,
    ) : ExternalCallAdapter {
        val mutableState = MutableStateFlow<ExternalCallSnapshot?>(null)
        override val callState: StateFlow<ExternalCallSnapshot?> = mutableState
        var answered = 0
        var rejected = 0
        var hungUp = 0
        override val controls: CallControlGateway = object : CallControlGateway {
            override suspend fun answer(): Boolean { answered++; return true }
            override suspend fun reject(): Boolean { rejected++; return true }
            override suspend fun hangUp(): Boolean { hungUp++; return true }
        }
        override val responseSink: CallResponseAudioSink = object : CallResponseAudioSink {
            override suspend fun playToCall(callId: String, speech: SynthesizedSpeech) =
                CallResponseResult.LocalPlaybackOnly
        }

        fun emit(state: ExternalCallState, callId: String = "call-1") {
            mutableState.value = ExternalCallSnapshot(callId = callId, state = state)
        }
    }

    /** Unconfined dispatcher so the hot-flow collector runs eagerly and emissions propagate sync. */
    private fun TestScope.newScope() = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    @Test
    fun activeStartsCaptureAndEndedStopsIt() = runTest {
        val scope = newScope()
        val capture = FakeCallAudioSource()
        val adapter = FakeAdapter(CallTransport.SIMULATED, capture, audioInput = NoopAudioInput())
        val coordinator = ExternalCallCoordinator(CallTransportRouter(setOf(adapter)), scope)

        coordinator.start(CallTransport.SIMULATED)

        adapter.emit(ExternalCallState.RINGING)
        assertThat(coordinator.state.value?.phase).isEqualTo(CoordinatedPhase.INCOMING)
        assertThat(capture.starts).isEqualTo(0)

        adapter.emit(ExternalCallState.ACTIVE)
        assertThat(coordinator.state.value?.phase).isEqualTo(CoordinatedPhase.ACTIVE)
        assertThat(coordinator.state.value?.audioAvailableForAi).isTrue()
        assertThat(capture.starts).isEqualTo(1)
        assertThat(capture.lastStartId).isEqualTo("call-1")

        adapter.emit(ExternalCallState.ENDED)
        assertThat(coordinator.state.value?.phase).isEqualTo(CoordinatedPhase.ENDED)
        assertThat(capture.stops).isEqualTo(1)
        assertThat(capture.lastStopId).isEqualTo("call-1")

        scope.cancel()
    }

    @Test
    fun repeatedActiveDoesNotRestartCapture() = runTest {
        val scope = newScope()
        val capture = FakeCallAudioSource()
        val adapter = FakeAdapter(CallTransport.SIMULATED, capture)
        val coordinator = ExternalCallCoordinator(CallTransportRouter(setOf(adapter)), scope)

        coordinator.start(CallTransport.SIMULATED)

        adapter.emit(ExternalCallState.ACTIVE)
        // A HOLDING blip then back to ACTIVE with the SAME id must not re-start capture.
        adapter.emit(ExternalCallState.HOLDING)
        adapter.emit(ExternalCallState.ACTIVE)

        assertThat(capture.starts).isEqualTo(1)
        scope.cancel()
    }

    @Test
    fun audioLessTransportSkipsCaptureButReportsState() = runTest {
        val scope = newScope()
        val adapter = FakeAdapter(CallTransport.TELECOM, callAudioSource = null)
        val coordinator = ExternalCallCoordinator(CallTransportRouter(setOf(adapter)), scope)

        coordinator.start(CallTransport.TELECOM)
        adapter.emit(ExternalCallState.ACTIVE)

        assertThat(coordinator.state.value?.phase).isEqualTo(CoordinatedPhase.ACTIVE)
        assertThat(coordinator.state.value?.audioAvailableForAi).isFalse()
        scope.cancel()
    }

    @Test
    fun nullSnapshotStopsCaptureAndMarksEnded() = runTest {
        val scope = newScope()
        val capture = FakeCallAudioSource()
        val adapter = FakeAdapter(CallTransport.SIMULATED, capture)
        val coordinator = ExternalCallCoordinator(CallTransportRouter(setOf(adapter)), scope)

        coordinator.start(CallTransport.SIMULATED)
        adapter.emit(ExternalCallState.ACTIVE)
        assertThat(capture.starts).isEqualTo(1)

        adapter.mutableState.value = null
        assertThat(capture.stops).isEqualTo(1)
        assertThat(coordinator.state.value?.phase).isEqualTo(CoordinatedPhase.ENDED)
        assertThat(coordinator.state.value?.callId).isNull()
        scope.cancel()
    }

    @Test
    fun controlCallsDelegateToActiveAdapter() = runTest {
        val scope = newScope()
        val adapter = FakeAdapter(CallTransport.SIMULATED, FakeCallAudioSource())
        val coordinator = ExternalCallCoordinator(CallTransportRouter(setOf(adapter)), scope)
        coordinator.start(CallTransport.SIMULATED)

        assertThat(coordinator.answer()).isTrue()
        assertThat(coordinator.reject()).isTrue()
        assertThat(coordinator.hangUp()).isTrue()
        assertThat(adapter.answered).isEqualTo(1)
        assertThat(adapter.rejected).isEqualTo(1)
        assertThat(adapter.hungUp).isEqualTo(1)
        scope.cancel()
    }

    @Test
    fun speakingGateClosesAndReopensCapture() = runTest {
        val scope = newScope()
        val adapter = FakeAdapter(CallTransport.SIMULATED, FakeCallAudioSource())
        val coordinator = ExternalCallCoordinator(CallTransportRouter(setOf(adapter)), scope)

        assertThat(coordinator.captureGate.isOpen.value).isTrue()
        coordinator.beginSpeaking()
        assertThat(coordinator.captureGate.isOpen.value).isFalse()
        coordinator.endSpeaking()
        assertThat(coordinator.captureGate.isOpen.value).isTrue()
        scope.cancel()
    }

    @Test
    fun shutdownReleasesCaptureAndClearsState() = runTest {
        val scope = newScope()
        val capture = FakeCallAudioSource()
        val adapter = FakeAdapter(CallTransport.SIMULATED, capture)
        val router = CallTransportRouter(setOf(adapter))
        val coordinator = ExternalCallCoordinator(router, scope)

        coordinator.start(CallTransport.SIMULATED)
        adapter.emit(ExternalCallState.ACTIVE)
        assertThat(capture.starts).isEqualTo(1)

        coordinator.shutdown()
        coordinator.shutdown()
        assertThat(capture.stops).isEqualTo(1)
        assertThat(coordinator.state.value).isNull()
        assertThat(router.activeTransport.value).isNull()
        scope.cancel()
    }
}
