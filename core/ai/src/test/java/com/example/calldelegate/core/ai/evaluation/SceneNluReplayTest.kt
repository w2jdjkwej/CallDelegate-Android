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
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Replays the recorded device corpora through the current rules, on the JVM.
 *
 * The device runs stored both the reference transcript and what the recogniser actually produced,
 * so the NLU half of those runs can be re-measured without a device: the ASR chain is unchanged, so
 * its output for the same audio is still the right input to feed. Two readings are reported --
 * reference text, which is the ceiling with a perfect recogniser, and recognised text, which is
 * what the rules really faced at that CER.
 *
 * Each sample also carries the scene the old build decided on, so this doubles as a per-case diff:
 * it shows exactly which cases a rule change fixed and which it cost, per scene, which is the
 * feedback that was missing while these rules were being narrowed and widened.
 *
 * Latency, memory and CER are not measurable here and still need the device.
 */
class SceneNluReplayTest {
    private val lenientJson = Json { ignoreUnknownKeys = true }

    @Test
    fun replayRecordedDeviceCorporaThroughCurrentRules() = runTest {
        val classifier = RuleBasedIntentClassifier(
            RuleProvider { AppResult.Success(loadProductionRules()) },
            RegexEntityExtractor(),
        )
        val enabledScenes = AppSettings().enabledScenes

        val report = StringBuilder()
        report.appendLine()
        report.appendLine("=== 场景 NLU 回放：当前规则 vs 设备产物记录 ===")
        report.appendLine()
        report.appendLine(
            "%-10s %5s | %-17s | %-17s | %s".format(
                "场景", "样本", "参考文本场景命中", "ASR文本场景命中", "相对旧产物",
            ),
        )
        report.appendLine("-".repeat(88))

        var evaluatedScenes = 0
        val intentLines = mutableListOf<String>()
        val movementLines = mutableListOf<String>()

        CORPORA.forEach { corpus ->
            val samples = loadSamples(corpus) ?: return@forEach
            evaluatedScenes++

            var refHit = 0
            var asrHit = 0
            var intentTotal = 0
            var intentHit = 0
            val fixed = mutableListOf<String>()
            val broke = mutableListOf<String>()

            samples.forEach { sample ->
                val expected = SceneType.valueOf(sample.expectedScene).id
                val reference = sample.referenceTextRaw.orEmpty()
                val recognised = sample.recognizedTextRaw.orEmpty()

                if (reference.isNotBlank() &&
                    classifier.classifyDetailed(reference, enabledScenes)?.scene == expected
                ) {
                    refHit++
                }

                val nowScene = recognised
                    .takeIf { it.isNotBlank() }
                    ?.let { classifier.classifyDetailed(it, enabledScenes)?.scene }
                val nowMatches = nowScene == expected
                if (nowMatches) asrHit++

                val thenMatches = sample.actualScene == expected
                when {
                    nowMatches && !thenMatches -> fixed += "${sample.caseId}(${sample.actualScene ?: "null"}→$expected)"
                    !nowMatches && thenMatches -> broke += "${sample.caseId}($expected→${nowScene ?: "null"})"
                }

                sample.expectedIntent?.takeIf { it.isNotBlank() }?.let { expectedIntent ->
                    intentTotal++
                    val actualIntent = recognised
                        .takeIf { it.isNotBlank() }
                        ?.let { classifier.classifyDetailed(it, enabledScenes)?.intent }
                    if (actualIntent == expectedIntent) intentHit++
                }
            }

            val total = samples.size
            val delta = fixed.size - broke.size
            report.appendLine(
                "%-10s %5d | %6d/%-3d %6.2f%% | %6d/%-3d %6.2f%% | %+d (修好%d 弄坏%d)".format(
                    corpus.label, total,
                    refHit, total, refHit * 100.0 / total,
                    asrHit, total, asrHit * 100.0 / total,
                    delta, fixed.size, broke.size,
                ),
            )
            if (intentTotal > 0) {
                intentLines += "%-10s 意图准确率 %d/%d = %.2f%%".format(
                    corpus.label, intentHit, intentTotal, intentHit * 100.0 / intentTotal,
                )
            }
            if (fixed.isNotEmpty()) movementLines += "  ${corpus.label} 修好: ${fixed.joinToString(", ")}"
            if (broke.isNotEmpty()) movementLines += "  ${corpus.label} 弄坏: ${broke.joinToString(", ")}"
        }

        report.appendLine()
        intentLines.forEach(report::appendLine)
        if (movementLines.isNotEmpty()) {
            report.appendLine()
            report.appendLine("逐条变化:")
            movementLines.forEach(report::appendLine)
        }
        report.appendLine()
        report.appendLine("注：CER、时延、内存无法在 JVM 侧测量，仍需真机。")
        println(report)

        // The recorded runs are far too large to keep in the repository, so they are present on the
        // machine that produced them and absent everywhere else. Skip rather than fail when they
        // are missing; this is a measurement, and a measurement with no data is not a defect.
        assumeTrue(
            "未找到设备语料（$evaluatedScenes/${CORPORA.size}），跳过回放。这些产物未入库，只存在于采集它们的机器上。",
            evaluatedScenes == CORPORA.size,
        )
        assertWithMessage("回放已执行").that(evaluatedScenes).isEqualTo(CORPORA.size)
    }

    private fun loadSamples(corpus: Corpus): List<ReplaySample>? {
        val root = File(projectRoot(), corpus.directory)
        if (!root.isDirectory) return null
        val file = root.walkTopDown().firstOrNull { it.name == "samples.json" } ?: return null
        return lenientJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ReplaySample.serializer()),
            file.readText(Charsets.UTF_8),
        )
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
        error("Unable to locate CallDelegate project root")
    }

    private data class Corpus(val label: String, val directory: String)

    private companion object {
        // Pinned to one run per scene -- the best recorded result for that scene -- so the
        // comparison has a fixed reference point rather than whichever directory sorts first.
        val CORPORA = listOf(
            Corpus(
                "快递外卖",
                "test/wav-call-data/wav-call-20260725-155848-097-9321fd66-160f-43f9-9223-bc926e651f4e",
            ),
            Corpus("打车出行", "test/ride/device-results/ride-hailing-round2-20260803"),
            Corpus("客服售后", "test/customer_service/device-results/customer-service-20260802-real-time-v4"),
            Corpus(
                "房产中介",
                "test/real_estate_34_16k_wav_and_manifest/device-results/" +
                    "wav-call-20260802-125539-029-4ae56820-909e-4444-ab43-23be46018e13",
            ),
            Corpus(
                "保险理财",
                "test/baoxianlicai/device-results/" +
                    "wav-call-20260802-220055-367-a5590834-761c-44ca-bd3e-0468f4631c5c",
            ),
            Corpus("骚扰识别", "test/spam_risk/wav-call-20260802-171253-691-f578098a-e1b2-4130-a01a-d148ee8ddd87"),
        )
    }
}

@Serializable
private data class ReplaySample(
    val caseId: String = "",
    val expectedScene: String = "UNCLASSIFIED",
    val actualScene: String? = null,
    val expectedIntent: String? = null,
    val referenceTextRaw: String? = null,
    val recognizedTextRaw: String? = null,
)
