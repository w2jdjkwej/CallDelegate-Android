package com.example.calldelegate.core.ai.coordination

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.AudioInputSource
import com.example.calldelegate.domain.api.AudioState
import com.example.calldelegate.domain.api.CallAudioSource
import com.example.calldelegate.domain.api.CallControlGateway
import com.example.calldelegate.domain.api.CallResponseAudioSink
import com.example.calldelegate.domain.api.CallResponseResult
import com.example.calldelegate.domain.api.CallSessionController
import com.example.calldelegate.domain.api.CallTransport
import com.example.calldelegate.domain.api.ExternalCallAdapter
import com.example.calldelegate.domain.api.ExternalCallResponseRoute
import com.example.calldelegate.domain.api.ExternalCallSnapshot
import com.example.calldelegate.domain.api.ExternalCallState
import com.example.calldelegate.domain.coordination.CallTransportRouter
import com.example.calldelegate.domain.model.CaptureRequest
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.SynthesizedSpeech
import com.example.calldelegate.domain.session.CallSessionSnapshot
import com.example.calldelegate.domain.session.SessionPhase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomatedCallSessionBridgeTest {

    /** Minimal simulated adapter with a controllable state machine and a turn-audio source. */
    private class SimAdapter : ExternalCallAdapter {
        override val transport = CallTransport.SIMULATED
        val mutableState = MutableStateFlow<ExternalCallSnapshot?>(null)
        override val callState: StateFlow<ExternalCallSnapshot?> = mutableState
        override val audioInput: AudioInputSource = object : AudioInputSource {
            override val mode = InputMode.MICROPHONE
            override val state = MutableStateFlow<AudioState>(AudioState.Idle)
            override suspend fun capture(request: CaptureRequest): AppResult<CapturedAudio> =
                AppResult.Success(CapturedAudio(ShortArray(0), 16_000, 0, null))
            override suspend fun cancel() = Unit
            override suspend fun release() = Unit
        }
        override val callAudioSource: CallAudioSource? = null
        override val controls = object : CallControlGateway {
            override suspend fun answer(): Boolean {
                val s = mutableState.value ?: return false
                mutableState.value = s.copy(state = ExternalCallState.ACTIVE)
                return true
            }
            override suspend fun reject() = hangUp()
            override suspend fun hangUp(): Boolean {
                val s = mutableState.value ?: return false
                mutableState.value = s.copy(state = ExternalCallState.ENDED)
                return true
            }
        }
        override val responseSink = object : CallResponseAudioSink {
            override suspend fun playToCall(callId: String, speech: SynthesizedSpeech) =
                CallResponseResult.LocalPlaybackOnly
        }

        fun ring(callId: String, number: String) {
            mutableState.value = ExternalCallSnapshot(callId, ExternalCallState.RINGING, number)
        }
    }

    @Test
    fun incomingAutoAnswersStartsAiAndEndsOnDialogueCompletion() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val adapter = SimAdapter()
        val router = CallTransportRouter(setOf(adapter))
        val coordinator = ExternalCallCoordinator(router, scope)
        val controller = FakeController()
        val bridge = AutomatedCallSessionBridge(coordinator, controller, scope, autoAnswer = true)

        coordinator.start(CallTransport.SIMULATED)
        bridge.start()

        adapter.ring("c1", "123")
        advanceUntilIdle()

        // Auto-answered → moved to ACTIVE, AI session started with the transport's turn audio.
        assertThat(controller.incomingNumbers).containsExactly("123")
        assertThat(controller.acceptedAudio).isSameInstanceAs(adapter.audioInput)
        assertThat(adapter.mutableState.value?.state).isEqualTo(ExternalCallState.ACTIVE)

        // Dialogue decides to end → bridge hangs up the still-open line.
        controller.stateFlow.value = CallSessionSnapshot(sessionId = "s1", phase = SessionPhase.COMPLETED)
        advanceUntilIdle()

        assertThat(adapter.mutableState.value?.state).isEqualTo(ExternalCallState.ENDED)
        assertThat(controller.endReasons).isNotEmpty()

        bridge.stop()
        bridge.stop()
        scope.cancel()
    }

    @Test
    fun anIncomingCallIsLeftRingingUntilTheDelayHasPassed() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val adapter = SimAdapter()
        val coordinator = ExternalCallCoordinator(CallTransportRouter(setOf(adapter)), scope)
        val controller = FakeController()
        val bridge = AutomatedCallSessionBridge(
            coordinator, controller, scope,
            autoAnswer = true,
            autoAnswerDelayMillis = { 2_000L },
        )
        coordinator.start(CallTransport.SIMULATED)
        bridge.start()

        adapter.ring("c1", "123")
        // runCurrent, not advanceUntilIdle: idling the scheduler would run the pending answer
        // whatever the clock says, and this test is about the clock.
        advanceTimeBy(1_900L)
        runCurrent()

        // Still ringing: the wait is what leaves room for the person holding the phone to take
        // their own call, so answering inside it would defeat the whole point of having one.
        assertThat(adapter.mutableState.value?.state).isEqualTo(ExternalCallState.RINGING)

        advanceTimeBy(200L)
        runCurrent()

        assertThat(adapter.mutableState.value?.state).isEqualTo(ExternalCallState.ACTIVE)
        bridge.stop()
        scope.cancel()
    }

    @Test
    fun aCallThatStopsRingingInsideTheDelayIsNeverAnswered() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val adapter = SimAdapter()
        val coordinator = ExternalCallCoordinator(CallTransportRouter(setOf(adapter)), scope)
        val controller = FakeController()
        val bridge = AutomatedCallSessionBridge(
            coordinator, controller, scope,
            autoAnswer = true,
            autoAnswerDelayMillis = { 2_000L },
        )
        coordinator.start(CallTransport.SIMULATED)
        bridge.start()

        adapter.ring("c1", "123")
        advanceTimeBy(500L)
        runCurrent()
        // The caller gives up, or the person holding the phone rejects it. The timer is still
        // pending, and a timer that answered on its own recollection would take a dead call.
        adapter.mutableState.value = adapter.mutableState.value?.copy(state = ExternalCallState.ENDED)
        advanceUntilIdle()

        advanceTimeBy(5_000L)
        advanceUntilIdle()

        assertThat(adapter.mutableState.value?.state).isEqualTo(ExternalCallState.ENDED)
        bridge.stop()
        scope.cancel()
    }

    @Test
    fun speakingPhaseClosesCaptureGate() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val adapter = SimAdapter()
        val coordinator = ExternalCallCoordinator(CallTransportRouter(setOf(adapter)), scope)
        val controller = FakeController()
        val bridge = AutomatedCallSessionBridge(coordinator, controller, scope, autoAnswer = false)
        coordinator.start(CallTransport.SIMULATED)
        bridge.start()

        controller.stateFlow.value = CallSessionSnapshot(sessionId = "s1", phase = SessionPhase.SPEAKING)
        advanceUntilIdle()
        assertThat(coordinator.captureGate.isOpen.value).isFalse()

        controller.stateFlow.value = CallSessionSnapshot(sessionId = "s1", phase = SessionPhase.RECORDING)
        advanceUntilIdle()
        assertThat(coordinator.captureGate.isOpen.value).isTrue()

        bridge.stop()
        scope.cancel()
    }
}

