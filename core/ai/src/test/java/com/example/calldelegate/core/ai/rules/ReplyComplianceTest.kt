package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReplyComplianceTest {
    @Test
    fun insuranceAdviceAndSensitiveActionsAreFlagged() {
        val result = ReplyCompliance.evaluate(
            SceneType.INSURANCE_FINANCE,
            "建议您投保这款产品，保额100万元，我可以替您退保。",
        )

        assertThat(result.safe).isFalse()
        assertThat(result.flags).containsExactly(
            "INSURANCE_FINANCE_ADVICE",
            "INSURANCE_FINANCE_EXACT_VALUE",
            "INSURANCE_FINANCE_OWNER_ACTION",
        )
    }

    @Test
    fun insuranceAcknowledgementIsSafe() {
        val result = ReplyCompliance.evaluate(
            SceneType.INSURANCE_FINANCE,
            "已记录您的来电内容，我会转达给机主。",
        )

        assertThat(result.safe).isTrue()
        assertThat(result.flags).isEmpty()
    }
}
