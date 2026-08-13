package com.example.calldelegate.core.audio

import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.AudioInputSource
import com.example.calldelegate.domain.api.AudioState
import com.example.calldelegate.domain.api.PresetRepository
import com.example.calldelegate.domain.model.CaptureRequest
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.PresetSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

class PresetAudioInputSource(private val presets: PresetRepository) : AudioInputSource {
    override val mode = InputMode.PRESET_AUDIO
    private val mutableState = MutableStateFlow<AudioState>(AudioState.Idle)
    override val state: StateFlow<AudioState> = mutableState.asStateFlow()
    private val cancelled = AtomicBoolean(false)

    override suspend fun capture(request: CaptureRequest): AppResult<CapturedAudio> = withContext(Dispatchers.Default) {
        val sample = request.presetId?.let(presets::find)
            ?: return@withContext AppResult.Failure(AppError("PRESET_NOT_FOUND", "未找到预设音频样例"))
        cancelled.set(false)
        mutableState.value = AudioState.Recording
        delay(120)
        if (cancelled.get()) {
            mutableState.value = AudioState.Idle
            return@withContext AppResult.Failure(AppError("AUDIO_CANCELLED", "预设输入已停止"))
        }
        val sampleRate = 16_000
        val duration = if (sample.kind == PresetSample.Kind.SILENCE) 2_000L else 1_200L
        val pcm = when (sample.kind) {
            PresetSample.Kind.SILENCE -> ShortArray((sampleRate * duration / 1_000L).toInt())
            else -> ShortArray((sampleRate * duration / 1_000L).toInt()) { index ->
                val carrier = sin(2.0 * PI * (170 + (index / 1_600 % 4) * 40) * index / sampleRate)
                (carrier * 2_500).toInt().toShort()
            }
        }
        mutableState.value = AudioState.Idle
        AppResult.Success(
            CapturedAudio(
                pcm16 = pcm,
                sampleRateHz = sampleRate,
                durationMillis = duration,
                recordingPath = null,
                transcriptHint = sample.transcript,
                speechDetected = sample.kind != PresetSample.Kind.SILENCE,
            ),
        )
    }

    override suspend fun cancel() { cancelled.set(true); mutableState.value = AudioState.Idle }
    override suspend fun release() { cancel() }
}
