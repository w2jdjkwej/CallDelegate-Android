package com.example.calldelegate.domain.coordination

import com.example.calldelegate.domain.api.ExternalCallState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoordinatedPhaseMapperTest {

    @Test
    fun everyExternalStateMapsToACoordinatedPhase() {
        val expected = mapOf(
            ExternalCallState.IDLE to CoordinatedPhase.IDLE,
            ExternalCallState.RINGING to CoordinatedPhase.INCOMING,
            ExternalCallState.CONNECTING to CoordinatedPhase.CONNECTING,
            ExternalCallState.ACTIVE to CoordinatedPhase.ACTIVE,
            ExternalCallState.HOLDING to CoordinatedPhase.ON_HOLD,
            ExternalCallState.ENDED to CoordinatedPhase.ENDED,
            ExternalCallState.ERROR to CoordinatedPhase.FAILED,
        )
        expected.forEach { (state, phase) ->
            assertThat(CoordinatedPhaseMapper.map(state)).isEqualTo(phase)
        }
    }

    @Test
    fun mappingCoversAllStatesExhaustively() {
        // Guards against a new ExternalCallState being added without a mapping.
        ExternalCallState.entries.forEach { state ->
            // Should not throw and must produce a non-null phase.
            assertThat(CoordinatedPhaseMapper.map(state)).isNotNull()
        }
    }
}
