package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.DialogueContext
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Turns that ask the owner to decide something, in the two scenes that ask it most.
 *
 * Of the sixty customer_service and insurance_finance turns in the fourth blind set, about forty
 * are notifications -- 工单已分配给当地服务网点, 理赔款已进入财务支付流程, 基金今天发生分红 -- and
 * 已记录，请问需要机主回电吗 is the right answer to every one of them.
 *
 * The rest ask for something an assistant standing in for an absent owner has no standing to give.
 * Choosing between a repair and a replacement, authorising a new mainboard, filling in a health
 * disclosure, naming the account a premium is debited from: whatever it answered, the owner would
 * be bound by it. Refusing by name is the only honest reply and it tells the caller what they are
 * waiting for, which 客服售后事项已记录 does not.
 */
class DecisionsTheOwnerMustMakeTest {
    private val rules = loadProductionRuleFile()
    private val provider = RuleProvider { AppResult.Success(rules) }
    private val classifier = RuleBasedIntentClassifier(provider, RegexEntityExtractor())
    private val engine = JsonDialogueEngine(provider, classifier, RegexEntityExtractor())
    private val scenes = AppSettings().enabledScenes

    private suspend fun replyTo(text: String) =
        engine.process(DialogueContext(sessionId = "owner-decides"), text, false, scenes).reply

    @Test
    fun aChoiceBetweenOutcomesIsNotTheAssistantsToMake() = runTest {
        val cases = listOf(
            "此次商品质量问题可以选择重新发货或者直接退款",
            "我们需要确认您是否接受维修而不是更换全新设备",
            "我们检测到寄修设备需要更换主板，正在等待您的维修确认",
        )
        cases.forEach { text ->
            assertWithMessage("input: %s", text)
                .that(classifier.classifyDetailed(text, scenes)?.intent)
                .isEqualTo("service_decision_required")
            assertWithMessage("input: %s must say who decides", text)
                .that(replyTo(text)).contains("机主本人决定")
        }
    }

    @Test
    fun materialAndAddressesAreTheOwnersToSupply() = runTest {
        val text = "我是厂家售后，联系您确认上门维修的具体地址"
        assertWithMessage("input: %s", text)
            .that(classifier.classifyDetailed(text, scenes)?.intent)
            .isEqualTo("service_evidence_request")
        assertWithMessage("input: %s", text).that(replyTo(text)).contains("机主本人提供")
    }

    @Test
    fun policyChangesAndDebitAccountsNeedTheOwner() = runTest {
        val cases = listOf(
            "我是保险公司保全人员，联系您确认保单信息变更事项",
            "您的保单需要补充健康告知，保险公司才能继续承保审核",
        )
        cases.forEach { text ->
            assertWithMessage("input: %s", text)
                .that(classifier.classifyDetailed(text, scenes)?.intent)
                .isEqualTo("finance_authorization_required")
            assertWithMessage("input: %s must say who signs", text)
                .that(replyTo(text)).contains("机主本人办理")
        }
    }

    /**
     * The refusals must stay off the notifications, which are the majority. 您申请的保险金部分已经
     * 赔付，剩余项目仍在进一步审核 was answered as though something needed authorising, because
     * 审核 and 变更 were auxiliary terms and they appear in every insurance notice.
     */
    @Test
    fun notificationsAreStillJustRecorded() = runTest {
        val cases = listOf(
            "您申请的保险金部分已经赔付，剩余项目仍在进一步审核",
            "您的维修工单已经分配给当地服务网点",
            "您购买的基金今天发生分红，分红方式设置为现金分红",
            "您持有的理财产品到期后可以选择赎回或者继续持有请在到期日前确认",
        )
        cases.forEach { text ->
            val reply = replyTo(text)
            assertWithMessage("input: %s must not be refused", text)
                .that(reply).doesNotContain("我不能代为")
        }
    }
}
