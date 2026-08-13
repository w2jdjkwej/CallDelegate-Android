package com.example.calldelegate.core.ai.rules.template

/**
 * How well one sentence matched one template, from both sides.
 *
 * Keeping both sides is the whole point. The current classifier adds weights for the evidence it
 * finds and never asks how much of the sentence that evidence accounts for, so two delivery words
 * inside a 43-character service narrative score the same as the same two words in a 6-character
 * delivery turn. One side alone cannot separate those:
 *
 * - [referenceCoverage] alone says "the template was fully found" and is happy either way.
 * - [inputCoverage] alone says "most of the sentence was explained" and would reward a template
 *   that matched a lot of common wording and nothing distinctive.
 *
 * Weights rather than counts, so that a run of particles cannot look like substance. See
 * [characterWeight].
 */
data class TemplateMatch(
    val inputMatched: Double,
    val inputWeight: Double,
    val referenceMatched: Double,
    val referenceWeight: Double,
    val slots: Map<String, String>,
    /**
     * Where the first required literal was found, so callers can apply the same negation rule they
     * apply to every other kind of evidence. Without it 我不需要贷款 -- a caller refusing -- reads
     * as a sales pitch, because the template only ever saw that the words were present.
     */
    val startIndex: Int,
) {
    /** How much of what the caller said this template accounts for, 0..1. */
    val inputCoverage: Double get() = if (inputWeight <= 0.0) 0.0 else inputMatched / inputWeight

    /** How much of the template was actually found, 0..1. */
    val referenceCoverage: Double get() = if (referenceWeight <= 0.0) 0.0 else referenceMatched / referenceWeight

    /**
     * A template only earns a high score by being both fully present and largely responsible for
     * the sentence, so the two coverages multiply. Anything additive would let a template that is
     * fully present but explains a tenth of the turn outscore one that explains the whole of it.
     */
    val score: Double get() = referenceCoverage * inputCoverage
}

/**
 * Finds the best placement of a template's parts inside a sentence.
 *
 * The search maximizes a *linear* objective -- matched reference weight first, then matched input
 * weight -- while [TemplateMatch.score] combining them is not linear. That split is deliberate.
 * Dynamic programming may only discard a partial solution when a better partial cannot become a
 * worse whole, which holds for a sum and does not hold for a product; scoring the finished match
 * instead keeps the search exact and leaves the scoring free to be shaped however the data asks.
 *
 * Parts are found in order and characters may be skipped between them. Everything the template does
 * not account for -- before it, after it, or between its parts -- stays in [TemplateMatch.inputWeight]
 * without being added to [TemplateMatch.inputMatched], which is exactly how a template that explains
 * a clause of a long sentence ends up scoring below one that explains the whole of a short one.
 */
object TemplateMatcher {

    fun match(template: SentenceTemplate, input: String): TemplateMatch? {
        if (input.isEmpty()) return null
        val parts = template.parts
        // best[position] = the best way to have placed the parts considered so far with the sentence
        // consumed up to position. Null means that combination is unreachable.
        var previous = arrayOfNulls<PartialMatch>(input.length + 1)
        // Before any part is placed, every starting position is reachable: the template is free to
        // begin anywhere, and what it leaves unclaimed is charged once at the end against the whole
        // sentence rather than tracked here.
        for (position in 0..input.length) previous[position] = PartialMatch()

        for (part in parts) {
            val current = arrayOfNulls<PartialMatch>(input.length + 1)
            for (position in 0..input.length) {
                val reached = previous[position] ?: continue
                when (part) {
                    is TemplatePart.Literal -> {
                        for (at in position..input.length) {
                            part.alternatives.forEach { alternative ->
                                if (input.startsWith(alternative, at)) {
                                    val end = at + alternative.length
                                    current.keepBest(
                                        end,
                                        // Input credit is what was actually read; reference credit
                                        // is what the part is worth whichever reading appeared, so
                                        // that coverage reaches exactly whole and not past it.
                                        // An optional part is not part of what the template
                                        // requires, so it only breaks ties -- by being preferred
                                        // over letting a capture swallow it.
                                        reached.advanced(
                                            inputMatched = textWeight(alternative),
                                            referenceMatched = if (part.optional) 0.0 else part.nominalWeight,
                                            optionalMatched = if (part.optional) part.nominalWeight else 0.0,
                                            matchedAt = if (part.optional) null else at,
                                        ),
                                    )
                                }
                            }
                        }
                        if (part.optional) current.keepBest(position, reached)
                    }

                    is TemplatePart.Capture -> {
                        // A capture explains the text it covers -- that is what it is for -- so its
                        // characters count as matched input, but they are not evidence about the
                        // template and add nothing to reference coverage.
                        for (end in position..input.length) {
                            val captured = input.substring(position, end)
                            current.keepBest(
                                end,
                                reached.advanced(
                                    inputMatched = textWeight(captured),
                                    referenceMatched = 0.0,
                                    slot = part.slot to captured,
                                ),
                            )
                        }
                    }
                }
            }
            previous = current
        }

        val best = previous.filterNotNull().maxWithOrNull(PARTIAL_ORDER) ?: return null
        if (best.referenceMatched <= 0.0) return null
        return TemplateMatch(
            inputMatched = best.inputMatched,
            inputWeight = textWeight(input),
            referenceMatched = best.referenceMatched,
            referenceWeight = template.requiredWeight,
            slots = best.slots.filterValues(String::isNotEmpty),
            startIndex = best.firstMatchIndex ?: 0,
        )
    }

    private fun Array<PartialMatch?>.keepBest(position: Int, candidate: PartialMatch) {
        val existing = this[position]
        if (existing == null || PARTIAL_ORDER.compare(candidate, existing) > 0) this[position] = candidate
    }

    private data class PartialMatch(
        val firstMatchIndex: Int? = null,
        val inputMatched: Double = 0.0,
        val referenceMatched: Double = 0.0,
        /** Optional literals found. Orders candidates without counting toward either coverage. */
        val optionalMatched: Double = 0.0,
        val slots: Map<String, String> = emptyMap(),
    ) {
        fun advanced(
            inputMatched: Double,
            referenceMatched: Double,
            optionalMatched: Double = 0.0,
            slot: Pair<String, String>? = null,
            matchedAt: Int? = null,
        ) = copy(
            firstMatchIndex = firstMatchIndex ?: matchedAt,
            inputMatched = this.inputMatched + inputMatched,
            referenceMatched = this.referenceMatched + referenceMatched,
            optionalMatched = this.optionalMatched + optionalMatched,
            slots = if (slot == null) slots else slots + slot,
        )
    }

    /**
     * Required reference weight first, then how much of the sentence was explained, then optional
     * parts as a tiebreak. Every term is a sum, which is what makes discarding a partial solution
     * safe; see the note on the class.
     */
    private val PARTIAL_ORDER = compareBy<PartialMatch>(
        { it.referenceMatched },
        { it.inputMatched },
        { it.optionalMatched },
    )
}
