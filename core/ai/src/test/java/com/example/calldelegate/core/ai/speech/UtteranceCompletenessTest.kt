package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.domain.api.StreamingRecognitionSnapshot
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class UtteranceCompletenessTest {
    private val completeness = UtteranceCompleteness()

    @Test fun absentOrEmptyEvidenceNeverAllowsAnEarlyCommit() {
        // A null snapshot means the recognizer produced nothing, not that the caller stopped talking.
        assertThat(completeness.looksComplete(null)).isFalse()
        assertThat(completeness.looksComplete("")).isFalse()
        assertThat(completeness.looksComplete("   ")).isFalse()
        assertThat(completeness.looksComplete("[unk]")).isFalse()
    }

    @Test fun shortPartialsStayOnTheLongWindowEvenWhenTheyEndWellFormed() {
        // Three characters is not enough context to conclude a turn ended.
        assertThat(completeness.looksComplete("好吗")).isFalse()
        assertThat(completeness.looksComplete("是吧")).isFalse()
    }

    @Test fun terminalPunctuationIsAcceptedButBareQuestionParticlesAreNot() {
        // Vosk does not add punctuation. A caller can pause after a question and then add an
        // alternative, so a bare "吗/吧" is not strong enough evidence for an early cut.
        assertThat(completeness.looksComplete("你现在方便接电话吗")).isFalse()
        assertThat(completeness.looksComplete("那我放在门口可以吧")).isFalse()
        assertThat(completeness.looksComplete("请问你在家吗？")).isTrue()
        assertThat(completeness.looksComplete("我已经到楼下了。")).isTrue()
    }

    @Test fun closingFormulasAreAcceptedAsEvidence() {
        assertThat(completeness.looksComplete("那就放在前台谢谢")).isTrue()
        assertThat(completeness.looksComplete("我知道了没问题")).isTrue()
        assertThat(completeness.looksComplete("好的")).isTrue()
        assertThat(completeness.looksComplete("没有了")).isTrue()
        assertThat(completeness.looksComplete("再见")).isTrue()
    }

    @Test fun narrowDeliveryEndingsAreAcceptedWithoutTreatingEveryLeAsComplete() {
        assertThat(completeness.looksComplete("可以给你放在门口了")).isTrue()
        assertThat(completeness.looksComplete("我不对给你放在前台了")).isTrue()
        assertThat(completeness.looksComplete("我在超市买了")).isFalse()
    }

    @Test fun explicitStateAnswersAreAcceptedAcrossEnabledScenes() {
        // Supplement, callback and confirmation questions are shared by the enabled delivery,
        // ride-hailing, customer-service, real-estate and insurance-finance flows.
        listOf(
            "没有",
            "没什么了",
            "不用了",
            "不需要回电",
            "麻烦回个电话",
            "不可以",
            "不同意",
        ).forEach { answer ->
            assertWithMessage("explicit answer: %s", answer)
                .that(completeness.looksComplete(answer))
                .isTrue()
        }
    }

    @Test fun explicitAnswersDoNotOverrideAContinuationAtTheEnd() {
        assertThat(completeness.looksComplete("不需要回电但是")).isFalse()
        assertThat(completeness.looksComplete("麻烦回个电话然后")).isFalse()
        assertThat(completeness.looksComplete("不需要你现在")).isFalse()
    }

    @Test fun conjunctionsAndDeterminersKeepTheLongWindow() {
        // These are the real Mandarin mid-sentence pause points; committing here truncates a caller.
        assertThat(completeness.looksComplete("我先把东西放在门口然后")).isFalse()
        assertThat(completeness.looksComplete("麻烦你帮我看一下那个")).isFalse()
        assertThat(completeness.looksComplete("我现在过不去因为")).isFalse()
        assertThat(completeness.looksComplete("这个是你的快递不过")).isFalse()
        assertThat(completeness.looksComplete("我把它放在")).isFalse()
        assertThat(completeness.looksComplete("这是您订的")).isFalse()
    }

    @Test fun aTrailingContinuationCueOverridesAnOtherwisePositiveEnding() {
        // "那个" ends the text, so the earlier question particle must not win.
        assertThat(completeness.looksComplete("你是说那个吗那个")).isFalse()
        assertThat(completeness.looksComplete("我到了小区门口，")).isFalse()
    }

    @Test fun ambiguousParticlesAreRejectedBecauseTheyOccurMidSentence() {
        // "了" is a perfective marker and "啊" a hesitation filler; both continue in real speech.
        assertThat(completeness.looksComplete("我在超市买了")).isFalse()
        assertThat(completeness.looksComplete("就是那种情况啊")).isFalse()
    }

    @Test fun bareXingIsRejectedSoBankAndBicycleAreNotCutMidSentence() {
        assertThat(completeness.looksComplete("我现在人在银行")).isFalse()
        assertThat(completeness.looksComplete("我骑的是自行车")).isFalse()
    }

    @Test fun whitespaceAndUnknownTokensDoNotHideTheRealEnding() {
        // Vosk emits space-separated tokens and [unk]; normalization must see the true final char.
        assertThat(completeness.looksComplete("你 现在 方便 接 电话 吗")).isFalse()
        assertThat(completeness.looksComplete("我 先 放在 门口 然后")).isFalse()
        assertThat(completeness.looksComplete("请问 你 在家吗？ [unk]")).isTrue()
    }

    @Test fun theRecognizersOwnSegmentDecisionIsEvidenceWhenTheTextCarriesNone() {
        // The shipped Mandarin model emits no punctuation, so these seven real turns produced no
        // text evidence at all and every one of them took the long grace. A closed segment is the
        // recognizer answering the same question from the audio.
        val turnsWithoutTextEvidence = listOf(
            "文化外卖到了",
            "太子有点破损",
            "还有已经是纯漏了一点",
            "产品漏了一点",
        )
        turnsWithoutTextEvidence.forEach { turn ->
            assertWithMessage("text alone: %s", turn)
                .that(completeness.looksComplete(turn))
                .isFalse()
            assertWithMessage("segment closed: %s", turn)
                .that(
                    completeness.snapshotLooksComplete(
                        StreamingRecognitionSnapshot("r", turn, recognizerClosedSegment = true),
                    ),
                )
                .isTrue()
        }

        assertThat(completeness.looksComplete("根据放在门口了")).isTrue()
        assertThat(completeness.looksComplete("我不对给你放在前台了")).isTrue()
    }

    @Test fun anOpenSegmentStillFallsBackToTheTextRules() {
        // Text still accumulating is the opposite of evidence, whatever it currently ends with.
        assertThat(
            completeness.snapshotLooksComplete(
                StreamingRecognitionSnapshot("r", "我先放在门口然后", recognizerClosedSegment = false),
            ),
        ).isFalse()
        // With no segment closed, a bare question particle is not enough to cut the caller off.
        assertThat(
            completeness.snapshotLooksComplete(
                StreamingRecognitionSnapshot("r", "你现在方便接电话吗", recognizerClosedSegment = false),
            ),
        ).isFalse()
    }

    @Test fun aMissingSnapshotIsNeverTreatedAsCompletion() {
        assertThat(completeness.snapshotLooksComplete(null)).isFalse()
    }
}
