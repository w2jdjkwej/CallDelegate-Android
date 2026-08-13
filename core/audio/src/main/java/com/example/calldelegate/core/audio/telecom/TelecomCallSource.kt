package com.example.calldelegate.core.audio.telecom

import com.example.calldelegate.domain.api.AudioInputSource
import com.example.calldelegate.domain.api.CallAudioSource
import com.example.calldelegate.domain.api.CallControlGateway
import com.example.calldelegate.domain.api.CallResponseAudioSink
import com.example.calldelegate.domain.api.CallTransport
import com.example.calldelegate.domain.api.ExternalCallAdapter
import com.example.calldelegate.domain.api.ExternalCallSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * TELECOM transport behind the [ExternalCallAdapter] seam. State + controls delegate to
 * [TelecomCallRegistry] (fed by the InCallService).
 *
 * On the validated Android 16 target, the app-side Shizuku bridge supplies decoded 16000 Hz mono
 * PCM and a privileged call-uplink sink. Both capabilities remain device/ROM dependent: their
 * concrete implementations must return explicit failures rather than silently using the mic or
 * local speaker.
 */
class TelecomCallSource(
    private val registry: TelecomCallRegistry,
    override val callAudioSource: CallAudioSource,
    override val audioInput: AudioInputSource,
    override val responseSink: CallResponseAudioSink,
) : ExternalCallAdapter {

    override val transport: CallTransport = CallTransport.TELECOM

    override val callState: StateFlow<ExternalCallSnapshot?> = registry.callState

    override val controls: CallControlGateway = object : CallControlGateway {
        override suspend fun answer(): Boolean = registry.answer()
        override suspend fun reject(): Boolean = registry.reject()
        override suspend fun hangUp(): Boolean = registry.hangUp()
    }

}
