package com.example.calldelegate.core.audio

import com.example.calldelegate.domain.api.AudioInputSource
import com.example.calldelegate.domain.api.CallAudioSource
import com.example.calldelegate.domain.api.CallControlGateway
import com.example.calldelegate.domain.api.CallResponseAudioSink
import com.example.calldelegate.domain.api.CallResponseResult
import com.example.calldelegate.domain.api.CallTransport
import com.example.calldelegate.domain.api.ExternalCallAdapter
import com.example.calldelegate.domain.api.ExternalCallSnapshot
import com.example.calldelegate.domain.model.SynthesizedSpeech
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Compile-safe phase-three seam. Network signaling and media transport are intentionally absent.
 * Registered as the VOIP candidate in the transport router so the routing surface is complete, but
 * it carries no media yet: [callAudioSource] is null and [responseSink] reports Unsupported.
 */
class ExampleVoIPAdapter : ExternalCallAdapter {
    override val transport: CallTransport = CallTransport.VOIP
    override val callState = MutableStateFlow<ExternalCallSnapshot?>(null)
    override val controls: CallControlGateway = UnsupportedControls(callState)
    override val audioInput: AudioInputSource? = null
    override val callAudioSource: CallAudioSource? = null
    override val responseSink: CallResponseAudioSink = UnsupportedResponseSink()
}

private class UnsupportedControls(
    private val state: MutableStateFlow<ExternalCallSnapshot?>,
) : CallControlGateway {
    override suspend fun answer(): Boolean = false
    override suspend fun reject(): Boolean = false
    override suspend fun hangUp(): Boolean {
        state.value = null
        return false
    }
}

private class UnsupportedResponseSink : CallResponseAudioSink {
    override suspend fun playToCall(callId: String, speech: SynthesizedSpeech): CallResponseResult =
        CallResponseResult.Unsupported("VoIP 媒体链路尚未连接")
}
