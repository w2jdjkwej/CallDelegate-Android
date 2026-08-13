package com.example.calldelegate.core.ai.rules.template

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TemplateMatcherTest {

    private fun match(template: String, input: String) =
        TemplateMatcher.match(SentenceTemplate.parse(template), input)

    @Test
    fun findsTheLiteralsAndCapturesWhatSitsBetweenThem() {
        val result = match("(您的|你的)?{item}(需要|要)签字确认", "您的快件需要签字确认")

        assertThat(result).isNotNull()
        assertThat(result!!.slots).containsExactly("item", "快件")
        assertThat(result.referenceCoverage).isWithin(1e-9).of(1.0)
        assertThat(result.inputCoverage).isWithin(1e-9).of(1.0)
    }

    /**
     * The wording this whole mechanism exists for. It scores zero in every scene today because
     * 快件 and 签字确认 are in no keyword list, and no threshold or ranking rule can reach a zero.
     */
    @Test
    fun matchesAWordingThatNoKeywordListContains() {
        val result = match("{item}(需要|要)签字确认", "您的快件需要签字确认方便下来接收吗")

        assertThat(result).isNotNull()
        assertThat(result!!.referenceCoverage).isWithin(1e-9).of(1.0)
        assertThat(result.slots["item"]).isEqualTo("您的快件")
    }

    @Test
    fun reportsNothingWhenARequiredLiteralIsAbsent() {
        assertThat(match("{item}(需要|要)签字确认", "我在楼下等您")).isNull()
    }

    /**
     * The case_030 shape: the same evidence inside a short turn and inside a long narrative must
     * not score the same. Additive weighting cannot tell these apart; two coverages can.
     */
    @Test
    fun theSameEvidenceScoresLowerWhenItExplainsLessOfTheTurn() {
        val short = match("我是(配送员|快递员)", "我是配送员")!!
        val buried = match(
            "我是(配送员|快递员)",
            "我是配送员这边跟您核对一下上个月的账单和保险理赔的进度以及后续的处理安排",
        )!!

        assertThat(short.referenceCoverage).isWithin(1e-9).of(1.0)
        assertThat(buried.referenceCoverage).isWithin(1e-9).of(1.0)
        // Both found everything the template asked for. Only input coverage separates them.
        assertThat(short.score).isGreaterThan(buried.score * 3)
    }

    @Test
    fun particlesDoNotDiluteCoverageTheWayContentDoes() {
        val withParticles = match("我(到|在)楼下", "我到楼下了啊就是的呢")!!
        val withContent = match("我(到|在)楼下", "我到楼下核对保险理赔进度")!!

        assertThat(withParticles.score).isGreaterThan(withContent.score)
    }

    @Test
    fun anOptionalPartCostsNothingWhenAbsent() {
        val withPrefix = match("(您的|你的)?包裹到了", "您的包裹到了")!!
        val withoutPrefix = match("(您的|你的)?包裹到了", "包裹到了")!!

        assertThat(withPrefix.referenceCoverage).isWithin(1e-9).of(1.0)
        assertThat(withoutPrefix.referenceCoverage).isWithin(1e-9).of(1.0)
    }

    @Test
    fun partsMustBeFoundInOrder() {
        assertThat(match("先{a}后{b}", "后面再说先来")).isNull()
    }

    @Test
    fun aCaptureExplainsTheTextItCoversRatherThanBeingCharged() {
        // The address is not noise, it is the answer, so it must not depress input coverage the way
        // unexplained text does.
        val result = match("(放在|送到){location}", "放在小区南门保安亭")!!

        assertThat(result.slots["location"]).isEqualTo("小区南门保安亭")
        assertThat(result.inputCoverage).isWithin(1e-9).of(1.0)
    }

    @Test
    fun reportsWhereItMatchedSoCallersCanApplyTheirOwnNegationRule() {
        // The matcher does not judge negation itself -- that rule lives with the rest of the
        // evidence handling -- but it has to say where it looked, or 我不需要贷款 is indistinguishable
        // from 我需要贷款.
        val refusal = match("(需要|要)(贷款|保险)", "我不需要贷款")!!

        assertThat(refusal.startIndex).isEqualTo(2)
        assertThat("我不需要贷款".substring(0, refusal.startIndex)).isEqualTo("我不")
    }

    @Test
    fun parsingRejectsATemplateThatWouldMatchAnything() {
        val error = runCatching { SentenceTemplate.parse("{a}{b}") }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun parsingRejectsMalformedSyntax() {
        listOf("(未闭合", "{未闭合", "(a||b)前缀").forEach { source ->
            assertThat(runCatching { SentenceTemplate.parse(source) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
