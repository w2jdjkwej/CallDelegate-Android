package com.example.calldelegate.core.ai.evaluation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EvaluationMetricsTest {
    @Test
    fun slotCountsTreatWrongValueAsFalsePositiveAndFalseNegative() {
        val counts = slotCounts(
            expected = mapOf("location" to "北门", "urgent" to "false"),
            actual = mapOf("location" to "南门", "urgent" to "false", "contact" to "13800138000"),
        )

        assertThat(counts.truePositive).isEqualTo(1)
        assertThat(counts.falsePositive).isEqualTo(2)
        assertThat(counts.falseNegative).isEqualTo(1)
        assertThat(checkNotNull(counts.precision)).isWithin(0.000001).of(1.0 / 3.0)
        assertThat(checkNotNull(counts.recall)).isWithin(0.000001).of(0.5)
        assertThat(checkNotNull(counts.f1)).isWithin(0.000001).of(0.4)
    }

    @Test
    fun purposeDoesNotInflateStructuredSlotMetrics() {
        val counts = slotCounts(
            expected = mapOf("purpose" to "完整原文", "location" to "北门"),
            actual = mapOf("purpose" to "完整原文", "location" to "北门"),
        )

        assertThat(counts.truePositive).isEqualTo(1)
        assertThat(counts.falsePositive).isEqualTo(0)
        assertThat(counts.falseNegative).isEqualTo(0)
        assertThat(counts.f1).isEqualTo(1.0)
    }

    @Test
    fun zeroDenominatorProducesUndefinedRate() {
        assertThat(rateMetric(correct = 0, total = 0).value).isNull()
    }
}
