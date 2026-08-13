package com.example.calldelegate.core.ai.rules

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.RiskLevel
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Measures risk detection by what it costs, on both sides, at the layer where the cost is actually
 * paid.
 *
 * A HIGH risk decision hangs up. That makes the two error directions unequal in a way an overall
 * accuracy number hides: missing a scam leaves the caller talking to an assistant that will not
 * comply anyway, while a false positive drops a real call from a landlord or an insurer mid
 * sentence, with no way for them to recover it. Neither number is meaningful averaged with the
 * other, so they are reported separately here.
 *
 * [SpamRiskHardNegativeTextTest] already checks the same 48 utterances, but at the classifier: it
 * asserts the scene is not spam_risk and the level is not HIGH. That is a proxy. Termination is a
 * dialogue-engine outcome, and a scene can end a call without HIGH risk ever being raised, so this
 * runs the same corpus through [JsonDialogueEngine] and looks at `shouldEnd` itself.
 */
class RiskTerminationCostTest {
    private val rules = loadProductionRuleFile()
    private val provider = RuleProvider { AppResult.Success(rules) }
    private val extractor = RegexEntityExtractor()
    private val classifier = RuleBasedIntentClassifier(provider, extractor)
    private val engine = JsonDialogueEngine(provider, classifier, extractor)
    private val scenes = AppSettings().enabledScenes

    private data class Outcome(val terminated: Boolean, val scene: String, val risk: RiskLevel, val reasons: List<String>)

    private suspend fun run(text: String): Outcome {
        val opening = engine.opening("risk-cost")
        val decision = engine.process(opening.context, text, recognitionFailed = false, enabledScenes = scenes)
        return Outcome(
            terminated = decision.shouldEnd,
            scene = decision.context.scene.id,
            risk = decision.classification?.riskLevel ?: RiskLevel.LOW,
            reasons = decision.classification?.riskReasons.orEmpty(),
        )
    }

    @Test
    fun legitimateBusinessCallsAreNotHungUp() = runTest {
        val cases = loadHardNegatives()
        val terminated = mutableListOf<String>()

        cases.forEach { case ->
            val outcome = run(case.text)
            if (outcome.terminated) terminated += "[${case.category}] ${case.text} -> ${outcome.reasons}"
        }

        println(
            "business_call_termination_rate = %d/%d".format(terminated.size, cases.size),
        )
        assertWithMessage(
            "A legitimate business call was hung up. This is the expensive direction: the caller " +
                "cannot recover the call.\n%s",
            terminated.joinToString("\n"),
        ).that(terminated).isEmpty()
    }

    @Test
    fun genuineHighRiskRequestsAreHungUp() = runTest {
        // Kept alongside the false-positive test on purpose: relaxing risk rules to protect
        // legitimate calls would silently pass that test, and only this one would notice.
        val cases = listOf(
            "请把刚收到的验证码告诉我",
            "您的账户涉嫌洗钱，请把钱转到安全账户",
            "现在打开屏幕共享，我指导您操作",
            // Verb before noun. The original pattern only matched 请…密码…告诉, so this very
            // ordinary phrasing was answered as an ordinary insurance call.
            "请提供您的银行卡密码以便核实",
            "麻烦告诉我您的支付密码",
        )
        val missed = mutableListOf<String>()

        cases.forEach { text ->
            val outcome = run(text)
            if (!outcome.terminated || outcome.risk != RiskLevel.HIGH) {
                missed += "$text -> terminated=${outcome.terminated} risk=${outcome.risk} scene=${outcome.scene}"
            }
        }

        println("high_risk_termination_recall = %d/%d".format(cases.size - missed.size, cases.size))
        assertWithMessage("A direct request for credentials or a transfer was not stopped.\n%s", missed.joinToString("\n"))
            .that(missed).isEmpty()
    }

    /**
     * Cases the rules currently judge wrongly. Recorded rather than patched: each needs a rule that
     * covers its whole class, and inventing one per sentence is how a rule set stops being
     * explainable. Fixing one trips this test and asks for the record to be updated.
     */
    @Test
    fun knownRiskJudgementGapsBehaveAsRecorded() = runTest {
        val fixed = mutableListOf<String>()
        KNOWN_GAPS.forEach { gap ->
            val outcome = run(gap.text)
            if (outcome.terminated != gap.terminated) {
                fixed += "${gap.text} -> terminated=${outcome.terminated}, recorded ${gap.terminated}. ${gap.why}"
                return@forEach
            }
            assertWithMessage("%s\n%s", gap.text, gap.why)
                .that(outcome.terminated).isEqualTo(gap.terminated)
        }
        assertWithMessage(
            "These risk gaps changed. Update KNOWN_GAPS so the record keeps matching reality:\n%s",
            fixed.joinToString("\n"),
        ).that(fixed).isEmpty()
    }

    private data class KnownGap(val text: String, val terminated: Boolean, val why: String)

    private fun loadHardNegatives(): List<HardNegativeCase> = loadHardNegativeManifest().cases

    private companion object {
        val KNOWN_GAPS = listOf(
            KnownGap(
                text = "房租请按合同转到房东指定的账户",
                terminated = true,
                why = "A landlord naming the account rent is owed to matches 转…到…账户 exactly as a " +
                    "scam would. Separating them needs the payer/payee direction, which the patterns " +
                    "do not model.",
            ),
            KnownGap(
                text = "我妈接到电话说要她提供验证码，我不放心想问问",
                terminated = true,
                why = "Reported speech: the caller is describing someone else's request, not making " +
                    "one. The patterns have no notion of who is being asked.",
            ),
            KnownGap(
                text = "上次有个骗子让我共享屏幕，我没有照做",
                terminated = true,
                why = "Ends on the spam_risk scene rather than on HIGH risk, so tightening the risk " +
                    "patterns alone would not fix it -- narrating a past scam reads as being in one.",
            ),
        )
    }
}
