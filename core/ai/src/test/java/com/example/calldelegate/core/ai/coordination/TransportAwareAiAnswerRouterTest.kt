package com.example.calldelegate.core.ai.coordination

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.AudioInputSource
import com.example.calldelegate.domain.api.AudioState
import com.example.calldelegate.domain.api.CallControlGateway
import com.example.calldelegate.domain.api.CallResponseAudioSink
import com.example.calldelegate.domain.api.CallResponseResult
import com.example.calldelegate.domain.api.CallSessionController
import com.example.calldelegate.domain.api.CallTransport
import com.example.calldelegate.domain.api.ExternalCallAdapter
import com.example.calldelegate.domain.api.ExternalCallResponseRoute
import com.example.calldelegate.domain.api.ExternalCallSnapshot
import com.example.calldelegate.domain.coordination.CallTransportRouter
import com.example.calldelegate.domain.api.ExternalCallState
import com.example.calldelegate.domain.model.CaptureRequest
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.SynthesizedSpeech
import com.example.calldelegate.domain.session.CallSessionSnapshot
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The button that answers a call cannot know which audio path the call has, and answering a real
 * one as a microphone session makes the assistant inaudible to the caller with nothing recorded to
 * say so. These pin which path each situation takes.
 */
class TransportAwareAiAnswerRouterTest {

    @Test
    fun aTelephonyCallIsAnsweredOverTheCallsOwnAudio() = runTest {
        val controller = RecordingController()
        val coordinator = coordinatorWith(backgroundScope, telecomAdapter())
        coordinator.start(CallTransport.TELECOM)
        runCurrent()

        TransportAwareAiAnswerRouter(coordinator, controller).acceptWithAi(InputMode.MICROPHONE)

        assertThat(controller.microphoneAccepts).isEmpty()
        assertThat(controller.externalAccepts).hasSize(1)
        val route = controller.externalAccepts.single().second
        assertThat(route?.callId).isEqualTo("call-1")
        // The reply reaches the call by being played out loud, which the person holding the phone
        // already hears, so a second local monitor would speak every reply twice.
        assertThat(route?.monitorLocally).isFalse()
    }

    @Test
    fun withNoTransportCarryingAudioTheMicrophoneSessionIsStillUsed() = runTest {
        val controller = RecordingController()
        val coordinator = coordinatorWith(backgroundScope, telecomAdapter())
        // Never started, so no adapter is routed: this is the simulated/preset case the app also runs.

        TransportAwareAiAnswerRouter(coordinator, controller).acceptWithAi(InputMode.MICROPHONE)

        assertThat(controller.externalAccepts).isEmpty()
        assertThat(controller.microphoneAccepts).containsExactly(InputMode.MICROPHONE)
    }

    @Test
    fun aCallWhoseTransportReportsNoAudioFallsBackRatherThanAnsweringSilently() = runTest {
        val controller = RecordingController()
        val coordinator = coordinatorWith(backgroundScope, telecomAdapter(audio = null))
        coordinator.start(CallTransport.TELECOM)
        runCurrent()

        TransportAwareAiAnswerRouter(coordinator, controller).acceptWithAi(InputMode.MICROPHONE)

        assertThat(controller.externalAccepts).isEmpty()
        assertThat(controller.microphoneAccepts).containsExactly(InputMode.MICROPHONE)
    }

    private fun coordinatorWith(scope: CoroutineScope, adapter: ExternalCallAdapter) =
        ExternalCallCoordinator(CallTransportRouter(setOf(adapter)), scope)

    private fun telecomAdapter(
        audio: AudioInputSource? = SilentTurnAudio(),
    ) = object : ExternalCallAdapter {
        override val transport = CallTransport.TELECOM
        override val callState: StateFlow<ExternalCallSnapshot?> =
            MutableStateFlow(ExternalCallSnapshot("call-1", ExternalCallState.ACTIVE, "10086"))
        override val callAudioSource = null
        override val audioInput = audio
        override val responseSink: CallResponseAudioSink = RecordingSink()
        override val controls = object : CallControlGateway {
            override suspend fun answer() = true
            override suspend fun reject() = true
            override suspend fun hangUp() = true
        }
    }
}

private class SilentTurnAudio : AudioInputSource {
    override val mode = InputMode.CALL_AUDIO
    override val state = MutableStateFlow<AudioState>(AudioState.Idle)
    override suspend fun capture(request: CaptureRequest) =
        AppResult.Success(CapturedAudio(shortArrayOf(1), 16_000, 1, null))
    override suspend fun cancel() = Unit
    override suspend fun release() = Unit
}

private class RecordingSink : CallResponseAudioSink {
    override suspend fun playToCall(callId: String, speech: SynthesizedSpeech): CallResponseResult =
        CallResponseResult.PlayedToCallUplink
}

private class RecordingController : CallSessionController {
    val microphoneAccepts = mutableListOf<InputMode>()
    val externalAccepts = mutableListOf<Pair<AudioInputSource, ExternalCallResponseRoute?>>()

    override val state = MutableStateFlow(CallSessionSnapshot())
    override suspend fun simulateIncoming(callerName: String?, callerNumber: String) = Unit
    override suspend fun decline() = Unit
    override suspend fun acceptNormally() = Unit
    override suspend fun acceptWithAi(inputMode: InputMode) { microphoneAccepts += inputMode }
    override suspend fun acceptExternalWithAi(
        turnAudio: AudioInputSource,
        responseRoute: ExternalCallResponseRoute?,
    ) { externalAccepts += turnAudio to responseRoute }
    override suspend fun setInputMode(mode: InputMode) = Unit
    override suspend fun submitText(text: String) = Unit
    override suspend fun submitPreset(presetId: String) = Unit
    override suspend fun captureMicrophoneTurn() = Unit
    override suspend fun requestHumanTakeover() = Unit
    override suspend fun end(reason: String) = Unit
    override suspend fun reset() = Unit
}
