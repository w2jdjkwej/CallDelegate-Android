package com.example.calldelegate.core.ai.coordination

import com.example.calldelegate.domain.api.CallSessionController
import com.example.calldelegate.domain.api.CallTransport
import com.example.calldelegate.domain.api.ExternalCallResponseRoute
import com.example.calldelegate.domain.coordination.CoordinatedCall
import com.example.calldelegate.domain.coordination.CoordinatedPhase
import com.example.calldelegate.domain.session.CallSessionSnapshot
import com.example.calldelegate.domain.session.SessionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Glue that turns an [ExternalCallCoordinator] into a fully automated answering session driven by a
 * [CallSessionController], with NO UI button presses:
 *
 *  - INCOMING  -> raise a ringing AI session and (optionally) auto-answer once it has rung a while
 *  - ACTIVE    -> start the automated multi-turn AI loop using the transport's turn audio
 *  - dialogue END (controller COMPLETED) -> hang up the call
 *  - call ENDED/hangup -> finalize the session
 *
 * It also mirrors the session's speaking phase onto the coordinator's capture gate so continuous
 * capture is paused while the assistant speaks ("TTS 期间停采"; for turn-based transports this is
 * already inherent, the gate just makes it explicit/safe for streaming transports).
 *
 * Lifecycle is owned by the caller (foreground service / activity): call [start] to begin observing
 * and [stop] to detach. Device-independent (domain interfaces only) → JVM-testable.
 */
class AutomatedCallSessionBridge(
    private val coordinator: ExternalCallCoordinator,
    private val controller: CallSessionController,
    private val scope: CoroutineScope,
    private val autoAnswer: Boolean = true,
    /**
     * How long the call is left ringing before the assistant picks it up.
     *
     * The wait is the feature, not an implementation detail. Answering on the first frame of the
     * first ring takes the call away from the person holding the phone before they can reach it,
     * and it picks up wrong numbers and one-ring hangups that would otherwise have ended by
     * themselves. Zero restores the previous answer-immediately behaviour.
     */
    private val autoAnswerDelayMillis: suspend () -> Long = { DEFAULT_AUTO_ANSWER_DELAY_MS },
) {
    private var job: Job? = null
    private val ringingCallId = AtomicReference<String?>(null)
    private val aiStarted = AtomicBoolean(false)
    /** The pending wait, so a call that stops ringing is not answered by a timer that outlived it. */
    private var autoAnswerJob: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch {
            launch { coordinator.state.collect { onCall(it) } }
            launch { controller.state.collect { onSession(it) } }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        reset()
    }

    private suspend fun onCall(call: CoordinatedCall?) {
        if (call == null) return
        when (call.phase) {
            CoordinatedPhase.INCOMING -> {
                if (ringingCallId.getAndSet(call.callId) != call.callId) {
                    controller.simulateIncoming(callerName = null, callerNumber = call.callerNumber ?: "")
                    if (autoAnswer) scheduleAutoAnswer(call.callId)
                }
            }
            CoordinatedPhase.ACTIVE -> {
                val activeCallId = call.callId ?: return
                if (ringingCallId.compareAndSet(null, activeCallId)) {
                    // Handles a manually answered call whose service started after the RINGING
                    // callback. The controller still needs one real session before AI can start.
                    controller.simulateIncoming(callerName = null, callerNumber = call.callerNumber ?: "")
                }
                val audio = coordinator.activeAudioInput
                val responseSink = coordinator.activeResponseSink
                if (call.audioAvailableForAi &&
                    audio != null &&
                    responseSink != null &&
                    aiStarted.compareAndSet(false, true)
                ) {
                    controller.acceptExternalWithAi(
                        turnAudio = audio,
                        responseRoute = ExternalCallResponseRoute(
                            callId = activeCallId,
                            sink = responseSink,
                            monitorLocally = call.transport == CallTransport.TELECOM,
                        ),
                    )
                }
            }
            CoordinatedPhase.ENDED, CoordinatedPhase.FAILED -> {
                if (ringingCallId.get() != null || aiStarted.get()) {
                    controller.end("call_ended")
                    reset()
                }
            }
            else -> Unit
        }
    }

    /**
     * Answers [callId] once it has rung for [autoAnswerDelayMillis], and only if it is still that
     * call and still ringing.
     *
     * Both halves of that check earn their place. The caller can give up inside the window, and the
     * person holding the phone can answer or reject it themselves; in either case the timer is
     * still pending, and a timer that answers on its own recollection of the state would take a
     * call that no longer exists or re-answer one a human already dealt with.
     */
    private fun scheduleAutoAnswer(callId: String?) {
        autoAnswerJob?.cancel()
        autoAnswerJob = scope.launch {
            delay(autoAnswerDelayMillis().coerceAtLeast(0L))
            val current = coordinator.state.value ?: return@launch
            if (current.callId != callId) return@launch
            if (current.phase != CoordinatedPhase.INCOMING) return@launch
            coordinator.answer()
        }
    }

    private suspend fun onSession(snapshot: CallSessionSnapshot) {
        when (snapshot.phase) {
            SessionPhase.OPENING, SessionPhase.SPEAKING -> coordinator.beginSpeaking()
            SessionPhase.RECORDING, SessionPhase.AWAITING_INPUT -> coordinator.endSpeaking()
            SessionPhase.COMPLETED -> {
                // Dialogue decided to end while the line is still up → hang up the call.
                if (aiStarted.compareAndSet(true, false)) coordinator.hangUp()
            }
            else -> Unit
        }
    }

    private fun reset() {
        autoAnswerJob?.cancel()
        autoAnswerJob = null
        ringingCallId.set(null)
        aiStarted.set(false)
    }

    companion object {
        /** Roughly one ring: long enough to reach for the phone, short enough not to look broken. */
        const val DEFAULT_AUTO_ANSWER_DELAY_MS = 2_000L
    }
}
