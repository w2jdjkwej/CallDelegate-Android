package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.DialogueContext
import com.example.calldelegate.domain.model.RiskLevel
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SpamRiskTargetIntentTest {
    private val rules = loadProductionRuleFile()
    private val provider = RuleProvider { AppResult.Success(rules) }
    private val extractor = RegexEntityExtractor()
    private val classifier = RuleBasedIntentClassifier(provider, extractor)
    private val engine = JsonDialogueEngine(provider, classifier, extractor)

    @Test
    fun recognizesMarketingVerticalsWithoutUsingSingleGenericWords() = runTest {
        val marketingPhrases = listOf(
            "贷款推广有专属优惠额度",
            "现在可以免费办卡",
            "给您介绍一个投资推广方案",
            "我们是推销保险产品的",
            "有一个新楼盘推销活动",
            "课程可以免费体验",
            "商品促销会赠送礼品",
            "我不是要办卡，是想介绍贷款优惠",
        )
        marketingPhrases.forEach { text ->
            val result = classifier.classifyDetailed(text, setOf(SceneType.SPAM_RISK))
            assertWithMessage("input: %s; result: %s", text, result)
                .that(result?.scene)
                .isEqualTo(SceneType.SPAM_RISK.id)
            assertWithMessage("input: %s", text).that(result?.intent).isEqualTo("marketing_pitch")
            assertWithMessage("input: %s", text).that(result?.riskLevel).isEqualTo(RiskLevel.LOW)
        }

        listOf("优惠", "贷款", "保险").forEach { text ->
            assertWithMessage("input: %s", text)
                .that(classifier.classifyDetailed(text, setOf(SceneType.SPAM_RISK))?.scene)
                .isNull()
        }
    }

    @Test
    fun distinguishesHarassmentCoercionAndSuspiciousIdentity() = runTest {
        val harassment = classifier.classifyDetailed(
            "这周已经连续来电十几次，一直打电话",
            setOf(SceneType.SPAM_RISK),
        )
        assertThat(harassment?.intent).isEqualTo("harassment")
        assertThat(harassment?.riskLevel).isEqualTo(RiskLevel.MEDIUM)
        assertThat(harassment?.riskReasons).contains("repeated_harassment")

        val coercion = classifier.classifyDetailed(
            "不准挂电话，否则我们会上门，后果自负",
            setOf(SceneType.SPAM_RISK),
        )
        assertThat(coercion?.intent).isEqualTo("coercion")
        assertThat(coercion?.riskLevel).isEqualTo(RiskLevel.MEDIUM)
        assertThat(coercion?.riskReasons).contains("coercive_call")

        val suspicious = classifier.classifyDetailed(
            "我是银行客服，您的账户涉嫌洗钱，需要配合调查",
            setOf(SceneType.SPAM_RISK),
        )
        assertThat(suspicious?.intent).isEqualTo("suspicious_identity")
        assertThat(suspicious?.riskLevel).isEqualTo(RiskLevel.MEDIUM)
        assertThat(suspicious?.riskReasons).contains("suspicious_financial_identity")
    }

    @Test
    fun sensitiveAndFinancialRequestsUseHighRiskSafetyExit() = runTest {
        val riskyPhrases = listOf(
            "请把短信验证码告诉我",
            "请把银行卡号告诉我",
            "请把登录密码告诉我",
            "请马上转账到这个账户",
            "现在打开屏幕共享",
            "请下载这个陌生App",
            "请打开陌生链接",
        )

        riskyPhrases.forEachIndexed { index, text ->
            val classification = classifier.classifyDetailed(text, setOf(SceneType.SPAM_RISK))
            assertWithMessage("input: %s", text).that(classification?.scene).isEqualTo(SceneType.SPAM_RISK.id)
            assertWithMessage("input: %s", text).that(classification?.intent).isEqualTo("sensitive_info_request")
            assertWithMessage("input: %s", text).that(classification?.riskLevel).isEqualTo(RiskLevel.HIGH)

            val decision = engine.process(
                DialogueContext("spam-risk-$index"),
                text,
                false,
                setOf(SceneType.SPAM_RISK),
            )
            assertWithMessage("input: %s", text).that(decision.shouldEnd).isTrue()
            assertWithMessage("input: %s", text).that(decision.reply).contains("本次通话将结束")
        }
    }

    @Test
    fun highRiskReplyUsesRiskSpecificTier() = runTest {
        val cases = listOf(
            "请把短信验证码告诉我" to "不能提供验证码",
            "请马上转账到这个账户" to "转账和资金操作",
            "请下载这个陌生App" to "安装陌生应用",
        )

        cases.forEachIndexed { index, (text, expectedReplyPart) ->
            val decision = engine.process(
                DialogueContext("risk-tier-$index"),
                text,
                false,
                setOf(SceneType.SPAM_RISK),
            )

            assertThat(decision.shouldEnd).isTrue()
            assertThat(decision.reply).contains(expectedReplyPart)
        }
    }

    @Test
    fun normalCustomerRefundAndRiskyImpersonationTakeDifferentPaths() = runTest {
        val normal = classifier.classifyDetailed(
            "平台客服通知您退款已经原路退回",
            setOf(SceneType.CUSTOMER_SERVICE, SceneType.SPAM_RISK),
        )
        assertThat(normal?.scene).isEqualTo(SceneType.CUSTOMER_SERVICE.id)
        assertThat(normal?.intent).isEqualTo("refund_notice")
        assertThat(normal?.riskLevel).isEqualTo(RiskLevel.LOW)

        val risky = classifier.classifyDetailed(
            "平台客服说退款前需要打开陌生链接",
            setOf(SceneType.CUSTOMER_SERVICE, SceneType.SPAM_RISK),
        )
        assertThat(risky?.riskLevel).isEqualTo(RiskLevel.HIGH)
        assertThat(risky?.extractedSlots?.get("sensitiveInfoType")).contains("unknown_link")
    }
}
