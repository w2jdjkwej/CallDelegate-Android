package com.example.calldelegate.core.audio

import com.example.calldelegate.domain.api.CallResponseAudioSink
import com.example.calldelegate.domain.api.CallResponseResult
import com.example.calldelegate.domain.model.SynthesizedSpeech

/**
 * Honest response sink for transports without a controllable uplink (SIMULATED). It never claims the
 * remote party heard the audio: callers should render it on the local speaker and treat the result
 * as [CallResponseResult.LocalPlaybackOnly].
 */
class LocalPlaybackResponseSink : CallResponseAudioSink {
    override suspend fun playToCall(callId: String, speech: SynthesizedSpeech): CallResponseResult =
        CallResponseResult.LocalPlaybackOnly
}
