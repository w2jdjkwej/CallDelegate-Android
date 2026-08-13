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

class CustomerServiceTargetIntentTest {
    private val rules = loadProductionRuleFile()
    private val provider = RuleProvider { AppResult.Success(rules) }
    private val extractor = RegexEntityExtractor()
    private val classifier = RuleBasedIntentClassifier(provider, extractor)
    private val engine = JsonDialogueEngine(provider, classifier, extractor)

    @Test
    fun recognizesCustomerServiceBusinessIntents() = runTest {
        val cases = mapOf(
            "京东客服来电核实订单尾号123456" to "order_inquiry",
            "平台客服正在处理您的售后申请" to "after_sales",
            "订单里的商品缺货，需要换成其他款式" to "order_change",
            "您申请的退款已经原路退回" to "refund_notice",
            "您的退款还在处理中，来电同步退款进度" to "refund_progress",
            "这次来电是对之前的投诉进行回访" to "complaint_followup",
            "平台客服进行服务满意度回访" to "service_followup",
            "客服想向您推荐会员升级优惠套餐" to "service_marketing",
            "您好这里是商城客服想确认一下您是否已经收到商品" to "service_identity",
            "您的维修工单已经受理工程师稍后会联系您" to "after_sales",
            "您的退款包含现金优惠券和积分三部分会分别退回" to "refund_notice",
            "您投诉的问题涉及商家配送员和平台三方责任" to "complaint_followup",
            "您申请退款已经审核通过" to "refund_notice",
            "您的维修公担已经受理工程师稍后会联系您" to "after_sales",
            "您购买的冰箱需要更换配件师傅周六下午可以上门" to "after_sales",
            "您申请退货时选择了质量问题请补充能够显示故障现象的照片或视频" to "after_sales",
        )

        cases.forEach { (text, expectedIntent) ->
            val result = classifier.classifyDetailed(text, setOf(SceneType.CUSTOMER_SERVICE))
            assertWithMessage("input: %s", text).that(result?.scene).isEqualTo(SceneType.CUSTOMER_SERVICE.id)
            assertWithMessage("input: %s", text).that(result?.intent).isEqualTo(expectedIntent)
        }
    }

    @Test
    fun phoneticEvidenceRequiresAUniqueStrongAnchor() {
        assertThat(
            ChinesePhoneticMatcher.findUniqueMatch(
                "您的话和商品一经发出",
                "换货商品",
            )?.sourceWindow,
        ).isEqualTo("话和商品")
        assertThat(
            ChinesePhoneticMatcher.findUniqueMatch(
                "引起和的商品缺少充电器",
                "换货商品",
            ),
        ).isNull()
    }

    @Test
    fun extractsCustomerServiceEntities() = runTest {
        val result = extractor.extract(
            SlotExtractionRequest(
                text = "我是京东平台客服，订单号JD123456正在进行售后换货，联系电话13800138000",
                expectedSlots = setOf(
                    "organization",
                    "platform",
                    "orderId",
                    "serviceType",
                    "issueType",
                    "contact",
                ),
                scene = SceneType.CUSTOMER_SERVICE,
            ),
        ).slots

        assertThat(result["organization"]).isEqualTo("京东")
        assertThat(result["platform"]).isEqualTo("京东")
        assertThat(result["orderId"]).isEqualTo("JD123456")
        assertThat(result["serviceType"]).isEqualTo("售后")
        assertThat(result["issueType"]).isEqualTo("换货")
        assertThat(result["contact"]).isEqualTo("13800138000")
    }

    @Test
    fun riskLayerOverridesImpersonatedCustomerService() = runTest {
        val riskyText = "我是平台客服，您的账户异常，请把短信验证码告诉我"
        val classification = classifier.classifyDetailed(
            riskyText,
            setOf(SceneType.CUSTOMER_SERVICE),
        )

        assertThat(classification?.riskLevel).isEqualTo(RiskLevel.HIGH)
        assertThat(classification?.extractedSlots?.get("sensitiveInfoType")).contains("sms_code")

        val decision = engine.process(
            DialogueContext("customer-risk"),
            riskyText,
            false,
            setOf(SceneType.CUSTOMER_SERVICE),
        )
        assertThat(decision.shouldEnd).isTrue()
        assertThat(decision.reply).contains("不能提供验证码")
        assertThat(decision.reply).doesNotContain("需要回电")
    }

    @Test
    fun ordinaryRefundNoticeIsNotPromotedToHighRisk() = runTest {
        val result = classifier.classifyDetailed(
            "您好，我是平台客服，您申请的退款已经原路退回",
            setOf(SceneType.CUSTOMER_SERVICE, SceneType.SPAM_RISK),
        )

        assertThat(result?.scene).isEqualTo(SceneType.CUSTOMER_SERVICE.id)
        assertThat(result?.intent).isEqualTo("refund_notice")
        assertThat(result?.riskLevel).isNotEqualTo(RiskLevel.HIGH)
    }
}
