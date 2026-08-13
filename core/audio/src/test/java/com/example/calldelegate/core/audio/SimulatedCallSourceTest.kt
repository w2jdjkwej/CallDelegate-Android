package com.example.calldelegate.core.audio

import com.example.calldelegate.domain.api.CallResponseResult
import com.example.calldelegate.domain.api.CallTransport
import com.example.calldelegate.domain.api.ExternalCallState
import com.example.calldelegate.domain.model.SynthesizedSpeech
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SimulatedCallSourceTest {

    @Test
    fun ringThenAnswerReachesActive() = runBlocking {
        val source = SimulatedCallSource(now = { 1_000L })
        assertThat(source.transport).isEqualTo(CallTransport.SIMULATED)
        assertThat(source.callState.value).isNull()

        assertThat(source.ringIncoming(callId = "c1", callerNumber = "123")).isTrue()
        assertThat(source.callState.value?.state).isEqualTo(ExternalCallState.RINGING)
        assertThat(source.callState.value?.callId).isEqualTo("c1")

        assertThat(source.controls.answer()).isTrue()
        assertThat(source.callState.value?.state).isEqualTo(ExternalCallState.ACTIVE)
    }

    @Test
    fun answerOnlyValidWhileRinging() = runBlocking {
        val source = SimulatedCallSource()
        assertThat(source.controls.answer()).isFalse() // no call
        source.ringIncoming()
        assertThat(source.controls.answer()).isTrue()
        assertThat(source.controls.answer()).isFalse() // already active
    }

    @Test
    fun hangUpMovesToEnded() = runBlocking {
        val source = SimulatedCallSource()
        source.ringIncoming()
        source.controls.answer()
        assertThat(source.controls.hangUp()).isTrue()
        assertThat(source.controls.hangUp()).isTrue()
        assertThat(source.callState.value?.state).isEqualTo(ExternalCallState.ENDED)
    }

    @Test
    fun secondRingIgnoredWhileCallActive() = runBlocking {
        val source = SimulatedCallSource()
        assertThat(source.ringIncoming(callId = "c1")).isTrue()
        assertThat(source.ringIncoming(callId = "c2")).isFalse()
        assertThat(source.callState.value?.callId).isEqualTo("c1")
    }

    @Test
    fun clearResetsToIdle() = runBlocking {
        val source = SimulatedCallSource()
        source.ringIncoming()
        source.clear()
        assertThat(source.callState.value).isNull()
        // A new call can start after clearing.
        assertThat(source.ringIncoming(callId = "c3")).isTrue()
    }

    @Test
    fun responseSinkIsHonestLocalPlayback() = runBlocking {
        val source = SimulatedCallSource()
        val result = source.responseSink.playToCall(
            "c1",
            SynthesizedSpeech("test", null, 1L, true, shortArrayOf(1)),
        )
        assertThat(result).isEqualTo(CallResponseResult.LocalPlaybackOnly)
    }
}
