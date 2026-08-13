package com.example.calldelegate.core.ai.evaluation

import com.example.calldelegate.core.ai.rules.DialogueRuleFile
import com.example.calldelegate.core.ai.rules.RegexEntityExtractor
import com.example.calldelegate.core.ai.rules.RuleBasedIntentClassifier
import com.example.calldelegate.core.ai.rules.RuleProvider
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

class NluEvaluationTest {
    private val strictJson = Json { ignoreUnknownKeys = false }

    @Test
    fun productionRulesGenerateStableRegressionAndChallengeReports() = runTest {
        val regressionCorpus = loadCorpus("zh_cn_regression.json")
        val challengeCorpus = loadCorpus("zh_cn_challenge.json")
        val validator = EvaluationCorpusValidator()
        validator.validate(regressionCorpus)
        validator.validate(challengeCorpus)

        assertThat(regressionCorpus.totalCaseCount).isEqualTo(52)
        assertThat(challengeCorpus.totalCaseCount).isEqualTo(32)
        val challengeTags = challengeCorpus.turnCases.flatMap { it.tags }.toSet()
        assertThat(challengeTags).containsAtLeast(
            "negation",
            "correction",
            "multi_intent",
            "sensitive_info",
        )
        assertThat(regressionCorpus.dialogueCases.map { it.outcome })
            .contains(DialogueOutcome.SAFE_END)

        val runner = NluEvaluationRunner(loadProductionRules())
        val regression = runner.evaluate(regressionCorpus)
        val challenge = runner.evaluate(challengeCorpus)
        val outputDirectory = File(projectRoot(), "core/ai/build/reports/evaluation")
        EvaluationReportWriter.write(outputDirectory, regression, challenge)

        val firstSummary = File(outputDirectory, "nlu-summary.json").readText(Charsets.UTF_8)
        val firstRegressionCsv = File(outputDirectory, "nlu-cases.csv").readText(Charsets.UTF_8)
        val firstChallengeCsv = File(outputDirectory, "challenge-cases.csv").readText(Charsets.UTF_8)
        EvaluationReportWriter.write(outputDirectory, regression, challenge)

        assertThat(File(outputDirectory, "nlu-summary.json").readText(Charsets.UTF_8))
            .isEqualTo(firstSummary)
        assertThat(File(outputDirectory, "nlu-cases.csv").readText(Charsets.UTF_8))
            .isEqualTo(firstRegressionCsv)
        assertThat(File(outputDirectory, "challenge-cases.csv").readText(Charsets.UTF_8))
            .isEqualTo(firstChallengeCsv)
        assertThat(firstSummary).contains("\"asrCerStatus\": \"NOT_MEASURED\"")
        assertThat(challenge.summary.futureSceneCases).isEqualTo(0)
        assertThat(challenge.summary.evaluatedCases).isEqualTo(32)

        val baseline = loadBaseline()
        listOf(regression, challenge).forEach { evaluation ->
            val comparison = baseline.corpus(evaluation.summary.corpusId).compareTo(evaluation)
            assertWithMessage(comparison.describe()).that(comparison.isClean).isTrue()
        }
    }

    @Test
    fun productionRulesHandleGeneralizedCrossSceneExpressions() = runTest {
        val ruleFile = loadProductionRules()
        val provider = RuleProvider { AppResult.Success(ruleFile) }
        val classifier = RuleBasedIntentClassifier(provider)
        val extractor = RegexEntityExtractor()
        val enabledScenes = AppSettings().enabledScenes
        val cases = listOf(
            CrossSceneCase(
                text = "免费体验课程有优惠，我不需要你们再联系",
                expectedScene = SceneType.SPAM_RISK,
                expectedIntent = "marketing_pitch",
                expectedSlots = mapOf("callbackNeeded" to "false"),
            ),
            CrossSceneCase(
                // A colleague's call selects no scene now that work is not one the system answers
                // for (see AppSettings.enabledScenes). Who called and how to reach them is still
                // read off the turn, because extraction does not depend on a scene being chosen.
                text = "我是技术部王磊，电话13900139000，稍后把资料发过来",
                expectedScene = null,
                expectedIntent = null,
                expectedSlots = mapOf(
                    "callerIdentity" to "王磊",
                    "organization" to "技术部",
                    "contact" to "13900139000",
                ),
            ),
            CrossSceneCase(
                text = "包裹留在南门保安室，不必给我回电话",
                expectedScene = SceneType.DELIVERY,
                expectedIntent = "delivery_request",
                expectedSlots = mapOf("callbackNeeded" to "false", "location" to "南门保安室"),
            ),
            CrossSceneCase(
                text = "麻烦回电，但请勿泄露机主住址",
                expectedScene = SceneType.UNKNOWN_IDENTITY,
                expectedIntent = "identity_inquiry",
                expectedSlots = mapOf("callbackNeeded" to "true"),
            ),
        )

        cases.forEach { case ->
            val match = classifier.classify(case.text, enabledScenes)
            assertWithMessage("input: %s", case.text).that(match?.scene).isEqualTo(case.expectedScene)
            assertWithMessage("input: %s", case.text).that(match?.intentId).isEqualTo(case.expectedIntent)

            val slots = extractor.extract(case.text, case.expectedSlots.keys)
            case.expectedSlots.forEach { (key, value) ->
                assertWithMessage("input: %s, slot: %s", case.text, key).that(slots[key]).isEqualTo(value)
            }
        }
    }

