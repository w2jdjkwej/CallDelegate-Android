package com.example.calldelegate.feature.main.ui

import com.example.calldelegate.domain.model.InputMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResultPresentationTest {
    @Test
    fun deliveryDiagnosticsAreHiddenAndUserFacingValueIsChinese() {
        val fields = resultExtraFields(
            mapOf(
                "deliveryIntent" to "access_blocked",
                "deliveryIntentScore" to "1.0",
                "deliveryIntentDecisionRule" to "access_blocked",
                "deliveryIntentMatchedEvidence" to "delivery:intent_priority:access_blocked",
                "deliveryIntentRejectedCandidates" to "delivery_arrived",
            ),
        )

        assertThat(fields).containsExactly(
            ResultDisplayField("配送状态", "无法进入配送地点"),
        )
        assertThat(fields.userFacingText()).doesNotContainMatch("[A-Za-z]")
    }

    @Test
    fun knownRiskMetadataIsLocalizedAndUnknownMetadataIsHidden() {
        val fields = resultExtraFields(
            mapOf(
                "sensitiveInfoType" to "sms_code,bank_card",
                "riskReason" to "request_sms_code,request_bank_card",
                "riskLevel" to "HIGH",
                "futureDebugField" to "debug_value",
            ),
        )

        assertThat(fields).containsExactly(
            ResultDisplayField("涉及敏感信息", "短信验证码、银行卡信息"),
            ResultDisplayField("风险原因", "索要短信验证码、索要银行卡信息"),
            ResultDisplayField("风险等级", "高风险"),
        ).inOrder()
        assertThat(fields.userFacingText()).doesNotContainMatch("[A-Za-z]")
    }

    @Test
    fun inputModesUseChineseNames() {
        assertThat(InputMode.MICROPHONE.resultDisplayName()).isEqualTo("麦克风")
        assertThat(InputMode.PRESET_AUDIO.resultDisplayName()).isEqualTo("预设音频")
        assertThat(InputMode.TEXT.resultDisplayName()).isEqualTo("文字")
    }
}

/**
 * Only what the user actually reads. Joining the fields themselves would fold in the data class
 * name and its property names, so the Latin-letter check could never pass whatever the content was.
 */
private fun List<ResultDisplayField>.userFacingText(): String =
    joinToString(separator = "") { field -> field.label + field.value }
