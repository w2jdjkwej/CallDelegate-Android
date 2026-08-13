package com.example.calldelegate.core.ai.evaluation

import com.example.calldelegate.core.ai.rules.RegexEntityExtractor
import com.example.calldelegate.core.ai.rules.RuleBasedIntentClassifier
import com.example.calldelegate.core.ai.rules.RuleProvider
import com.example.calldelegate.core.ai.rules.loadProductionRuleFile
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.RuleClassificationResult
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class SpamRiskDualInputEvaluationTest {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Test
    fun evaluatesReferenceAndObservedAsrTextSeparately() = runTest {
        val root = projectRoot()
        val manifestFile = File(root, "test/spam_risk/manifest.json")
        val samplesFile = File(
            root,
            "test/fixtures/spam_risk_asr_samples.json",
        )
        assertThat(manifestFile.isFile).isTrue()
        assertThat(samplesFile.isFile).isTrue()

        val manifestCases = json.parseToJsonElement(manifestFile.readText(Charsets.UTF_8))
            .jsonObject.getValue("cases") as JsonArray
        val observedSamples = json.parseToJsonElement(samplesFile.readText(Charsets.UTF_8)) as JsonArray
        val recognizedByCase = observedSamples.associate { element ->
            val sample = element.jsonObject
            sample.string("caseId") to sample.stringOrNull("recognizedTextRaw").orEmpty()
        }
        assertThat(manifestCases).hasSize(36)
        assertThat(recognizedByCase).hasSize(36)

        val rules = loadProductionRuleFile()
        val classifier = RuleBasedIntentClassifier(
            provider = RuleProvider { AppResult.Success(rules) },
            extractor = RegexEntityExtractor(),
        )
        val enabledScenes = SceneType.entries.filterNot { it == SceneType.UNCLASSIFIED }.toSet()
        val caseResults = manifestCases.map { element ->
            val case = element.jsonObject
            val caseId = case.string("caseId")
            val referenceText = case.string("referenceText")
            val recognizedText = recognizedByCase.getValue(caseId)
            val expectedScene = case.string("expectedScene").lowercase()
            val expectedEntities = case["expectedEntities"]?.jsonObject.orEmpty().mapValues { (_, value) ->
                value.jsonPrimitive.content
            }
            val reference = classifier.classifyDetailed(referenceText, enabledScenes)
            val recognized = classifier.classifyDetailed(recognizedText, enabledScenes)
            SpamRiskDualInputCaseResult(
                caseId = caseId,
                referenceText = referenceText,
                recognizedText = recognizedText,
                expectedScene = expectedScene,
                expectedRiskReason = expectedEntities["riskReason"],
                reference = reference.toEvaluationResult(expectedScene, expectedEntities["riskReason"]),
                recognized = recognized.toEvaluationResult(expectedScene, expectedEntities["riskReason"]),
            )
        }
        val report = SpamRiskDualInputReport(
            generatedFromManifest = manifestFile.relativeTo(root).invariantSeparatorsPath,
            generatedFromSamples = samplesFile.relativeTo(root).invariantSeparatorsPath,
            referenceText = caseResults.summaryOf { it.reference },
            observedAsrText = caseResults.summaryOf { it.recognized },
            cases = caseResults,
        )

        val outputDirectory = File(root, "core/ai/build/reports/evaluation").apply { mkdirs() }
        File(outputDirectory, "spam-risk-dual-input-summary.json").writeText(
            json.encodeToString(report),
            Charsets.UTF_8,
        )
        File(outputDirectory, "spam-risk-dual-input-cases.csv").writeText(
            buildCsv(caseResults),
            Charsets.UTF_8,
        )

        assertThat(report.referenceText.totalCases).isEqualTo(36)
        assertThat(report.observedAsrText.totalCases).isEqualTo(36)
    }

    private fun RuleClassificationResult?.toEvaluationResult(
        expectedScene: String,
        expectedRiskReason: String?,
    ): SpamRiskInputResult {
        val riskReasons = this?.riskReasons.orEmpty()
        return SpamRiskInputResult(
            scene = this?.scene,
            intent = this?.intent,
            topicScene = this?.topicScene,
            callNature = this?.callNature?.name,
            riskLevel = this?.riskLevel?.name,
            riskReasons = riskReasons,
            confidence = this?.confidence,
            sceneMargin = this?.sceneMargin,
            shouldClarify = this?.shouldClarify,
            sceneCandidates = this?.sceneCandidates.orEmpty(),
            sceneMatched = this?.scene == expectedScene,
            expectedRiskReasonMatched = expectedRiskReason?.let(riskReasons::contains),
        )
    }

    private fun List<SpamRiskDualInputCaseResult>.summaryOf(
        selector: (SpamRiskDualInputCaseResult) -> SpamRiskInputResult,
    ): SpamRiskInputSummary {
        val results = map(selector)
        val sceneMatches = results.count { it.sceneMatched }
        val riskReasonCases = results.count { it.expectedRiskReasonMatched != null }
        val riskReasonMatches = results.count { it.expectedRiskReasonMatched == true }
        return SpamRiskInputSummary(
            totalCases = size,
            sceneMatchedCases = sceneMatches,
            sceneAccuracy = sceneMatches.toDouble() / size,
            highRiskCases = results.count { it.riskLevel == "HIGH" },
            mediumRiskCases = results.count { it.riskLevel == "MEDIUM" },
            unclassifiedCases = results.count { it.scene == null },
            clarificationCases = results.count { it.shouldClarify == true },
            expectedRiskReasonCases = riskReasonCases,
            expectedRiskReasonMatchedCases = riskReasonMatches,
            expectedRiskReasonAccuracy = riskReasonMatches.toDouble().takeIf { riskReasonCases > 0 }
                ?.div(riskReasonCases),
        )
    }

    private fun buildCsv(cases: List<SpamRiskDualInputCaseResult>): String = buildString {
        appendLine(
            "caseId,inputType,expectedScene,actualScene,intent,topicScene,callNature,riskLevel," +
                "riskReasons,confidence,sceneMargin,shouldClarify,sceneCandidates,sceneMatched,text",
        )
        cases.forEach { case ->
            appendCsvRow(case, "REFERENCE", case.referenceText, case.reference)
            appendCsvRow(case, "OBSERVED_ASR", case.recognizedText, case.recognized)
        }
    }

    private fun StringBuilder.appendCsvRow(
        case: SpamRiskDualInputCaseResult,
        inputType: String,
        text: String,
        result: SpamRiskInputResult,
    ) {
        appendLine(
            listOf(
                case.caseId,
                inputType,
                case.expectedScene,
                result.scene,
                result.intent,
                result.topicScene,
                result.callNature,
                result.riskLevel,
                result.riskReasons.joinToString("|"),
                result.confidence,
                result.sceneMargin,
                result.shouldClarify,
                result.sceneCandidates.joinToString("|"),
                result.sceneMatched,
                text,
            ).joinToString(",") { value -> csv(value?.toString().orEmpty()) },
        )
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.stringOrNull(name: String): String? = get(name)?.jsonPrimitive?.content

    private fun projectRoot(): File {
        var current: File? = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile
        }
        error("Unable to locate CallDelegate project root")
    }
}

