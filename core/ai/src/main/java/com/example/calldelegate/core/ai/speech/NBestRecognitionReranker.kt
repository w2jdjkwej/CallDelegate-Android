package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.domain.model.RuleClassificationResult

/** One recognition hypothesis together with what the classifier made of it. */
data class RerankCandidate(
    /** Position in the recognizer's own N-best list; 0 is the hypothesis it ranked best. */
    val rank: Int,
    val text: String,
    val classification: RuleClassificationResult,
)

/**
 * Which hypothesis understanding should run on, and why.
 *
 * [reasons] is empty exactly when the recognizer's own best hypothesis was kept, so a caller can
 * tell "nothing to improve" from "improved for this named reason" without comparing texts.
 */
data class RerankDecision(
    val chosenRank: Int,
    val text: String,
    val reasons: List<String> = emptyList(),
) {
    val changedHypothesis: Boolean get() = chosenRank != 0
}

/**
 * Picks the recognition hypothesis that understanding runs on.
 *
 * The recognizer ranks hypotheses by acoustics alone, and for a phone call that is not the only
 * evidence available: 剩余挤压多少 and 剩余解押多少 sound alike, and only one of them is a sentence
 * a mortgage caller would say. The alternatives are already in the lattice, so consulting them
 * costs a classification pass rather than a second decode -- unlike the scene-vocabulary retry,
 * which re-decodes the audio against a hand-written phrase list.
 *
 * Deliberate limits, because a rerank is the system overruling its own recognizer:
 *
 * - It never changes the transcript. The record keeps what the recognizer reported as its best
 *   reading; the rerank only decides what the classifier reads. A caller comparing the two can
 *   always see that a substitution happened.
 * - It never changes the risk decision. Ending a call is the most expensive thing this system
 *   does, and a hypothesis the decoder ranked lower is not enough evidence to start or stop one --
 *   in either direction.
 * - It only accepts a *categorical* improvement: a scene where there was none, a clarification no
 *   longer needed, a required slot recovered. Chasing a higher confidence score would tune the
 *   recognizer's output to whatever the current rule weights happen to reward.
 * - It only considers hypotheses that are near-homophones of the best one. Reranking is for
 *   repairing a word or two; a candidate that rewrites the sentence is a different utterance, and
 *   the acoustics that ranked it lower are the better judge.
 */
