package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.CallNature
import com.example.calldelegate.domain.model.RiskLevel
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SpamRiskOptimizationTest {
    private val productionRules = loadProductionRuleFile()
    private val enabledScenes = SceneType.entries.filterNot { it == SceneType.UNCLASSIFIED }.toSet()

    @Test
    fun highRiskOverridesBusinessSceneAndRetainsTopic() = runTest {
        val classifier = classifier(productionRules)

        val sms = checkNotNull(classifier.classifyDetailed(
            "您的信用卡出现异常消费请告诉我短信验证码以便取消交易",
            enabledScenes,
        ))
        assertThat(sms.scene).isEqualTo(SceneType.SPAM_RISK.id)
        assertThat(sms.topicScene).isEqualTo(SceneType.INSURANCE_FINANCE.id)
        assertThat(sms.riskLevel).isEqualTo(RiskLevel.HIGH)
        assertThat(sms.riskReasons).contains("request_sms_code")

        val screenShare = checkNotNull(classifier.classifyDetailed(
            "您的账户涉嫌异常交易请配合开启屏幕共享核查资金",
            enabledScenes,
        ))
        assertThat(screenShare.scene).isEqualTo(SceneType.SPAM_RISK.id)
        assertThat(screenShare.topicScene).isEqualTo(SceneType.INSURANCE_FINANCE.id)
        assertThat(screenShare.riskReasons).contains("request_screen_share")
    }

    @Test
    fun annotatePolicyPreservesBusinessScene() = runTest {
        val rules = productionRules.copy(
            safety = productionRules.safety.copy(riskSceneCommitPolicy = RiskSceneCommitPolicy.ANNOTATE),
        )
        val result = checkNotNull(classifier(rules).classifyDetailed(
            "您的信用卡出现异常消费请告诉我短信验证码以便取消交易",
            enabledScenes,
        ))

        assertThat(result.scene).isEqualTo(SceneType.INSURANCE_FINANCE.id)
        assertThat(result.topicScene).isNull()
        assertThat(result.riskLevel).isEqualTo(RiskLevel.HIGH)
    }

    @Test
    fun expandedRiskRulesCoverReferenceAndObservedAsrPhrases() = runTest {
        val classifier = classifier(productionRules)
        val cases = mapOf(
            "我们不需要银行卡密码只要您报出验证码就能完成身份认证" to "request_sms_code",
            "您的退款通道被冻结需要先转移比保证金验证账户之后会全部返还" to "request_transfer",
            "平台正在清退网课费用请进入指定群聊按照克服要求完成登记" to "request_unknown_channel",
            "您之前投资的项目可以申请退款但必须先购买指定证券完成资金对冲" to
                "request_investment_action",
            "我们是受平台委托做权益确认不卖保险也不推荐理财你先说一下身份证后四位和开户银行" to
                "request_partial_identity",
            "您的会员已经自动续费我现在知道您打开手机银行办理退款" to "guided_banking_operation",
            "您的快递虽然显示签收但平台笑笑您赔偿请先下载会软件" to "request_unknown_app",
            "您的身份信息可能被别人注册了贷款账户现在为您转接公安部门" to "impersonate_authority",
            "您购买的商品检测不合格需要通过指定页面申请退款" to "request_unknown_channel",
        )

        cases.forEach { (text, expectedRiskId) ->
            val result = checkNotNull(classifier.classifyDetailed(text, enabledScenes))
            assertWithMessage("input: %s", text).that(result.scene).isEqualTo(SceneType.SPAM_RISK.id)
            assertWithMessage("input: %s", text).that(result.riskLevel).isEqualTo(RiskLevel.HIGH)
            assertWithMessage("input: %s", text).that(result.riskReasons).contains(expectedRiskId)
        }
    }

    @Test
    fun twoMediumSignalsEscalateButOneMediumDoesNotOverride() = runTest {
        val classifier = classifier(productionRules)

        val combined = checkNotNull(classifier.classifyDetailed(
            "我是银行客服您的账户涉嫌洗钱需要配合调查，不准挂电话否则后果自负",
            enabledScenes,
        ))
        assertThat(combined.riskLevel).isEqualTo(RiskLevel.HIGH)
        assertThat(combined.scene).isEqualTo(SceneType.SPAM_RISK.id)
        assertThat(combined.riskReasons).containsAtLeast("suspicious_financial_identity", "coercive_call")

        val single = checkNotNull(classifier.classifyDetailed(
            "我是银行客服您的账户涉嫌洗钱需要配合调查",
            enabledScenes,
        ))
        assertThat(single.riskLevel).isEqualTo(RiskLevel.MEDIUM)
        assertThat(single.callNature).isEqualTo(CallNature.SUSPICIOUS)
    }

    @Test
    fun callerPreemptiveDenialIsPositiveButUserRefusalAndSafetyWarningRemainNegative() = runTest {
        val classifier = classifier(productionRules)

        val preemptive = checkNotNull(classifier.classifyDetailed(
            "我们不是推销只想做个简单回访顺便介绍一项会员权益",
            enabledScenes,
        ))
        assertThat(preemptive.scene).isEqualTo(SceneType.SPAM_RISK.id)
        assertThat(preemptive.intent).isEqualTo("marketing_pitch")
        assertThat(preemptive.matchedEvidence).contains("spam_risk:marketing_pitch:preemptive_denial")

        val refusal = checkNotNull(classifier.classifyDetailed("我不需要贷款", enabledScenes))
        assertThat(refusal.scene).isNotEqualTo(SceneType.SPAM_RISK.id)
        assertThat(refusal.riskLevel).isEqualTo(RiskLevel.LOW)

        val warning = checkNotNull(classifier.classifyDetailed("验证码请勿告诉任何人", enabledScenes))
        assertThat(warning.scene).isNotEqualTo(SceneType.SPAM_RISK.id)
        assertThat(warning.riskLevel).isEqualTo(RiskLevel.LOW)
    }

    @Test
    fun debugTraceReportsScoresEvidenceRiskGateAndFinalSource() = runTest {
        val classifier = classifier(productionRules, debugTraceEnabled = true)
        val result = checkNotNull(classifier.classifyDetailed(
            "您的信用卡出现异常消费请告诉我短信验证码以便取消交易",
            enabledScenes,
        ))
        val trace = checkNotNull(result.debugTrace)

        assertThat(trace.inputText).contains("短信验证码")
        assertThat(trace.sceneScores).isNotEmpty()
        assertThat(trace.intentScores).isNotEmpty()
        assertThat(trace.intentScores.flatMap { it.evidence }).isNotEmpty()
        assertThat(trace.thresholds.minimumSceneScore).isGreaterThan(0f)
        assertThat(trace.risk.invoked).isTrue()
        assertThat(trace.risk.matchedRiskIds).contains("request_sms_code")
        assertThat(trace.risk.overrideApplied).isTrue()
        assertThat(trace.finalScene).isEqualTo(SceneType.SPAM_RISK.id)
        assertThat(trace.finalSceneSource).isEqualTo("RISK_OVERRIDE")
    }

    @Test
    fun customerServiceContextDowngradesSensitiveInfoDisclaimerInsteadOfOverridingScene() = runTest {
        val classifier = classifier(productionRules, debugTraceEnabled = true)
        val result = checkNotNull(classifier.classifyDetailed(
            "为了核实售后申请请您通过订单页面上传材料我们不会要求您提供密码或短信验证码",
            enabledScenes,
        ))

        assertThat(result.scene).isEqualTo(SceneType.CUSTOMER_SERVICE.id)
        assertThat(result.riskLevel).isEqualTo(RiskLevel.MEDIUM)
        assertThat(result.riskReasons).containsExactly("context_exemption")
        assertThat(result.debugTrace?.risk?.contextExemptedRiskIds).contains("request_sms_code")
    }

    @Test
    fun completedAutoRenewalCancellationDoesNotRemainAThreat() = runTest {
        val result = checkNotNull(classifier(productionRules).classifyDetailed(
            "您之前申请关闭会员自动续费目前已经处理完成本月到期后不会再次扣款",
            enabledScenes,
        ))

        assertThat(result.scene).isEqualTo(SceneType.CUSTOMER_SERVICE.id)
        assertThat(result.riskLevel).isEqualTo(RiskLevel.LOW)
        assertThat(result.riskReasons).isEmpty()
    }

    @Test
    fun semanticPrimitivesRecoverSpamRiskCoverageWithoutLoweringGlobalThreshold() = runTest {
        val cases = mapOf(
            "您好需要办理贷款吗" to "R9b_need_probe",
            "我们这里有低价保险" to "R1c_price_bait",
            "免费礼品需要了解一下吗" to "R1b_gift_bait",
            "我们的新楼盘正在做活动" to "R9a_explicit_promotion",
            "现在充值一千赠送五百" to "R1b_gift_bait",
            "您的号码被抽中可以领取奖品" to "R1a_prize_bait",
            "我们可以帮您快速提高信用额度" to "R7_financial_action",
            "这里有一款高收益理财产品推荐给您" to "R1c_price_bait",
            "我们是教育机构想送您一次免费试听课" to "R1b_gift_bait",
            "您之前浏览过汽车现在这款车正在降价促销" to "R9a_explicit_promotion",
            "我们这里有内部房源价格比市场价低今天可以安排看房" to "R1c_price_bait",
            "您符合贷款条件不需要抵押当天就可以放款" to "R7_financial_action",
            "您的电话套餐费用偏高现在可以免费升级到更优惠的套餐" to "R9a_explicit_promotion",
            "您的保险即将失效今天不处理就会自动产生新的费用" to "R4_loss_threat",
            "您开通了一项直播服务不及时关闭每个月会扣两千元" to "R4_loss_threat",
            "您的快递运输途中损坏我们可以为您办理三倍赔偿" to "R1c_price_bait",
            "这里是平台高级客服系统误把您设置成代理商不解除会持续扣费" to "R3_authority_impersonation",
            "您有一笔百万医疗保险正在生效今晚十二点以后会自动扣款" to "R2_urgency",
            "这是最后一次通知逾期不处理会影响您的征信请立即联系工作人员" to "R2_urgency",
        )

        cases.forEach { (text, primitiveId) ->
            val result = checkNotNull(classifier(productionRules, debugTraceEnabled = true).classifyDetailed(
                text,
                enabledScenes,
            ))
            assertWithMessage("input: %s; result: %s", text, result)
                .that(result.scene)
                .isEqualTo(SceneType.SPAM_RISK.id)
            assertWithMessage("input: %s", text)
                .that(result.matchedEvidence.any { it.contains(primitiveId) })
                .isTrue()
        }

        val weak = checkNotNull(classifier(productionRules).classifyDetailed("最近考虑买房吗", enabledScenes))
        assertThat(weak.scene).isNotEqualTo(SceneType.SPAM_RISK.id)
        assertThat(weak.confidence).isLessThan(0.55f)
    }

    @Test
    fun openingDetectionRemainsOptInForAmbiguousOpeningQuestions() = runTest {
        val rules = productionRules.copy(
            classification = productionRules.classification.copy(
                spamRiskSemantics = productionRules.classification.spamRiskSemantics.copy(
                    openingDetectionEnabled = true,
                ),
            ),
        )
        val result = checkNotNull(classifier(rules).classifyDetailed("最近考虑买房吗", enabledScenes))

        assertThat(result.scene).isEqualTo(SceneType.SPAM_RISK.id)
        assertThat(result.matchedEvidence).contains("spam_risk:marketing_pitch:semantic_primitive:R9b_need_probe")
    }

    private fun classifier(
        rules: DialogueRuleFile,
        debugTraceEnabled: Boolean = false,
    ) = RuleBasedIntentClassifier(
        provider = RuleProvider { AppResult.Success(rules) },
        extractor = RegexEntityExtractor(),
        debugTraceEnabled = debugTraceEnabled,
    )
}
