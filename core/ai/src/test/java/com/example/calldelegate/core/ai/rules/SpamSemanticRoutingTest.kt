package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The fraud-marker layer decides two things, and this covers the second one.
 *
 * The first is whether a call is fraud at all, and it was already right often enough to be
 * trusted: a semantic score that clears natureThreshold on its own is lifted above every business
 * scene, which is the only way a scam wearing another domain's vocabulary can be caught. 您有一笔
 * 海外包裹被海关扣留，需要缴纳解冻费 is, word for word, a delivery call.
 *
 * The second is which spam intent answers it, and that was wrong for every turn the layer caught,
 * because the score went to marketing_pitch whatever had matched. Each intent replies differently
 * -- marketing_pitch says 机主目前不考虑相关服务, coercion says 我不会接受威胁或代替机主执行任何
 * 操作 -- so a police-impersonation scam demanding a transfer was answered as though it were
 * selling something. Right scene, wrong register, and the register is the part the caller hears.
 *
 * Primitives now name the intent they are evidence for and the heaviest match decides. The scoring
 * is untouched: these turns cleared the threshold before this test existed and still do.
 */
class SpamSemanticRoutingTest {
    private val rules = loadProductionRuleFile()
    private val classifier = RuleBasedIntentClassifier(RuleProvider { AppResult.Success(rules) })
    private val enabledScenes = AppSettings().enabledScenes

    @Test
    fun aThreatIsAnsweredAsAThreatAndAPitchAsAPitch() = runTest {
        val cases = mapOf(
            // Property held hostage until a fee is paid: R14 and R10, both coercion.
            "您有一笔海外包裹被海关扣留，需要缴纳解冻费" to "coercion",
            // Impersonated authority plus an instruction not to hang up.
            "这里是公安机关，您的银行卡涉及案件，禁止挂断电话并联系其他人" to "coercion",
            "我们可以代办政府补贴，先支付两百元材料费，审核不过全额退款" to "coercion",
            // A prize and a yield are sales talk however the money is collected.
            "您中了本期手机大奖，只需要支付九十九元税费就能安排寄送" to "marketing_pitch",
            "加入我们的投资群，每天都有老师推荐涨停股票" to "marketing_pitch",
            "我们有一批法院低价房源，只对内部客户开放" to "marketing_pitch",
        )

        cases.forEach { (text, expectedIntent) ->
            val result = classifier.classifyDetailed(text, enabledScenes)
            assertWithMessage("input: %s", text)
                .that(result?.scene).isEqualTo(SceneType.SPAM_RISK.id)
            assertWithMessage("input: %s must be answered as %s", text, expectedIntent)
                .that(result?.intent).isEqualTo(expectedIntent)
        }
    }

    /**
     * The layer earns its override by staying silent on business that merely shares fraud's
     * subject matter. 业主要求签约当天支付定金并核验贷款资格 scored 0.82 and took a real_estate
     * turn, on 当天 read as urgency and a bare 贷款 read as a financial pitch.
     */
    @Test
    fun ordinaryBusinessDoesNotTripTheOverride() = runTest {
        val cases = mapOf(
            "业主要求签约当天支付定金并核验贷款资格" to SceneType.REAL_ESTATE,
            "这笔贷款本月应还金额包含本金和利息" to SceneType.INSURANCE_FINANCE,
            "您申请的退保正在核算现金价值" to SceneType.INSURANCE_FINANCE,
        )

        cases.forEach { (text, expected) ->
            val result = classifier.classifyDetailed(text, enabledScenes)
            assertWithMessage("input: %s must not be read as fraud", text)
                .that(result?.scene).isEqualTo(expected.id)
        }
    }
}
