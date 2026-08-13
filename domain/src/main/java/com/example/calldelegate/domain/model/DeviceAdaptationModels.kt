package com.example.calldelegate.domain.model

enum class DeviceTier { LOW, MEDIUM, HIGH }

enum class SocFamily { QUALCOMM, MEDIATEK, SAMSUNG, GOOGLE, UNISOC, OTHER, UNKNOWN }

enum class ThermalSeverity { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, UNKNOWN }

enum class BenchmarkStage { ASR_INITIALIZATION, ASR_INFERENCE, TTS_INITIALIZATION, TTS_GENERATION }

enum class BenchmarkState { PENDING, COLLECTING, COMPLETE }

enum class InferenceBackend { CPU }

data class InferenceBenchmarkSample(
    val stage: BenchmarkStage,
    val elapsedMillis: Long,
    val successful: Boolean,
)

data class DeviceBenchmarkSummary(
    val state: BenchmarkState = BenchmarkState.PENDING,
    val asrInitializationP95Millis: Long? = null,
    val asrInferenceP95Millis: Long? = null,
    val ttsInitializationP95Millis: Long? = null,
    val ttsGenerationP95Millis: Long? = null,
    val peakPssMb: Int? = null,
    val maxThermalSeverity: ThermalSeverity = ThermalSeverity.UNKNOWN,
    val asrInitializationSamples: Int = 0,
    val asrInferenceSamples: Int = 0,
    val ttsInitializationSamples: Int = 0,
    val ttsGenerationSamples: Int = 0,
    val failures: Int = 0,
) {
    val completedSamples: Int
        get() = asrInitializationSamples + asrInferenceSamples +
            ttsInitializationSamples + ttsGenerationSamples
}

data class InferencePolicy(
    val backend: InferenceBackend = InferenceBackend.CPU,
    val ttsThreadCount: Int = 1,
    val preloadAsrOnIncoming: Boolean = false,
    val preloadTtsOnIncoming: Boolean = false,
    val releaseAsrBeforeTts: Boolean = true,
    val releaseTtsAfterSynthesis: Boolean = true,
    val releaseAsrOnSessionEnd: Boolean = true,
    val releaseTtsOnSessionEnd: Boolean = true,
    val allowConcurrentSpeechModels: Boolean = false,
    val maxResidentLanguageModels: Int = 1,
    val maxTurnDurationMillis: Long = 15_000L,
    val maxSingleModelMemoryMb: Int = 700,
    val maxConcurrentModelMemoryMb: Int = 700,
    val hardwareAccelerationEligible: Boolean = false,
) {
    companion object {
        fun forTier(tier: DeviceTier, accelerationEligible: Boolean = false): InferencePolicy = when (tier) {
            DeviceTier.LOW -> InferencePolicy()
            DeviceTier.MEDIUM -> InferencePolicy(
                ttsThreadCount = 2,
                preloadAsrOnIncoming = true,
                releaseAsrBeforeTts = false,
                releaseTtsAfterSynthesis = false,
                releaseAsrOnSessionEnd = false,
                allowConcurrentSpeechModels = true,
                maxSingleModelMemoryMb = 900,
                maxConcurrentModelMemoryMb = 1_100,
                hardwareAccelerationEligible = accelerationEligible,
            )
            DeviceTier.HIGH -> InferencePolicy(
                ttsThreadCount = 4,
                preloadAsrOnIncoming = true,
                preloadTtsOnIncoming = true,
                releaseAsrBeforeTts = false,
                releaseTtsAfterSynthesis = false,
                releaseAsrOnSessionEnd = false,
                releaseTtsOnSessionEnd = false,
                allowConcurrentSpeechModels = true,
                maxSingleModelMemoryMb = 1_200,
                maxConcurrentModelMemoryMb = 1_200,
                hardwareAccelerationEligible = accelerationEligible,
            )
        }
    }
}

data class DeviceProfile(
    val tier: DeviceTier = DeviceTier.LOW,
    val baseTier: DeviceTier = DeviceTier.LOW,
    val totalRamMb: Int = 0,
    val nominalRamGb: Int = 0,
    val memoryClassMb: Int = 0,
    val cpuCoreCount: Int = 1,
    val primaryAbi: String = "unknown",
    val arm64Supported: Boolean = false,
    val socFamily: SocFamily = SocFamily.UNKNOWN,
    val socModel: String = "unknown",
    val thermalSeverity: ThermalSeverity = ThermalSeverity.UNKNOWN,
    val lowMemory: Boolean = false,
    val currentPssMb: Int = 0,
    val benchmark: DeviceBenchmarkSummary = DeviceBenchmarkSummary(),
    val policy: InferencePolicy = InferencePolicy.forTier(DeviceTier.LOW),
    val reasons: List<String> = listOf("等待设备检测"),
    val detectedAtEpochMillis: Long = 0L,
)
