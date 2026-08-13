package com.example.calldelegate.core.common

import kotlin.math.ceil

/**
 * Small, deterministic summary helpers for benchmark output.
 *
 * Percentiles use the nearest-rank rule. Callers must always report the sample count together with
 * the values because a percentile from a small sample is only a descriptive statistic.
 */
object PerformanceStatistics {
    fun nearestRank(values: List<Long>, percentile: Int): Long? {
        if (values.isEmpty()) return null
        require(percentile in 1..100) { "percentile must be in 1..100" }

        val sorted = values.sorted()
        val index = (ceil(sorted.size * percentile / 100.0).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    fun median(values: List<Long>): Long? = nearestRank(values, 50)

    fun summary(values: List<Long>): LatencySummary = LatencySummary(
        sampleCount = values.size,
        minMillis = values.minOrNull(),
        medianMillis = median(values),
        p90Millis = nearestRank(values, 90),
        p95Millis = nearestRank(values, 95),
        maxMillis = values.maxOrNull(),
    )
}

data class LatencySummary(
    val sampleCount: Int,
    val minMillis: Long?,
    val medianMillis: Long?,
    val p90Millis: Long?,
    val p95Millis: Long?,
    val maxMillis: Long?,
)
