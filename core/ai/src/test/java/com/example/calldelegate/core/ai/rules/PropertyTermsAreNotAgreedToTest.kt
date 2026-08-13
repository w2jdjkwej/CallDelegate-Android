package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.DialogueContext
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * An estate agent quoting a price, naming a landlord's terms or proposing a viewing is not making
 * conversation. They are waiting for an answer, and silence from something that sounds like the
 * owner is an answer -- 房产事项已记录，请问需要机主回电吗 leaves 价格还能进一步商量 looking like
 * it was taken on board.
 *
 * The scene declares sixteen intents and capture_property had transitions for eight, so
 * price_negotiation, transaction_process, rental_business, viewing_follow_up, purchase_follow_up,
 * property_attribute, property_status and property_risk_disclosure all fell to the catch-all. Two
 * turns in three of the fourth blind set's real_estate cases got that one sentence.
 */
class PropertyTermsAreNotAgreedToTest {
    private val rules = loadProductionRuleFile()
    private val provider = RuleProvider { AppResult.Success(rules) }
    private val classifier = RuleBasedIntentClassifier(provider, RegexEntityExtractor())
    private val engine = JsonDialogueEngine(provider, classifier, RegexEntityExtractor())
    private val scenes = AppSettings().enabledScenes

    private suspend fun replyTo(text: String) =
        engine.process(DialogueContext(sessionId = "property"), text, false, scenes).reply

    @Test
    fun priceAndTermsAreRefusedByName() = runTest {
        val cases = mapOf(
            "业主希望买方首付款比例高一些，价格还能进一步商量" to "机主本人决定",
            "房东可以接受一个月押金，但不接受按月支付租金" to "机主本人决定",
            "房东愿意承担物业费，但希望租金按照季度支付" to "机主本人决定",
            "这套住宅属于满两年房源，过户税费需要根据买方情况计算" to "机主本人办理",
            "您关注的房子已经取得不动产权证，可以正常办理交易" to "机主本人办理",
        )
        cases.forEach { (text, expected) ->
            assertWithMessage("input: %s must say who settles it", text)
                .that(replyTo(text)).contains(expected)
        }
    }

    /** 带看 is a viewing, not a letting term, and it was a rental_business keyword. */
    @Test
    fun aProposedViewingIsAnsweredAsAViewing() = runTest {
        val text = "这套房离您公司只有三站地铁，我可以今晚安排带看"
        assertWithMessage("input: %s", text).that(replyTo(text)).contains("看房")
        assertWithMessage("input: %s must not be answered as a letting term", text)
            .that(replyTo(text)).doesNotContain("租金和租约条件")
    }

    /** Descriptions of the property are still just recorded, and say what was recorded. */
    @Test
    fun descriptionsAreRecordedNotRefused() = runTest {
        val cases = listOf(
            "房子的客厅和两个卧室都朝南，全天采光比较好",
            "这套房产证面积是一百零三平方米，三室两厅",
            "这个楼盘目前已经交房，可以直接查看实体房源",
        )
        cases.forEach { text ->
            assertWithMessage("input: %s must not be refused", text)
                .that(replyTo(text)).doesNotContain("我不能代为")
            assertWithMessage("input: %s must no longer get the catch-all", text)
                .that(replyTo(text)).doesNotContain("房产事项已记录")
        }
    }
}
