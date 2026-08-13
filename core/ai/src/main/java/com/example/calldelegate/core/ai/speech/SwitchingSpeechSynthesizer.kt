package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.SpeechSynthesizer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SwitchingSpeechSynthesizer(
    private val mock: SpeechSynthesizer,
    private val real: SpeechSynthesizer,
) : SpeechSynthesizer, ConfigurableSpeechModule {
    private val mutex = Mutex()
    private var selected: SpeechSynthesizer = mock
    override var isMock: Boolean = true
        private set

    override suspend fun configure(mockMode: Boolean): AppResult<Unit> = mutex.withLock {
        selectLocked(mockMode, forceReload = false)
        selected.initialize()
    }

    suspend fun select(mockMode: Boolean, forceReload: Boolean = false): AppResult<Unit> = mutex.withLock {
        selectLocked(mockMode, forceReload)
        AppResult.Success(Unit)
    }

    override suspend fun initialize(): AppResult<Unit> = mutex.withLock { selected.initialize() }

    internal suspend fun initialize(threadCount: Int): AppResult<Unit> = mutex.withLock {
        val active = selected
        if (active is SherpaSpeechSynthesizer) {
            active.initializeForThreadCount(threadCount)
        } else {
            active.initialize()
        }
    }

    internal val latestSynthesisObservation: TtsSynthesisObservation?
        get() = (selected as? SherpaSpeechSynthesizer)?.latestSynthesisObservation

    internal val activeEngineInstanceId: Long?
        get() = (selected as? SherpaSpeechSynthesizer)?.activeEngineInstanceId

    internal val activeThreadCount: Int?
        get() = (selected as? SherpaSpeechSynthesizer)?.activeThreadCount

    override suspend fun synthesize(text: String, sessionId: String) =
        mutex.withLock { selected.synthesize(text, sessionId) }

    override suspend fun release() = mutex.withLock { selected.release() }

    private suspend fun selectLocked(mockMode: Boolean, forceReload: Boolean) {
        val next = if (mockMode) mock else real
        if (selected !== next || forceReload) selected.release()
        selected = next
        isMock = mockMode
    }
}
