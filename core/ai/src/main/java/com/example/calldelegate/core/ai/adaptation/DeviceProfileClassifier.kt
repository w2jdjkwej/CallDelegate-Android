package com.example.calldelegate.core.ai.adaptation

import com.example.calldelegate.domain.model.BenchmarkState
import com.example.calldelegate.domain.model.DeviceBenchmarkSummary
import com.example.calldelegate.domain.model.DeviceProfile
import com.example.calldelegate.domain.model.DeviceTier
import com.example.calldelegate.domain.model.InferencePolicy
import com.example.calldelegate.domain.model.SocFamily
import com.example.calldelegate.domain.model.ThermalSeverity

data class DeviceFacts(
    val totalRamMb: Int,
    val nominalRamGb: Int,
    val memoryClassMb: Int,
    val cpuCoreCount: Int,
    val primaryAbi: String,
    val arm64Supported: Boolean,
    val socFamily: SocFamily,
    val socModel: String,
    val thermalSeverity: ThermalSeverity,
    val lowMemory: Boolean,
    val currentPssMb: Int,
    val detectedAtEpochMillis: Long,
)

data class AdaptationThresholds(
    /**
     * Full ASR plus full TTS generation budget. The current TTS API is not streaming.
     *
     * Gates hardware-acceleration eligibility only; exceeding it no longer changes model residency.
     * Still uncalibrated: the one device measured so far reports ASR+TTS P95=2204ms, so every device
     * fails this budget today. Calibrating it needs 6GB~8GB target data, which is NOT_RUN.
     */
    val steadySpeechPipelineP95Millis: Long = 1_800L,
    val singleModelInitializationP95Millis: Long = 3_000L,
    /** Memory budgets. Unlike the speed budgets, exceeding these does demote and release models. */
    val benchmarkPeakPssMb: Int = 1_400,
    val hardPeakPssMb: Int = 1_500,
)

