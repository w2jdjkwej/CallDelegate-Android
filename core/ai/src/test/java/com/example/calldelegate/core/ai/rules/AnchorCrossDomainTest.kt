package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Guards the assumption the anchor mechanism rests on: an anchor names a domain because no other
 * domain can use that word.
 *
 * Two rules amplify an anchor. A scene that produced one sorts ahead of every scene that did not,
 * whatever they scored; and when the runner-up has no anchor, the reported margin is raised to just
 * past the clarification threshold, on the grounds that there is nothing to clarify. Both hold only
 * while the anchor really is exclusive. Where it was not, a wrong scene did not merely win -- it won
 * without ever asking the caller: 我是保险中介 went to real_estate on 中介, and 保费的发票 went to
 * customer_service on 发票, both silently, because insurance_finance declared no anchor of its own
 * and so could not outrank one.
 *
 * The loader enforces that no two scenes declare the same anchor. That catches configuration
 * conflicts, not shared meaning -- 中介 appeared in no other scene's vocabulary, yet insurance and
 * lending have agents too. The labelled corpus contains no cross-domain anchor collisions either,
 * so a green evaluation baseline said nothing about this. These cases are written by hand for that
 * reason, and they are the check that has to pass before an anchor is added or removed.
 */
class AnchorCrossDomainTest {
    private val rules = loadProductionRuleFile()
    private val provider = RuleProvider { AppResult.Success(rules) }
    private val extractor = RegexEntityExtractor()
    private val classifier = RuleBasedIntentClassifier(provider, extractor)
    private val enabledScenes = AppSettings().enabledScenes

    @Test
    fun sharedVocabularyDoesNotHandTheCallToTheWrongDomain() = runTest {
        val cases = mapOf(
            // 中介 and 保费: the agent belongs to whichever industry the rest of the sentence names.
            "我是保险中介，想跟您确认一下续保时间" to SceneType.INSURANCE_FINANCE,
            // 发票 is issued in every domain, so the domain has to come from somewhere else.
            "保费的发票麻烦寄到我家里" to SceneType.INSURANCE_FINANCE,
            "上个月的租金发票什么时候能开给我" to SceneType.REAL_ESTATE,
            // 打车 names the topic of the complaint, not the caller: this is a service call.
            "我上周打车的订单要退款，帮我处理一下" to SceneType.CUSTOMER_SERVICE,
            // 退货 says why the parcel exists; the person collecting it is a courier.
            "您要退货的包裹我现在上门取件" to SceneType.DELIVERY,
            // 返现 is ordinary after-sales wording, not by itself a fraud signal.
            "您的售后补偿会以返现的方式退回原账户" to SceneType.CUSTOMER_SERVICE,
            // 佣金 and 中介 are shared, but 链家 is a brokerage and 这套房子 is a dwelling, so this
            // sentence does name its industry and there is nothing left to ask about. It sat in
            // aBareCrossDomainTermAsksInsteadOfCommitting below until real_estate was given the
            // vocabulary to recognise 这套房子: it asked because nothing matched, not by design.
            "我是链家的中介，这套房子的佣金怎么算" to SceneType.REAL_ESTATE,
        )

        cases.forEach { (text, expected) ->
            val result = classifier.classifyDetailed(text, enabledScenes)
            assertWithMessage("input: %s", text).that(result?.scene).isEqualTo(expected.id)
            assertWithMessage("input: %s must not need clarification", text)
                .that(result?.shouldClarify).isFalse()
        }
    }

    @Test
    fun aBareCrossDomainTermAsksInsteadOfCommitting() = runTest {
        // Neither names an industry on its own. Committing to one silently is the failure mode this
        // whole test exists for -- 佣金 alone used to be answered as fraud. A third case lived here
        // once and has moved up: it named a brokerage and a dwelling, so asking was never right.
        listOf("佣金是多少", "中介费怎么算").forEach { text ->
            val result = classifier.classifyDetailed(text, enabledScenes)
            assertWithMessage("input: %s must ask rather than guess", text)
                .that(result?.shouldClarify).isTrue()
        }
    }

    /**
     * An anchor decides the domain, but it is a word, and a word can appear inside another domain's
     * account of events. Here a service agent is describing a complaint that involves a delivery,
     * and 配送员 alone used to hand the turn to delivery -- 0.67 against customer_service's 1.51,
     * unreachable at any score while the anchor was an absolute tier. Recorded on device as
     * customer_service_030 on 2026-08-08.
     *
     * The pair below is what bounds RuleThresholds.anchorDomainPriority from either side: the first
     * must stay with delivery, the second must not.
     */
    @Test
    fun anAnchorInsideAnotherDomainsAccountDoesNotDecideTheCall() = runTest {
        val complaint = classifier.classifyDetailed(
            "您投诉的问题涉及商家配送员和平台三方责任我们会先核对录音签收记录和聊天凭证再给出处理结果",
            enabledScenes,
        )
        assertWithMessage("a service agent describing a delivery complaint is a service call")
            .that(complaint?.scene).isEqualTo(SceneType.CUSTOMER_SERVICE.id)
        assertWithMessage("the evidence is not close, so there is nothing to clarify")
            .that(complaint?.shouldClarify).isFalse()

        val courier = classifier.classifyDetailed("我是配送员现在到您楼下了", enabledScenes)
        assertWithMessage("an anchor still decides an ordinary contest")
            .that(courier?.scene).isEqualTo(SceneType.DELIVERY.id)
    }

    /**
     * The other half of the contract: narrowing vocabulary to stop one scene bleeding into another
     * routinely costs cases in the scene that was narrowed, so the calls that genuinely belong to
     * these domains are pinned here too.
     */
    @Test
    fun genuineDomainCallsStillCommit() = runTest {
        val cases = mapOf(
            "我这边打车过来，司机说找不到上车点" to SceneType.RIDE_HAILING,
            "您申请的退货我们已经审核通过了" to SceneType.CUSTOMER_SERVICE,
            "我是房产中介，想约您明天看房" to SceneType.REAL_ESTATE,
            "刷单返现，先垫付一单就能赚佣金" to SceneType.SPAM_RISK,
        )

        cases.forEach { (text, expected) ->
            val result = classifier.classifyDetailed(text, enabledScenes)
            assertWithMessage("input: %s", text).that(result?.scene).isEqualTo(expected.id)
            assertWithMessage("input: %s", text).that(result?.shouldClarify).isFalse()
        }
    }
}
