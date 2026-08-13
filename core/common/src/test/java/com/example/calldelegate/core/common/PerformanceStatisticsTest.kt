package com.example.calldelegate.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerformanceStatisticsTest {
    @Test fun nearestRankUsesTheDocumentedRule() {
        val values = listOf(90L, 10L, 70L, 50L, 30L)

        assertEquals(10L, PerformanceStatistics.nearestRank(values, 1))
        assertEquals(50L, PerformanceStatistics.nearestRank(values, 50))
        assertEquals(90L, PerformanceStatistics.nearestRank(values, 90))
        assertEquals(90L, PerformanceStatistics.nearestRank(values, 95))
    }

    @Test fun emptySamplesProduceNoPercentile() {
        assertNull(PerformanceStatistics.nearestRank(emptyList(), 50))
        assertEquals(
            LatencySummary(0, null, null, null, null, null),
            PerformanceStatistics.summary(emptyList()),
        )
    }
}
