package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.RiskLevel
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The risk detector may override any scene, at any score, and set confidence to 0.95. That power is
 * right -- a scam wearing a courier's words has to be stoppable -- and it is why the patterns that
 * wield it have to be about intent rather than about subject matter.
 *
 * Two of them were not. 这个包裹是到付件，签收时需要支付运费 was answered as fraud at 1.00 because
 * 运费 sat in a list beside 保证金 and 解冻金, and 这份外卖需要您提供楼栋门禁密码，我才能送到房门口
 * was answered as fraud at 1.00 because a door code and a banking credential are both 密码. Both
 * turns had already matched delivery's own anchor and every keyword it owns. Cash on delivery and
 * a gate code are what couriers phone about; a demo caller placing a to-pay parcel would have been
 * told the assistant suspects them of fraud.
 *
 * Recorded on the fourth blind set, 2026-08-08.
 */
class LegitimateRequestIsNotFraudTest {
    private val rules = loadProductionRuleFile()
    private val classifier = RuleBasedIntentClassifier(RuleProvider { AppResult.Success(rules) })
    private val enabledScenes = AppSettings().enabledScenes

    @Test
    fun ordinaryCourierRequestsAreNotTreatedAsFraud() = runTest {
        val cases = listOf(
            "这个包裹是到付件，签收时需要支付运费",
            "这份外卖需要您提供楼栋门禁密码，我才能送到房门口",
            "这件快递需要收件人提供取件码，我才能完成交付",
        )

        cases.forEach { text ->
            val result = classifier.classifyDetailed(text, enabledScenes)
            assertWithMessage("input: %s must stay with delivery", text)
                .that(result?.scene).isEqualTo(SceneType.DELIVERY.id)
            assertWithMessage("input: %s must not be escalated to high risk", text)
                .that(result?.riskLevel).isNotEqualTo(RiskLevel.HIGH)
        }
    }

    /**
     * The narrowing above must not have bought itself with coverage. Each of these is the same
     * request made by a scam, and each must still be caught.
     */
    @Test
    fun theSameRequestMadeByAScamIsStillCaught() = runTest {
        val cases = listOf(
            "您的退款通道被冻结需要先转一笔保证金验证账户之后会全部返还",
            "您有一笔海外包裹被海关扣留，需要缴纳解冻费",
            "您购买的机票出现异常，重新出票前需要先缴纳认证资金",
            "我是银行风控中心，您的账户涉嫌洗钱，需要提供短信验证码",
            "您的个人征信将在今晚被列入黑名单，缴纳处理费可以立即解除",
        )

        cases.forEach { text ->
            val result = classifier.classifyDetailed(text, enabledScenes)
            assertWithMessage("input: %s must be caught as fraud", text)
                .that(result?.scene).isEqualTo(SceneType.SPAM_RISK.id)
        }
    }

    /**
     * 我 next to an arrival verb is a driver; 我 ten characters away from one is a coincidence.
     * (?:司机|师傅|我).{0,10}到了 read 我这边筛到了三套符合预算的出租房 as a taxi pulling up, and
     * 我给您送的鲜花到了 likewise.
     */
    @Test
    fun anArrivalNeedsSomebodyArriving() = runTest {
        val notArrivals = mapOf(
            "您希望找两室一厅，我这边筛到了三套符合预算的出租房" to SceneType.REAL_ESTATE,
            "我给您送的鲜花到了，请问是直接交给收件人吗" to SceneType.DELIVERY,
        )
        notArrivals.forEach { (text, expected) ->
            assertWithMessage("input: %s", text)
                .that(classifier.classifyDetailed(text, enabledScenes)?.scene)
                .isEqualTo(expected.id)
        }

        val arrivals = listOf("我到了", "我已经到您小区门口了", "司机已经到达上车点", "我现在到楼下了")
        arrivals.forEach { text ->
            assertWithMessage("input: %s must still read as an arrival", text)
                .that(classifier.classifyDetailed(text, enabledScenes)?.scene)
                .isEqualTo(SceneType.RIDE_HAILING.id)
        }
    }
}
