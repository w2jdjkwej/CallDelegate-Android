package com.example.calldelegate.core.audio.telecom

import com.example.calldelegate.domain.api.ExternalCallSnapshot
import com.example.calldelegate.domain.api.ExternalCallState
import com.example.calldelegate.domain.telecom.TelecomCallStateMapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide bridge between `android.telecom` InCallService callbacks and the project-internal
 * single-active-call state. `android.telecom.Call` is wrapped behind [TelecomCallHandle], so this
 * class is pure Kotlin and its policy logic is JVM-testable.
 *
 * Single-active-call policy (MVP): only ONE call may drive the takeover flow at a time; any second
 * concurrent call is explicitly rejected. Control operations validate the target callId so call A
 * can never act on call B.
 */
class TelecomCallRegistry(
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val _callState = MutableStateFlow<ExternalCallSnapshot?>(null)
    val callState: StateFlow<ExternalCallSnapshot?> = _callState.asStateFlow()

    private var active: TelecomCallHandle? = null
    private var startedAt: Long = 0L

    fun onCallAdded(handle: TelecomCallHandle) {
        synchronized(lock) {
            val current = active
            if (current === handle) return // idempotent add
            if (current != null) {
                // Second concurrent call is unsupported in the MVP: reject it explicitly.
                runCatching { handle.reject() }
                return
            }
            active = handle
            startedAt = now()
            handle.setStateListener { state -> onStateChanged(handle, state) }
            publish(handle, handle.currentState)
        }
    }

    fun onCallRemoved(handle: TelecomCallHandle) {
        synchronized(lock) {
            if (handle !== active) return // A removed -> never touch B
            if (_callState.value?.state != ExternalCallState.ENDED) {
                publish(handle, TelecomCallStateMapper.STATE_DISCONNECTED)
            }
            detach(handle)
        }
    }

    fun answer(): Boolean = withActive { it.answer() }
    fun reject(): Boolean = withActive { it.reject() }
    fun hangUp(): Boolean = withActive { it.disconnect() }

    fun answer(callId: String): Boolean = withActive(callId) { it.answer() }
    fun reject(callId: String): Boolean = withActive(callId) { it.reject() }
    fun hangUp(callId: String): Boolean = withActive(callId) { it.disconnect() }

    val activeCallId: String? get() = synchronized(lock) { active?.id }

    private fun onStateChanged(handle: TelecomCallHandle, state: Int) {
        synchronized(lock) {
            if (handle !== active) return // ignore events from non-active calls (idempotent/safe)
            publish(handle, state)
            if (TelecomCallStateMapper.map(state) == ExternalCallState.ENDED) {
                detach(handle)
            }
        }
    }

    private inline fun withActive(action: (TelecomCallHandle) -> Unit): Boolean {
        val handle = synchronized(lock) { active } ?: return false
        return runCatching { action(handle); true }.getOrDefault(false)
    }

    private inline fun withActive(callId: String, action: (TelecomCallHandle) -> Unit): Boolean {
        val handle = synchronized(lock) { active } ?: return false
        if (handle.id != callId) return false
        return runCatching { action(handle); true }.getOrDefault(false)
    }

    private fun publish(handle: TelecomCallHandle, telecomState: Int) {
        _callState.value = ExternalCallSnapshot(
            callId = handle.id,
            state = TelecomCallStateMapper.map(telecomState),
            callerNumber = handle.callerNumber,
            callerName = null,
            isIncoming = handle.isIncoming,
            startedAtMillis = startedAt,
        )
    }

    private fun detach(handle: TelecomCallHandle) {
        handle.setStateListener(null)
        if (active === handle) {
            active = null
            startedAt = 0L
            _callState.value = null
        }
    }
}
