package com.example.calldelegate.core.ai.coordination

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.AudioInputSource
import com.example.calldelegate.domain.api.CallTransport
import com.example.calldelegate.domain.api.CallResponseAudioSink
import com.example.calldelegate.domain.api.ExternalCallAdapter
import com.example.calldelegate.domain.api.ExternalCallSnapshot
import com.example.calldelegate.domain.coordination.CaptureGate
import com.example.calldelegate.domain.coordination.CoordinatedCall
import com.example.calldelegate.domain.coordination.CoordinatedPhase
import com.example.calldelegate.domain.coordination.CoordinatedPhaseMapper
import com.example.calldelegate.domain.coordination.CallTransportRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transport-agnostic call orchestrator. Given a [CallTransportRouter], it:
 *  - selects a transport and observes its single-active-call state,
 *  - maps telephony state to a coarse [CoordinatedPhase],
 *  - starts the continuous [callAudioSource][ExternalCallAdapter.callAudioSource] on ACTIVE and
 *    stops it on ENDED/FAILED (so hanging up cancels capture — "挂断取消"),
 *  - exposes a [CaptureGate] the turn loop honors so no capture turn overlaps TTS ("TTS 期间停采").
 *
 * Scope discipline: the observe loop runs in the injected [scope] (no GlobalScope). Capture is
 * always stopped via [NonCancellable] in the loop's `finally`, so cancellation/hangup still releases
 * the recorder. Device-independent (domain interfaces + coroutines only) → JVM-testable with fakes.
 *
 * MVP single-active-call: [start] cancels any prior observation before beginning a new transport.
 */
class ExternalCallCoordinator(
    private val router: CallTransportRouter,
    private val scope: CoroutineScope,
    private val onCaptureError: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<CoordinatedCall?>(null)
    val state: StateFlow<CoordinatedCall?> = _state.asStateFlow()

    /** Closed while the assistant speaks; the turn loop awaits it before capturing. */
    val captureGate: CaptureGate = CaptureGate()

    private var observeJob: Job? = null
    private var activeAdapter: ExternalCallAdapter? = null
    // Touched only by the single observe coroutine.
    private var capturingCallId: String? = null

    /** Route to [transport] and begin observing its call state. Cancels any prior observation. */
    fun start(transport: CallTransport): ExternalCallAdapter {
        val adapter = router.select(transport)
        activeAdapter = adapter
        observeJob?.cancel()
        observeJob = scope.launch {
            try {
                adapter.callState.collect { snapshot -> onSnapshot(adapter, snapshot) }
            } finally {
                withContext(NonCancellable) { stopCapture(adapter) }
            }
        }
        return adapter
    }

    suspend fun answer(): Boolean = activeAdapter?.controls?.answer() ?: false
    suspend fun reject(): Boolean = activeAdapter?.controls?.reject() ?: false
    suspend fun hangUp(): Boolean = activeAdapter?.controls?.hangUp() ?: false

    /** Turn-based audio source of the active transport, or null when the transport carries no audio. */
    val activeAudioInput: AudioInputSource?
        get() = activeAdapter?.audioInput

    /** Transport-specific assistant audio output for the active call. */
    val activeResponseSink: CallResponseAudioSink?
        get() = activeAdapter?.responseSink

    /** Assistant started speaking: close the capture gate so no turn overlaps TTS. */
    fun beginSpeaking() = captureGate.pause()

    /** Assistant finished speaking: reopen the capture gate for the next turn. */
    fun endSpeaking() = captureGate.resume()

    /**
     * Stop observing, release capture, and clear routing/state. Capture is released by the observe
     * job's `finally` (which reads [capturingCallId]); call [shutdown] before switching transports.
     */
    fun shutdown() {
        observeJob?.cancel()
        observeJob = null
        activeAdapter = null
        router.clear()
        _state.value = null
    }

    private suspend fun onSnapshot(adapter: ExternalCallAdapter, snapshot: ExternalCallSnapshot?) {
        if (snapshot == null) {
            stopCapture(adapter)
            // Preserve the terminal phase for observers; the call simply disappeared.
            _state.value = _state.value?.copy(callId = null, phase = CoordinatedPhase.ENDED)
            return
        }
        val phase = CoordinatedPhaseMapper.map(snapshot.state)
        when (phase) {
            CoordinatedPhase.ACTIVE -> startCapture(adapter, snapshot.callId)
            CoordinatedPhase.ENDED, CoordinatedPhase.FAILED -> stopCapture(adapter)
            else -> Unit
        }
        _state.value = CoordinatedCall(
            transport = adapter.transport,
            callId = snapshot.callId,
            phase = phase,
            callerNumber = snapshot.callerNumber,
            // AI can answer when a turn-based audio source exists (mic for SIMULATED, stream wrapper
            // for VOIP). Independent of [callAudioSource], which only governs continuous capture.
            audioAvailableForAi = adapter.audioInput != null,
        )
    }

    private suspend fun startCapture(adapter: ExternalCallAdapter, callId: String) {
        val source = adapter.callAudioSource ?: return
        if (capturingCallId == callId) return // idempotent for repeated ACTIVE emissions
        stopCapture(adapter) // release any stale capture from a previous call id
        when (val result = source.start(callId)) {
            is AppResult.Success -> {
                capturingCallId = callId
                captureGate.resume()
            }
            is AppResult.Failure -> onCaptureError(result.error.userMessage)
        }
    }

    private suspend fun stopCapture(adapter: ExternalCallAdapter) {
        val source = adapter.callAudioSource ?: return
        val id = capturingCallId ?: return
        capturingCallId = null
        source.stop(id)
    }
}
