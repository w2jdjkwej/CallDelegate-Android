package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.DialogueContext
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Every scene ends its first reply with 请问需要机主回电吗, and ask_callback answered exactly three
 * things: callback_yes, callback_no, and everything else with 当前信息已经记录，我会转告机主。再见.
 * So the conversation was one exchange deep no matter how good that exchange was, and a caller who
 * said anything other than yes or no was hung up on:
 *
 *   中介：业主价格还能商量
 *   AI：  价格和付款条件需要机主本人决定…请问需要机主回电吗？
 *   中介：那这套房现在有人在租吗
 *   AI：  当前信息已经记录，我会转告机主。再见。
 *
 * Nothing was wrong with the question. It just was not yes or no. A follow-up state now hands a
 * turn back to its capture state when the capture state answers it by name, so the assistant
 * answers and asks again.
 */
class SecondTurnIsNotAHangUpTest {
    private val rules = loadProductionRuleFile()
    private val provider = RuleProvider { AppResult.Success(rules) }
    private val classifier = RuleBasedIntentClassifier(provider, RegexEntityExtractor())
    private val engine = JsonDialogueEngine(provider, classifier, RegexEntityExtractor())
    private val scenes = AppSettings().enabledScenes

    @Test
    fun allSixScenesAnswerASecondBusinessTurn() = runTest {
        val conversations = mapOf(
            "delivery" to ("您的快递到了，放在门口可以吗" to "这件包裹是到付件，签收时需要支付运费"),
            "ride_hailing" to ("我已经到您小区门口了" to "这趟行程是跨城订单，我需要确认您是否携带较多行李"),
            "customer_service" to (
                "您的维修工单已经分配给当地服务网点" to "我们需要确认您是否接受维修而不是更换全新设备"
                ),
            "real_estate" to (
                "业主刚刚把这套二手房的挂牌总价下调了五万元" to "房东可以接受一个月押金，但不接受按月支付租金"
                ),
            "insurance_finance" to (
                "您的车贷下期还款日期是本月二十八号" to "您的保单需要补充健康告知，保险公司才能继续承保审核"
                ),
            "spam_risk" to ("我们这里有低价房源推荐" to "只要缴纳税费就可以领取奖金"),
        )

        conversations.forEach { (scene, turns) ->
            val (first, second) = turns
            val opening = engine.process(DialogueContext(sessionId = "second-$scene"), first, false, scenes)
            val followUp = engine.process(opening.context, second, false, scenes)

            // spam_risk is the one scene that should end a call, and it ends it on the first turn.
            if (scene == "spam_risk") {
                assertWithMessage("scene %s must end a fraud call", scene)
                    .that(opening.shouldEnd).isTrue()
                return@forEach
            }
            assertWithMessage("scene %s: the first turn must not end the call", scene)
                .that(opening.shouldEnd).isFalse()
            assertWithMessage("scene %s: a second business turn must not be hung up on", scene)
                .that(followUp.reply).doesNotContain("当前信息已经记录，我会转告机主。再见。")
            assertWithMessage("scene %s: the second turn must be answered, not brushed off", scene)
                .that(followUp.reply).isNotEqualTo(opening.reply)
        }
    }

    /** Yes and no still end the call, which is the whole point of asking. */
    @Test
    fun answeringTheCallbackQuestionStillEndsTheCall() = runTest {
        listOf("需要回电" to true, "不用回电了" to true).forEach { (answer, shouldEnd) ->
            val opening = engine.process(
                DialogueContext(sessionId = "callback"),
                "业主刚刚把这套二手房的挂牌总价下调了五万元",
                false,
                scenes,
            )
            val closing = engine.process(opening.context, answer, false, scenes)
            assertWithMessage("answering '%s' must end the call", answer)
                .that(closing.shouldEnd).isEqualTo(shouldEnd)
        }
    }

    /**
     * A turn with nothing in it is asked again rather than hung up on, which is what the retry
     * strategy is for -- but the retries are finite, so the call cannot be held open indefinitely.
     */
    @Test
    fun anEmptySecondTurnIsAskedAgainAndThenEnds() = runTest {
        var context = engine.process(
            DialogueContext(sessionId = "unanswerable"),
            "业主刚刚把这套二手房的挂牌总价下调了五万元",
            false,
            scenes,
        ).context

        val first = engine.process(context, "嗯嗯嗯", false, scenes)
        assertWithMessage("a turn with nothing in it deserves the question again, not a hang-up")
            .that(first.shouldEnd).isFalse()
        context = first.context

        var ended = false
        repeat(8) {
            if (!ended) {
                val decision = engine.process(context, "嗯嗯嗯", false, scenes)
                context = decision.context
                ended = decision.shouldEnd
            }
        }
        assertWithMessage("retries are finite; the call must not stay open forever")
            .that(ended).isTrue()
    }
}
