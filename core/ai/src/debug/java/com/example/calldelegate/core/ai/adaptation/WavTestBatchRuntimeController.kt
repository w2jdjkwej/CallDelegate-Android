package com.example.calldelegate.core.ai.adaptation

import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.SpeechRuntimeManager

/** Debug-only access to the existing runtime's bounded WAV batch residency mode. */
class WavTestBatchRuntimeController(
    private val runtime: SpeechRuntimeManager,
) {
    suspend fun begin(disableMaxTurnDuration: Boolean = false): AppResult<Unit> {
        val adaptive = runtime as? AdaptiveSpeechRuntime
            ?: return AppResult.Failure(
                AppError("WAV_BATCH_RUNTIME_UNSUPPORTED", "当前语音运行时不支持 WAV 批处理资源管理"),
            )
        return adaptive.beginWavTestBatch(disableMaxTurnDuration)
    }

    suspend fun end() {
        (runtime as? AdaptiveSpeechRuntime)?.endWavTestBatch()
    }
}
