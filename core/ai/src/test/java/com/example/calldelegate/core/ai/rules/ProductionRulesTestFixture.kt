package com.example.calldelegate.core.ai.rules

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val strictRuleJson = Json { ignoreUnknownKeys = false }

internal fun loadProductionRuleFile(): DialogueRuleFile {
    val file = File(projectRoot(), "app/src/main/assets/dialogue_rules.json")
    return strictRuleJson.decodeFromString(DialogueRuleFile.serializer(), file.readText(Charsets.UTF_8))
}

/**
 * Utterances that use risk vocabulary legitimately. Shared by the classifier-level check in
 * [SpamRiskHardNegativeTextTest] and the termination-cost check in [RiskTerminationCostTest], which
 * read the same corpus at different layers.
 */
@Serializable
internal data class HardNegativeManifest(
    val schemaVersion: Int,
    val kind: String,
    val cases: List<HardNegativeCase>,
)

@Serializable
internal data class HardNegativeCase(
    val caseId: String,
    val category: String,
    val text: String,
)

internal fun loadHardNegativeManifest(): HardNegativeManifest {
    val file = File(projectRoot(), "test/spam_risk/hard_negative_text_manifest.json")
    return strictRuleJson.decodeFromString(HardNegativeManifest.serializer(), file.readText(Charsets.UTF_8))
}

private fun projectRoot(): File {
    var current: File? = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
    while (current != null) {
        if (File(current, "settings.gradle.kts").isFile && File(current, "app/src/main/assets/dialogue_rules.json").isFile) {
            return current
        }
        current = current.parentFile
    }
    error("Unable to locate CallDelegate project root")
}
