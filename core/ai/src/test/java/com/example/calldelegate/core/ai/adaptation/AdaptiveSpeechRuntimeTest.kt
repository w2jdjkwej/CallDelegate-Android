package com.example.calldelegate.core.ai.adaptation

import com.example.calldelegate.core.ai.speech.SwitchingSpeechRecognizer
import com.example.calldelegate.core.ai.speech.SwitchingSpeechSynthesizer
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.DeviceProfileProvider
import com.example.calldelegate.domain.api.SpeechRecognizer
import com.example.calldelegate.domain.api.SpeechSynthesizer
import com.example.calldelegate.domain.model.BenchmarkStage
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.DeviceProfile
import com.example.calldelegate.domain.model.DeviceTier
import com.example.calldelegate.domain.model.InferenceBenchmarkSample
import com.example.calldelegate.domain.model.InferencePolicy
import com.example.calldelegate.domain.model.RecognitionResult
import com.example.calldelegate.domain.model.SynthesizedSpeech
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AdaptiveSpeechRuntimeTest {
    @Test fun lowTierNeverKeepsAsrAndTtsLoadedAcrossStageSwitch() = runTest {
        val events = mutableListOf<String>()
        val profiles = FakeProfiles(DeviceTier.LOW)
        val realAsr = TrackingRecognizer(events)
        val realTts = TrackingSynthesizer(events)
        val runtime = runtime(profiles, realAsr, realTts)
        runtime.configure(mockMode = false)

        runtime.recognize(audio())
        runtime.synthesize("好的", "session")

        assertThat(events).containsExactly(
            "asr_init", "asr_infer", "asr_release", "tts_init", "tts_generate", "tts_release",
        ).inOrder()
        assertThat(realAsr.loaded).isFalse()
        assertThat(realTts.loaded).isFalse()

        assertThat(profiles.samples.map { it.stage.name }).containsAtLeast(
            "ASR_INITIALIZATION", "ASR_INFERENCE", "TTS_INITIALIZATION", "TTS_GENERATION",
        )
    }

    @Test fun highTierPreloadsAndRetainsBothSpeechEngines() = runTest {
        val events = mutableListOf<String>()
        val profiles = FakeProfiles(DeviceTier.HIGH)
        val realAsr = TrackingRecognizer(events)
        val realTts = TrackingSynthesizer(events)
        val runtime = runtime(profiles, realAsr, realTts)
        runtime.configure(mockMode = false)

        runtime.onIncoming()
        runtime.recognize(audio())
        runtime.synthesize("好的", "session")
        runtime.onSessionEnded()

        assertThat(realAsr.loaded).isTrue()
        assertThat(realTts.loaded).isTrue()
        assertThat(events).doesNotContain("asr_release")
        assertThat(events).doesNotContain("tts_release")
    }

    @Test fun wavBatchKeepsRealSpeechEnginesLoadedUntilBatchEnds() = runTest {
        val events = mutableListOf<String>()
        val profiles = FakeProfiles(DeviceTier.HIGH)
        val realAsr = TrackingRecognizer(events)
        val realTts = TrackingSynthesizer(events)
        val runtime = runtime(profiles, realAsr, realTts)
        runtime.configure(mockMode = false)

        assertThat(runtime.beginWavTestBatch()).isInstanceOf(AppResult.Success::class.java)
        runtime.recognize(audio())
        runtime.synthesize("reply", "session")
        runtime.onSessionEnded()

        assertThat(events).containsExactly(
            "asr_init", "tts_init", "asr_infer", "tts_generate",
        ).inOrder()
        assertThat(realAsr.loaded).isTrue()
        assertThat(realTts.loaded).isTrue()

        runtime.endWavTestBatch()

        assertThat(events).containsExactly(
            "asr_init", "tts_init", "asr_infer", "tts_generate", "asr_release", "tts_release",
        ).inOrder()
    }

    @Test fun wavBatchCanDisableOnlyItsOwnTurnDurationLimit() = runTest {
        val runtime = runtime(
            FakeProfiles(DeviceTier.HIGH),
            TrackingRecognizer(mutableListOf()),
            TrackingSynthesizer(mutableListOf()),
        )
        runtime.configure(mockMode = false)

        assertThat(runtime.currentPolicy().maxTurnDurationMillis).isEqualTo(15_000L)
        assertThat(runtime.beginWavTestBatch(disableMaxTurnDuration = true))
            .isInstanceOf(AppResult.Success::class.java)
        assertThat(runtime.currentPolicy().maxTurnDurationMillis).isEqualTo(0L)

        runtime.endWavTestBatch()

        assertThat(runtime.currentPolicy().maxTurnDurationMillis).isEqualTo(15_000L)
    }

    @Test fun wavBatchRejectsLowTierWithoutPretendingToRetainBothEngines() = runTest {
        val events = mutableListOf<String>()
        val runtime = runtime(
            FakeProfiles(DeviceTier.LOW),
            TrackingRecognizer(events),
            TrackingSynthesizer(events),
        )
        runtime.configure(mockMode = false)

        val result = runtime.beginWavTestBatch()

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat((result as AppResult.Failure).error.code).isEqualTo("WAV_BATCH_RESIDENCY_UNSUPPORTED")
        assertThat(events).isEmpty()
    }

    @Test fun wavBatchDoesNotReloadTtsAfterARecordedBenchmarkChangesDevicePolicy() = runTest {
        val events = mutableListOf<String>()
        val profiles = FakeProfiles(DeviceTier.HIGH, transitionOnStage = BenchmarkStage.ASR_INFERENCE)
        val runtime = runtime(
            profiles,
            TrackingRecognizer(events),
            TrackingSynthesizer(events),
        )
        runtime.configure(mockMode = false)

        assertThat(runtime.beginWavTestBatch()).isInstanceOf(AppResult.Success::class.java)
        runtime.recognize(audio())
        runtime.synthesize("reply", "session")

        assertThat(events).containsExactly(
            "asr_init", "tts_init", "asr_infer", "tts_generate",
        ).inOrder()
        runtime.endWavTestBatch()
    }

    @Test fun aBenchmarkDowngradeIsAppliedBeforeTtsGenerationStarts() = runTest {
        val events = mutableListOf<String>()
        val profiles = FakeProfiles(DeviceTier.MEDIUM, transitionOnStage = BenchmarkStage.TTS_INITIALIZATION)
        val realAsr = TrackingRecognizer(events)
        val realTts = TrackingSynthesizer(events)
        val runtime = runtime(profiles, realAsr, realTts)
        runtime.configure(mockMode = false)

        runtime.onIncoming()
        runtime.synthesize("好的", "session")

        assertThat(events).containsExactly(
            "asr_init", "tts_init", "tts_release", "tts_init", "asr_release", "tts_generate", "tts_release",
        ).inOrder()
        assertThat(realAsr.loaded).isFalse()
        assertThat(realTts.loaded).isFalse()

        profiles.setTier(DeviceTier.HIGH)
        assertThat(runtime.currentPolicy().ttsThreadCount)
            .isEqualTo(InferencePolicy.forTier(DeviceTier.LOW).ttsThreadCount)

        runtime.onSessionEnded()
        assertThat(runtime.currentPolicy().ttsThreadCount)
            .isEqualTo(InferencePolicy.forTier(DeviceTier.HIGH).ttsThreadCount)
    }

    @Test fun benchmarkUpgradeIsDeferredUntilTheCallEnds() = runTest {
        val events = mutableListOf<String>()
        val profiles = FakeProfiles(
            tier = DeviceTier.MEDIUM,
            transitionOnStage = BenchmarkStage.ASR_INFERENCE,
            transitionToTier = DeviceTier.HIGH,
        )
        val realTts = TrackingSynthesizer(events)
        val runtime = runtime(profiles, TrackingRecognizer(events), realTts)
        runtime.configure(mockMode = false)

        runtime.onIncoming()
        runtime.synthesize("opening", "session")
        runtime.recognize(audio())
        runtime.synthesize("reply", "session")

        assertThat(runtime.currentPolicy().ttsThreadCount).isEqualTo(2)
        assertThat(events).containsExactly(
            "asr_init", "tts_init", "tts_generate", "asr_infer", "tts_generate",
        ).inOrder()

        runtime.onSessionEnded()

        assertThat(runtime.currentPolicy().ttsThreadCount).isEqualTo(4)
        assertThat(realTts.loaded).isFalse()
        runtime.onIncoming()
        assertThat(realTts.loaded).isTrue()
    }

    private fun runtime(
        profiles: DeviceProfileProvider,
        realAsr: SpeechRecognizer,
        realTts: SpeechSynthesizer,
    ) = AdaptiveSpeechRuntime(
        profiles,
        SwitchingSpeechRecognizer(NoOpRecognizer(), realAsr),
        SwitchingSpeechSynthesizer(NoOpSynthesizer(), realTts),
    )

    private fun audio() = CapturedAudio(shortArrayOf(1, 2), 16_000, 1, null, speechDetected = true)
}

