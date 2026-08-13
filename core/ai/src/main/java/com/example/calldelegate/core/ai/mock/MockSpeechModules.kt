package com.example.calldelegate.core.ai.mock

import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.SpeechRecognizer
import com.example.calldelegate.domain.api.SpeechSynthesizer
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.RecognitionResult
import com.example.calldelegate.domain.model.SynthesizedSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

class MockSpeechRecognizer : SpeechRecognizer {
    @Volatile private var initialized = false

    override suspend fun initialize(): AppResult<Unit> {
        delay(25)
        initialized = true
        return AppResult.Success(Unit)
    }

    override suspend fun recognize(audio: CapturedAudio): AppResult<RecognitionResult> = withContext(Dispatchers.Default) {
        if (!initialized) initialize()
        val transcriptHint = audio.transcriptHint
        when {
            !audio.speechDetected -> AppResult.Failure(AppError("ASR_SILENCE", "没有检测到语音"))
            transcriptHint.isNullOrBlank() -> AppResult.Success(
                RecognitionResult("您好，我是快递员，快递放在驿站可以吗？", 0.72f, true),
            )
            transcriptHint == "__UNRECOGNIZABLE__" -> AppResult.Failure(
                AppError("ASR_UNRECOGNIZABLE", "未能识别这段语音"),
            )
            else -> AppResult.Success(RecognitionResult(transcriptHint, 0.99f, true))
        }
    }

    override suspend fun release() { initialized = false }
}

/** Mock TTS emits a short deterministic tone and keeps the real reply visible in the transcript. */
class MockSpeechSynthesizer : SpeechSynthesizer {
    private val sampleRate = 16_000
    @Volatile private var initialized = false

    override suspend fun initialize(): AppResult<Unit> {
        initialized = true
        return AppResult.Success(Unit)
    }

    override suspend fun synthesize(text: String, sessionId: String): AppResult<SynthesizedSpeech> =
        withContext(Dispatchers.Default) {
            if (!initialized) initialize()
            val duration = (280L + text.length * 7L).coerceIn(280L, 900L)
            val count = (sampleRate * duration / 1_000L).toInt()
            val frequency = 620.0 + (text.hashCode().ushr(1) % 120)
            val samples = ShortArray(count) { index ->
                val envelope = when {
                    index < 320 -> index / 320.0
                    index > count - 320 -> (count - index).coerceAtLeast(0) / 320.0
                    else -> 1.0
                }
                (sin(2.0 * PI * frequency * index / sampleRate) * 3_500.0 * envelope).toInt().toShort()
            }
            AppResult.Success(
                SynthesizedSpeech(text, null, duration, true, samples, sampleRate),
            )
        }

    override suspend fun release() { initialized = false }
}
