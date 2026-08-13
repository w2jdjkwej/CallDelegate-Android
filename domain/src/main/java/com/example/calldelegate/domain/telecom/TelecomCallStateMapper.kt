package com.example.calldelegate.domain.telecom

import com.example.calldelegate.domain.api.ExternalCallState

/**
 * Pure mapping from Android Telecom `Call.STATE_*` integers to the project-internal
 * [ExternalCallState]. Business/UI code must NOT switch on raw `Call.STATE_*` values directly.
 *
 * The constants mirror `android.telecom.Call.STATE_*` (stable, frozen platform values) and are
 * duplicated here on purpose so the mapping is pure-JVM testable WITHOUT an Android runtime.
 */
object TelecomCallStateMapper {
    const val STATE_NEW = 0
    const val STATE_DIALING = 1
    const val STATE_RINGING = 2
    const val STATE_HOLDING = 3
    const val STATE_ACTIVE = 4
    const val STATE_DISCONNECTED = 7
    const val STATE_SELECT_PHONE_ACCOUNT = 8
    const val STATE_CONNECTING = 9
    const val STATE_DISCONNECTING = 10
    const val STATE_PULLING_CALL = 11
    const val STATE_AUDIO_PROCESSING = 12
    const val STATE_SIMULATED_RINGING = 13

    fun map(telecomState: Int): ExternalCallState = when (telecomState) {
        STATE_RINGING, STATE_SIMULATED_RINGING -> ExternalCallState.RINGING
        STATE_NEW,
        STATE_DIALING,
        STATE_CONNECTING,
        STATE_SELECT_PHONE_ACCOUNT,
        STATE_PULLING_CALL,
        STATE_AUDIO_PROCESSING -> ExternalCallState.CONNECTING
        STATE_ACTIVE -> ExternalCallState.ACTIVE
        STATE_HOLDING -> ExternalCallState.HOLDING
        STATE_DISCONNECTED, STATE_DISCONNECTING -> ExternalCallState.ENDED
        else -> ExternalCallState.ERROR
    }
}
