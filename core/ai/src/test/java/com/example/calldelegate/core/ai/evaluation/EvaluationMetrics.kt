package com.example.calldelegate.core.ai.evaluation

data class RateMetric(
    val correct: Int,
    val total: Int,
    val value: Double?,
)

fun rateMetric(correct: Int, total: Int): RateMetric {
    require(correct >= 0) { "correct must not be negative" }
    require(total >= 0) { "total must not be negative" }
    require(correct <= total) { "correct must not exceed total" }
    val value = if (total == 0) null else correct.toDouble() / total
    return RateMetric(correct, total, value)
}

data class SlotCounts(
    val truePositive: Int,
    val falsePositive: Int,
    val falseNegative: Int,
) {
    val precision: Double?
        get() = ratio(truePositive, truePositive + falsePositive)

    val recall: Double?
        get() = ratio(truePositive, truePositive + falseNegative)

    val f1: Double?
        get() {
            val currentPrecision = precision ?: return null
            val currentRecall = recall ?: return null
            val sum = currentPrecision + currentRecall
            return if (sum == 0.0) 0.0 else 2.0 * currentPrecision * currentRecall / sum
        }

    operator fun plus(other: SlotCounts): SlotCounts = SlotCounts(
        truePositive = truePositive + other.truePositive,
        falsePositive = falsePositive + other.falsePositive,
        falseNegative = falseNegative + other.falseNegative,
    )
}

fun slotCounts(expected: Map<String, String>, actual: Map<String, String>): SlotCounts {
    val expectedPairs = expected.filterKeys { it != PURPOSE_SLOT }.entries.toSet()
    val actualPairs = actual.filterKeys { it != PURPOSE_SLOT }.entries.toSet()
    val truePositive = expectedPairs.intersect(actualPairs).size
    return SlotCounts(
        truePositive = truePositive,
        falsePositive = actualPairs.size - truePositive,
        falseNegative = expectedPairs.size - truePositive,
    )
}

data class CharacterErrorRateResult(
    val referenceCharacters: Int,
    val editDistance: Int,
    val rate: Double?,
)

object CharacterErrorRate {
    fun calculate(reference: String, hypothesis: String): CharacterErrorRateResult {
        val normalizedReference = normalize(reference)
        val normalizedHypothesis = normalize(hypothesis)
        val distance = editDistance(normalizedReference, normalizedHypothesis)
        val rate = ratio(distance, normalizedReference.size)
        return CharacterErrorRateResult(
            referenceCharacters = normalizedReference.size,
            editDistance = distance,
            rate = rate,
        )
    }

    private fun normalize(text: String): CharArray = text
        .asSequence()
        .filterNot { it.isWhitespace() || it.isPunctuation() }
        .map { it.lowercaseChar() }
        .toList()
        .toCharArray()

    private fun editDistance(first: CharArray, second: CharArray): Int {
        val columns: CharArray
        val rows: CharArray
        if (first.size <= second.size) {
            columns = first
            rows = second
        } else {
            columns = second
            rows = first
        }

        var previous = IntArray(columns.size + 1) { it }
        var current = IntArray(columns.size + 1)
        for (rowIndex in rows.indices) {
            current[0] = rowIndex + 1
            for (columnIndex in columns.indices) {
                val substitutionCost = if (rows[rowIndex] == columns[columnIndex]) 0 else 1
                val deletion = previous[columnIndex + 1] + 1
                val insertion = current[columnIndex] + 1
                val substitution = previous[columnIndex] + substitutionCost
                current[columnIndex + 1] = minOf(deletion, insertion, substitution)
            }
            val temporary = previous
            previous = current
            current = temporary
        }
        return previous[columns.size]
    }
}

private fun ratio(numerator: Int, denominator: Int): Double? =
    if (denominator == 0) null else numerator.toDouble() / denominator

private fun Char.isPunctuation(): Boolean = when (Character.getType(this)) {
    Character.CONNECTOR_PUNCTUATION.toInt(),
    Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(),
    Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
    Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(),
    -> true
    else -> false
}

private const val PURPOSE_SLOT = "purpose"
