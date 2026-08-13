package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.RecognitionComputeMetrics
import com.example.calldelegate.domain.api.RecognitionAttemptsMetricsSource
import com.example.calldelegate.domain.api.RecognitionMetricsSource
import com.example.calldelegate.domain.api.SpeechRecognizer
import com.example.calldelegate.domain.api.SpeechRecognitionContext
import com.example.calldelegate.domain.api.StreamingSpeechRecognitionSession
import com.example.calldelegate.domain.api.StreamingSpeechRecognizer
import com.example.calldelegate.domain.model.CapturedAudio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ConfigurableSpeechModule {
    val isMock: Boolean
    suspend fun configure(mockMode: Boolean): AppResult<Unit>
}

class SwitchingSpeechRecognizer(
    private val mock: SpeechRecognizer,
    private val real: SpeechRecognizer,
) : SpeechRecognizer, StreamingSpeechRecognizer, ConfigurableSpeechModule, RecognitionMetricsSource,
    RecognitionAttemptsMetricsSource, VoskAlternativesExperimentController {
    private val mutex = Mutex()
    @Volatile private var selected: SpeechRecognizer = mock
    private val unavailableMetrics = MutableStateFlow<RecognitionComputeMetrics?>(null)
    override val latestRecognitionMetrics: StateFlow<RecognitionComputeMetrics?> =
        (real as? RecognitionMetricsSource)?.latestRecognitionMetrics ?: unavailableMetrics
    private val unavailableAttempts = MutableStateFlow<List<RecognitionComputeMetrics>>(emptyList())
    override val latestRecognitionAttempts: StateFlow<List<RecognitionComputeMetrics>> =
        (real as? RecognitionAttemptsMetricsSource)?.latestRecognitionAttempts ?: unavailableAttempts
    override var isMock: Boolean = true
        private set
    override val supportsStreamingRecognition: Boolean
        get() = !isMock && (selected as? StreamingSpeechRecognizer)?.supportsStreamingRecognition == true

    override suspend fun configure(mockMode: Boolean): AppResult<Unit> = mutex.withLock {
        selectLocked(mockMode, forceReload = false)
        selected.initialize()
    }

    suspend fun select(mockMode: Boolean, forceReload: Boolean = false): AppResult<Unit> = mutex.withLock {
        selectLocked(mockMode, forceReload)
        AppResult.Success(Unit)
    }

    override suspend fun initialize(): AppResult<Unit> = mutex.withLock { selected.initialize() }

    override suspend fun recognize(audio: CapturedAudio) = mutex.withLock { selected.recognize(audio) }

    override suspend fun recognize(audio: CapturedAudio, context: SpeechRecognitionContext) =
        mutex.withLock { selected.recognize(audio, context) }

    override suspend fun openStreamingRecognition(
        sampleRateHz: Int,
        context: SpeechRecognitionContext,
    ): AppResult<StreamingSpeechRecognitionSession> = mutex.withLock {
        val streaming = selected as? StreamingSpeechRecognizer
            ?: return@withLock AppResult.Failure(
                AppError("ASR_STREAMING_UNSUPPORTED", "当前语音识别器不支持流式识别"),
            )
        streaming.openStreamingRecognition(sampleRateHz, context)
    }

    override suspend fun release() = mutex.withLock {
        selected.release()
    }

    override fun setMaxAlternativesOverride(maxAlternatives: Int?) {
        (real as? VoskAlternativesExperimentController)?.setMaxAlternativesOverride(maxAlternatives)
    }

    private suspend fun selectLocked(mockMode: Boolean, forceReload: Boolean) {
        val next = if (mockMode) mock else real
        if (selected !== next || forceReload) selected.release()
        selected = next
        isMock = mockMode
    }
}