    @Test
    fun genericWordsAloneDoNotSelectAScene() = runTest {
        val provider = RuleProvider { AppResult.Success(loadProductionRules()) }
        val classifier = RuleBasedIntentClassifier(provider)
        val enabledScenes = AppSettings().enabledScenes

        listOf("我是李明", "请联系", "需要", "回电").forEach { text ->
            assertWithMessage("input: %s", text).that(classifier.classify(text, enabledScenes)).isNull()
        }
    }

    @Test
    fun intentAndSlotPolarityStayConsistent() = runTest {
        val provider = RuleProvider { AppResult.Success(loadProductionRules()) }
        val classifier = RuleBasedIntentClassifier(provider)
        val extractor = RegexEntityExtractor()
        val cases = listOf(
            PolarityCase("不需要再回电话", SceneType.WORK, "callback_no", "callbackNeeded", "false"),
            PolarityCase("麻烦稍后回个电话", SceneType.DELIVERY, "callback_yes", "callbackNeeded", "true"),
            PolarityCase("这事并不算紧急", SceneType.WORK, "urgent_no", "urgent", "false"),
        )

        cases.forEach { case ->
            val match = classifier.classify(case.text, setOf(case.scene))
            val slots = extractor.extract(case.text, setOf(case.slotName))
            assertWithMessage("input: %s", case.text).that(match?.intentId).isEqualTo(case.expectedIntent)
            assertWithMessage("input: %s", case.text).that(slots[case.slotName]).isEqualTo(case.slotValue)
        }
    }

    @Test
    fun validatorRejectsDuplicateCaseIds() {
        val corpus = loadCorpus("zh_cn_regression.json")
        val duplicate = corpus.copy(
            expectedTurnCaseCount = corpus.expectedTurnCaseCount + 1,
            turnCases = corpus.turnCases + corpus.turnCases.first(),
        )
        val failure = runCatching { EvaluationCorpusValidator().validate(duplicate) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure).hasMessageThat().contains("unique")
    }

    @Test
    fun strictDecoderRejectsUnknownFields() {
        val invalidJson = """
            {
              "schemaVersion": 1,
              "corpusId": "invalid",
              "language": "zh-CN",
              "sourceType": "SYNTHETIC_TEXT",
              "evidenceLevel": "SYNTHETIC_REGRESSION",
              "kind": "REGRESSION",
              "expectedTurnCaseCount": 0,
              "expectedDialogueCaseCount": 0,
              "unexpected": true
            }
        """.trimIndent()

        assertThat(
            runCatching { strictJson.decodeFromString(EvaluationCorpus.serializer(), invalidJson) }.isFailure,
        ).isTrue()
    }

    private fun loadCorpus(name: String): EvaluationCorpus {
        val resource = checkNotNull(javaClass.getResource("/evaluation/$name"))
        return strictJson.decodeFromString(EvaluationCorpus.serializer(), resource.readText(Charsets.UTF_8))
    }

    private fun loadBaseline(): EvaluationBaseline {
        val resource = checkNotNull(javaClass.getResource("/evaluation/nlu_baseline.json"))
        return strictJson.decodeFromString(EvaluationBaseline.serializer(), resource.readText(Charsets.UTF_8))
    }

    private fun loadProductionRules(): DialogueRuleFile {
        val ruleFile = File(projectRoot(), "app/src/main/assets/dialogue_rules.json")
        return strictJson.decodeFromString(DialogueRuleFile.serializer(), ruleFile.readText(Charsets.UTF_8))
    }

    private fun projectRoot(): File {
        val userDirectory = checkNotNull(System.getProperty("user.dir"))
        var current: File? = File(userDirectory).canonicalFile
        while (current != null) {
            val settings = File(current, "settings.gradle.kts")
            val rules = File(current, "app/src/main/assets/dialogue_rules.json")
            if (settings.isFile && rules.isFile) return current
            current = current.parentFile
        }
        error("Unable to locate CallDelegate project root from ${System.getProperty("user.dir")}")
    }

    private data class CrossSceneCase(
        val text: String,
        /** Null when no scene should be selected, which slot extraction is independent of. */
        val expectedScene: SceneType?,
        val expectedIntent: String?,
        val expectedSlots: Map<String, String>,
    )

    private data class PolarityCase(
        val text: String,
        val scene: SceneType,
        val expectedIntent: String,
        val slotName: String,
        val slotValue: String,
    )
}