private class FakeProfiles(
    tier: DeviceTier,
    private val transitionOnStage: BenchmarkStage? = null,
    private val transitionToTier: DeviceTier = DeviceTier.LOW,
) : DeviceProfileProvider {
    private val mutable = MutableStateFlow(
        DeviceProfile(
            tier = tier,
            baseTier = tier,
            arm64Supported = true,
            policy = InferencePolicy.forTier(tier),
        ),
    )
    override val profile: StateFlow<DeviceProfile> = mutable
    val samples = mutableListOf<InferenceBenchmarkSample>()
    override suspend fun refresh() = Unit
    fun setTier(tier: DeviceTier) {
        mutable.value = mutable.value.copy(
            tier = tier,
            policy = InferencePolicy.forTier(tier),
        )
    }
    override suspend fun recordBenchmark(sample: InferenceBenchmarkSample) {
        samples += sample
        if (sample.stage == transitionOnStage) {
            mutable.value = mutable.value.copy(
                tier = transitionToTier,
                policy = InferencePolicy.forTier(transitionToTier),
            )
        }
    }
    override suspend fun invalidateBenchmark(reason: String) = Unit
}

private class TrackingRecognizer(private val events: MutableList<String>) : SpeechRecognizer {
    var loaded = false
    override suspend fun initialize(): AppResult<Unit> {
        if (!loaded) events += "asr_init"
        loaded = true
        return AppResult.Success(Unit)
    }
    override suspend fun recognize(audio: CapturedAudio): AppResult<RecognitionResult> {
        check(loaded)
        events += "asr_infer"
        return AppResult.Success(RecognitionResult("text", 1f, false))
    }
    override suspend fun release() {
        if (loaded) events += "asr_release"
        loaded = false
    }
}

private class TrackingSynthesizer(private val events: MutableList<String>) : SpeechSynthesizer {
    var loaded = false
    override suspend fun initialize(): AppResult<Unit> {
        if (!loaded) events += "tts_init"
        loaded = true
        return AppResult.Success(Unit)
    }
    override suspend fun synthesize(text: String, sessionId: String): AppResult<SynthesizedSpeech> {
        check(loaded)
        events += "tts_generate"
        return AppResult.Success(SynthesizedSpeech(text, null, 1, false, shortArrayOf(1), 16_000))
    }
    override suspend fun release() {
        if (loaded) events += "tts_release"
        loaded = false
    }
}

private class NoOpRecognizer : SpeechRecognizer {
    override suspend fun initialize(): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun recognize(audio: CapturedAudio) = AppResult.Success(RecognitionResult("mock", 1f, true))
    override suspend fun release() = Unit
}

private class NoOpSynthesizer : SpeechSynthesizer {
    override suspend fun initialize(): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun synthesize(text: String, sessionId: String) =
        AppResult.Success(SynthesizedSpeech(text, null, 1, true, shortArrayOf(1), 16_000))
    override suspend fun release() = Unit
}
