package com.example.calldelegate.testing.wav

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WavCallManifestReaderTest {

    @Test
    fun resolvesWavOnlyInsideManifestDirectory() {
        val directory = Files.createTempDirectory("wav-manifest").toFile()
        File(directory, "audio.wav").writeBytes(byteArrayOf())
        val manifest = File(directory, "manifest.json")
        manifest.writeText(
            """
            {
              "schemaVersion": 2,
              "manifestVersion": "test",
              "defaultInitialScene": "DELIVERY",
              "cases": [{
                "caseId": "case_001",
                "wavFile": "audio.wav",
                "referenceText": "reference",
                "speechStartMs": 0,
                "speechEndMs": 10,
                "expectedScene": "delivery",
                "expectedDeliveryIntent": "placed",
                "expectedEntities": {"location": "North Gate"},
                "expectedHotwords": ["North Gate"]
              }]
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val parsed = WavCallManifestReader.read(manifest)

        assertThat(parsed.manifestVersion).isEqualTo("test")
        assertThat(parsed.cases.single().input.wavFile).isEqualTo(File(directory, "audio.wav").canonicalFile)
        assertThat(parsed.cases.single().input.initialScene?.id).isEqualTo("delivery")
        assertThat(parsed.cases.single().evaluation.referenceText).isEqualTo("reference")
        assertThat(parsed.cases.single().evaluation.expectedDeliveryIntent).isEqualTo("placed")
        assertThat(parsed.cases.single().evaluation.expectedHotwords).containsExactly("North Gate")
    }

    @Test
    fun rejectsPathEscapingManifestDirectory() {
        val directory = Files.createTempDirectory("wav-manifest").toFile()
        val manifest = File(directory, "manifest.json")
        manifest.writeText(
            """{"schemaVersion":2,"cases":[{"caseId":"case_001","wavFile":"../outside.wav"}]}""",
            Charsets.UTF_8,
        )

        val failure = runCatching { WavCallManifestReader.read(manifest) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(WavCallManifestException::class.java)
        assertThat((failure as WavCallManifestException).code).isEqualTo("MANIFEST_WAV_ESCAPE")
    }

    @Test
    fun readsSchemaV3EvaluationLabelsAndDigitSpans() {
        val directory = Files.createTempDirectory("wav-manifest-v3").toFile()
        val manifest = File(directory, "manifest.json")
        manifest.writeText(
            """
            {
              "schemaVersion": 3,
              "manifestVersion": "test-v3",
              "cases": [{
                "caseId": "case_001",
                "wavFile": "audio.wav",
                "expectedScene": "CUSTOMER_SERVICE",
                "expectedIntent": "after_sales",
                "expectedCallNature": "SERVICE",
                "expectedRiskLevel": "LOW",
                "expectedDigitSpans": [[4, 8]],
                "turnId": "turn_001",
                "turnIndex": 0
              }]
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val evaluation = WavCallManifestReader.read(manifest).cases.single().evaluation

        assertThat(evaluation.expectedIntent).isEqualTo("after_sales")
        assertThat(evaluation.expectedCallNature).isEqualTo("SERVICE")
        assertThat(evaluation.expectedRiskLevel).isEqualTo("LOW")
        assertThat(evaluation.expectedDigitSpans).containsExactly(listOf(4, 8))
        assertThat(evaluation.turnId).isEqualTo("turn_001")
        assertThat(evaluation.turnIndex).isEqualTo(0)
        assertThat(evaluation.evaluateEntities).isFalse()
    }

    @Test
    fun treatsEmptyEntityExpectationAsUnannotatedByDefault() {
        val directory = Files.createTempDirectory("wav-manifest-entities").toFile()
        val manifest = File(directory, "manifest.json")
        manifest.writeText(
            """
            {
              "schemaVersion": 2,
              "cases": [{
                "caseId": "case_001",
                "wavFile": "audio.wav",
                "expectedEntities": {}
              }]
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val evaluation = WavCallManifestReader.read(manifest).cases.single().evaluation

        assertThat(evaluation.expectedEntities).isEmpty()
        assertThat(evaluation.evaluateEntities).isFalse()
    }
}
