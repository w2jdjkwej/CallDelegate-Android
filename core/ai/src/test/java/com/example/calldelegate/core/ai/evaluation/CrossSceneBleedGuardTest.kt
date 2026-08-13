package com.example.calldelegate.core.ai.evaluation

import com.example.calldelegate.core.ai.rules.DialogueRuleFile
import com.example.calldelegate.core.ai.rules.RegexEntityExtractor
import com.example.calldelegate.core.ai.rules.RuleBasedIntentClassifier
import com.example.calldelegate.core.ai.rules.RuleProvider
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Holds the scene rules to their own lane.
 *
 * The recurring failure in this rule set is bleeding: widening one scene's vocabulary quietly
 * pulls in utterances that belong to another, and narrowing it back drops utterances that were
 * genuinely its own. Per-scene tests cannot see this, because each one only ever looks at its own
 * scene passing.
 *
 * The device corpora already label real utterances per scene, so every corpus doubles as a set of
 * negatives for every other scene. The invariant checked here is deliberately one-sided: an
 * utterance may be classified as its own scene, or left unclassified, but it must never be claimed
 * by a different one. Failing to recognise something is a coverage gap and shows up in the
 * evaluation corpora; being claimed by the wrong scene is the bleed this guard exists to catch.
 */
class CrossSceneBleedGuardTest {
    private val strictJson = Json { ignoreUnknownKeys = true }

    @Test
    fun labelledUtterancesAreNeverClaimedByAnotherScene() = runTest {
        val classifier = RuleBasedIntentClassifier(
            RuleProvider { AppResult.Success(loadProductionRules()) },
            RegexEntityExtractor(),
        )
        val enabledScenes = AppSettings().enabledScenes

        val baseline = loadBaseline()
        val knownById = baseline.knownBleeds.associateBy { it.caseId }
        val observed = mutableListOf<GuardBleed>()

        loadCorpora().forEach { case ->
            val actual = classifier.classifyDetailed(case.text, enabledScenes)?.scene
            if (actual != null && actual != case.expectedScene.id) {
                observed += GuardBleed(
                    caseId = case.caseId,
                    text = case.text,
                    expectedScene = case.expectedScene.id,
                    actualScene = actual,
                )
            }
        }

        val observedById = observed.associateBy { it.caseId }
        val newBleeds = observed.filterNot { it.caseId in knownById }
        val resolved = baseline.knownBleeds.filterNot { it.caseId in observedById }
        val changed = baseline.knownBleeds.filter { known ->
            observedById[known.caseId]?.actualScene?.let { it != known.actualScene } == true
        }

        val message = buildString {
            appendLine("Cross-scene bleed guard does not match its recorded baseline.")
            appendLine("Checked ${loadCorpora().size} labelled utterances across the device corpora.")
            if (newBleeds.isNotEmpty()) {
                appendLine()
                appendLine("NEW BLEED -- another scene claimed these:")
                newBleeds.forEach { bleed ->
                    appendLine("  ${bleed.caseId}: ${bleed.expectedScene} -> ${bleed.actualScene}")
                    appendLine("      ${bleed.text}")
                }
            }
            if (resolved.isNotEmpty()) {
                appendLine()
                appendLine("RESOLVED -- remove these from cross_scene_guard_baseline.json:")
                resolved.forEach { known ->
                    appendLine("  ${known.caseId} (was ${known.expectedScene} -> ${known.actualScene})")
                }
            }
            if (changed.isNotEmpty()) {
                appendLine()
                appendLine("CHANGED TARGET -- still bleeding, but into a different scene:")
                changed.forEach { known ->
                    appendLine(
                        "  ${known.caseId}: recorded ${known.actualScene}, now ${observedById[known.caseId]?.actualScene}",
                    )
                }
            }
        }

        assertWithMessage(message)
            .that(newBleeds.isEmpty() && resolved.isEmpty() && changed.isEmpty())
            .isTrue()
    }

    private fun loadCorpora(): List<GuardCase> = CORPUS_FILES.flatMap { (scene, relativePath) ->
        val file = File(projectRoot(), relativePath)
        check(file.isFile) { "Guard corpus missing: $relativePath" }
        strictJson.decodeFromString(GuardManifest.serializer(), file.readText(Charsets.UTF_8))
            .cases
            .mapNotNull { raw ->
                raw.referenceText?.takeIf { it.isNotBlank() }?.let { text ->
                    GuardCase(caseId = raw.caseId, text = text, expectedScene = scene)
                }
            }
    }

    private fun loadBaseline(): GuardBaseline {
        val resource = checkNotNull(javaClass.getResource("/evaluation/cross_scene_guard_baseline.json"))
        return Json { ignoreUnknownKeys = false }
            .decodeFromString(GuardBaseline.serializer(), resource.readText(Charsets.UTF_8))
    }

    private fun loadProductionRules(): DialogueRuleFile {
        val ruleFile = File(projectRoot(), "app/src/main/assets/dialogue_rules.json")
        return Json { ignoreUnknownKeys = false }
            .decodeFromString(DialogueRuleFile.serializer(), ruleFile.readText(Charsets.UTF_8))
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

    private data class GuardCase(
        val caseId: String,
        val text: String,
        val expectedScene: SceneType,
    )

    private companion object {
        val CORPUS_FILES = listOf(
            SceneType.SPAM_RISK to "test/spam_risk/manifest.json",
            SceneType.RIDE_HAILING to "test/ride/manifest.json",
            SceneType.REAL_ESTATE to "test/real_estate_34_16k_wav_and_manifest/manifest.json",
            SceneType.INSURANCE_FINANCE to "test/baoxianlicai/manifest_insurance_finance_36_merged.json",
        )
    }
}

@Serializable
private data class GuardManifest(val cases: List<GuardManifestCase> = emptyList())

@Serializable
private data class GuardManifestCase(
    val caseId: String,
    val referenceText: String? = null,
)

@Serializable
data class GuardBaseline(
    val schemaVersion: Int,
    val knownBleeds: List<GuardBleed> = emptyList(),
)

@Serializable
data class GuardBleed(
    val caseId: String,
    val expectedScene: String,
    val actualScene: String,
    val text: String = "",
    val reason: String = "",
)
