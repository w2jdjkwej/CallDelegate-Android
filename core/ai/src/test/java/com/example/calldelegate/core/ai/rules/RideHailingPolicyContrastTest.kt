package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RideHailingPolicyContrastTest {
    private val classifier = RuleBasedIntentClassifier(
        provider = RuleProvider { AppResult.Success(loadProductionRuleFile()) },
    )

    @Test
    fun referenceLikeRideExpressionsUseIndependentGateAndIntentPrecedence() = runTest {
        val cases = mapOf(
            "您好我是接您的网约车司机已经接到您的订单了" to "confirm_order_info",
            "我是接您这笔订单的司机车牌尾号是六八二一" to "confirm_order_info",
            "我开的是一辆白色轿车已经停在上车点旁边" to "driver_arrived",
            "我已经到达上车定位附近请确认是否能看到我的车辆" to "confirm_pickup_location",
            "这边不能长时间停车请您准备好后尽快到上车点上车" to "urge_passenger",
            "您设置的上车点在小区南门请确认您是否正在南门候车" to "confirm_pickup_location",
            "我现在在您设置的上车点马路对面准备绕行到您这一侧接您" to "confirm_pickup_location",
            "小区北门暂时封闭我改到东门接您可以吗" to "trip_exception",
            "您的上车点在机场二号航站楼请确认您是在出发层还是到达层等车" to "confirm_pickup_location",
            "我已经进入地下停车场现在停在负一层电梯口旁边的上客区" to "driver_arrived",
            "我已经在上车点等了几分钟但没有看到您请确认是否站在另一个出口" to "cannot_find_passenger",
            "您的上车定位显示在高架桥上但我现在位于桥下辅路请告诉我您靠近哪个匝道口" to "ask_passenger_location",
            "我不在医院正门而是在正门向东五十米的临时上客区车牌尾号是零二一" to "confirm_pickup_location",
            "您刚刚修改了上车点我需要掉头前往新的位置预计比原来晚五分钟到达" to "trip_exception",
            "我接到的是手机号尾号三七五九的乘客请您确认一下避免上错车辆" to "confirm_order_info",
            "订单显示您要前往机场二号航站楼请确认是国内出发还是国际出发" to "confirm_order_info",
            "前往目的地的原路线因为交通事故封闭我准备改走环城路" to "trip_exception",
            "车辆刚刚出现故障暂时无法前往上车点接您我正在联系平台重新派车" to "trip_exception",
        )

        cases.forEach { (text, expectedIntent) ->
            assertWithMessage("policy input=$text")
                .that(RideHailingIntentPolicy.decide(text))
                .isNotNull()
            val result = classifier.classifyDetailed(text, setOf(SceneType.RIDE_HAILING))
            assertWithMessage("input=$text")
                .that(result?.scene)
                .isEqualTo(SceneType.RIDE_HAILING.id)
            assertWithMessage("input=$text")
                .that(result?.intent)
                .isEqualTo(expectedIntent)
        }
    }

    @Test
    fun sharedWordsAndForeignSceneCuesDoNotOpenRideScene() = runTest {
        val hardNegatives = listOf(
            "快递订单显示在机场二号航站楼请确认收货地址",
            "我是保险公司的司机培训专员想核对保单",
            "车牌尾号用于车险续保请确认资料",
            "我在会议室门口等您请尽快进来",
            "平台确认了酒店预订订单目的地是机场",
            "请告诉我会议室在哪个出口",
            "订单已经确认",
        )

        hardNegatives.forEach { text ->
            val result = classifier.classifyDetailed(text, setOf(SceneType.RIDE_HAILING))
            assertWithMessage("input=$text")
                .that(result?.scene)
                .isNotEqualTo(SceneType.RIDE_HAILING.id)
        }
    }
}
