package com.example.calldelegate.core.ai.evaluation

import com.example.calldelegate.core.ai.rules.DialogueRuleFile
import com.example.calldelegate.core.ai.rules.JsonDialogueEngine
import com.example.calldelegate.core.ai.rules.RegexEntityExtractor
import com.example.calldelegate.domain.model.DialogueContext
import com.example.calldelegate.core.ai.rules.RuleBasedIntentClassifier
import com.example.calldelegate.core.ai.rules.RuleProvider
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs a blind set: utterances written after the rules were tuned, which the rules have never seen.
 *
 * Everything else measuring these rules -- the evaluation corpora, the device runs, the bleed guard
 * -- was available while they were being tuned, and some of it demonstrably steered that tuning.
 * Scores on that material say the rules reproduce what they were fitted to; they cannot say the
 * rules generalise. Only material authored afterwards can.
 *
 * That property is destroyed by using it twice. Once a number from here drives a rule change, this
 * becomes a second training set and the next number it produces means nothing. So this reports and
 * never gates: there is no threshold to fail, no baseline to update, and nothing here that creates
 * a reason to edit the rules until a fresh set is written.
 *
 * Input: [BLIND_SET_PATH], tab- or comma-separated, one case per line.
 *
 *     delivery            您好您的快递到了放门口可以吗
 *     spam_risk           恭喜您被抽中免费领取礼品
 *     none                喂你好哪位
 *     ride_hailing,driver_arrived,师傅我在小区南门等您
 *
 * Column one is the expected scene id, or `none` when no scene should be selected. The last column
 * is the utterance. An optional middle column is the expected intent. Blank lines and lines opening
 * with `#` are ignored.
 */
class BlindSetEvaluationTest {

    @Test
    fun classifyBlindSetAndReport() = runTest {
        val available = SPLITS.filter { File(projectRoot(), it.second).isFile }
        assumeTrue("未找到任何盲测集，跳过。", available.isNotEmpty())
        available.forEach { (label, path) -> reportSplit(label, path) }
    }

