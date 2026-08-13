package com.example.calldelegate.domain.coordination

import com.example.calldelegate.domain.api.CallTransport
import com.example.calldelegate.domain.api.ExternalCallAdapter
import com.example.calldelegate.domain.api.ExternalCallSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * Runtime selector across the available [ExternalCallAdapter] transports. Hilt binds all candidate
 * implementations as @Singleton, which is fixed at build time; this router adds the missing runtime
 * dimension by choosing WHICH transport is currently active (a plain @Singleton binding cannot swap
 * itself at runtime).
 *
 * Pure Kotlin (no Android types), so selection policy is JVM-testable.
 */
class CallTransportRouter(adapters: Set<ExternalCallAdapter>) {

    private val byTransport: Map<CallTransport, ExternalCallAdapter> = buildMap {
        adapters.forEach { adapter ->
            require(!containsKey(adapter.transport)) {
                "Duplicate ExternalCallAdapter for transport ${adapter.transport}"
            }
            put(adapter.transport, adapter)
        }
    }

    private val _activeTransport = MutableStateFlow<CallTransport?>(null)
    val activeTransport: StateFlow<CallTransport?> = _activeTransport.asStateFlow()

    fun available(): Set<CallTransport> = byTransport.keys

    fun adapter(transport: CallTransport): ExternalCallAdapter? = byTransport[transport]

    val active: ExternalCallAdapter?
        get() = _activeTransport.value?.let { byTransport[it] }

    /** Marks [transport] active and returns its adapter. Throws if the transport is not registered. */
    fun select(transport: CallTransport): ExternalCallAdapter {
        val adapter = byTransport[transport]
            ?: throw IllegalArgumentException("No ExternalCallAdapter registered for $transport")
        _activeTransport.value = transport
        return adapter
    }

    fun clear() {
        _activeTransport.value = null
    }

    /** Reactive single-active-call state of whichever transport is currently selected. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun activeCallState(): Flow<ExternalCallSnapshot?> =
        activeTransport.flatMapLatest { transport ->
            transport?.let { byTransport[it]?.callState } ?: flowOf(null)
        }
}
