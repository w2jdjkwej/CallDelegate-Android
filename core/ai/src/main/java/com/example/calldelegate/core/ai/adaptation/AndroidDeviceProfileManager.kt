package com.example.calldelegate.core.ai.adaptation

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import com.example.calldelegate.core.ai.BuildConfig
import com.example.calldelegate.domain.api.DeviceProfileProvider
import com.example.calldelegate.domain.model.BenchmarkStage
import com.example.calldelegate.domain.model.BenchmarkState
import com.example.calldelegate.domain.model.DeviceBenchmarkSummary
import com.example.calldelegate.domain.model.DeviceProfile
import com.example.calldelegate.domain.model.InferenceBenchmarkSample
import com.example.calldelegate.domain.model.SocFamily
import com.example.calldelegate.domain.model.ThermalSeverity
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidDeviceProfileManager(
    context: Context,
    private val classifier: DeviceProfileClassifier = DeviceProfileClassifier(),
) : DeviceProfileProvider {
    private val appContext = context.applicationContext ?: context
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val mutableProfile = MutableStateFlow(initialProfile())
    override val profile: StateFlow<DeviceProfile> = mutableProfile.asStateFlow()

    override suspend fun refresh() = withContext(Dispatchers.Default) {
        mutex.withLock { updateProfileLocked() }
    }

    override suspend fun recordBenchmark(sample: InferenceBenchmarkSample) = withContext(Dispatchers.Default) {
        mutex.withLock {
            ensureSignatureLocked()
            val facts = readFacts()
            if (sample.successful) {
                val values = readValues(sample.stage).toMutableList()
                values += sample.elapsedMillis.coerceAtLeast(0L)
                writeValues(sample.stage, values.takeLast(MAX_SAMPLES_PER_STAGE))
            } else {
                preferences.edit().putInt(KEY_FAILURES, preferences.getInt(KEY_FAILURES, 0) + 1).apply()
            }
            preferences.edit()
                .putInt(KEY_PEAK_PSS_MB, maxOf(preferences.getInt(KEY_PEAK_PSS_MB, 0), facts.currentPssMb))
                .putString(
                    KEY_MAX_THERMAL,
                    hotterOf(readStoredThermal(), facts.thermalSeverity).name,
                )
                .apply()
            updateProfileLocked(facts)
        }
    }

    override suspend fun invalidateBenchmark(reason: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            clearBenchmarkLocked(reason)
            updateProfileLocked()
        }
    }

    override suspend fun overrideNominalRamGb(gb: Int?) = withContext(Dispatchers.Default) {
        check(BuildConfig.DEBUG) { "RAM override is available only in debug builds" }
        require(gb == null || gb in SUPPORTED_RAM_OVERRIDE_GB) {
            "RAM override must be null or between ${SUPPORTED_RAM_OVERRIDE_GB.first} and ${SUPPORTED_RAM_OVERRIDE_GB.last} GB"
        }
        mutex.withLock {
            val editor = preferences.edit()
            if (gb == null) {
                editor.remove(KEY_NOMINAL_RAM_OVERRIDE_GB)
            } else {
                editor.putInt(KEY_NOMINAL_RAM_OVERRIDE_GB, gb)
            }
            // commit(), not apply(): apply() persists asynchronously, and a stale override silently
            // mis-tiers every later run of the app. A batch that set an override and then had its
            // process killed before the async write landed left the device pinned to that override
            // for good, which is exactly what happened on 2026-08-04.
            editor.commit()
            updateProfileLocked()
        }
    }

    private fun initialProfile(): DeviceProfile {
        ensureSignatureLocked()
        return classifiedProfile(readFacts())
    }

    private fun updateProfileLocked(facts: DeviceFacts = readFacts()) {
        ensureSignatureLocked()
        mutableProfile.value = classifiedProfile(facts)
    }

    private fun classifiedProfile(facts: DeviceFacts): DeviceProfile {
        val base = classifier.classify(facts, readSummary())
        val invalidationReason = preferences.getString(KEY_INVALIDATION_REASON, null)
        return if (invalidationReason.isNullOrBlank()) base else base.copy(
            reasons = base.reasons + "基准已重置：$invalidationReason",
        )
    }

    private fun readFacts(): DeviceFacts {
        val activityManager = requireNotNull(appContext.getSystemService(ActivityManager::class.java)) {
            "ActivityManager unavailable"
        }
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val totalRamMb = (memory.totalMem / MEBIBYTE).toInt()
        val detectedNominalRamGb = ceil(memory.totalMem.toDouble() / GIBIBYTE.toDouble()).toInt()
        val nominalRamGb = if (BuildConfig.DEBUG) {
            preferences.getInt(KEY_NOMINAL_RAM_OVERRIDE_GB, detectedNominalRamGb)
        } else {
            detectedNominalRamGb
        }
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" }
        val socManufacturer = Build.SOC_MANUFACTURER.orEmpty()
        val socModel = Build.SOC_MODEL.orEmpty().ifBlank { Build.HARDWARE.orEmpty() }.ifBlank { "unknown" }
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        return DeviceFacts(
            totalRamMb = totalRamMb,
            nominalRamGb = nominalRamGb,
            memoryClassMb = activityManager.memoryClass,
            cpuCoreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            primaryAbi = primaryAbi,
            arm64Supported = Build.SUPPORTED_64_BIT_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) },
            socFamily = detectSocFamily(socManufacturer, socModel, Build.HARDWARE.orEmpty()),
            socModel = socModel,
            thermalSeverity = powerManager?.currentThermalStatus?.let(::mapThermalStatus) ?: ThermalSeverity.UNKNOWN,
            lowMemory = memory.lowMemory,
            currentPssMb = (Debug.getPss() / 1024).toInt().coerceAtLeast(0),
            detectedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    private fun readSummary(): DeviceBenchmarkSummary {
        val asrInit = readValues(BenchmarkStage.ASR_INITIALIZATION)
        val asrInference = readValues(BenchmarkStage.ASR_INFERENCE)
        val ttsInit = readValues(BenchmarkStage.TTS_INITIALIZATION)
        val ttsGeneration = readValues(BenchmarkStage.TTS_GENERATION)
        val totalSamples = asrInit.size + asrInference.size + ttsInit.size + ttsGeneration.size
        val complete = asrInit.size >= MIN_INITIALIZATION_SAMPLES &&
            ttsInit.size >= MIN_INITIALIZATION_SAMPLES &&
            asrInference.size >= MIN_INFERENCE_SAMPLES &&
            ttsGeneration.size >= MIN_INFERENCE_SAMPLES
        val state = when {
            complete -> BenchmarkState.COMPLETE
            totalSamples == 0 -> BenchmarkState.PENDING
            else -> BenchmarkState.COLLECTING
        }
        return DeviceBenchmarkSummary(
            state = state,
            asrInitializationP95Millis = p95(asrInit),
            asrInferenceP95Millis = p95(asrInference),
            ttsInitializationP95Millis = p95(ttsInit),
            ttsGenerationP95Millis = p95(ttsGeneration),
            peakPssMb = preferences.getInt(KEY_PEAK_PSS_MB, 0).takeIf { it > 0 },
            maxThermalSeverity = readStoredThermal(),
            asrInitializationSamples = asrInit.size,
            asrInferenceSamples = asrInference.size,
            ttsInitializationSamples = ttsInit.size,
            ttsGenerationSamples = ttsGeneration.size,
            failures = preferences.getInt(KEY_FAILURES, 0),
        )
    }

    private fun readValues(stage: BenchmarkStage): List<Long> = preferences
        .getString(stage.key(), null)
        ?.split(',')
        ?.mapNotNull { it.toLongOrNull() }
        .orEmpty()

    private fun writeValues(stage: BenchmarkStage, values: List<Long>) {
        preferences.edit()
            .putString(stage.key(), values.joinToString(","))
            .remove(KEY_INVALIDATION_REASON)
            .apply()
    }

    private fun p95(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun ensureSignatureLocked() {
        val current = buildSignature()
        val previous = preferences.getString(KEY_SIGNATURE, null)
        if (previous != null && previous != current) clearBenchmarkLocked("设备或应用版本变化")
        if (previous != current) preferences.edit().putString(KEY_SIGNATURE, current).apply()
    }

    private fun buildSignature(): String {
        val version = runCatching {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            "${info.longVersionCode}:${info.lastUpdateTime}"
        }.getOrDefault("0:0")
        return listOf(Build.FINGERPRINT, Build.SOC_MODEL, Build.SUPPORTED_ABIS.joinToString(), version).joinToString("|")
    }

    private fun clearBenchmarkLocked(reason: String) {
        val editor = preferences.edit()
            .remove(KEY_FAILURES)
            .remove(KEY_PEAK_PSS_MB)
            .remove(KEY_MAX_THERMAL)
            .putString(KEY_INVALIDATION_REASON, reason)
        BenchmarkStage.entries.forEach { editor.remove(it.key()) }
        editor.apply()
    }

    private fun readStoredThermal(): ThermalSeverity = preferences.getString(KEY_MAX_THERMAL, null)
        ?.let { runCatching { ThermalSeverity.valueOf(it) }.getOrNull() }
        ?: ThermalSeverity.UNKNOWN

    private fun BenchmarkStage.key() = "stage_${name.lowercase()}"

    private companion object {
        const val PREFERENCES_NAME = "device_inference_adaptation"
        const val KEY_SIGNATURE = "benchmark_signature"
        const val KEY_FAILURES = "benchmark_failures"
        const val KEY_PEAK_PSS_MB = "benchmark_peak_pss_mb"
        const val KEY_MAX_THERMAL = "benchmark_max_thermal"
        const val KEY_INVALIDATION_REASON = "benchmark_invalidation_reason"
        const val KEY_NOMINAL_RAM_OVERRIDE_GB = "debug_nominal_ram_override_gb"
        const val MIN_INITIALIZATION_SAMPLES = 1
        const val MIN_INFERENCE_SAMPLES = 3
        const val MAX_SAMPLES_PER_STAGE = 20
        const val MEBIBYTE = 1024L * 1024L
        const val GIBIBYTE = 1024L * MEBIBYTE
        val SUPPORTED_RAM_OVERRIDE_GB = 4..16
    }
}

