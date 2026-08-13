package com.example.calldelegate.core.ai.speech

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads the JSON a Vosk recognizer emits.
 *
 * This is kept apart from the JNI binding so the shape of every result Vosk can produce is
 * testable without a model, a native library or a device -- the shapes are what change when the
 * recognizer is asked for more detail, and getting one of them wrong silently yields empty text.
 *
 * Vosk emits four shapes, decided by setMaxAlternatives and setWords:
 *
 * ```
 * {"text": "..."}
 * {"text": "...", "result": [{"word": "...", "conf": 1.0, "start": 0.1, "end": 0.4}]}
 * {"alternatives": [{"text": "...", "confidence": 305.4}]}
 * {"alternatives": [{"text": "...", "confidence": 305.4, "result": [...]}]}
 * ```
 *
 * Note that asking for alternatives moves the text out of the top level. A parser that only reads
 * "text" does not fail on those two shapes, it returns an empty transcript.
 */
internal fun parseVoskRecognition(resultJson: String): VoskRecognition = runCatching {
    val root = Json.parseToJsonElement(resultJson).jsonObject
    val alternatives = root["alternatives"]?.jsonArray
        ?: return@runCatching VoskRecognition(
            text = root.text(),
            words = root.words(),
        )
    val hypotheses = alternatives.map { element ->
        VoskHypothesis(
            text = element.jsonObject.text(),
            score = element.jsonObject["confidence"]?.jsonPrimitive?.floatOrNull,
        )
    }
    VoskRecognition(
        // Vosk ranks alternatives best-first, so the primary transcript is the first entry. Keeping
        // the whole list including that entry means a consumer never has to splice them back
        // together to see the full N-best.
        text = hypotheses.firstOrNull()?.text.orEmpty(),
        alternatives = hypotheses,
        words = alternatives.firstOrNull()?.jsonObject?.words().orEmpty(),
    )
}.getOrDefault(VoskRecognition(""))

internal fun parseVoskPartialText(resultJson: String): String = runCatching {
    Json.parseToJsonElement(resultJson).jsonObject["partial"]?.jsonPrimitive?.content.orEmpty().trim()
}.getOrDefault("")

private fun JsonObject.text(): String = this["text"]?.jsonPrimitive?.content.orEmpty().trim()

/**
 * A word with no confidence is dropped rather than defaulted, because the mean of a confidence list
 * padded with invented values reads as a measurement while being partly fiction.
 */
private fun JsonObject.words(): List<VoskWord> =
    this["result"]?.jsonArray?.mapNotNull { element ->
        val word = element.jsonObject
        VoskWord(
            word = word["word"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null,
            confidence = word["conf"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null,
            startSeconds = word["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            endSeconds = word["end"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        )
    }.orEmpty()
