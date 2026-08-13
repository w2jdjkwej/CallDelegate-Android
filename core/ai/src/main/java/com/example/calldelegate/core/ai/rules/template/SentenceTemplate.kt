package com.example.calldelegate.core.ai.rules.template

/**
 * One sentence shape an intent can be said in, as a sequence of parts to be found in order.
 *
 * This exists because the keyword-and-regex form does not survive the thing it is asked to do.
 * delivery_request currently carries 11 core keywords, 63 auxiliary keywords and 22 regular
 * expressions, and blind material still lands on nothing: 您的快件需要签字确认，方便下来接收吗 scores
 * zero in every scene, because 快件 and 签字确认 are simply not in the lists. Enumerating wordings is
 * the losing half of the problem, and 78 per cent of blind failures are of exactly this kind --
 * scored at zero everywhere, so no threshold or ranking rule can reach them.
 *
 * A template says what the sentence *does* instead of which words it contains, and says it once for
 * intent and slots together rather than in two pipelines that later have to be reconciled.
 *
 * Syntax, deliberately small:
 *
 * ```
 * (您的|你的)?{item}(需要|要)签字确认
 * ```
 *
 * - `(a|b|c)` one of these, `(a|b)?` optionally one of these
 * - `{name}`  capture whatever sits here into the slot `name`
 * - anything else is a literal that must appear
 *
 * Parts match in order and characters may be skipped between them; how much was skipped is what
 * [TemplateMatcher] scores. There is no repetition operator and no nesting: both would buy
 * expressiveness that regular expressions already provide and that has not been the missing piece.
 */
data class SentenceTemplate(
    val parts: List<TemplatePart>,
) {
    /** Total weight of what must be found for this template to be fully covered. */
    val requiredWeight: Double = parts.filterIsInstance<TemplatePart.Literal>()
        .filterNot(TemplatePart.Literal::optional)
        .sumOf(TemplatePart.Literal::nominalWeight)

    init {
        require(parts.any { it is TemplatePart.Literal && !it.optional }) {
            "A template with nothing required would match every sentence"
        }
    }

    companion object {
        /** @throws IllegalArgumentException when the source cannot be read as a template. */
        fun parse(source: String): SentenceTemplate = SentenceTemplate(parseParts(source))
    }
}

sealed interface TemplatePart {
    /**
     * Text that must appear. [alternatives] holds the readings that count as the same thing, so
     * (需要|要) is one part rather than two templates.
     */
    data class Literal(
        val alternatives: List<String>,
        val optional: Boolean = false,
    ) : TemplatePart {
        init {
            require(alternatives.isNotEmpty()) { "A literal needs at least one alternative" }
            require(alternatives.none(String::isEmpty)) { "A literal alternative cannot be empty" }
        }

        /**
         * What finding this part is worth, whichever reading was found.
         *
         * It is the longest reading rather than the one that matched, so that (需要|要) demands as
         * much as 需要 alone. Crediting the reading that matched made a template easier to cover
         * whenever the short form appeared, and taking the shortest would have meant that adding a
         * synonym quietly weakened every template it was added to -- the opposite of why one is
         * added.
         */
        val nominalWeight: Double = alternatives.maxOf(::textWeight)
    }

    /** A run of characters kept as the value of [slot]. */
    data class Capture(val slot: String) : TemplatePart {
        init {
            require(slot.isNotBlank()) { "A capture needs a slot name" }
        }
    }
}

/**
 * What a character is worth.
 *
 * Particles and fillers carry almost nothing: whether the caller said 的 or 了 does not change what
 * they want, but counting them equally would let a long polite sentence dilute a short template's
 * coverage until it looked like a poor match. They are not weighted zero, because a sentence made
 * only of them should still not look fully covered.
 */
internal fun characterWeight(character: Char): Double =
    if (character in FILLER_CHARACTERS) FILLER_WEIGHT else 1.0

internal fun textWeight(text: String): Double = text.sumOf(::characterWeight)

private const val FILLER_WEIGHT = 0.2
private const val FILLER_CHARACTERS = "的了着过吗呢吧啊呀哦嗯呃就也还都很挺蛮"

private fun parseParts(source: String): List<TemplatePart> {
    val parts = ArrayList<TemplatePart>()
    val literal = StringBuilder()
    var index = 0

    fun flushLiteral() {
        if (literal.isNotEmpty()) {
            parts += TemplatePart.Literal(listOf(literal.toString()))
            literal.clear()
        }
    }

    while (index < source.length) {
        when (source[index]) {
            '(' -> {
                val close = source.indexOf(')', index)
                require(close > index) { "Unclosed ( at $index in: $source" }
                flushLiteral()
                val alternatives = source.substring(index + 1, close).split('|').map(String::trim)
                require(alternatives.all(String::isNotEmpty)) { "Empty alternative in: $source" }
                val optional = close + 1 < source.length && source[close + 1] == '?'
                parts += TemplatePart.Literal(alternatives, optional)
                index = close + if (optional) 2 else 1
            }
            '{' -> {
                val close = source.indexOf('}', index)
                require(close > index) { "Unclosed { at $index in: $source" }
                flushLiteral()
                parts += TemplatePart.Capture(source.substring(index + 1, close).trim())
                index = close + 1
            }
            else -> {
                literal.append(source[index])
                index += 1
            }
        }
    }
    flushLiteral()
    require(parts.isNotEmpty()) { "Empty template" }
    return parts
}
