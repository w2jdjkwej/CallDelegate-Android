package com.example.calldelegate.domain.coordination

import com.example.calldelegate.domain.api.AudioInputSource
import com.example.calldelegate.domain.api.CallAudioSource
import com.example.calldelegate.domain.api.CallControlGateway
import com.example.calldelegate.domain.api.CallResponseAudioSink
import com.example.calldelegate.domain.api.CallResponseResult
import com.example.calldelegate.domain.api.CallTransport
import com.example.calldelegate.domain.api.ExternalCallAdapter
import com.example.calldelegate.domain.api.ExternalCallSnapshot
import com.example.calldelegate.domain.api.ExternalCallState
import com.example.calldelegate.domain.model.SynthesizedSpeech
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CallTransportRouterTest {

    private class FakeAdapter(override val transport: CallTransport) : ExternalCallAdapter {
        val mutableState = MutableStateFlow<ExternalCallSnapshot?>(null)
        override val callState: StateFlow<ExternalCallSnapshot?> = mutableState
        override val controls: CallControlGateway = object : CallControlGateway {
            override suspend fun answer() = true
            override suspend fun reject() = true
            override suspend fun hangUp() = true
        }
        override val audioInput: AudioInputSource? = null
        override val callAudioSource: CallAudioSource? = null
        override val responseSink: CallResponseAudioSink = object : CallResponseAudioSink {
            override suspend fun playToCall(callId: String, speech: SynthesizedSpeech) =
                CallResponseResult.LocalPlaybackOnly
        }
    }

    @Test
    fun registersAllTransportsAndResolvesByKey() {
        val sim = FakeAdapter(CallTransport.SIMULATED)
        val tel = FakeAdapter(CallTransport.TELECOM)
        val router = CallTransportRouter(setOf(sim, tel))

        assertThat(router.available()).containsExactly(CallTransport.SIMULATED, CallTransport.TELECOM)
        assertThat(router.adapter(CallTransport.SIMULATED)).isSameInstanceAs(sim)
        assertThat(router.adapter(CallTransport.VOIP)).isNull()
    }

    @Test
    fun duplicateTransportIsRejected() {
        val a = FakeAdapter(CallTransport.SIMULATED)
        val b = FakeAdapter(CallTransport.SIMULATED)
        try {
            CallTransportRouter(setOf(a, b))
            throw AssertionError("expected IllegalArgumentException for duplicate transport")
        } catch (expected: IllegalArgumentException) {
            assertThat(expected).hasMessageThat().contains("SIMULATED")
        }
    }

    @Test
    fun selectMarksActiveAndClearResets() {
        val sim = FakeAdapter(CallTransport.SIMULATED)
        val router = CallTransportRouter(setOf(sim))

        assertThat(router.active).isNull()
        val selected = router.select(CallTransport.SIMULATED)
        assertThat(selected).isSameInstanceAs(sim)
        assertThat(router.activeTransport.value).isEqualTo(CallTransport.SIMULATED)
        assertThat(router.active).isSameInstanceAs(sim)

        router.clear()
        assertThat(router.activeTransport.value).isNull()
        assertThat(router.active).isNull()
    }

    @Test
    fun selectingUnregisteredTransportThrows() {
        val router = CallTransportRouter(setOf(FakeAdapter(CallTransport.SIMULATED)))
        try {
            router.select(CallTransport.VOIP)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertThat(expected).hasMessageThat().contains("VOIP")
        }
    }

    @Test
    fun activeCallStateFollowsSelectedTransport() = runTest {
        val sim = FakeAdapter(CallTransport.SIMULATED)
        val router = CallTransportRouter(setOf(sim))

        // Before selection: null.
        assertThat(router.activeCallState().first()).isNull()

        sim.mutableState.value = ExternalCallSnapshot(
            callId = "c1",
            state = ExternalCallState.RINGING,
        )
        router.select(CallTransport.SIMULATED)

        val snapshot = router.activeCallState().first { it != null }
        assertThat(snapshot!!.callId).isEqualTo("c1")
        assertThat(snapshot.state).isEqualTo(ExternalCallState.RINGING)
    }
}
