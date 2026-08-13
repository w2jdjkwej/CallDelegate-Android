package com.example.calldelegate.core.ai.evaluation

import com.example.calldelegate.core.ai.rules.DialogueRuleFile
import com.example.calldelegate.core.ai.rules.JsonDialogueEngine
import com.example.calldelegate.core.ai.rules.RegexEntityExtractor
import com.example.calldelegate.core.ai.rules.RuleBasedIntentClassifier
import com.example.calldelegate.core.ai.rules.RuleProvider
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.DialogueContext
import com.example.calldelegate.domain.model.SceneType
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class EvaluationCorpusValidator {
    private val supportedScenes = SceneType.entries
        .filter { it != SceneType.UNCLASSIFIED && it.id != "sales" }
        .map { it.id }
        .toSet()

    fun validate(corpus: EvaluationCorpus) {
        require(corpus.schemaVersion == 1) { "Unsupported evaluation schema ${corpus.schemaVersion}" }
        require(corpus.corpusId.isNotBlank()) { "corpusId must not be blank" }
        require(corpus.language == "zh-CN") { "The first evaluation version only accepts zh-CN" }
        require(corpus.sourceType == "SYNTHETIC_TEXT") { "sourceType must be SYNTHETIC_TEXT" }
        require(corpus.expectedTurnCaseCount == corpus.turnCases.size) {
            "Expected ${corpus.expectedTurnCaseCount} turn cases, found ${corpus.turnCases.size}"
        }
        require(corpus.expectedDialogueCaseCount == corpus.dialogueCases.size) {
            "Expected ${corpus.expectedDialogueCaseCount} dialogue cases, found ${corpus.dialogueCases.size}"
        }
        require(corpus.totalCaseCount > 0) { "Evaluation corpus must not be empty" }
        require(
            corpus.evidenceLevel == if (corpus.kind == CorpusKind.REGRESSION) {
                EvidenceLevel.SYNTHETIC_REGRESSION
            } else {
                EvidenceLevel.SYNTHETIC_CHALLENGE
            },
        ) { "evidenceLevel does not match corpus kind" }

        val ids = corpus.turnCases.map { it.id } + corpus.dialogueCases.map { it.id }
        require(ids.all(ID_PATTERN::matches)) { "Case IDs must use lowercase letters, digits, and underscores" }
        require(ids.distinct().size == ids.size) { "Evaluation case IDs must be unique" }

        corpus.turnCases.forEach { validateTurnCase(corpus.kind, it) }
        corpus.dialogueCases.forEach { validateDialogueCase(corpus.kind, it) }
    }

    private fun validateTurnCase(kind: CorpusKind, case: TurnEvaluationCase) {
        require(case.text.isNotBlank()) { "${case.id}: text must not be blank" }
        validateTags(case.id, case.tags)
        validateEnabledScenes(case.id, case.enabledScenes)
        validateSlots(case.id, case.expectedSlots)
        validateSupport(kind, case.id, case.expectedSupport, case.expectedScene)
        if (case.expectedScene == null) {
            require(case.expectedIntent == null) { "${case.id}: expectedIntent requires expectedScene" }
        }
    }

    private fun validateDialogueCase(kind: CorpusKind, case: DialogueEvaluationCase) {
        validateTags(case.id, case.tags)
        validateEnabledScenes(case.id, case.enabledScenes)
        validateSlots(case.id, case.expectedFinalSlots)
        validateSupport(kind, case.id, case.expectedSupport, case.expectedFinalScene)
        require(case.turns.isNotEmpty()) { "${case.id}: turns must not be empty" }
        require(case.expectedFinalState.isNotBlank()) { "${case.id}: expectedFinalState must not be blank" }
        case.turns.forEachIndexed { index, turn ->
            if (turn.recognitionFailed) {
                require(turn.text.isNullOrBlank()) { "${case.id}: failed turn $index must not contain text" }
            } else {
                require(!turn.text.isNullOrBlank()) { "${case.id}: turn $index must contain text" }
            }
        }
        val fragments = case.requiredReplyFragments + case.forbiddenReplyFragments
        require(fragments.all { it.isNotBlank() }) { "${case.id}: reply fragments must not be blank" }
        require(case.requiredReplyFragments.intersect(case.forbiddenReplyFragments.toSet()).isEmpty()) {
            "${case.id}: required and forbidden reply fragments overlap"
        }
    }

    private fun validateSupport(
        kind: CorpusKind,
        caseId: String,
        expectation: SupportExpectation,
        expectedScene: String?,
    ) {
        if (kind == CorpusKind.REGRESSION) {
            require(expectation == SupportExpectation.SUPPORTED) {
                "$caseId: regression cases cannot target future scenes"
            }
        }
        if (expectation == SupportExpectation.FUTURE) {
            require(kind == CorpusKind.CHALLENGE) { "$caseId: future cases belong in challenge corpus" }
            require(!expectedScene.isNullOrBlank() && expectedScene !in supportedScenes) {
                "$caseId: future case must name a scene that is not implemented"
            }
        } else if (expectedScene != null) {
            require(expectedScene in supportedScenes || expectedScene == SceneType.UNCLASSIFIED.id) {
                "$caseId: unknown supported scene $expectedScene"
            }
        }
    }

    private fun validateEnabledScenes(caseId: String, sceneIds: List<String>) {
        require(sceneIds.distinct().size == sceneIds.size) { "$caseId: enabledScenes contains duplicates" }
        require(sceneIds.all { it in supportedScenes }) { "$caseId: enabledScenes contains an unknown scene" }
    }

    private fun validateSlots(caseId: String, slots: Map<String, String>) {
        require(slots.keys.all { it in KNOWN_SLOTS }) { "$caseId: expectedSlots contains an unknown field" }
        require(slots.values.all { it.isNotBlank() }) { "$caseId: expected slot values must not be blank" }
    }

    private fun validateTags(caseId: String, tags: List<String>) {
        require(tags.isNotEmpty()) { "$caseId: at least one tag is required" }
        require(tags.all { it.isNotBlank() }) { "$caseId: tags must not be blank" }
        require(tags.distinct().size == tags.size) { "$caseId: tags must be unique" }
    }

    private companion object {
        val ID_PATTERN = Regex("[a-z0-9_]+")
        val KNOWN_SLOTS = setOf(
            "callerIdentity",
            "organization",
            "purpose",
            "urgent",
            "callbackNeeded",
            "time",
            "location",
            "contact",
        )
    }
}

