package com.example.calldelegate.domain.api

import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.session.CallSessionSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * Answers with the assistant over whichever audio path the call in front of the user actually has.
 *
 * The button that answers a call cannot choose that path itself. Calling
 * [CallSessionController.acceptWithAi] unconditionally is what made the assistant inaudible to the
 * far end of a real call: it opens a microphone session, which has no uplink to speak into, so the
 * reply was played to the handset's own speaker and nowhere else. Whether a telephony call with
 * usable audio exists is known to the layer that owns the transports, not to the screen.
 */
interface AiAnswerRouter {
    /** @param microphoneFallback the input mode to use when no call transport carries audio. */
    suspend fun acceptWithAi(microphoneFallback: InputMode)
}

interface CallSessionController {
    val state: StateFlow<CallSessionSnapshot>
    suspend fun simulateIncoming(callerName: String?, callerNumber: String)
    suspend fun decline()
    suspend fun acceptNormally()
    suspend fun acceptWithAi(inputMode: InputMode)
    /**
     * Accept a ringing external call with AI and drive it automatically (no UI button presses).
     * [turnAudio] supplies one VAD-segmented buffer per turn.
     */
    suspend fun acceptExternalWithAi(
        turnAudio: AudioInputSource,
        responseRoute: ExternalCallResponseRoute? = null,
    )
    suspend fun setInputMode(mode: InputMode)
    suspend fun submitText(text: String)
    suspend fun submitPreset(presetId: String)
    suspend fun captureMicrophoneTurn()
    suspend fun requestHumanTakeover()
    suspend fun end(reason: String = "user_ended")
    suspend fun reset()
}

/**
 * Optional test and controlled-transport seam for starting a call with already known context.
 *
 * Production callers should only use this when the transport has trustworthy prior-turn state.
 * Evaluation labels must never be converted into a preset implicitly.
 */
interface DialogueContextPresetController {
    suspend fun presetNextDialogueContext(initialScene: SceneType)
}

interface HumanTakeoverController {
    val requested: StateFlow<Boolean>
    suspend fun request(sessionId: String)
    suspend fun clear()
}

// External-call boundary. Generalized from the former VoIP-only seam so that simulated,
// Telecom and (future) VoIP transports share ONE abstraction instead of parallel skeletons.
// The domain layer deliberately contains no SIP/WebRTC/Telecom implementation.
interface ExternalCallAdapter {
    val transport: CallTransport
    /** Single active call for the MVP. Null means no active external call. */
    val callState: StateFlow<ExternalCallSnapshot?>
    val controls: CallControlGateway
    /** Turn-based audio for the existing ASR pipeline; null when the transport has no audio. */
    val audioInput: AudioInputSource?
    /**
     * Continuous raw-PCM source the coordinator starts/stops around the whole call. Null when the
     * transport has no AI-usable audio (e.g. carrier Telecom, where the uplink/downlink is SILENCED
     * for non-privileged apps). [audioInput] typically wraps this stream for turn-based ASR.
     */
    val callAudioSource: CallAudioSource?
    /** Honest call-uplink boundary. May return LocalPlaybackOnly / Unsupported. */
    val responseSink: CallResponseAudioSink
}

/** Marker for an [AudioInputSource] whose PCM originates from an external (non-mic) call. */
interface RemoteAudioInputSource : AudioInputSource

interface CallControlGateway {
    suspend fun answer(): Boolean
    suspend fun reject(): Boolean
    suspend fun hangUp(): Boolean
}

enum class ExternalCallState { IDLE, RINGING, CONNECTING, ACTIVE, HOLDING, ENDED, ERROR }
