package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.DialogueContext
import com.example.calldelegate.domain.model.RiskLevel
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.SlotExtractionRequest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InsuranceFinanceTargetIntentTest {
    private val rules = loadProductionRuleFile()
    private val provider = RuleProvider { AppResult.Success(rules) }
    private val extractor = RegexEntityExtractor()
    private val classifier = RuleBasedIntentClassifier(provider, extractor)
    private val engine = JsonDialogueEngine(provider, classifier, extractor)

    @Test
    fun recognizesInsuranceAndFinanceBusinessIntents() = runTest {
        val cases = mapOf(
            "保险公司来电核实这次事故的理赔信息" to "claim_verification",
            "理赔专员通知需要补充住院证明和发票" to "claim_document_request",
            "来电同步您的理赔审核进度" to "claim_progress",
            "您的车险保单本月底即将到期" to "policy_expiry",
            "保险客服提醒您办理车险续保" to "renewal_service",
            "银行来电提醒贷款下周还款" to "repayment_notice",
            "信用卡出现一笔异常消费" to "transaction_alert",
            "电子保单已经送达，请注意查收" to "policy_service",
            "给您推荐一款医疗险产品" to "insurance_marketing",
            "银行想介绍一款投资理财产品" to "financial_marketing",
        )

        cases.forEach { (text, expectedIntent) ->
            val result = classifier.classifyDetailed(text, setOf(SceneType.INSURANCE_FINANCE))
            assertWithMessage("input: %s", text).that(result?.scene).isEqualTo(SceneType.INSURANCE_FINANCE.id)
            assertWithMessage("input: %s", text).that(result?.intent).isEqualTo(expectedIntent)
        }
    }

    @Test
    fun extractsInsuranceAndFinanceEntities() = runTest {
        val result = extractor.extract(
            SlotExtractionRequest(
                text = "我是中国平安，车险保单下周到期，本次来电是续保提醒，事情紧急，电话13800138000",
                expectedSlots = setOf(
                    "organization",
                    "insuranceType",
                    "expiryTime",
                    "time",
                    "serviceType",
                    "contactPurpose",
                    "urgent",
                    "contact",
                ),
                scene = SceneType.INSURANCE_FINANCE,
            ),
        ).slots

        assertThat(result["organization"]).isEqualTo("中国平安")
        assertThat(result["insuranceType"]).isEqualTo("车险")
        assertThat(result["expiryTime"]).isEqualTo("下周")
        assertThat(result["time"]).isEqualTo("下周")
        assertThat(result["serviceType"]).isEqualTo("续保")
        assertThat(result["contactPurpose"]).isEqualTo("续保")
        assertThat(result["urgent"]).isEqualTo("true")
        assertThat(result["contact"]).isEqualTo("13800138000")
    }

    @Test
    fun highRiskStillOverridesLockedInsuranceScene() = runTest {
        val initial = engine.process(
            DialogueContext("insurance-locked"),
            "中国平安来电核实理赔信息",
            false,
            setOf(SceneType.INSURANCE_FINANCE),
        )
        assertThat(initial.context.scene).isEqualTo(SceneType.INSURANCE_FINANCE)

        val riskyPhrases = listOf(
            "请把短信验证码告诉我",
            "请把支付密码告诉我",
            "请把银行卡号和CVV安全码报给我",
            "请马上转账到安全账户",
            "现在打开屏幕共享",
            "请下载这个陌生App",
            "请打开短信里的陌生链接",
            "请把完整身份证号告诉我",
        )
        riskyPhrases.forEach { text ->
            val decision = engine.process(
                initial.context,
                text,
                false,
                setOf(SceneType.INSURANCE_FINANCE),
            )
            assertWithMessage("input: %s", text).that(decision.context.riskLevel).isEqualTo(RiskLevel.HIGH)
            assertWithMessage("input: %s", text).that(decision.shouldEnd).isTrue()
            assertWithMessage("input: %s", text).that(decision.reply).contains("本次通话将结束")
        }
    }

    @Test
    fun suspiciousFinancialIdentityIsMarkedButNormalExpiryNoticeIsNotHighRisk() = runTest {
        val suspicious = classifier.classifyDetailed(
            "我是银行客服，您的账户涉嫌洗钱，需要配合调查",
            setOf(SceneType.INSURANCE_FINANCE, SceneType.SPAM_RISK),
        )
        assertThat(suspicious?.riskLevel).isEqualTo(RiskLevel.MEDIUM)
        assertThat(suspicious?.riskReasons).contains("suspicious_financial_identity")

        val expiry = classifier.classifyDetailed(
            "中国平安通知您的车险保单本月底到期",
            setOf(SceneType.INSURANCE_FINANCE, SceneType.SPAM_RISK),
        )
        assertThat(expiry?.scene).isEqualTo(SceneType.INSURANCE_FINANCE.id)
        assertThat(expiry?.intent).isEqualTo("policy_expiry")
        assertThat(expiry?.riskLevel).isNotEqualTo(RiskLevel.HIGH)
    }

    @Test
    fun entityEvidenceRecoversFundAsInsuranceDomainWithoutChangingTranscript() = runTest {
        val result = checkNotNull(classifier.classifyDetailed(
            "您持有的基金近期波动较大想确认您的风险承受能力有没有变化",
            setOf(SceneType.INSURANCE_FINANCE),
        ))

        assertWithMessage("result=%s", result).that(result.scene)
            .isEqualTo(SceneType.INSURANCE_FINANCE.id)
        assertThat(result.extractedSlots["insuranceType"]).isEqualTo("基金")
        assertThat(result.matchedEvidence.any { it.startsWith("insurance_finance:") && it.endsWith(":entity_derived:insuranceType") })
            .isTrue()
    }

    @Test
    fun clauseAxisCoversResponsibilityNarrativesAsOneBusinessCategory() = runTest {
        val texts = listOf(
            "您购买的是基础医疗保障不包含住院津贴和长期护理责任",
            "您申请的医疗理赔中有一部分费用不在合同约定的责任范围内",
            "您购买的分红保险包含保证利益和非保证利益",
        )

        texts.forEach { text ->
            val result = checkNotNull(classifier.classifyDetailed(text, setOf(SceneType.INSURANCE_FINANCE)))
            assertWithMessage("input: %s", text).that(result.scene)
                .isEqualTo(SceneType.INSURANCE_FINANCE.id)
            assertWithMessage("input: %s", text)
                .that(result.matchedEvidence.any { it.contains(":domain_axis:clause:") })
                .isTrue()
        }
    }

    @Test
    fun officialChannelSecurityReminderDoesNotCreateSpamRisk() = runTest {
        val result = checkNotNull(classifier.classifyDetailed(
            "您提交的保单查询申请已经受理请通过官方应用查看处理结果我们不会在电话中索要密码或短信验证码",
            setOf(SceneType.INSURANCE_FINANCE, SceneType.SPAM_RISK),
        ))

        assertThat(result.scene).isEqualTo(SceneType.INSURANCE_FINANCE.id)
        assertThat(result.riskLevel).isNotEqualTo(RiskLevel.HIGH)
        assertThat(result.matchedEvidence.any { it.contains("R3_authority_impersonation") }).isFalse()
    }

    @Test
    fun userInitiatedInvestmentStateDoesNotReplaceConfirmedInsuranceTopic() = runTest {
        val debugClassifier = RuleBasedIntentClassifier(
            provider = provider,
            extractor = extractor,
            debugTraceEnabled = true,
        )
        val result = checkNotNull(debugClassifier.classifyDetailed(
            "您申请购买的理财产品已经进入风险确认环节页面展示的历史业绩不代表未来实际收益",
            setOf(SceneType.INSURANCE_FINANCE, SceneType.SPAM_RISK),
        ))

        assertThat(result.scene).isEqualTo(SceneType.INSURANCE_FINANCE.id)
        assertWithMessage("result=%s", result.debugTrace).that(result.debugTrace?.risk?.topicProtected).isTrue()
    }

    @Test
    fun entityDrivenClarificationProvidesSpecificReply() = runTest {
        val decision = engine.process(
            DialogueContext("insurance-entity-driven"),
            "您持有的基金近期波动较大想确认您的风险承受能力有没有变化",
            false,
            setOf(SceneType.INSURANCE_FINANCE),
        )

        assertThat(decision.reply).contains("基金")
        assertThat(decision.replyTemplateId).isEqualTo("entity_driven_clarification")
    }

    @Test
    fun insurancePhoneticMatcherCoversNasalFinalLossWithSharedDomainAnchor() {
        val match = ChinesePhoneticMatcher.findUniqueMatch("医疗洗", "医疗险")

        assertThat(match?.level).isEqualTo("initial_sequence")
        assertThat(match?.keyword).isEqualTo("医疗险")
    }
}
