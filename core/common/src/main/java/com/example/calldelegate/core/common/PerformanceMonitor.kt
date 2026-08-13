package com.example.calldelegate.core.common

import android.os.Debug
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PerformanceSample(
    val name: String,
    val elapsedMillis: Long,
    val nativeHeapBytes: Long,
    val javaHeapBytes: Long,
    val capturedAtEpochMillis: Long,
)

/** Records measurements only; it intentionally contains no claimed benchmark result. */
class PerformanceMonitor {
    private val starts = ConcurrentHashMap<String, Long>()
    private val mutableSamples = MutableStateFlow<List<PerformanceSample>>(emptyList())
    val samples: StateFlow<List<PerformanceSample>> = mutableSamples.asStateFlow()

    fun start(name: String) {
        starts[name] = SystemClock.elapsedRealtimeNanos()
    }

    fun stop(name: String): PerformanceSample? {
        val start = starts.remove(name) ?: return null
        val runtime = Runtime.getRuntime()
        return PerformanceSample(
            name = name,
            elapsedMillis = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L,
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            javaHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
            capturedAtEpochMillis = java.lang.System.currentTimeMillis(),
        ).also { sample ->
            mutableSamples.value = (mutableSamples.value + sample).takeLast(100)
        }
    }
}