class NluEvaluationRunner(ruleFile: DialogueRuleFile) {
    private val supportedScenes = SceneType.entries.filter { it != SceneType.UNCLASSIFIED && it.id != "sales" }.toSet()
    private val provider = RuleProvider { AppResult.Success(ruleFile) }
    private val extractor = RegexEntityExtractor()
    private val classifier = RuleBasedIntentClassifier(provider)
    private val engine = JsonDialogueEngine(provider, classifier, extractor)

    suspend fun evaluate(corpus: EvaluationCorpus): CorpusEvaluation {
        val turnResults = corpus.turnCases.map { evaluateTurn(corpus.corpusId, it) }
        val dialogueResults = corpus.dialogueCases.map { evaluateDialogue(corpus.corpusId, it) }
        val results = (turnResults + dialogueResults).sortedBy { it.id }
        return CorpusEvaluation(summarize(corpus, results), results)
    }

    private suspend fun evaluateTurn(corpusId: String, case: TurnEvaluationCase): EvaluationCaseResult {
        val enabledScenes = resolveScenes(case.enabledScenes)
        val match = classifier.classify(case.text, enabledScenes)
        val expectedSlots = structuredSlots(case.expectedSlots)
        val requestedSlots = case.expectedSlots.keys.ifEmpty { setOf(NO_EXPECTED_SLOTS) }
        val actualSlots = structuredSlots(extractor.extract(case.text, requestedSlots))
            .filterKeys { it in expectedSlots.keys }
        val sceneMatched = case.expectedScene == match?.scene?.id
        val intentMatched = case.expectedIntent == match?.intentId
        val slotsMatched = expectedSlots == actualSlots
        val failures = mutableListOf<String>()
        if (case.expectedSupport == SupportExpectation.SUPPORTED) {
            if (!sceneMatched) failures += "scene expected=${case.expectedScene} actual=${match?.scene?.id}"
            if (!intentMatched) failures += "intent expected=${case.expectedIntent} actual=${match?.intentId}"
            if (!slotsMatched) failures += "slots expected=$expectedSlots actual=$actualSlots"
        } else {
            failures += "future_scene_not_implemented"
        }

        return EvaluationCaseResult(
            corpusId = corpusId,
            id = case.id,
            caseType = "TURN",
            supportExpectation = case.expectedSupport,
            enabledScenes = enabledScenes.map { it.id }.sorted(),
            tags = case.tags.sorted(),
            inputText = case.text,
            expectedScene = case.expectedScene,
            actualScene = match?.scene?.id,
            expectedIntent = case.expectedIntent,
            actualIntent = match?.intentId,
            expectedSlots = expectedSlots,
            actualSlots = actualSlots,
            outcome = null,
            passed = if (case.expectedSupport == SupportExpectation.SUPPORTED) failures.isEmpty() else null,
            failures = failures,
            sceneMatched = if (case.expectedSupport == SupportExpectation.SUPPORTED) sceneMatched else null,
            intentMatched = if (case.expectedSupport == SupportExpectation.SUPPORTED) intentMatched else null,
            slotCounts = slotCounts(expectedSlots, actualSlots),
            dialogueCompleted = null,
        )
    }

