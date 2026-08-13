package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.SpeechRecognizer
import com.example.calldelegate.domain.api.SpeechRuntimeInitialization
import com.example.calldelegate.domain.api.SpeechRuntimeManager
import com.example.calldelegate.domain.api.SpeechRecognitionContext
import com.example.calldelegate.domain.api.SpeechSynthesizer
import com.example.calldelegate.domain.api.StreamingSpeechRecognitionSession
import com.example.calldelegate.domain.api.StreamingSpeechRecognizer
import com.example.calldelegate.domain.api.StreamingSpeechRuntimeManager
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.DeviceTier
import com.example.calldelegate.domain.model.InferencePolicy
import com.example.calldelegate.domain.model.ModuleStatus

/** Compatibility path for tests and callers that have not opted into device adaptation. */
class DirectSpeechRuntimeManager(
    private val recognizer: SpeechRecognizer,
    private val synthesizer: SpeechSynthesizer,
) : SpeechRuntimeManager, StreamingSpeechRuntimeManager {
    override var isMock: Boolean = true
        private set
    override val supportsStreamingRecognition: Boolean
        get() = !isMock && (recognizer as? StreamingSpeechRecognizer)?.supportsStreamingRecognition == true

    override suspend fun configure(mockMode: Boolean): SpeechRuntimeInitialization {
        val configurableAsr = recognizer as? ConfigurableSpeechModule
        val asr = configurableAsr?.configure(mockMode) ?: recognizer.initialize()
        val configurableTts = synthesizer as? ConfigurableSpeechModule
        val tts = configurableTts?.configure(mockMode) ?: synthesizer.initialize()
        isMock = mockMode
        return SpeechRuntimeInitialization(
            asrStatus = asr.toModuleStatus(mockMode, "vosk-0.3.75"),
            ttsStatus = tts.toModuleStatus(mockMode, "sherpa-onnx-1.13.2"),
        )
    }

    override suspend fun onIncoming() = Unit
    override suspend fun recognize(audio: CapturedAudio) = recognizer.recognize(audio)
    override suspend fun recognize(audio: CapturedAudio, context: SpeechRecognitionContext) =
        recognizer.recognize(audio, context)

    override suspend fun openStreamingRecognition(
        sampleRateHz: Int,
        context: SpeechRecognitionContext,
    ): AppResult<StreamingSpeechRecognitionSession> {
        val streaming = recognizer as? StreamingSpeechRecognizer
        if (isMock || streaming?.supportsStreamingRecognition != true) {
            return AppResult.Failure(
                AppError("ASR_STREAMING_UNSUPPORTED", "当前运行模式不支持流式识别"),
            )
        }
        return streaming.openStreamingRecognition(sampleRateHz, context)
    }
    override suspend fun synthesize(text: String, sessionId: String) = synthesizer.synthesize(text, sessionId)
    override suspend fun onSessionEnded() = Unit

    override suspend fun releaseAll() {
        recognizer.release()
        synthesizer.release()
    }

    override fun currentPolicy(): InferencePolicy = InferencePolicy.forTier(DeviceTier.LOW)
}

private fun AppResult<Unit>.toModuleStatus(mockMode: Boolean, version: String): ModuleStatus = when (this) {
    is AppResult.Failure -> ModuleStatus.Error(error.userMessage)
    is AppResult.Success -> if (mockMode) ModuleStatus.MockReady else ModuleStatus.RealReady(version)
}
