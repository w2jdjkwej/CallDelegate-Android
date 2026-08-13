package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.domain.model.RiskLevel
import com.example.calldelegate.domain.model.RuleClassificationResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NBestRecognitionRerankerTest {
    private val reranker = NBestRecognitionReranker()

    @Test
    fun thereIsNothingToExamineWhenTheDecoderOfferedOneReading() {
        val undecided = classification(scene = null)

        assertThat(reranker.shouldRerank(undecided, emptyList())).isFalse()
        assertThat(reranker.shouldRerank(undecided, listOf("我到楼下了"))).isFalse()
        assertThat(reranker.shouldRerank(undecided, listOf("我到楼下了", "我到楼下了"))).isFalse()
    }

    @Test
    fun alternativesAreExaminedOnlyWhenTheClassifierCouldNotSettleTheTurn() {
        val readings = listOf("旁边的上课去", "旁边的上客区")

        assertThat(reranker.shouldRerank(classification(scene = null), readings)).isTrue()
        assertThat(
            reranker.shouldRerank(classification(scene = "ride_hailing", shouldClarify = true), readings),
        ).isTrue()
        // Examining every disagreeing turn was measured over 172 recorded utterances: it raised
        // turns examined from 8 to 162, produced the same three correct substitutions and one wrong
        // one, and left scene accuracy unchanged. Paying 154 classifications for that is not a trade.
        assertThat(reranker.shouldRerank(classification(scene = "ride_hailing"), readings)).isFalse()
    }

    @Test
    fun keepsTheRecognizersBestHypothesisWhenNoAlternativeImprovesAnything() {
        val decision = reranker.rerank(
            listOf(
                candidate(0, "我到楼下了", classification(scene = "delivery", intent = "arrived")),
                candidate(1, "我到楼上了", classification(scene = "delivery", intent = "arrived")),
            ),
        )

        assertThat(decision.chosenRank).isEqualTo(0)
        assertThat(decision.text).isEqualTo("我到楼下了")
        assertThat(decision.changedHypothesis).isFalse()
        assertThat(decision.reasons).isEmpty()
    }

    @Test
    fun choosesAnAlternativeThatNamesASceneWhereTheBestHypothesisNamedNone() {
        val decision = reranker.rerank(
            listOf(
                candidate(0, "剩余挤压还有多少", classification(scene = null)),
                candidate(1, "剩余解押还有多少", classification(scene = "real_estate", intent = "mortgage")),
            ),
        )

        assertThat(decision.chosenRank).isEqualTo(1)
        assertThat(decision.text).isEqualTo("剩余解押还有多少")
        assertThat(decision.reasons).containsExactly("scene_recovered:real_estate")
    }

    @Test
    fun choosesAnAlternativeThatRemovesTheNeedToAskWhichSceneItIs() {
        val decision = reranker.rerank(
            listOf(
                candidate(0, "我是送外卖的骑手", classification(scene = "delivery", shouldClarify = true)),
                candidate(1, "我是送外卖的棋手", classification(scene = "delivery", shouldClarify = false)),
            ),
        )

        assertThat(decision.chosenRank).isEqualTo(1)
        assertThat(decision.reasons).containsExactly("clarification_resolved:delivery")
    }

    @Test
    fun choosesAnAlternativeThatRecoversAMissingSlot() {
        val decision = reranker.rerank(
            listOf(
                candidate(0, "放在保安挺就行", classification(scene = "delivery", slots = mapOf("issueType" to "放置"))),
                candidate(
                    1,
                    "放在保安亭就行",
                    classification(
                        scene = "delivery",
                        slots = mapOf("issueType" to "放置", "location" to "保安亭"),
                    ),
                ),
            ),
        )

        assertThat(decision.chosenRank).isEqualTo(1)
        assertThat(decision.reasons).containsExactly("slots_recovered:location")
    }

    /**
     * The exact turn that made this rule necessary, from the device run of 2026-08-07. The
     * recognizer's best reading filled no slot; a lower-ranked one turned 了 into 个, and 等个几分钟
     * matched the duration pattern. Rank 1 was the reference text word for word, so accepting the
     * slot "recovery" meant reaching past a perfect reading for a worse one.
     */
    @Test
    fun refusesASlotRecoveredWithAValueThatIsNotOfThatSlotsKind() {
        val decision = reranker.rerank(
            listOf(
                candidate(0, "我已经在上车点等了几分钟的没有看到您", classification(scene = "ride_hailing")),
                candidate(1, "我已经在上车点等了几分钟但没有看到您", classification(scene = "ride_hailing")),
                candidate(
                    2,
                    "我已经在上车点等个几分钟的没有看到您",
                    classification(scene = "ride_hailing", slots = mapOf("estimatedTime" to "几分钟")),
                ),
            ),
        )

        assertThat(decision.chosenRank).isEqualTo(0)
    }

    @Test
    fun acceptsASlotRecoveredWithAValueOfTheRightKind() {
        val decision = reranker.rerank(
            listOf(
                candidate(0, "我大概十分钟到楼下", classification(scene = "ride_hailing")),
                candidate(
                    1,
                    "我大概十分钟到楼下",
                    classification(scene = "ride_hailing", slots = mapOf("estimatedTime" to "十分钟")),
                ),
            ),
        )

        assertThat(decision.reasons).containsExactly("slots_recovered:estimatedTime")
    }

    @Test
    fun aFreeTextSlotIsAcceptedWithoutGuessingAShapeForIt() {
        val decision = reranker.rerank(
            listOf(
                candidate(0, "商品缺少充电器", classification(scene = "customer_service")),
                candidate(
                    1,
                    "商品缺少充电器",
                    classification(scene = "customer_service", slots = mapOf("issueType" to "缺货")),
                ),
            ),
        )

        assertThat(decision.reasons).containsExactly("slots_recovered:issueType")
    }

    @Test
    fun refusesAnAlternativeThatTradesOneSlotForAnother() {
        val decision = reranker.rerank(
            listOf(
                candidate(0, "订单一二三四号", classification(scene = "delivery", slots = mapOf("orderNumber" to "1234"))),
                candidate(1, "订单一二三时号", classification(scene = "delivery", slots = mapOf("time" to "三时"))),
            ),
        )

        // Filling a different slot is not reading the same turn better, it is reading another turn.
        assertThat(decision.chosenRank).isEqualTo(0)
    }

    @Test
    fun refusesAnAlternativeThatWouldChangeTheRiskDecision() {
        val escalating = reranker.rerank(
            listOf(
                candidate(0, "请问验证一下地址", classification(scene = null, risk = RiskLevel.LOW)),
                candidate(
                    1,
                    "请问验证码是多少",
                    classification(scene = "spam_risk", risk = RiskLevel.HIGH),
                ),
            ),
        )
        val deescalating = reranker.rerank(
            listOf(
                candidate(0, "请问验证码是多少", classification(scene = "spam_risk", risk = RiskLevel.HIGH)),
                candidate(1, "请问验证一下地址", classification(scene = "delivery", risk = RiskLevel.LOW)),
            ),
        )

        // Ending a call is the most expensive thing this system does. A hypothesis the decoder
        // ranked lower is not evidence enough to start one, nor to call one off.
        assertThat(escalating.chosenRank).isEqualTo(0)
        assertThat(deescalating.chosenRank).isEqualTo(0)
    }

    @Test
    fun refusesAnAlternativeThatRewritesTheSentenceRatherThanAWord() {
        val decision = reranker.rerank(
            listOf(
                candidate(0, "我在楼下等您很久了", classification(scene = null)),
                candidate(1, "保险理赔需要材料", classification(scene = "insurance_finance")),
            ),
        )

        assertThat(decision.chosenRank).isEqualTo(0)
    }

    @Test
    fun doesNotLookBeyondTheConfiguredRank() {
        val candidates = listOf(
            candidate(0, "剩余挤压还有多少", classification(scene = null)),
            candidate(1, "剩余挤鸭还有多少", classification(scene = null)),
            candidate(2, "剩余几压还有多少", classification(scene = null)),
            candidate(3, "剩余机压还有多少", classification(scene = null)),
            candidate(4, "剩余解押还有多少", classification(scene = "real_estate")),
        )

        assertThat(reranker.rerank(candidates).chosenRank).isEqualTo(0)
        assertThat(NBestRecognitionReranker(maximumRank = 4).rerank(candidates).chosenRank).isEqualTo(4)
    }

    @Test
    fun refusesAnAlternativeThatWouldItselfNeedClarification() {
        val decision = reranker.rerank(
            listOf(
                candidate(0, "我到了这边", classification(scene = null)),
                candidate(
                    1,
                    "我到了那边",
                    classification(scene = "delivery", shouldClarify = true),
                ),
            ),
        )

        assertThat(decision.chosenRank).isEqualTo(0)
    }

    @Test
    fun takesTheEarliestQualifyingRankBecauseTheAcousticsAlreadyOrderedThem() {
        val decision = reranker.rerank(
            listOf(
                candidate(0, "剩余挤压还有多少", classification(scene = null)),
                candidate(1, "剩余解押还有多少", classification(scene = "real_estate")),
                candidate(2, "剩余借押还有多少", classification(scene = "real_estate", confidence = 0.99f)),
            ),
        )

        assertThat(decision.chosenRank).isEqualTo(1)
    }

    private fun candidate(rank: Int, text: String, classification: RuleClassificationResult) =
        RerankCandidate(rank = rank, text = text, classification = classification)

    private fun classification(
        scene: String?,
        intent: String? = null,
        confidence: Float = 0.5f,
        shouldClarify: Boolean = false,
        risk: RiskLevel = RiskLevel.LOW,
        slots: Map<String, String> = emptyMap(),
    ) = RuleClassificationResult(
        scene = scene,
        intent = intent,
        riskLevel = risk,
        confidence = confidence,
        shouldClarify = shouldClarify,
        extractedSlots = slots,
    )
}