private fun detectSocFamily(manufacturer: String, model: String, hardware: String): SocFamily {
    val text = "$manufacturer $model $hardware".lowercase()
    return when {
        "qualcomm" in text || "qcom" in text || "snapdragon" in text -> SocFamily.QUALCOMM
        "mediatek" in text || "mtk" in text || "dimensity" in text -> SocFamily.MEDIATEK
        "samsung" in text || "exynos" in text -> SocFamily.SAMSUNG
        "google" in text || "tensor" in text -> SocFamily.GOOGLE
        "unisoc" in text || "spreadtrum" in text || "sprd" in text -> SocFamily.UNISOC
        text.isBlank() -> SocFamily.UNKNOWN
        else -> SocFamily.OTHER
    }
}

private fun mapThermalStatus(status: Int): ThermalSeverity = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> ThermalSeverity.NONE
    PowerManager.THERMAL_STATUS_LIGHT -> ThermalSeverity.LIGHT
    PowerManager.THERMAL_STATUS_MODERATE -> ThermalSeverity.MODERATE
    PowerManager.THERMAL_STATUS_SEVERE -> ThermalSeverity.SEVERE
    PowerManager.THERMAL_STATUS_CRITICAL -> ThermalSeverity.CRITICAL
    PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalSeverity.EMERGENCY
    PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalSeverity.EMERGENCY
    else -> ThermalSeverity.UNKNOWN
}

private fun hotterOf(first: ThermalSeverity, second: ThermalSeverity): ThermalSeverity {
    fun ThermalSeverity.rank(): Int = when (this) {
        ThermalSeverity.NONE -> 0
        ThermalSeverity.LIGHT -> 1
        ThermalSeverity.MODERATE -> 2
        ThermalSeverity.SEVERE -> 3
        ThermalSeverity.CRITICAL -> 4
        ThermalSeverity.EMERGENCY -> 5
        ThermalSeverity.UNKNOWN -> -1
    }
    return if (first.rank() >= second.rank()) first else second
}
