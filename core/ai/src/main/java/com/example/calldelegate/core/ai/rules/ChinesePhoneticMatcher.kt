package com.example.calldelegate.core.ai.rules

/**
 * Finds a unique, conservative phonetic match without changing the ASR text.
 *
 * Unknown characters make a candidate ineligible, so an incomplete table cannot create a broad
 * fuzzy match.
 */
internal object ChinesePhoneticMatcher {
    data class Match(
        val keyword: String,
        val sourceWindow: String,
        val level: String,
    )

    private data class Syllable(
        val pinyin: String,
        val initial: String,
    )

    private val syllables = mapOf(
        '维' to Syllable("wei", "w"),
        '修' to Syllable("xiu", "x"),
        '工' to Syllable("gong", "g"),
        '单' to Syllable("dan", "d"),
        '公' to Syllable("gong", "g"),
        '担' to Syllable("dan", "d"),
        '换' to Syllable("huan", "h"),
        '货' to Syllable("huo", "h"),
        '话' to Syllable("hua", "h"),
        '和' to Syllable("he", "h"),
        '商' to Syllable("shang", "sh"),
        '品' to Syllable("pin", "p"),
        '申' to Syllable("shen", "sh"),
        '请' to Syllable("qing", "q"),
        '戏' to Syllable("xi", "x"),
        '生' to Syllable("sheng", "sh"),
        '情' to Syllable("qing", "q"),
        '更' to Syllable("geng", "g"),
        '配' to Syllable("pei", "p"),
        '件' to Syllable("jian", "j"),
        '冰' to Syllable("bing", "b"),
        '箱' to Syllable("xiang", "x"),
        '退' to Syllable("tui", "t"),
        '回' to Syllable("hui", "h"),
        '寄' to Syllable("ji", "j"),
        '的' to Syllable("de", "d"),
        '引' to Syllable("yin", "y"),
        '起' to Syllable("qi", "q"),
        '时' to Syllable("shi", "sh"),
        '质' to Syllable("zhi", "zh"),
        '问' to Syllable("wen", "w"),
        '题' to Syllable("ti", "t"),
        '照' to Syllable("zhao", "zh"),
        '片' to Syllable("pian", "p"),
        '视' to Syllable("shi", "sh"),
        '频' to Syllable("pin", "p"),
        '效' to Syllable("xiao", "x"),
        '肖' to Syllable("xiao", "x"),
        '到' to Syllable("dao", "d"),
        '其' to Syllable("qi", "q"),
        '期' to Syllable("qi", "q"),
        '主' to Syllable("zhu", "zh"),
        '线' to Syllable("xian", "x"),
        '险' to Syllable("xian", "x"),
        '附' to Syllable("fu", "f"),
        '加' to Syllable("jia", "j"),
        '医' to Syllable("yi", "y"),
        '疗' to Syllable("liao", "l"),
        '洗' to Syllable("xi", "x"),
    )

    fun findUniqueMatch(text: String, keyword: String): Match? {
        if (keyword.length !in 2..8 || text.length < keyword.length || text.contains(keyword)) return null
        val keywordSignature = signature(keyword) ?: return null
        val candidates = text.indices
            .flatMap { index ->
                listOf(keyword.length - 1, keyword.length, keyword.length + 1)
                    .filter { length -> length >= 2 && index + length <= text.length }
                    .mapNotNull { length ->
                        val window = text.substring(index, index + length)
                        val windowSignature = signature(window) ?: return@mapNotNull null
                        val exactPinyin = windowSignature.pinyin == keywordSignature.pinyin
                        if (!hasStrongAnchor(keyword, window)) return@mapNotNull null
                        if (!exactPinyin &&
                            (keyword.length !in 2..6 || windowSignature.initials != keywordSignature.initials)
                        ) {
                            return@mapNotNull null
                        }
                        Candidate(
                            match = Match(
                                keyword = keyword,
                                sourceWindow = window,
                                level = if (exactPinyin) "phonetic_exact" else "initial_sequence",
                            ),
                            initialDistance = if (exactPinyin) 0 else 1,
                            lengthDelta = kotlin.math.abs(length - keyword.length),
                        )
                    }
            }
            .distinctBy { it.match.sourceWindow }
        val best = candidates.minWithOrNull(
            compareBy<Candidate> { it.initialDistance }.thenBy { it.lengthDelta },
        ) ?: return null
        val bestCount = candidates.count {
            it.initialDistance == best.initialDistance && it.lengthDelta == best.lengthDelta
        }
        return best.match.takeIf { bestCount == 1 }
    }


    private fun signature(text: String): Signature? {
        val values = text.map { syllables[it] ?: return null }
        return Signature(
            pinyin = values.joinToString(separator = "") { it.pinyin },
            initials = values.joinToString(separator = "") { it.initial },
        )
    }

    private fun hasStrongAnchor(keyword: String, window: String): Boolean {
        if (keyword.length < 2 || window.length < 2) return false
        for (start in keyword.indices) {
            for (length in 2..(keyword.length - start)) {
                if (window.contains(keyword.substring(start, start + length))) return true
            }
        }
        return false
    }

    private data class Signature(
        val pinyin: String,
        val initials: String,
    )

    private data class Candidate(
        val match: Match,
        val initialDistance: Int,
        val lengthDelta: Int,
    )
}
