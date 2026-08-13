package com.example.calldelegate.core.ai.adaptation

import com.example.calldelegate.domain.model.BenchmarkState
import com.example.calldelegate.domain.model.DeviceBenchmarkSummary
import com.example.calldelegate.domain.model.DeviceTier
import com.example.calldelegate.domain.model.SocFamily
import com.example.calldelegate.domain.model.ThermalSeverity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceProfileClassifierTest {
    private val classifier = DeviceProfileClassifier()

    @Test fun fourGigabyteDeviceUsesLowAndMutuallyExclusiveSpeechModels() {
        val profile = classifier.classify(facts(ramGb = 4), DeviceBenchmarkSummary())

        assertThat(profile.tier).isEqualTo(DeviceTier.LOW)
        assertThat(profile.policy.ttsThreadCount).isEqualTo(1)
        assertThat(profile.policy.allowConcurrentSpeechModels).isFalse()
        assertThat(profile.policy.releaseAsrBeforeTts).isTrue()
    }

    @Test fun sixGigabyteDeviceUsesMediumWhileCalibrationIsPending() {
        val profile = classifier.classify(facts(ramGb = 6), DeviceBenchmarkSummary())

        assertThat(profile.tier).isEqualTo(DeviceTier.MEDIUM)
        assertThat(profile.policy.ttsThreadCount).isEqualTo(2)
        assertThat(profile.policy.preloadAsrOnIncoming).isTrue()
        assertThat(profile.policy.preloadTtsOnIncoming).isFalse()
    }

    @Test fun highTierIsLockedUntilCompleteBenchmarkPasses() {
        val pending = classifier.classify(facts(ramGb = 8), DeviceBenchmarkSummary())
        val passed = classifier.classify(facts(ramGb = 8), completeBenchmark())

        assertThat(pending.baseTier).isEqualTo(DeviceTier.HIGH)
        assertThat(pending.tier).isEqualTo(DeviceTier.MEDIUM)
        assertThat(passed.tier).isEqualTo(DeviceTier.HIGH)
        assertThat(passed.policy.hardwareAccelerationEligible).isTrue()
    }

    @Test fun aSlowBenchmarkKeepsTheTierSoModelsStayResident() {
        // Previously this demoted 6GB to LOW, whose policy releases and reloads ASR and TTS every
        // turn -- the response made the slow device slower. Speed evidence must not drive residency.
        val benchmark = completeBenchmark(asrInferenceMs = 1_200, ttsGenerationMs = 1_000)

        val profile = classifier.classify(facts(ramGb = 6), benchmark)

        assertThat(profile.tier).isEqualTo(DeviceTier.MEDIUM)
        assertThat(profile.policy.releaseAsrBeforeTts).isFalse()
        assertThat(profile.policy.releaseTtsAfterSynthesis).isFalse()
        // The device is still recognized as slow, and still loses acceleration eligibility.
        assertThat(profile.policy.hardwareAccelerationEligible).isFalse()
        assertThat(profile.reasons.joinToString()).contains("ASR+TTS P95=2200ms")
        assertThat(profile.reasons.joinToString()).contains("不因此释放模型")
    }

    @Test fun aSlowBenchmarkOnAnEightGigabyteDeviceKeepsHigh() {
        val benchmark = completeBenchmark(asrInferenceMs = 1_200, ttsGenerationMs = 1_000)

        val profile = classifier.classify(facts(ramGb = 8), benchmark)

        assertThat(profile.tier).isEqualTo(DeviceTier.HIGH)
        assertThat(profile.policy.hardwareAccelerationEligible).isFalse()
    }

    @Test fun highPeakPssStillDemotesBecauseReleasingModelsIsWhatRelievesMemory() {
        val benchmark = completeBenchmark(peakPssMb = 1_450)

        val profile = classifier.classify(facts(ramGb = 6), benchmark)

        assertThat(profile.tier).isEqualTo(DeviceTier.LOW)
        assertThat(profile.policy.releaseAsrBeforeTts).isTrue()
        assertThat(profile.reasons.joinToString()).contains("降档以释放模型")
    }

    @Test fun whenABenchmarkIsBothSlowAndMemoryHungryTheMemoryEvidenceDecidesTheTier() {
        val benchmark = completeBenchmark(
            asrInferenceMs = 1_200,
            ttsGenerationMs = 1_000,
            peakPssMb = 1_450,
        )

        val profile = classifier.classify(facts(ramGb = 8), benchmark)

        assertThat(profile.tier).isEqualTo(DeviceTier.MEDIUM)
        assertThat(profile.reasons.joinToString()).contains("降档以释放模型")
        assertThat(profile.reasons.joinToString()).contains("不因此释放模型")
    }

    @Test fun severeThermalPressureForcesLowEvenOnHighMemoryDevice() {
        val profile = classifier.classify(
            facts(ramGb = 8, thermal = ThermalSeverity.SEVERE),
            completeBenchmark(),
        )

        assertThat(profile.tier).isEqualTo(DeviceTier.LOW)
        assertThat(profile.policy.ttsThreadCount).isEqualTo(1)
    }

    @Test fun cpuCountCapsTtsThreadsAndModerateHeatReducesOneMore() {
        val profile = classifier.classify(
            facts(ramGb = 8, cpuCores = 3, thermal = ThermalSeverity.MODERATE),
            completeBenchmark(),
        )

        assertThat(profile.tier).isEqualTo(DeviceTier.HIGH)
        assertThat(profile.policy.ttsThreadCount).isEqualTo(2)
    }

    private fun facts(
        ramGb: Int,
        cpuCores: Int = 8,
        thermal: ThermalSeverity = ThermalSeverity.NONE,
    ) = DeviceFacts(
        totalRamMb = ramGb * 900,
        nominalRamGb = ramGb,
        memoryClassMb = 256,
        cpuCoreCount = cpuCores,
        primaryAbi = "arm64-v8a",
        arm64Supported = true,
        socFamily = SocFamily.QUALCOMM,
        socModel = "test-soc",
        thermalSeverity = thermal,
        lowMemory = false,
        currentPssMb = 300,
        detectedAtEpochMillis = 1L,
    )

    private fun completeBenchmark(
        asrInferenceMs: Long = 500,
        ttsGenerationMs: Long = 700,
        peakPssMb: Int = 900,
    ) = DeviceBenchmarkSummary(
        state = BenchmarkState.COMPLETE,
        asrInitializationP95Millis = 1_000,
        asrInferenceP95Millis = asrInferenceMs,
        ttsInitializationP95Millis = 1_000,
        ttsGenerationP95Millis = ttsGenerationMs,
        peakPssMb = peakPssMb,
        maxThermalSeverity = ThermalSeverity.MODERATE,
        asrInitializationSamples = 1,
        asrInferenceSamples = 3,
        ttsInitializationSamples = 1,
        ttsGenerationSamples = 3,
    )
}