    private suspend fun evaluateDialogue(
        corpusId: String,
        case: DialogueEvaluationCase,
    ): EvaluationCaseResult {
        val enabledScenes = resolveScenes(case.enabledScenes)
        var context = DialogueContext(case.id)
        var ended = false
        var finalIntent: String? = null
        val replies = mutableListOf<String>()
        val failures = mutableListOf<String>()

        case.turns.forEachIndexed { index, turn ->
            if (ended) {
                failures += "dialogue ended before turn ${index + 1}"
                return@forEachIndexed
            }
            val decision = engine.process(context, turn.text, turn.recognitionFailed, enabledScenes)
            context = decision.context
            replies += decision.reply
            finalIntent = decision.matchedIntent ?: finalIntent
            ended = decision.shouldEnd
        }

        val expectedSlots = structuredSlots(case.expectedFinalSlots)
        val actualSlots = structuredSlots(context.slots).filterKeys { it in expectedSlots.keys }
        val sceneMatched = case.expectedFinalScene == context.scene.id
        val stateMatched = case.expectedFinalState == context.stateId
        val endMatched = case.expectedShouldEnd == ended
        val slotsMatched = expectedSlots == actualSlots
        if (!sceneMatched) failures += "scene expected=${case.expectedFinalScene} actual=${context.scene.id}"
        if (!stateMatched) failures += "state expected=${case.expectedFinalState} actual=${context.stateId}"
        if (!endMatched) failures += "shouldEnd expected=${case.expectedShouldEnd} actual=$ended"
        if (!slotsMatched) failures += "slots expected=$expectedSlots actual=$actualSlots"
        val replyText = replies.joinToString(" | ")
        case.requiredReplyFragments.filterNot(replyText::contains).forEach {
            failures += "required reply fragment missing=$it"
        }
        case.forbiddenReplyFragments.filter(replyText::contains).forEach {
            failures += "forbidden reply fragment present=$it"
        }

        val isSupported = case.expectedSupport == SupportExpectation.SUPPORTED
        if (!isSupported) {
            failures.clear()
            failures += "future_scene_not_implemented"
        }

        return EvaluationCaseResult(
            corpusId = corpusId,
            id = case.id,
            caseType = "DIALOGUE",
            supportExpectation = case.expectedSupport,
            enabledScenes = enabledScenes.map { it.id }.sorted(),
            tags = case.tags.sorted(),
            inputText = case.turns.joinToString(" || ") {
                if (it.recognitionFailed) "<RECOGNITION_FAILED>" else it.text.orEmpty()
            },
            expectedScene = case.expectedFinalScene,
            actualScene = context.scene.id,
            expectedIntent = null,
            actualIntent = finalIntent,
            expectedSlots = expectedSlots,
            actualSlots = actualSlots,
            outcome = case.outcome,
            passed = if (isSupported) failures.isEmpty() else null,
            failures = failures,
            sceneMatched = if (isSupported) sceneMatched else null,
            intentMatched = null,
            slotCounts = slotCounts(expectedSlots, actualSlots),
            dialogueCompleted = if (isSupported) failures.isEmpty() else null,
        )
    }

    private fun resolveScenes(sceneIds: List<String>): Set<SceneType> {
        if (sceneIds.isEmpty()) return supportedScenes
        return sceneIds.mapTo(linkedSetOf()) { id -> SceneType.entries.first { it.id == id } }
    }

