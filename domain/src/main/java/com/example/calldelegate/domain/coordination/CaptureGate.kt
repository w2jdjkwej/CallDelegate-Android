package com.example.calldelegate.domain.coordination

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * A simple open/closed gate the coordinator closes while the assistant is speaking so a turn-based
 * capture never starts on top of TTS ("TTS 期间停采" — avoids the assistant recognizing its own
 * reply / echo). The turn loop calls [awaitOpen] before starting a capture turn.
 *
 * Pure Kotlin, JVM-testable. Starts open.
 */
class CaptureGate {
    private val _open = MutableStateFlow(true)
    val isOpen: StateFlow<Boolean> = _open.asStateFlow()

    /** Close the gate (assistant started speaking). Idempotent. */
    fun pause() { _open.value = false }

    /** Open the gate (assistant finished speaking). Idempotent. */
    fun resume() { _open.value = true }

    /** Suspends until the gate is open. Returns immediately when already open. */
    suspend fun awaitOpen() {
        _open.first { it }
    }
}
