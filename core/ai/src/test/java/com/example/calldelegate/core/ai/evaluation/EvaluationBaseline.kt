package com.example.calldelegate.core.ai.evaluation

import kotlinx.serialization.Serializable

/**
 * The evaluation result this repository currently stands at.
 *
 * Asserting "no case may fail" turns every rule change into a single red/green bit: it says nothing
 * about whether the change fixed more than it broke, which scenes moved, or whether a failure is
 * new or long-standing. Rule work needs that detail, because narrowing one scene's vocabulary to
 * stop it bleeding into another routinely costs a case somewhere else.
 *
 * So the gate compares against a recorded state instead. A case that used to pass and now fails is
 * a regression and fails the build. A case listed here that starts passing also fails the build,
 * asking for the entry to be removed -- otherwise the record silently drifts away from reality and
 * stops being worth trusting.
 */
@Serializable
data class EvaluationBaseline(
    val schemaVersion: Int,
    val corpora: List<BaselineCorpus>,
) {
    fun corpus(corpusId: String): BaselineCorpus =
        corpora.firstOrNull { it.corpusId == corpusId }
            ?: error("No baseline recorded for corpus '$corpusId'. Add one before evaluating it.")
}

@Serializable
data class BaselineCorpus(
    val corpusId: String,
    /** Cases known to fail today. Each needs a reason, so the list cannot grow by inertia. */
    val knownFailures: List<BaselineKnownFailure> = emptyList(),
    /** Lower bounds on corpus-level metrics, which catch degradation inside still-passing cases. */
    val metricFloors: BaselineMetricFloors? = null,
)

@Serializable
data class BaselineKnownFailure(
    val caseId: String,
    val reason: String,
)

@Serializable
data class BaselineMetricFloors(
    val sceneAccuracy: Double,
    val intentAccuracy: Double,
    val slotF1: Double,
)

/** The difference between a recorded baseline and an evaluation that just ran. */
data class BaselineComparison(
    val corpusId: String,
    val newFailures: List<EvaluationCaseResult>,
    val newlyPassing: List<BaselineKnownFailure>,
    val stillFailing: List<BaselineKnownFailure>,
    val floorBreaches: List<String>,
) {
    val isClean: Boolean
        get() = newFailures.isEmpty() && newlyPassing.isEmpty() && floorBreaches.isEmpty()

    fun describe(): String = buildString {
        appendLine("Corpus $corpusId does not match its recorded baseline.")
        if (newFailures.isNotEmpty()) {
            appendLine()
            appendLine("NOT IN BASELINE -- either a regression, or a failure that was never recorded:")
            newFailures.forEach { case ->
                appendLine("  ${case.id}  (${case.inputText})")
                appendLine("      ${case.failures.joinToString("; ")}")
            }
        }
        if (newlyPassing.isNotEmpty()) {
            appendLine()
            appendLine("FIXED -- remove these from nlu_baseline.json to lock the improvement in:")
            newlyPassing.forEach { known -> appendLine("  ${known.caseId}  (was: ${known.reason})") }
        }
        if (floorBreaches.isNotEmpty()) {
            appendLine()
            appendLine("BELOW FLOOR:")
            floorBreaches.forEach { breach -> appendLine("  $breach") }
        }
        if (stillFailing.isNotEmpty()) {
            appendLine()
            appendLine("Unchanged known failures (${stillFailing.size}), not the cause of this failure:")
            stillFailing.forEach { known -> appendLine("  ${known.caseId}: ${known.reason}") }
        }
    }
}

fun BaselineCorpus.compareTo(evaluation: CorpusEvaluation): BaselineComparison {
    val failedNow = evaluation.cases.filter { it.passed == false }
    val failedNowById = failedNow.associateBy { it.id }
    val knownById = knownFailures.associateBy { it.caseId }

    val floorBreaches = buildList {
        metricFloors?.let { floors ->
            evaluation.summary.sceneAccuracy.value?.let { actual ->
                if (actual < floors.sceneAccuracy) add("sceneAccuracy $actual < ${floors.sceneAccuracy}")
            }
            evaluation.summary.intentAccuracy.value?.let { actual ->
                if (actual < floors.intentAccuracy) add("intentAccuracy $actual < ${floors.intentAccuracy}")
            }
            evaluation.summary.slotMetrics.f1?.let { actual ->
                if (actual < floors.slotF1) add("slotF1 $actual < ${floors.slotF1}")
            }
        }
    }

    return BaselineComparison(
        corpusId = corpusId,
        newFailures = failedNow.filterNot { it.id in knownById },
        newlyPassing = knownFailures.filterNot { it.caseId in failedNowById },
        stillFailing = knownFailures.filter { it.caseId in failedNowById },
        floorBreaches = floorBreaches,
    )
}
