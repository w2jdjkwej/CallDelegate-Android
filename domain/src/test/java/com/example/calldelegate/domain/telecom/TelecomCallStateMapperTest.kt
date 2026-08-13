package com.example.calldelegate.domain.telecom

import com.example.calldelegate.domain.api.ExternalCallState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TelecomCallStateMapperTest {

    @Test
    fun ringingStatesMapToRinging() {
        assertThat(TelecomCallStateMapper.map(TelecomCallStateMapper.STATE_RINGING))
            .isEqualTo(ExternalCallState.RINGING)
        assertThat(TelecomCallStateMapper.map(TelecomCallStateMapper.STATE_SIMULATED_RINGING))
            .isEqualTo(ExternalCallState.RINGING)
    }

    @Test
    fun connectingLikeStatesMapToConnecting() {
        listOf(
            TelecomCallStateMapper.STATE_NEW,
            TelecomCallStateMapper.STATE_DIALING,
            TelecomCallStateMapper.STATE_CONNECTING,
            TelecomCallStateMapper.STATE_SELECT_PHONE_ACCOUNT,
            TelecomCallStateMapper.STATE_PULLING_CALL,
            TelecomCallStateMapper.STATE_AUDIO_PROCESSING,
        ).forEach { state ->
            assertThat(TelecomCallStateMapper.map(state)).isEqualTo(ExternalCallState.CONNECTING)
        }
    }

    @Test
    fun activeMapsToActive() {
        assertThat(TelecomCallStateMapper.map(TelecomCallStateMapper.STATE_ACTIVE))
            .isEqualTo(ExternalCallState.ACTIVE)
    }

    @Test
    fun holdingMapsToHolding() {
        assertThat(TelecomCallStateMapper.map(TelecomCallStateMapper.STATE_HOLDING))
            .isEqualTo(ExternalCallState.HOLDING)
    }

    @Test
    fun disconnectedStatesMapToEnded() {
        assertThat(TelecomCallStateMapper.map(TelecomCallStateMapper.STATE_DISCONNECTED))
            .isEqualTo(ExternalCallState.ENDED)
        assertThat(TelecomCallStateMapper.map(TelecomCallStateMapper.STATE_DISCONNECTING))
            .isEqualTo(ExternalCallState.ENDED)
    }

    @Test
    fun unknownStateMapsToError() {
        assertThat(TelecomCallStateMapper.map(9999)).isEqualTo(ExternalCallState.ERROR)
        assertThat(TelecomCallStateMapper.map(-1)).isEqualTo(ExternalCallState.ERROR)
    }
}
