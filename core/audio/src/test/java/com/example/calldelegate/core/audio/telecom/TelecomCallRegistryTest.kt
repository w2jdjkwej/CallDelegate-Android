package com.example.calldelegate.core.audio.telecom

import com.example.calldelegate.domain.api.ExternalCallState
import com.example.calldelegate.domain.telecom.TelecomCallStateMapper
import com.google.common.truth.Truth.assertThat
import org.junit.Test

private class FakeTelecomCallHandle(
    override val id: String,
    override val callerNumber: String? = "138****0000",
    override val isIncoming: Boolean = true,
    initialState: Int = TelecomCallStateMapper.STATE_RINGING,
) : TelecomCallHandle {
    override var currentState: Int = initialState
    var answered = false
    var rejected = false
    var disconnected = false
    private var listener: ((Int) -> Unit)? = null

    override fun answer() { answered = true }
    override fun reject() { rejected = true }
    override fun disconnect() { disconnected = true }
    override fun setStateListener(listener: ((Int) -> Unit)?) { this.listener = listener }

    fun emit(state: Int) {
        currentState = state
        listener?.invoke(state)
    }
}

class TelecomCallRegistryTest {

    @Test
    fun addingCallPublishesMappedSnapshot() {
        val registry = TelecomCallRegistry(now = { 1_000L })
        val call = FakeTelecomCallHandle("call-1")

        registry.onCallAdded(call)

        val snapshot = registry.callState.value!!
        assertThat(snapshot.callId).isEqualTo("call-1")
        assertThat(snapshot.state).isEqualTo(ExternalCallState.RINGING)
        assertThat(snapshot.startedAtMillis).isEqualTo(1_000L)
    }

    @Test
    fun secondConcurrentCallIsRejectedAndIgnored() {
        val registry = TelecomCallRegistry()
        val first = FakeTelecomCallHandle("call-1")
        val second = FakeTelecomCallHandle("call-2")

        registry.onCallAdded(first)
        registry.onCallAdded(second)

        assertThat(second.rejected).isTrue()
        assertThat(registry.activeCallId).isEqualTo("call-1")
        assertThat(registry.callState.value!!.callId).isEqualTo("call-1")
    }

    @Test
    fun stateTransitionsAreMappedAndEndClearsState() {
        val registry = TelecomCallRegistry()
        val call = FakeTelecomCallHandle("call-1")

        registry.onCallAdded(call)
        call.emit(TelecomCallStateMapper.STATE_ACTIVE)
        assertThat(registry.callState.value!!.state).isEqualTo(ExternalCallState.ACTIVE)

        call.emit(TelecomCallStateMapper.STATE_HOLDING)
        assertThat(registry.callState.value!!.state).isEqualTo(ExternalCallState.HOLDING)

        call.emit(TelecomCallStateMapper.STATE_DISCONNECTED)
        assertThat(registry.callState.value).isNull()
        assertThat(registry.activeCallId).isNull()
    }

    @Test
    fun repeatedActiveKeepsStableSnapshot() {
        val registry = TelecomCallRegistry(now = { 5_000L })
        val call = FakeTelecomCallHandle("call-1")

        registry.onCallAdded(call)
        registry.onCallAdded(call) // idempotent add
        call.emit(TelecomCallStateMapper.STATE_ACTIVE)
        call.emit(TelecomCallStateMapper.STATE_ACTIVE) // idempotent state

        val snapshot = registry.callState.value!!
        assertThat(snapshot.state).isEqualTo(ExternalCallState.ACTIVE)
        assertThat(snapshot.startedAtMillis).isEqualTo(5_000L)
    }

    @Test
    fun repeatedDisconnectedIsIdempotent() {
        val registry = TelecomCallRegistry()
        val call = FakeTelecomCallHandle("call-1")

        registry.onCallAdded(call)
        call.emit(TelecomCallStateMapper.STATE_DISCONNECTED)
        // Further terminal events must not crash or resurrect the call.
        call.emit(TelecomCallStateMapper.STATE_DISCONNECTED)
        registry.onCallRemoved(call)

        assertThat(registry.callState.value).isNull()
    }

    @Test
    fun controlsValidateCallId() {
        val registry = TelecomCallRegistry()
        val call = FakeTelecomCallHandle("call-1")
        registry.onCallAdded(call)

        assertThat(registry.answer("wrong-id")).isFalse()
        assertThat(call.answered).isFalse()

        assertThat(registry.answer("call-1")).isTrue()
        assertThat(call.answered).isTrue()
    }

    @Test
    fun removingNonActiveCallDoesNotAffectActive() {
        val registry = TelecomCallRegistry()
        val active = FakeTelecomCallHandle("call-1")
        val other = FakeTelecomCallHandle("call-2")
        registry.onCallAdded(active)

        registry.onCallRemoved(other)

        assertThat(registry.activeCallId).isEqualTo("call-1")
        assertThat(registry.callState.value!!.callId).isEqualTo("call-1")
    }

    @Test
    fun hangUpDisconnectsActiveCall() {
        val registry = TelecomCallRegistry()
        val call = FakeTelecomCallHandle("call-1")
        registry.onCallAdded(call)

        assertThat(registry.hangUp()).isTrue()
        assertThat(call.disconnected).isTrue()
    }
}
