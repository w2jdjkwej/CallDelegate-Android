package com.example.calldelegate.core.ai.evaluation

import com.example.calldelegate.core.ai.rules.DialogueRuleFile
import com.example.calldelegate.core.ai.rules.JsonDialogueEngine
import com.example.calldelegate.core.ai.rules.RegexEntityExtractor
import com.example.calldelegate.core.ai.rules.RuleBasedIntentClassifier
import com.example.calldelegate.core.ai.rules.RuleProvider
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.DialogueContext
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the multi-turn scripts as conversations, carrying the dialogue context from turn to turn.
 *
 * The device harness cannot do this: it reads turnId and turnIndex but starts every case in a fresh
 * session, so each line is a first turn. What the scripts actually exercise is the dialogue logic,
 * which is deterministic given the text, so it belongs here where it runs in seconds and repeats
 * exactly. Real speech is tested elsewhere, on the recorded corpora.
 *
 * Reports rather than asserts a score, with three exceptions that are defects rather than
 * judgements: hanging up on a caller who is still talking, ending before the script does, and
 * spam_risk failing to end on its first turn.
 */
class MultiTurnScriptEvaluationTest {

    private data class Script(val id: String, val turns: List<String>)

    @Test
    fun scriptsAreAnsweredTurnByTurn() = runTest {
        val scriptFile = File(projectRoot(), SCRIPT_PATH)
        assumeTrue("未找到多轮剧本，跳过。", scriptFile.isFile)
        val scripts = parse(scriptFile)
        assumeTrue("剧本为空。", scripts.isNotEmpty())

        val provider = RuleProvider { AppResult.Success(loadProductionRules()) }
        val classifier = RuleBasedIntentClassifier(provider, RegexEntityExtractor())
        val engine = JsonDialogueEngine(provider, classifier, RegexEntityExtractor())
        val scenes = AppSettings().enabledScenes

        val report = StringBuilder()
        val hungUpOn = mutableListOf<String>()
        val endedEarly = mutableListOf<String>()
        val spamNotEnded = mutableListOf<String>()
        var answered = 0
        var total = 0

        report.appendLine()
        report.appendLine("=== 多轮剧本评测 · ${scripts.size} 个剧本 ===")

        scripts.forEach { script ->
            report.appendLine()
            report.appendLine("--- ${script.id} ---")
            var context = DialogueContext(sessionId = "script-${script.id}")
            var ended = false
            script.turns.forEachIndexed { index, spoken ->
                if (ended) {
                    if (index < script.turns.size) endedEarly += "${script.id}@${index + 1}"
                    return@forEachIndexed
                }
                val decision = engine.process(context, spoken, false, scenes)
                context = decision.context
                ended = decision.shouldEnd
                total++
                val fallback = decision.reply.contains("没有听清") ||
                    decision.reply.contains("没有听明白") ||
                    decision.reply.contains("没有确认清楚")
                if (!fallback) answered++
                if (decision.reply == HANG_UP) hungUpOn += "${script.id}@${index + 1}"
                report.appendLine("  > $spoken")
                report.appendLine("    ${decision.reply}${if (decision.shouldEnd) "  [结束]" else ""}")
            }
            if (script.id.startsWith("S") && !script.id.startsWith("S4") && !ended) {
                spamNotEnded += script.id
            }
        }

        report.appendLine()
        report.appendLine("实质回复率：$answered/$total = %.1f%%".format(answered * 100.0 / total))
        report.appendLine("被挂断的续话轮：${hungUpOn.size}  ${hungUpOn.joinToString()}")
        report.appendLine("提前结束的剧本：${endedEarly.size}  ${endedEarly.joinToString()}")
        report.appendLine("诈骗剧本未结束：${spamNotEnded.size}  ${spamNotEnded.joinToString()}")
        println(report)

        assertWithMessage("续话轮不得被挂断，这是 57012ca 修好的行为").that(hungUpOn).isEmpty()
        assertWithMessage("剧本未说完不得结束通话").that(endedEarly).isEmpty()
        assertWithMessage("诈骗来电必须结束通话").that(spamNotEnded).isEmpty()
    }

    private fun parse(file: File): List<Script> {
        val scripts = mutableListOf<Script>()
        var id: String? = null
        var turns = mutableListOf<String>()
        file.readLines().forEach { line ->
            val header = SCRIPT_HEADER.find(line.trim())
            if (header != null) {
                id?.let { scripts += Script(it, turns) }
                id = header.groupValues[1]
                turns = mutableListOf()
            } else if (line.startsWith("> ") && id != null) {
                turns += line.removePrefix("> ").trim()
            }
        }
        id?.let { scripts += Script(it, turns) }
        return scripts.filter { it.turns.isNotEmpty() }
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
        error("Unable to locate project root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val SCRIPT_PATH = "test/blind/multiturn_scripts_v1.md"
        const val HANG_UP = "当前信息已记录，我会转告机主。再见。"
        val SCRIPT_HEADER = Regex("^### ([A-Z]\\d) ")
    }
}
