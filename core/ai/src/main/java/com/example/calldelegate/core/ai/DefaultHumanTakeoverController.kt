package com.example.calldelegate.core.ai

import com.example.calldelegate.domain.api.HumanTakeoverController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DefaultHumanTakeoverController : HumanTakeoverController {
    private val mutableRequested = MutableStateFlow(false)
    override val requested: StateFlow<Boolean> = mutableRequested.asStateFlow()
    var lastRequestedSessionId: String? = null
        private set

    override suspend fun request(sessionId: String) {
        lastRequestedSessionId = sessionId
        mutableRequested.value = true
    }

    override suspend fun clear() {
        lastRequestedSessionId = null
        mutableRequested.value = false
    }
}
