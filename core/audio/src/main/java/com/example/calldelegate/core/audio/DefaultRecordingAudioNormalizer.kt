package com.example.calldelegate.core.audio

import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.RecordingAudioNormalizer
import com.example.calldelegate.domain.model.NormalizedRecordingAudio
import com.example.calldelegate.domain.model.SESSION_RECORDING_SAMPLE_RATE_HZ

class DefaultRecordingAudioNormalizer : RecordingAudioNormalizer {
    override fun normalize(
        samples: ShortArray,
        sourceSampleRateHz: Int,
    ): AppResult<NormalizedRecordingAudio> {
        if (sourceSampleRateHz !in SUPPORTED_SOURCE_RATES_HZ) {
            return AppResult.Failure(
                AppError(
                    code = "AUDIO_RESAMPLE_UNSUPPORTED_RATE",
                    userMessage = "不支持的录音源采样率：${sourceSampleRateHz}Hz",
                ),
            )
        }
        if (sourceSampleRateHz == SESSION_RECORDING_SAMPLE_RATE_HZ || samples.isEmpty()) {
            return AppResult.Success(
                NormalizedRecordingAudio(samples, SESSION_RECORDING_SAMPLE_RATE_HZ),
            )
        }
        return runCatching {
            val processor = StreamingPcmResampler(
                sourceRateHz = sourceSampleRateHz,
                targetRateHz = SESSION_RECORDING_SAMPLE_RATE_HZ,
            )
            NormalizedRecordingAudio(
                samples = processor.process(samples, endOfInput = true),
                sampleRateHz = SESSION_RECORDING_SAMPLE_RATE_HZ,
            )
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = {
                AppResult.Failure(
                    AppError(
                        code = "AUDIO_RESAMPLE",
                        userMessage = "会话录音重采样失败",
                        detail = it.message,
                    ),
                )
            },
        )
    }

    private companion object {
        val SUPPORTED_SOURCE_RATES_HZ = setOf(8_000, 16_000, 22_050, 24_000, 44_100, 48_000)
    }
}
