package com.example.calldelegate.core.ai.coordination

import com.example.calldelegate.domain.api.AiAnswerRouter
import com.example.calldelegate.domain.api.CallSessionController
import com.example.calldelegate.domain.api.CallTransport
import com.example.calldelegate.domain.api.ExternalCallResponseRoute
import com.example.calldelegate.domain.coordination.CoordinatedPhase
import com.example.calldelegate.domain.model.InputMode

/**
 * Sends the answer button down the call's own audio path when the call has one.
 *
 * Answering a real telephony call as a microphone session is not a degraded version of answering it
 * properly -- it is inaudible to the caller. The microphone session has no response route, so the
 * assistant's reply goes to the handset speaker and the far end hears silence, with nothing recorded
 * anywhere to say so. That is what happened on 2026-08-08: three answered calls, every reply played
 * locally, no uplink attempted, and no error, because nothing had gone wrong -- the wrong path had
 * simply been chosen before anything could.
 *
 * The microphone path is kept for the simulated and preset sessions the app also runs, which have no
 * transport behind them.
 */
class TransportAwareAiAnswerRouter(
    private val coordinator: ExternalCallCoordinator,
    private val controller: CallSessionController,
) : AiAnswerRouter {

    override suspend fun acceptWithAi(microphoneFallback: InputMode) {
        val call = coordinator.state.value
        val turnAudio = coordinator.activeAudioInput
        val responseSink = coordinator.activeResponseSink
        val callId = call?.callId

        val usable = call != null &&
            call.phase in ANSWERABLE_PHASES &&
            call.audioAvailableForAi &&
            turnAudio != null &&
            responseSink != null &&
            callId != null
        if (!usable) {
            controller.acceptWithAi(microphoneFallback)
            return
        }

        controller.acceptExternalWithAi(
            turnAudio = checkNotNull(turnAudio),
            responseRoute = ExternalCallResponseRoute(
                callId = checkNotNull(callId),
                sink = checkNotNull(responseSink),
                // No separate local monitor. The reply reaches a telephony call by being played out
                // loud (SpeakerphoneCallResponseSink), which the person holding the phone already
                // hears; adding the monitor would speak every reply twice, once to the speaker and
                // once to the earpiece.
                monitorLocally = false,
            ),
        )
    }

    private companion object {
        /**
         * A call that is still ringing is answered by the same button, so both phases route here;
         * the controller decides whether the session is in a state that can be taken over.
         */
        val ANSWERABLE_PHASES = setOf(CoordinatedPhase.INCOMING, CoordinatedPhase.ACTIVE)
    }
}
