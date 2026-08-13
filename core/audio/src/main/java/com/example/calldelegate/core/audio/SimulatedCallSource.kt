package com.example.calldelegate.core.audio

import com.example.calldelegate.domain.api.AudioInputSource
import com.example.calldelegate.domain.api.CallAudioSource
import com.example.calldelegate.domain.api.CallControlGateway
import com.example.calldelegate.domain.api.CallResponseAudioSink
import com.example.calldelegate.domain.api.CallResponseResult
import com.example.calldelegate.domain.api.CallTransport
import com.example.calldelegate.domain.api.ExternalCallAdapter
import com.example.calldelegate.domain.api.ExternalCallSnapshot
import com.example.calldelegate.domain.api.ExternalCallState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * SIMULATED transport behind the same [ExternalCallAdapter] seam used by Telecom/VoIP, so the
 * coordinator drives every transport through one code path. Audio for the AI chain comes from the
 * injected [audioInput] (e.g. microphone/preset) since a simulated call has no real media link.
 *
 * The response sink is [LocalPlaybackResponseSink]: TTS is honestly played locally, never claimed to
 * reach a remote party.
 *
 * State machine (single active call): [ringIncoming] -> RINGING; answer -> ACTIVE;
 * reject/hangUp -> ENDED. [clear] returns to idle (null). Pure Kotlin, JVM-testable.
 */
class SimulatedCallSource(
    override val audioInput: AudioInputSource? = null,
    override val callAudioSource: CallAudioSource? = null,
    override val responseSink: CallResponseAudioSink = LocalPlaybackResponseSink(),
    private val now: () -> Long = System::currentTimeMillis,
) : ExternalCallAdapter {

    override val transport: CallTransport = CallTransport.SIMULATED

    private val _callState = MutableStateFlow<ExternalCallSnapshot?>(null)
    override val callState: StateFlow<ExternalCallSnapshot?> = _callState.asStateFlow()

    override val controls: CallControlGateway = object : CallControlGateway {
        override suspend fun answer(): Boolean {
            val current = _callState.value ?: return false
            if (current.state != ExternalCallState.RINGING) return false
            _callState.value = current.copy(state = ExternalCallState.ACTIVE)
            return true
        }

        override suspend fun reject(): Boolean = end()

        override suspend fun hangUp(): Boolean = end()

        private fun end(): Boolean {
            val current = _callState.value ?: return false
            if (current.state == ExternalCallState.ENDED) return true
            _callState.value = current.copy(state = ExternalCallState.ENDED)
            return true
        }
    }

    /** Dev/test entry: raise a simulated inbound call. Ignored if a call is already active. */
    fun ringIncoming(
        callId: String = UUID.randomUUID().toString(),
        callerNumber: String? = null,
        callerName: String? = null,
    ): Boolean {
        if (_callState.value != null) return false
        _callState.value = ExternalCallSnapshot(
            callId = callId,
            state = ExternalCallState.RINGING,
            callerNumber = callerNumber,
            callerName = callerName,
            isIncoming = true,
            startedAtMillis = now(),
        )
        return true
    }

    /** Reset to idle (no active call). */
    fun clear() {
        _callState.value = null
    }
}
