package com.example.calldelegate.core.ai.speech

/**
 * How much two recognitions of the same audio disagree: Levenshtein distance over the longer of
 * the two, after dropping whitespace and punctuation. 0.0 means identical, 1.0 nothing in common.
 *
 * Punctuation and spacing are ignored because the recognizer places them inconsistently between
 * hypotheses of one utterance, and a disagreement about a comma is not a disagreement about words.
 */
internal fun normalizedTextDifference(first: String, second: String): Double {
    val left = first.replace(DIFFERENCE_IGNORED_REGEX, "").lowercase()
    val right = second.replace(DIFFERENCE_IGNORED_REGEX, "").lowercase()
    val maximumLength = maxOf(left.length, right.length)
    if (maximumLength == 0) return 0.0
    return editDistance(left, right).toDouble() / maximumLength.toDouble()
}

private fun editDistance(first: String, second: String): Int {
    if (first.isEmpty()) return second.length
    if (second.isEmpty()) return first.length
    var previous = IntArray(second.length + 1) { it }
    var current = IntArray(second.length + 1)
    for (firstIndex in first.indices) {
        current[0] = firstIndex + 1
        for (secondIndex in second.indices) {
            val substitutionCost = if (first[firstIndex] == second[secondIndex]) 0 else 1
            current[secondIndex + 1] = minOf(
                current[secondIndex] + 1,
                previous[secondIndex + 1] + 1,
                previous[secondIndex] + substitutionCost,
            )
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[second.length]
}

private val DIFFERENCE_IGNORED_REGEX = Regex("[\\s，。！？、,.!?：:；;‘’“”\"']+")
