package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.domain.api.StreamingRecognitionSnapshot

/**
 * Decides whether an endpoint candidate's partial transcript carries positive evidence that the
 * caller finished a sentence, so the turn may commit on the short grace window instead of the long
 * one.
 *
 * The contract is deliberately asymmetric. Committing too late costs latency; committing too early
 * truncates a real caller mid-sentence and corrupts both the transcript and the NLU result. So this
 * only ever returns true on evidence, and every uncertain case -- empty text, missing snapshot,
 * unknown ending, too few characters -- falls back to false and keeps the long window.
 *
 * It does not attempt general sentence parsing. It answers one narrow question: does this partial
 * end at a place where Mandarin speakers actually stop?
 */
class UtteranceCompleteness(
    private val minimumCharacters: Int = DEFAULT_MINIMUM_CHARACTERS,
) {

    /**
     * Judges a whole snapshot, preferring the recognizer's own endpoint decision to the text rules.
     *
     * The text rules below were written against transcripts that carry punctuation. The Mandarin
     * model actually shipped emits none -- seven turns of a real delivery call came back as 文化外卖
     * 到了, 太子有点破损, 产品漏了一点, with not one full stop among them -- so the punctuation rule can
     * never fire. A recognizer that has closed a segment and begun no new one has decided the same
     * question from the audio, which is evidence the text cannot supply here.
     */
    fun snapshotLooksComplete(snapshot: StreamingRecognitionSnapshot?): Boolean {
        val observed = snapshot ?: return false
        if (observed.recognizerClosedSegment) return true
        return looksComplete(observed.partialTextRaw)
    }

    fun looksComplete(partialTextRaw: String?): Boolean {
        val text = normalize(partialTextRaw ?: return false)

        // Exact state answers outrank generic last-character cues. For example, "好的" and
        // "不需要" are complete answers even though their last characters also occur mid-clause.
        if (text in EXACT_SHORT_REPLIES) return true

        // A trailing continuation cue outranks any positive signal: "麻烦你帮我看一下那个" ends on a
        // determiner and is plainly unfinished even though it is long and otherwise well formed.
        if (CONTINUATION_ENDINGS.any(text::endsWith)) return false
        if (text.endsWith(COMMA) || text.endsWith(ENUMERATION_COMMA)) return false

        // These formulas remain complete when they follow contextual text. Checking the length
        // before the exact answers and formulas made short replies unreachable.
        if (COMPLETION_PHRASES.any(text::endsWith)) return true
        if (text.length < minimumCharacters) return false

        if (TERMINAL_PUNCTUATION.any(text::endsWith)) return true
        return false
    }

    private fun normalize(raw: String): String = raw
        .replace(UNKNOWN_TOKEN, "")
        .filterNot(Char::isWhitespace)

    companion object {
        const val DEFAULT_MINIMUM_CHARACTERS = 6

        private const val UNKNOWN_TOKEN = "[unk]"
        private const val COMMA = "，"
        private const val ENUMERATION_COMMA = "、"

        private val TERMINAL_PUNCTUATION = listOf("。", "？", "！", ".", "?", "!")

        /**
         * Exact answers used by the dialogue states shared across all production scenes. Exact
         * matching matters here: a partial such as "不需要" must not make the longer unfinished
         * phrase "不需要你现在..." look complete.
         */
        private val EXACT_SHORT_REPLIES = setOf(
            "谢谢",
            "麻烦了",
            "好的",
            "没问题",
            "没有",
            "没有了",
            "没了",
            "不用了",
            "没什么了",
            "再见",
            "不需要",
            "不用",
            "不必",
            "无需",
            "不可以",
            "不行",
            "不同意",
        )

        /** Endings that remain decisive when preceded by identity or other context. */
        private val COMPLETION_PHRASES = listOf(
            "谢谢",
            "麻烦了",
            "好的",
            "没问题",
            "再见",
            "没有补充",
            "不需要回电",
            "不用回电",
            "无需回电",
            "不必回电",
            "不用联系",
            "麻烦回电",
            "麻烦回个电话",
            "请回电",
            "请回电话",
            "请回个电话",
            // Narrow delivery endings observed in two consecutive real-device calls. Bare "了"
            // remains excluded because it also occurs in unfinished clauses such as "我买了三个".
            "放在门口了",
            "放在前台了",
        )

        /**
         * Endings that signal more speech is coming. Conjunctions, determiners and measure-word
         * fragments are the common Mandarin mid-sentence pause points, which is precisely where a
         * short grace window would cut a caller off.
         */
        private val CONTINUATION_ENDINGS = listOf(
            "然后", // 然后
            "就是", // 就是
            "那个", // 那个
            "这个", // 这个
            "因为", // 因为
            "所以", // 所以
            "但是", // 但是
            "不过", // 不过
            "而且", // 而且
            "如果", // 如果
            "另外", // 另外
            "还有", // 还有
            "的", // 的
            "和", // 和
            "跟", // 跟
            "在", // 在
            "把", // 把
            "给", // 给
            "要", // 要
            "去", // 去
        )
    }
}