class DeviceProfileClassifier(
    private val thresholds: AdaptationThresholds = AdaptationThresholds(),
) {
    fun classify(facts: DeviceFacts, benchmark: DeviceBenchmarkSummary): DeviceProfile {
        val baseTier = when {
            facts.nominalRamGb <= 4 -> DeviceTier.LOW
            facts.nominalRamGb <= 7 -> DeviceTier.MEDIUM
            else -> DeviceTier.HIGH
        }
        var tier = baseTier
        val reasons = mutableListOf("${facts.nominalRamGb.coerceAtLeast(0)}GB RAM 静态档位：$baseTier")

        if (baseTier == DeviceTier.HIGH && benchmark.state != BenchmarkState.COMPLETE) {
            tier = DeviceTier.MEDIUM
            reasons += "HIGH 必须由完整首次基准解锁，校准前按 MEDIUM 运行"
        }

        if (!facts.arm64Supported) {
            tier = DeviceTier.LOW
            reasons += "未检测到 arm64-v8a，当前发布包不支持该 ABI"
        }
        if (facts.lowMemory) {
            tier = DeviceTier.LOW
            reasons += "系统报告低内存，临时进入 LOW"
        }
        if (facts.thermalSeverity.isAtLeast(ThermalSeverity.SEVERE)) {
            tier = DeviceTier.LOW
            reasons += "当前热状态为 ${facts.thermalSeverity}，临时进入 LOW"
        }
        if (facts.currentPssMb >= thresholds.hardPeakPssMb) {
            tier = DeviceTier.LOW
            reasons += "当前 PSS ${facts.currentPssMb}MB 已达到硬上限"
        }

        var benchmarkPassed = false
        if (benchmark.state == BenchmarkState.COMPLETE) {
            val pipelineP95 = listOfNotNull(
                benchmark.asrInferenceP95Millis,
                benchmark.ttsGenerationP95Millis,
            ).takeIf { it.size == 2 }?.sum()
            val slowInitialization = listOfNotNull(
                benchmark.asrInitializationP95Millis,
                benchmark.ttsInitializationP95Millis,
            ).any { it > thresholds.singleModelInitializationP95Millis }
            val slowPipeline = pipelineP95 != null && pipelineP95 > thresholds.steadySpeechPipelineP95Millis
            val highPss = benchmark.peakPssMb?.let { it > thresholds.benchmarkPeakPssMb } == true
            val hardPss = benchmark.peakPssMb?.let { it >= thresholds.hardPeakPssMb } == true
            val hotBenchmark = benchmark.maxThermalSeverity.isAtLeast(ThermalSeverity.SEVERE)

            // Memory and thermal pressure demote, because a lower tier releases models and that is
            // what relieves them. A slow pipeline must NOT demote: the lower tier's response is to
            // release and reload models every turn, which makes an already-slow device slower. The
            // two signals are therefore judged separately -- memory decisions on memory evidence,
            // speed decisions on speed evidence.
            if (hardPss || hotBenchmark) {
                tier = DeviceTier.LOW
                reasons += if (hardPss) {
                    "基准峰值 PSS ${benchmark.peakPssMb}MB 达到硬上限"
                } else {
                    "基准期间达到 ${benchmark.maxThermalSeverity} 热状态"
                }
            } else if (highPss) {
                tier = tier.demote()
                reasons += "基准峰值 PSS=${benchmark.peakPssMb}MB 超过档位门槛，降档以释放模型"
            }

            val slowBenchmark = slowInitialization || slowPipeline
            if (slowBenchmark) {
                reasons += buildString {
                    append("首次基准慢于门槛")
                    if (slowPipeline) append("，ASR+TTS P95=${pipelineP95}ms")
                    if (slowInitialization) append("，模型初始化偏慢")
                    append("；仅关闭硬件加速资格，不因此释放模型")
                }
            }
            benchmarkPassed = !slowBenchmark && !highPss && !hardPss && !hotBenchmark
            if (benchmarkPassed) reasons += "首次基准通过当前项目门槛"
        } else {
            reasons += "首次实测校准${if (benchmark.state == BenchmarkState.PENDING) "尚未开始" else "采集中"}"
        }

        val accelerationEligible = benchmarkPassed && tier != DeviceTier.LOW
        var policy = InferencePolicy.forTier(tier, accelerationEligible).copy(
            ttsThreadCount = minOf(
                InferencePolicy.forTier(tier, accelerationEligible).ttsThreadCount,
                facts.cpuCoreCount.coerceAtLeast(1),
            ),
        )
        if (facts.thermalSeverity == ThermalSeverity.MODERATE && policy.ttsThreadCount > 1) {
            policy = policy.copy(ttsThreadCount = policy.ttsThreadCount - 1)
            reasons += "中等热状态下临时减少 1 个 TTS 线程"
        }
        return DeviceProfile(
            tier = tier,
            baseTier = baseTier,
            totalRamMb = facts.totalRamMb,
            nominalRamGb = facts.nominalRamGb,
            memoryClassMb = facts.memoryClassMb,
            cpuCoreCount = facts.cpuCoreCount,
            primaryAbi = facts.primaryAbi,
            arm64Supported = facts.arm64Supported,
            socFamily = facts.socFamily,
            socModel = facts.socModel,
            thermalSeverity = facts.thermalSeverity,
            lowMemory = facts.lowMemory,
            currentPssMb = facts.currentPssMb,
            benchmark = benchmark,
            policy = policy,
            reasons = reasons,
            detectedAtEpochMillis = facts.detectedAtEpochMillis,
        )
    }
}

private fun DeviceTier.demote(): DeviceTier = when (this) {
    DeviceTier.HIGH -> DeviceTier.MEDIUM
    DeviceTier.MEDIUM -> DeviceTier.LOW
    DeviceTier.LOW -> DeviceTier.LOW
}

private fun ThermalSeverity.isAtLeast(other: ThermalSeverity): Boolean {
    fun ThermalSeverity.rank(): Int = when (this) {
        ThermalSeverity.NONE -> 0
        ThermalSeverity.LIGHT -> 1
        ThermalSeverity.MODERATE -> 2
        ThermalSeverity.SEVERE -> 3
        ThermalSeverity.CRITICAL -> 4
        ThermalSeverity.EMERGENCY -> 5
        ThermalSeverity.UNKNOWN -> -1
    }
    return rank() >= other.rank()
}
