package com.example.calldelegate.domain.coordination

import com.example.calldelegate.domain.api.CallTransport
import com.example.calldelegate.domain.api.ExternalCallState

/**
 * Transport-agnostic call phase the coordinator drives the AI session from. Distinct from
 * [ExternalCallState] (telephony layer) and from
 * [com.example.calldelegate.domain.session.SessionPhase] (fine-grained AI turn phase): this is the
 * coarse call lifecycle shared by SIMULATED / TELECOM / VOIP.
 */
enum class CoordinatedPhase { IDLE, INCOMING, CONNECTING, ACTIVE, ON_HOLD, ENDED, FAILED }

/**
 * Single active-call snapshot as seen by the coordinator. [audioAvailableForAi] is derived from the
 * adapter exposing a non-null `audioInput` (the turn-based ASR source the AI loop reads); the UI must
 * not promise AI answering on a transport whose audio is unavailable (honest boundary for carrier
 * Telecom, whose audio is SILENCED for non-privileged apps).
 */
data class CoordinatedCall(
    val transport: CallTransport,
    val callId: String?,
    val phase: CoordinatedPhase,
    val callerNumber: String? = null,
    val audioAvailableForAi: Boolean = false,
)

/** Pure mapping from telephony [ExternalCallState] to the coarse [CoordinatedPhase]. JVM-testable. */
object CoordinatedPhaseMapper {
    fun map(state: ExternalCallState): CoordinatedPhase = when (state) {
        ExternalCallState.IDLE -> CoordinatedPhase.IDLE
        ExternalCallState.RINGING -> CoordinatedPhase.INCOMING
        ExternalCallState.CONNECTING -> CoordinatedPhase.CONNECTING
        ExternalCallState.ACTIVE -> CoordinatedPhase.ACTIVE
        ExternalCallState.HOLDING -> CoordinatedPhase.ON_HOLD
        ExternalCallState.ENDED -> CoordinatedPhase.ENDED
        ExternalCallState.ERROR -> CoordinatedPhase.FAILED
    }
}