    private suspend fun reportSplit(label: String, path: String) {
        val cases = parse(File(projectRoot(), path))
        if (cases.isEmpty()) return

        val classifier = RuleBasedIntentClassifier(
            RuleProvider { AppResult.Success(loadProductionRules()) },
            RegexEntityExtractor(),
        )
        val enabledScenes = AppSettings().enabledScenes

        // Classifying correctly is not the product; answering is. The engine runs on the same turn
        // so the report can show what the caller would actually have heard back.
        val engine = JsonDialogueEngine(
            RuleProvider { AppResult.Success(loadProductionRules()) },
            classifier,
            RegexEntityExtractor(),
        )
        val results = cases.mapIndexed { index, case ->
            val classification = classifier.classifyDetailed(case.text, enabledScenes)
            val decision = engine.process(
                context = DialogueContext(sessionId = "blind-$index"),
                callerText = case.text,
                recognitionFailed = false,
                enabledScenes = enabledScenes,
            )
            BlindResult(
                case = case,
                actualScene = classification?.scene,
                actualIntent = classification?.intent,
                confidence = classification?.confidence ?: 0f,
                reply = decision.reply,
                replyTemplateId = decision.replyTemplateId,
                isFallbackReply = decision.isFallbackTemplate ||
                    decision.replyTemplateId?.startsWith("fallback") == true ||
                    decision.replyTemplateId == "clarification",
            )
        }

        val scened = results.filter { it.case.expectedScene != null }
        val nones = results.filter { it.case.expectedScene == null }
        val report = StringBuilder()

        report.appendLine()
        report.appendLine("=== 盲测集评估 · $label ===")
        report.appendLine("来源：$path   共 ${cases.size} 条（场景 ${scened.size} + none ${nones.size}）")
        report.appendLine()

        report.appendLine("逐场景命中率（括号内为已调参语料上的成绩，供对照）")
        report.appendLine("-".repeat(66))
        SceneType.entries.forEach { scene ->
            val group = scened.filter { it.case.expectedScene == scene }
            if (group.isEmpty()) return@forEach
            val hits = group.count { it.correctScene }
            val tuned = TUNED_CORPUS_REFERENCE[scene]
            report.appendLine(
                "  %-18s %3d/%-3d %6.1f%%   %s".format(
                    scene.id, hits, group.size, hits * 100.0 / group.size,
                    tuned?.let { "(已调参语料 %.1f%%)".format(it) } ?: "",
                ),
            )
        }
        if (scened.isNotEmpty()) {
            val hits = scened.count { it.correctScene }
            report.appendLine("-".repeat(66))
            report.appendLine(
                "  %-18s %3d/%-3d %6.1f%%".format("合计", hits, scened.size, hits * 100.0 / scened.size),
            )
        }

        if (nones.isNotEmpty()) {
            val falsePositives = nones.filter { it.actualScene != null }
            report.appendLine()
            report.appendLine(
                "none 类误报：%d/%d = %.1f%%（应当不选任何场景）".format(
                    falsePositives.size, nones.size, falsePositives.size * 100.0 / nones.size,
                ),
            )
            falsePositives.forEach { r ->
                report.appendLine("    误判为 %-18s %s".format(r.actualScene, r.case.text))
            }
        }

        val intentAnnotated = scened.filter { it.case.expectedIntent != null }
        if (intentAnnotated.isNotEmpty()) {
            val hits = intentAnnotated.count { it.actualIntent == it.case.expectedIntent }
            report.appendLine()
            report.appendLine(
                "意图准确率：%d/%d = %.1f%%（仅统计标注了期望意图的 %d 条）".format(
                    hits, intentAnnotated.size, hits * 100.0 / intentAnnotated.size, intentAnnotated.size,
                ),
            )
        }

        val nulls = scened.count { it.actualScene == null }
        report.appendLine()
        report.appendLine(
            "判成 null：%d 条（占判错 %d 条的 %.0f%%）—— 无场景意味着无法追问，比判到相邻场景更糟".format(
                nulls, scened.size - scened.count { it.correctScene },
                if (scened.count { !it.correctScene } == 0) 0.0
                else nulls * 100.0 / scened.count { !it.correctScene },
            ),
        )

        val substantive = scened.count { it.replyIsSubstantive }
        report.appendLine(
            "实质回复率：%d/%d = %.1f%%（不是「没听清，请再说一遍」这类兜底）".format(
                substantive, scened.size, substantive * 100.0 / scened.size,
            ),
        )
        report.appendLine()
        report.appendLine("回复样例（对话是否成立，看这里而不是看命中率）：")
        scened.take(4).forEach { r ->
            report.appendLine("  「${r.case.text}」")
            report.appendLine("    → ${r.reply.ifBlank { "（空）" }}")
            report.appendLine("      模板=${r.replyTemplateId ?: "null"} 场景=${r.actualScene ?: "null"}")
        }

        val misses = scened.filterNot { it.correctScene }
        report.appendLine()
        if (misses.isEmpty()) {
            report.appendLine("场景全部命中。")
        } else if (label == "测试集") {
            report.appendLine("测试集判错明细已隐藏——逐条查看它就等于用它调参。只看上面的总分。")
        } else {
            report.appendLine("判错明细（${misses.size} 条）：")
            misses.forEach { r ->
                report.appendLine(
                    "  期望 %-18s 实际 %-18s conf=%.2f".format(
                        r.case.expectedScene?.id, r.actualScene ?: "null", r.confidence,
                    ),
                )
                report.appendLine("      ${r.case.text}")
                r.case.expectedIntent?.let { expected ->
                    report.appendLine("      意图 期望 $expected 实际 ${r.actualIntent ?: "null"}")
                }
            }
        }

        report.appendLine()
        println(report)

        assertWithMessage("$label 已解析并执行").that(results).hasSize(cases.size)
    }