private class FakeController : CallSessionController {
    val stateFlow = MutableStateFlow(CallSessionSnapshot())
    override val state: StateFlow<CallSessionSnapshot> = stateFlow
    val incomingNumbers = mutableListOf<String>()
    var acceptedAudio: AudioInputSource? = null
    var acceptedResponseRoute: ExternalCallResponseRoute? = null
    val endReasons = mutableListOf<String>()

    override suspend fun simulateIncoming(callerName: String?, callerNumber: String) {
        incomingNumbers += callerNumber
    }
    override suspend fun decline() = Unit
    override suspend fun acceptNormally() = Unit
    override suspend fun acceptWithAi(inputMode: InputMode) = Unit
    override suspend fun acceptExternalWithAi(
        turnAudio: AudioInputSource,
        responseRoute: ExternalCallResponseRoute?,
    ) {
        acceptedAudio = turnAudio
        acceptedResponseRoute = responseRoute
    }
    override suspend fun setInputMode(mode: InputMode) = Unit
    override suspend fun submitText(text: String) = Unit
    override suspend fun submitPreset(presetId: String) = Unit
    override suspend fun captureMicrophoneTurn() = Unit
    override suspend fun requestHumanTakeover() = Unit
    override suspend fun end(reason: String) { endReasons += reason }
    override suspend fun reset() = Unit
}
