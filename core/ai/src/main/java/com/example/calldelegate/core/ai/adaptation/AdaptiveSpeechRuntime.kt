package com.example.calldelegate.core.ai.adaptation

import com.example.calldelegate.core.ai.speech.SwitchingSpeechRecognizer
import com.example.calldelegate.core.ai.speech.SwitchingSpeechSynthesizer
import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.DeviceProfileProvider
import com.example.calldelegate.domain.api.SpeechRuntimeInitialization
import com.example.calldelegate.domain.api.SpeechRuntimeManager
import com.example.calldelegate.domain.api.SpeechRecognitionContext
import com.example.calldelegate.domain.api.StreamingSpeechRecognitionSession
import com.example.calldelegate.domain.api.StreamingSpeechRuntimeManager
import com.example.calldelegate.domain.model.BenchmarkStage
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.DeviceTier
import com.example.calldelegate.domain.model.InferenceBenchmarkSample
import com.example.calldelegate.domain.model.InferencePolicy
import com.example.calldelegate.domain.model.ModuleStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AdaptiveSpeechRuntime(
    private val profiles: DeviceProfileProvider,
    private val recognizer: SwitchingSpeechRecognizer,
    private val synthesizer: SwitchingSpeechSynthesizer,
) : SpeechRuntimeManager, StreamingSpeechRuntimeManager {
    private val mutex = Mutex()
    @Volatile override var isMock: Boolean = true
        private set
    override val supportsStreamingRecognition: Boolean
        get() = !isMock && recognizer.supportsStreamingRecognition
    private var asrLoaded = false
    private var ttsLoaded = false
    private var loadedTtsThreadCount: Int? = null
    @Volatile private var activeSessionPolicy: InferencePolicy? = null
    private var pendingTtsReloadReason = "first_load"
    private var lastTtsInitializationDurationMillis = 0L
    private var lastTtsReloadReason = "not_requested"
    private var wavTestBatchResidency = false
    @Volatile private var wavTestTurnDurationUnlimited = false

    override suspend fun configure(mockMode: Boolean): SpeechRuntimeInitialization {
        profiles.refresh()
        return mutex.withLock {
            recognizer.select(mockMode, forceReload = true)
            synthesizer.select(mockMode, forceReload = true)
            asrLoaded = false
            ttsLoaded = false
            loadedTtsThreadCount = null
            activeSessionPolicy = null
            pendingTtsReloadReason = "runtime_configured"
            wavTestBatchResidency = false
            wavTestTurnDurationUnlimited = false
            isMock = mockMode

            if (!mockMode) {
                return@withLock SpeechRuntimeInitialization(
                    asrStatus = ModuleStatus.Deferred("按设备档位在来电或识别前加载"),
                    ttsStatus = ModuleStatus.Deferred("按设备档位在播报前加载"),
                )
            }

            val asr = initializeAsrLocked()
            val tts = initializeTtsLocked(currentPolicy())
            SpeechRuntimeInitialization(
                asrStatus = asr.toStatus(true, "mock"),
                ttsStatus = tts.toStatus(true, "mock"),
            )
        }
    }

    override suspend fun onIncoming() {
        profiles.refresh()
        if (isMock) return
        mutex.withLock {
            if (wavTestBatchResidency) {
                initializeAsrLocked()
                initializeTtsLocked(currentPolicy())
                return@withLock
            }
            freezeCurrentPolicyLocked("incoming_call")
            var policy = currentPolicy()
            reconcilePolicyLocked(policy)
            if (policy.preloadAsrOnIncoming) initializeAsrLocked()
            policy = currentPolicy()
            reconcilePolicyLocked(policy)
            if (policy.preloadTtsOnIncoming) initializeTtsLocked(policy)
            enforceIncomingResidencyLocked(currentPolicy())
        }
    }

    override suspend fun recognize(audio: CapturedAudio) = recognize(audio, SpeechRecognitionContext())

    override suspend fun recognize(
        audio: CapturedAudio,
        context: SpeechRecognitionContext,
    ) = withRefreshedProfile {
        val policy = currentPolicy()
        if (!wavTestBatchResidency) reconcilePolicyLocked(policy)
        if (!wavTestBatchResidency && (policy.releaseTtsAfterSynthesis || policy.releaseAsrBeforeTts)) {
            releaseTtsLocked("asr_stage_switch")
        }
        when (val initialized = initializeAsrLocked()) {
            is AppResult.Failure -> initialized
            is AppResult.Success -> {
                if (!wavTestBatchResidency) enforceActiveModelLocked(activeAsr = true, policy = currentPolicy())
                val started = System.nanoTime()
                val result = recognizer.recognize(audio, context)
                recordResult(
                    BenchmarkStage.ASR_INFERENCE,
                    started,
                    result,
                    ASR_NON_BENCHMARK_ERRORS,
                    ASR_COMPLETED_ERRORS,
                )
                if (!wavTestBatchResidency) enforceActiveModelLocked(activeAsr = true, policy = currentPolicy())
                result
            }
        }
    }

    override suspend fun openStreamingRecognition(
        sampleRateHz: Int,
        context: SpeechRecognitionContext,
    ): AppResult<StreamingSpeechRecognitionSession> {
        profiles.refresh()
        return mutex.withLock {
            if (!supportsStreamingRecognition) {
                return@withLock AppResult.Failure(
                    AppError("ASR_STREAMING_UNSUPPORTED", "当前运行模式不支持流式识别"),
                )
            }
            val policy = currentPolicy()
            if (!wavTestBatchResidency) reconcilePolicyLocked(policy)
            if (!wavTestBatchResidency && (policy.releaseTtsAfterSynthesis || policy.releaseAsrBeforeTts)) {
                releaseTtsLocked("asr_stage_switch")
            }
            when (val initialized = initializeAsrLocked()) {
                is AppResult.Failure -> initialized
                is AppResult.Success -> {
                    if (!wavTestBatchResidency) {
                        enforceActiveModelLocked(activeAsr = true, policy = currentPolicy())
                    }
                    when (val opened = recognizer.openStreamingRecognition(sampleRateHz, context)) {
                        is AppResult.Failure -> opened
                        is AppResult.Success -> AppResult.Success(AdaptiveStreamingSession(opened.value))
                    }
                }
            }
        }
    }

    override suspend fun synthesize(text: String, sessionId: String) = withRefreshedProfile {
        val policy = currentPolicy()
        if (!wavTestBatchResidency) reconcilePolicyLocked(policy)
        if (!wavTestBatchResidency && policy.releaseAsrBeforeTts) releaseAsrLocked()
        when (val initialized = initializeTtsLocked(policy)) {
            is AppResult.Failure -> initialized
            is AppResult.Success -> {
                if (!wavTestBatchResidency) {
                    val effectivePolicy = currentPolicy()
                    if (loadedTtsThreadCount != effectivePolicy.ttsThreadCount) {
                        when (val reconfigured = initializeTtsLocked(effectivePolicy)) {
                            is AppResult.Failure -> return@withRefreshedProfile reconfigured
                            is AppResult.Success -> Unit
                        }
                    }
                    enforceActiveModelLocked(activeAsr = false, policy = effectivePolicy)
                }
                val started = System.nanoTime()
                val result = synthesizer.synthesize(text, sessionId)
                recordResult(BenchmarkStage.TTS_GENERATION, started, result, TTS_NON_BENCHMARK_ERRORS)
                logTtsBreakdown(text.length, result is AppResult.Success)
                if (!wavTestBatchResidency && currentPolicy().releaseTtsAfterSynthesis) {
                    releaseTtsLocked("release_after_synthesis")
                }
                result
            }
        }
    }

    override suspend fun onSessionEnded() {
        profiles.refresh()
        if (isMock) return
        mutex.withLock {
            if (wavTestBatchResidency) return@withLock
            val policy = currentPolicy()
            if (policy.releaseAsrOnSessionEnd) releaseAsrLocked()
            if (policy.releaseTtsOnSessionEnd) releaseTtsLocked("session_end")
            activeSessionPolicy = null
            reconcilePolicyLocked(profiles.profile.value.policy)
        }
    }

    override suspend fun releaseAll() = mutex.withLock {
        wavTestBatchResidency = false
        wavTestTurnDurationUnlimited = false
        activeSessionPolicy = null
        releaseAsrLocked()
        releaseTtsLocked("release_all")
    }

    /**
     * Debug WAV-runner hook. It reserves both existing real speech handles for one batch and is
     * intentionally unavailable through the public [SpeechRuntimeManager] interface.
     */
    internal suspend fun beginWavTestBatch(
        disableMaxTurnDuration: Boolean = false,
    ): AppResult<Unit> {
        profiles.refresh()
        return mutex.withLock {
            if (isMock) {
                return@withLock AppResult.Failure(AppError("WAV_BATCH_MOCK", "WAV 测试不能使用 Mock 语音运行时"))
            }
            val policy = currentPolicy()
            if (!policy.allowConcurrentSpeechModels) {
                return@withLock AppResult.Failure(
                    AppError(
                        "WAV_BATCH_RESIDENCY_UNSUPPORTED",
                        "当前设备策略不允许 ASR 与 TTS 在整个 WAV 测试批次中同时驻留",
                    ),
                )
            }
            freezeCurrentPolicyLocked("wav_test_batch")
            wavTestBatchResidency = true
            wavTestTurnDurationUnlimited = disableMaxTurnDuration
            when (val asr = initializeAsrLocked()) {
                is AppResult.Failure -> {
                    wavTestBatchResidency = false
                    wavTestTurnDurationUnlimited = false
                    activeSessionPolicy = null
                    return@withLock asr
                }
                is AppResult.Success -> Unit
            }
            when (val tts = initializeTtsLocked(policy)) {
                is AppResult.Failure -> {
                    wavTestBatchResidency = false
                    wavTestTurnDurationUnlimited = false
                    activeSessionPolicy = null
                    releaseAsrLocked()
                    return@withLock tts
                }
                is AppResult.Success -> AppResult.Success(Unit)
            }
        }
    }

    internal suspend fun endWavTestBatch() = mutex.withLock {
        wavTestBatchResidency = false
        wavTestTurnDurationUnlimited = false
        activeSessionPolicy = null
        releaseAsrLocked()
        releaseTtsLocked("wav_test_batch_end")
    }

    override fun currentPolicy(): InferencePolicy {
        val latestProfile = profiles.profile.value
        val frozenPolicy = activeSessionPolicy
        val policy = when {
            frozenPolicy == null -> latestProfile.policy
            latestProfile.tier == DeviceTier.LOW -> {
                // A safety downgrade applies immediately and remains frozen for the rest of the
                // session. A later recovery must not rebuild TTS again in the same call.
                activeSessionPolicy = latestProfile.policy
                latestProfile.policy
            }
            else -> frozenPolicy
        }
        return if (wavTestTurnDurationUnlimited) {
            policy.copy(maxTurnDurationMillis = 0L)
        } else {
            policy
        }
    }

    private suspend fun initializeAsrLocked(): AppResult<Unit> {
        if (asrLoaded) return AppResult.Success(Unit)
        val started = System.nanoTime()
        val result = recognizer.initialize()
        asrLoaded = result is AppResult.Success
        if (!isMock) record(BenchmarkStage.ASR_INITIALIZATION, started, result is AppResult.Success)
        return result
    }

    private suspend fun initializeTtsLocked(policy: InferencePolicy): AppResult<Unit> {
        if (ttsLoaded && (wavTestBatchResidency || loadedTtsThreadCount == policy.ttsThreadCount)) {
            lastTtsInitializationDurationMillis = 0L
            lastTtsReloadReason = "resident"
            return AppResult.Success(Unit)
        }
        if (ttsLoaded) releaseTtsLocked(
            "thread_count_changed_${loadedTtsThreadCount ?: -1}_to_${policy.ttsThreadCount}",
        )
        val existingThreadCount = synthesizer.activeThreadCount
        val existingInstanceId = synthesizer.activeEngineInstanceId
        val reloadReason = when {
            existingInstanceId == null -> pendingTtsReloadReason
            existingThreadCount == policy.ttsThreadCount -> "adopt_prewarmed_engine"
            else -> "prewarmed_thread_count_changed_${existingThreadCount ?: -1}_to_${policy.ttsThreadCount}"
        }
        val started = System.nanoTime()
        val result = synthesizer.initialize(policy.ttsThreadCount)
        val elapsedMillis = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
        ttsLoaded = result is AppResult.Success
        loadedTtsThreadCount = synthesizer.activeThreadCount.takeIf { ttsLoaded } ?: policy.ttsThreadCount.takeIf { ttsLoaded }
        lastTtsInitializationDurationMillis = elapsedMillis
        lastTtsReloadReason = reloadReason
        pendingTtsReloadReason = "released"
        logInfo(
            "tts init: duration=${elapsedMillis}ms reason=$reloadReason " +
                "threads=${loadedTtsThreadCount ?: -1} instance=${synthesizer.activeEngineInstanceId ?: -1} " +
                "success=${result is AppResult.Success}",
        )
        if (!isMock) record(BenchmarkStage.TTS_INITIALIZATION, started, result is AppResult.Success)
        return result
    }

    private suspend fun reconcilePolicyLocked(policy: InferencePolicy) {
        if (ttsLoaded && loadedTtsThreadCount != policy.ttsThreadCount) {
            releaseTtsLocked("thread_count_changed_${loadedTtsThreadCount ?: -1}_to_${policy.ttsThreadCount}")
        }
    }

    private suspend fun enforceIncomingResidencyLocked(policy: InferencePolicy) {
        if (!policy.preloadAsrOnIncoming) releaseAsrLocked()
        if (!policy.preloadTtsOnIncoming) releaseTtsLocked("incoming_residency_policy")
        if (!policy.allowConcurrentSpeechModels && asrLoaded && ttsLoaded) releaseAsrLocked()
    }

    /** Re-apply a policy that may have changed after initialization or a measured inference. */
    private suspend fun enforceActiveModelLocked(activeAsr: Boolean, policy: InferencePolicy) {
        // Never release the model that is about to run; only reconcile the idle TTS handle.
        if (activeAsr) reconcilePolicyLocked(policy)
        if (policy.allowConcurrentSpeechModels) return
        if (activeAsr) releaseTtsLocked("single_model_residency") else releaseAsrLocked()
    }

    private suspend fun releaseAsrLocked() {
        if (!asrLoaded) return
        try {
            recognizer.release()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The handle is considered unusable even if a native close reports an error.
        } finally {
            asrLoaded = false
        }
    }

    private suspend fun releaseTtsLocked(reason: String) {
        if (!ttsLoaded) return
        try {
            synthesizer.release()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The handle is considered unusable even if a native close reports an error.
        } finally {
            ttsLoaded = false
            loadedTtsThreadCount = null
            pendingTtsReloadReason = reason
        }
    }

    private fun freezeCurrentPolicyLocked(reason: String) {
        if (activeSessionPolicy != null) return
        activeSessionPolicy = profiles.profile.value.policy
        val policy = checkNotNull(activeSessionPolicy)
        logInfo(
            "speech policy frozen: reason=$reason tier=${profiles.profile.value.tier} " +
                "ttsThreads=${policy.ttsThreadCount} concurrent=${policy.allowConcurrentSpeechModels}",
        )
    }

    private fun logTtsBreakdown(replyChars: Int, successful: Boolean) {
        val synthesis = synthesizer.latestSynthesisObservation
        logInfo(
            "tts turn: init=${lastTtsInitializationDurationMillis}ms " +
                "generate=${synthesis?.generationDurationMillis ?: -1}ms " +
                "cache=${synthesis?.cacheSource?.logValue ?: "unknown"} " +
                "cacheLookup=${synthesis?.cacheLookupDurationMillis ?: -1}ms " +
                "persist=${synthesis?.persistenceDurationMillis ?: -1}ms " +
                "synthesisTotal=${synthesis?.totalDurationMillis ?: -1}ms " +
                "reloadReason=$lastTtsReloadReason threads=${synthesis?.threadCount ?: loadedTtsThreadCount ?: -1} " +
                "instance=${synthesis?.engineInstanceId ?: synthesizer.activeEngineInstanceId ?: -1} " +
                "replyChars=$replyChars success=$successful",
        )
    }

    private fun logInfo(message: String) {
        runCatching { android.util.Log.i("TtsPipeline", message) }
    }

    private suspend fun <T> withRefreshedProfile(block: suspend () -> AppResult<T>): AppResult<T> {
        profiles.refresh()
        return mutex.withLock { block() }
    }

    private suspend fun <T> recordResult(
        stage: BenchmarkStage,
        startedNanos: Long,
        result: AppResult<T>,
        ignoredErrors: Set<String>,
        completedErrors: Set<String> = emptySet(),
    ) {
        if (isMock) return
        if (result is AppResult.Failure && result.error.code in ignoredErrors) return
        val completed = result is AppResult.Success ||
            (result is AppResult.Failure && result.error.code in completedErrors)
        record(stage, startedNanos, completed)
    }

    private suspend fun record(stage: BenchmarkStage, startedNanos: Long, successful: Boolean) {
        val elapsedMillis = ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0L)
        recordDuration(stage, elapsedMillis, successful)
    }

    private suspend fun recordDuration(stage: BenchmarkStage, elapsedMillis: Long, successful: Boolean) {
        try {
            profiles.recordBenchmark(InferenceBenchmarkSample(stage, elapsedMillis, successful))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Benchmark persistence must never break the offline speech path.
        }
    }

    private inner class AdaptiveStreamingSession(
        private val delegate: StreamingSpeechRecognitionSession,
    ) : StreamingSpeechRecognitionSession {
        override val recognizerId: String?
            get() = delegate.recognizerId

        override suspend fun accept(samples: ShortArray): AppResult<Unit> = delegate.accept(samples)

        override suspend fun snapshot() = delegate.snapshot()

        override suspend fun finish(speechDetected: Boolean): AppResult<com.example.calldelegate.domain.model.RecognitionResult> {
            val result = delegate.finish(speechDetected)
            recordStreamingResult(result)
            enforceStreamingResidency()
            return result
        }

        override suspend fun cancel() {
            delegate.cancel()
            enforceStreamingResidency()
        }

        private suspend fun recordStreamingResult(
            result: AppResult<com.example.calldelegate.domain.model.RecognitionResult>,
        ) {
            if (isMock) return
            if (result is AppResult.Failure && result.error.code in ASR_NON_BENCHMARK_ERRORS) return
            val durationMillis = recognizer.latestRecognitionMetrics.value?.computeDurationMillis ?: return
            val completed = result is AppResult.Success ||
                (result is AppResult.Failure && result.error.code in ASR_COMPLETED_ERRORS)
            recordDuration(BenchmarkStage.ASR_INFERENCE, durationMillis, completed)
        }

        private suspend fun enforceStreamingResidency() = mutex.withLock {
            if (!wavTestBatchResidency) {
                enforceActiveModelLocked(activeAsr = true, policy = currentPolicy())
            }
        }
    }

    private companion object {
        val ASR_NON_BENCHMARK_ERRORS = setOf(
            "ASR_SILENCE", "ASR_EMPTY_AUDIO", "ASR_SAMPLE_RATE",
        )
        val ASR_COMPLETED_ERRORS = setOf("ASR_UNRECOGNIZABLE")
        val TTS_NON_BENCHMARK_ERRORS = setOf("TTS_EMPTY_TEXT")
    }
}

private fun AppResult<Unit>.toStatus(mockMode: Boolean, version: String): ModuleStatus = when (this) {
    is AppResult.Failure -> ModuleStatus.Error(error.userMessage)
    is AppResult.Success -> if (mockMode) ModuleStatus.MockReady else ModuleStatus.RealReady(version)
}