class NBestRecognitionReranker(
    /**
     * How far down the N-best list to look. Rank is used instead of the recognizer's score because
     * that score is an unnormalized likelihood whose scale varies with model and utterance length,
     * so no fixed threshold on it means the same thing twice; the ordering, however, always does.
     */
    private val maximumRank: Int = 3,
    /** At most this fraction of the turn may change. See [normalizedTextDifference]. */
    private val maximumTextDifference: Double = 0.34,
) {
    /**
     * Whether the alternatives are worth classifying at all.
     *
     * Restricted to turns the classifier could not settle, which is a cost decision rather than a
     * correctness one -- what may be accepted is decided entirely by [rerank].
     *
     * Widening it was tried and measured. Across the same 172 recorded utterances, examining every
     * turn whose readings disagreed raised the turns examined from 8 to 162 and produced exactly
     * the same three correct substitutions, plus one wrong one; scene accuracy was 167/172 either
     * way. The extra 154 classifications bought nothing, so the narrow rule stands. What the
     * experiment did buy was the defect behind that wrong substitution, which [rerank] now guards
     * against and which the narrow rule had simply never reached.
     *
     * An earlier version also consulted word confidence. That reading is unobtainable: the decoder
     * returns it only when no N-best list was requested, so it can never accompany the very
     * hypotheses it would be judging (see VoskRecognizerOptions).
     */
    fun shouldRerank(top: RuleClassificationResult, alternatives: List<String>): Boolean =
        alternatives.distinct().size > 1 && (top.scene == null || top.shouldClarify)

    fun rerank(candidates: List<RerankCandidate>): RerankDecision {
        val top = candidates.firstOrNull()
            ?: return RerankDecision(chosenRank = 0, text = "")
        val improved = candidates.asSequence()
            .drop(1)
            .filter { it.rank in 1..maximumRank }
            // Earliest qualifying rank wins. The improvements are categorical, so among candidates
            // that all clear the bar there is nothing to prefer except the acoustics, and those
            // already put them in this order.
            .firstNotNullOfOrNull { candidate ->
                improvementOver(top, candidate)?.let { candidate to it }
            }
            ?: return RerankDecision(chosenRank = top.rank, text = top.text)
        return RerankDecision(
            chosenRank = improved.first.rank,
            text = improved.first.text,
            reasons = listOf(improved.second),
        )
    }

    private fun improvementOver(top: RerankCandidate, candidate: RerankCandidate): String? {
        if (candidate.classification.riskLevel != top.classification.riskLevel) return null
        if (normalizedTextDifference(top.text, candidate.text) > maximumTextDifference) return null
        val topScene = top.classification.scene
        val candidateScene = candidate.classification.scene ?: return null
        if (candidate.classification.shouldClarify) return null
        return when {
            topScene == null -> "$REASON_SCENE_RECOVERED:$candidateScene"
            candidateScene != topScene -> null
            top.classification.shouldClarify -> "$REASON_CLARIFICATION_RESOLVED:$candidateScene"
            else -> recoveredSlots(top.classification, candidate.classification)
                ?.let { "$REASON_SLOTS_RECOVERED:$it" }
        }
    }

    /**
     * Slot keys the candidate filled that the best hypothesis left empty, or null when it also
     * dropped one. A hypothesis that trades the order number for a time has not read the turn
     * better, it has read a different turn.
     *
     * Every gained slot must also carry a value of the shape its name promises, because slots come
     * from regular expressions and a *worse* transcription can match one by accident. Measured on
     * 2026-08-07: the recognizer's best reading of one ride-hailing turn was 等了几分钟 and filled
     * no slot; a lower-ranked reading turned 了 into 个, and 等个几分钟 matched the duration pattern
     * and filled estimatedTime with 几分钟. That counted as a recovery and this rule substituted a
     * worse reading -- over rank 1, which happened to be the reference text word for word. 几分钟
     * is not an estimate, and the shape check is what says so: a time carries a number.
     */
    private fun recoveredSlots(
        top: RuleClassificationResult,
        candidate: RuleClassificationResult,
    ): String? {
        val before = top.extractedSlots.filterValues(String::isNotBlank).keys
        val after = candidate.extractedSlots.filterValues(String::isNotBlank)
        if (!after.keys.containsAll(before)) return null
        val gained = (after.keys - before).filter { slot -> isPlausibleValue(slot, after.getValue(slot)) }
        return gained.sorted().joinToString(",").takeIf { gained.isNotEmpty() }
    }

    /**
     * Whether a slot value looks like the thing its slot is named for.
     *
     * Only slots with a shape that can be stated are checked. Inventing a test for a free-text slot
     * such as issueType would reject correct values on a guess, which is worse than the accidental
     * match it would be trying to prevent -- so those pass unexamined.
     */
    private fun isPlausibleValue(slot: String, value: String): Boolean = when (slot) {
        in NUMERIC_SLOTS -> value.any(::isNumeral)
        else -> true
    }

    /**
     * 几 and 多 are quantities without a number, which is exactly the distinction that matters here:
     * 十分钟 and 半小时 are estimates, 几分钟 is not.
     */
    private fun isNumeral(character: Char): Boolean =
        character.isDigit() || character in CHINESE_NUMERALS

    companion object {
        const val REASON_SCENE_RECOVERED = "scene_recovered"
        const val REASON_CLARIFICATION_RESOLVED = "clarification_resolved"
        const val REASON_SLOTS_RECOVERED = "slots_recovered"

        /** Slots whose value is meaningless without a number in it. */
        private val NUMERIC_SLOTS = setOf(
            "estimatedTime",
            "time",
            "viewingTime",
            "expiryTime",
            "orderNumber",
            "orderId",
            "licensePlate",
            "contact",
        )
        private const val CHINESE_NUMERALS = "〇零一二三四五六七八九十百千万两半"
    }
}
