package com.example.calldelegate.domain.api

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.DeviceProfile
import com.example.calldelegate.domain.model.InferenceBenchmarkSample
import com.example.calldelegate.domain.model.InferencePolicy
import com.example.calldelegate.domain.model.ModuleStatus
import com.example.calldelegate.domain.model.RecognitionResult
import com.example.calldelegate.domain.model.SynthesizedSpeech
import kotlinx.coroutines.flow.StateFlow

interface DeviceProfileProvider {
    val profile: StateFlow<DeviceProfile>
    suspend fun refresh()
    suspend fun recordBenchmark(sample: InferenceBenchmarkSample)
    suspend fun invalidateBenchmark(reason: String)

    /** Debug-only test hook; production implementations may safely ignore it. */
    suspend fun overrideNominalRamGb(gb: Int?) = Unit
}

data class SpeechRuntimeInitialization(
    val asrStatus: ModuleStatus,
    val ttsStatus: ModuleStatus,
)

interface SpeechRuntimeManager {
    val isMock: Boolean
    suspend fun configure(mockMode: Boolean): SpeechRuntimeInitialization
    suspend fun onIncoming()
    suspend fun recognize(audio: CapturedAudio): AppResult<RecognitionResult>
    suspend fun recognize(
        audio: CapturedAudio,
        context: SpeechRecognitionContext,
    ): AppResult<RecognitionResult> = recognize(audio)
    suspend fun synthesize(text: String, sessionId: String): AppResult<SynthesizedSpeech>
    suspend fun onSessionEnded()
    suspend fun releaseAll()
    fun currentPolicy(): InferencePolicy
}

/** Optional runtime capability that preserves device residency policy for streaming ASR. */
interface StreamingSpeechRuntimeManager {
    val supportsStreamingRecognition: Boolean

    suspend fun openStreamingRecognition(
        sampleRateHz: Int,
        context: SpeechRecognitionContext,
    ): AppResult<StreamingSpeechRecognitionSession>
}