@Serializable
private data class SpamRiskDualInputReport(
    val schemaVersion: Int = 1,
    val generatedFromManifest: String,
    val generatedFromSamples: String,
    val referenceText: SpamRiskInputSummary,
    val observedAsrText: SpamRiskInputSummary,
    val cases: List<SpamRiskDualInputCaseResult>,
)

@Serializable
private data class SpamRiskInputSummary(
    val totalCases: Int,
    val sceneMatchedCases: Int,
    val sceneAccuracy: Double,
    val highRiskCases: Int,
    val mediumRiskCases: Int,
    val unclassifiedCases: Int,
    val clarificationCases: Int,
    val expectedRiskReasonCases: Int,
    val expectedRiskReasonMatchedCases: Int,
    val expectedRiskReasonAccuracy: Double?,
)

@Serializable
private data class SpamRiskDualInputCaseResult(
    val caseId: String,
    val referenceText: String,
    val recognizedText: String,
    val expectedScene: String,
    val expectedRiskReason: String?,
    val reference: SpamRiskInputResult,
    val recognized: SpamRiskInputResult,
)

@Serializable
private data class SpamRiskInputResult(
    val scene: String?,
    val intent: String?,
    val topicScene: String?,
    val callNature: String?,
    val riskLevel: String?,
    val riskReasons: List<String>,
    val confidence: Float?,
    val sceneMargin: Float?,
    val shouldClarify: Boolean?,
    val sceneCandidates: List<String>,
    val sceneMatched: Boolean,
    val expectedRiskReasonMatched: Boolean?,
)