    private fun summarize(corpus: EvaluationCorpus, results: List<EvaluationCaseResult>): CorpusSummary {
        val evaluated = results.filter { it.passed != null }
        val passedCases = evaluated.count { it.passed == true }
        val sceneResults = evaluated.mapNotNull { it.sceneMatched }
        val intentResults = evaluated.mapNotNull { it.intentMatched }
        val dialogueResults = evaluated.filter { it.dialogueCompleted != null }
        val recoveryResults = dialogueResults.filter { it.outcome == DialogueOutcome.RECOVERED }
        val safeEndResults = dialogueResults.filter { it.outcome == DialogueOutcome.SAFE_END }
        val slots = evaluated.fold(SlotCounts(0, 0, 0)) { total, result -> total + result.slotCounts }

        return CorpusSummary(
            corpusId = corpus.corpusId,
            totalCases = results.size,
            evaluatedCases = evaluated.size,
            futureSceneCases = results.size - evaluated.size,
            passedCases = passedCases,
            failedCases = evaluated.size - passedCases,
            casePassRate = reportRate(passedCases, evaluated.size),
            sceneAccuracy = reportRate(sceneResults.count { it }, sceneResults.size),
            intentAccuracy = reportRate(intentResults.count { it }, intentResults.size),
            slotMetrics = ReportSlotMetrics(
                truePositive = slots.truePositive,
                falsePositive = slots.falsePositive,
                falseNegative = slots.falseNegative,
                precision = slots.precision,
                recall = slots.recall,
                f1 = slots.f1,
            ),
            dialogueCompletionRate = reportRate(
                dialogueResults.count { it.dialogueCompleted == true },
                dialogueResults.size,
            ),
            recoveryRate = reportRate(
                recoveryResults.count { it.dialogueCompleted == true },
                recoveryResults.size,
            ),
            safeTerminationRate = reportRate(
                safeEndResults.count { it.dialogueCompleted == true },
                safeEndResults.size,
            ),
        )
    }

    private fun reportRate(correct: Int, total: Int): ReportRate {
        val metric = rateMetric(correct, total)
        return ReportRate(metric.correct, metric.total, metric.value)
    }

    private fun structuredSlots(slots: Map<String, String>): Map<String, String> = slots
        .filterKeys { it != "purpose" }
        .toSortedMap()

    private companion object {
        const val NO_EXPECTED_SLOTS = "__no_expected_slots__"
    }
}

object EvaluationReportWriter {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun write(outputDirectory: File, regression: CorpusEvaluation, challenge: CorpusEvaluation) {
        check(outputDirectory.exists() || outputDirectory.mkdirs()) {
            "Unable to create evaluation report directory: $outputDirectory"
        }
        val summary = EvaluationSummaryReport(
            regression = regression.summary,
            challenge = challenge.summary,
        )
        File(outputDirectory, "nlu-summary.json")
            .writeText(json.encodeToString(summary) + NEW_LINE, Charsets.UTF_8)
        File(outputDirectory, "nlu-cases.csv")
            .writeText(toCsv(regression.cases), Charsets.UTF_8)
        File(outputDirectory, "challenge-cases.csv")
            .writeText(toCsv(challenge.cases), Charsets.UTF_8)
    }

    private fun toCsv(results: List<EvaluationCaseResult>): String {
        val rows = mutableListOf(CSV_HEADER)
        results.sortedBy { it.id }.forEach { result ->
            rows += listOf(
                result.corpusId,
                result.id,
                result.caseType,
                result.supportExpectation.name,
                result.enabledScenes.joinToString("|"),
                result.tags.joinToString("|"),
                result.inputText,
                result.expectedScene.orEmpty(),
                result.actualScene.orEmpty(),
                result.expectedIntent.orEmpty(),
                result.actualIntent.orEmpty(),
                slotsText(result.expectedSlots),
                slotsText(result.actualSlots),
                result.outcome?.name.orEmpty(),
                result.passed?.toString().orEmpty(),
                result.failures.joinToString(" | "),
            ).joinToString(",", transform = ::csvCell)
        }
        return rows.joinToString(NEW_LINE, postfix = NEW_LINE)
    }

    private fun slotsText(slots: Map<String, String>): String =
        slots.toSortedMap().entries.joinToString("|") { (key, value) -> "$key=$value" }

    private fun csvCell(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private const val CSV_HEADER =
        "\"corpus_id\",\"case_id\",\"case_type\",\"support_expectation\",\"enabled_scenes\",\"tags\",\"input_text\",\"expected_scene\",\"actual_scene\",\"expected_intent\",\"actual_intent\",\"expected_slots\",\"actual_slots\",\"outcome\",\"passed\",\"failures\""
    private const val NEW_LINE = "\n"
}
