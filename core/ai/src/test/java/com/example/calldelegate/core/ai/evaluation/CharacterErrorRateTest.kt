package com.example.calldelegate.core.ai.evaluation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class CharacterErrorRateTest {
    @Test
    fun csvFixturesCoverCoreEditCases() {
        val resource = checkNotNull(javaClass.getResource("/evaluation/cer_metric_fixtures.csv"))
        val rows = resource.readText(Charsets.UTF_8)
            .lineSequence()
            .filter { it.isNotBlank() }
            .drop(1)

        rows.forEach { row ->
            val columns = row.split(',', limit = 6)
            check(columns.size == 6) { "CER fixture must contain six columns: $row" }
            val id = columns[0]
            val result = CharacterErrorRate.calculate(columns[1], columns[2])
            val expectedRate = columns[5].takeIf { it.isNotBlank() }?.toDouble()

            assertWithMessage("$id reference characters").that(result.referenceCharacters)
                .isEqualTo(columns[3].toInt())
            assertWithMessage("$id edit distance").that(result.editDistance)
                .isEqualTo(columns[4].toInt())
            assertWithMessage("$id CER").that(result.rate).isEqualTo(expectedRate)
        }
    }
}