    private fun parse(file: File): List<BlindCase> =
        file.readLines(Charsets.UTF_8).mapIndexedNotNull { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
            // Tabs win when present: an utterance may legitimately contain a comma, and splitting
            // on both would slice the text into fragments and silently score the tail.
            val columns = (if (line.contains('\t')) line.split('\t') else line.split(','))
                .map(String::trim)
                .filter(String::isNotEmpty)
            require(columns.size >= 2) { "第 ${index + 1} 行需要至少两列（场景、文本）：$raw" }

            val sceneToken = columns.first()
            val expectedScene = if (sceneToken.equals("none", ignoreCase = true)) {
                null
            } else {
                SceneType.entries.firstOrNull { it.id == sceneToken }
                    ?: throw IllegalArgumentException(
                        "第 ${index + 1} 行的场景 '$sceneToken' 无法识别。可用：" +
                            SceneType.entries.filterNot { it == SceneType.UNCLASSIFIED }
                                .joinToString(", ") { it.id } + ", none",
                    )
            }
            BlindCase(
                expectedScene = expectedScene,
                expectedIntent = columns.getOrNull(2)?.takeIf { columns.size >= 3 },
                text = columns.last(),
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

    private data class BlindCase(
        val expectedScene: SceneType?,
        val expectedIntent: String?,
        val text: String,
    )

    private data class BlindResult(
        val case: BlindCase,
        val actualScene: String?,
        val actualIntent: String?,
        val confidence: Float,
        val reply: String = "",
        val replyTemplateId: String? = null,
        val isFallbackReply: Boolean = false,
    ) {
        val correctScene: Boolean get() = actualScene == case.expectedScene?.id

        /** A reply that moves the call forward: it answers or asks something specific. */
        val replyIsSubstantive: Boolean get() = reply.isNotBlank() && !isFallbackReply
    }

    private companion object {
        /**
         * Train may be inspected case by case and tuned against. Test reports only its totals --
         * reading its misses is how a test set quietly becomes a second training set. Holdout is
         * the insurance corpus, left untouched entirely so one scene stays genuinely unseen.
         */
        val SPLITS = listOf(
            "训练集" to "test/blind/blind_train.tsv",
            "测试集" to "test/blind/blind_test.tsv",
            "holdout" to "test/blind/blind_holdout.tsv",
            // Written after the anchor and accumulation work landed, so nothing in the rules could
            // have been shaped by it. Misses are printed: it has been spent once already by being
            // read, and the value left in it is showing which categories still fail.
            "盲测v2" to "test/blind/blind_set_v2.tsv",
            // Written after all the template work, by hand, without reference to any failure this
            // repository had recorded. Nothing here has been seen by a rule, which makes its first
            // number the cleanest estimate of generalisation available -- and spends it.
            "盲测v3" to "test/blind/blind_set_v3.tsv",
            // v2 and v3 were both spent finding out where the vocabulary was starved and which
            // phrases collide, so the numbers they give now are worth less than the ones they
            // opened with. This one was written after all three of those rounds had landed and
            // been measured, and read once. 210 cases, 30 per scene and 30 that are not calls at
            // all -- the widest and the only clean measurement in the repository.
            "盲测v4" to "test/blind/blind_set_v4.tsv",
        )

        /**
         * Scene accuracy on the recorded device runs, measured on recognised text. Shown beside the
         * blind numbers purely as contrast: this material was available during tuning, so the gap
         * between the two columns is the quantity of interest, not either column alone.
         */
        val TUNED_CORPUS_REFERENCE = mapOf(
            SceneType.DELIVERY to 87.5,
            SceneType.RIDE_HAILING to 94.4,
            SceneType.CUSTOMER_SERVICE to 90.0,
            SceneType.REAL_ESTATE to 97.1,
            SceneType.INSURANCE_FINANCE to 100.0,
            SceneType.SPAM_RISK to 94.4,
        )
    }
}
