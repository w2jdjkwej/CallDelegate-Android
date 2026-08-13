package com.example.calldelegate.telecom.recording

import android.util.Log
import com.example.calldelegate.core.audio.AdaptivePcmGain
import com.example.calldelegate.domain.api.CallResponseAudioSink
import com.example.calldelegate.domain.api.CallResponseResult
import com.example.calldelegate.domain.model.SynthesizedSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends TTS PCM to Android's privileged call-uplink injection AudioTrack through Shizuku.
 *
 * No local-speaker fallback is performed here; the session controller owns its local monitor. A
 * successful result only means Android accepted and consumed the call-redirection PCM. Some OEM
 * carrier stacks still fail to make that track audible remotely, so real-call verification remains
 * the acceptance boundary.
 */
class ShizukuCallUplinkAudioSink(
    private val connector: ShizukuCaptureConnector,
) : CallResponseAudioSink {
    override suspend fun playToCall(
        callId: String,
        speech: SynthesizedSpeech,
    ): CallResponseResult = withContext(Dispatchers.IO) {
        if (speech.pcm16.isEmpty()) {
            return@withContext CallResponseResult.Failed(
                code = "CALL_UPLINK_EMPTY",
                message = "TTS 没有生成可注入的 PCM",
            )
        }
        if (speech.sampleRateHz !in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ) {
            return@withContext CallResponseResult.Unsupported(
                "系统通话上行不支持 ${speech.sampleRateHz}Hz",
            )
        }

        val remote = runCatching { connector.connect() }.getOrElse { error ->
            return@withContext CallResponseResult.Failed(
                code = "CALL_UPLINK_CONNECT",
                message = error.message ?: "无法连接 Shizuku 音频服务",
            )
        }
        val startStatus = runCatching {
            remote.startUplinkInjection(speech.sampleRateHz)
        }.getOrElse { error ->
            return@withContext CallResponseResult.Failed(
                code = "CALL_UPLINK_START",
                message = error.message ?: "通话上行注入启动失败",
            )
        }
        if (startStatus.isNotEmpty()) {
            return@withContext if (startStatus.isUnsupportedCapability()) {
                CallResponseResult.Unsupported("当前系统不允许 PSTN 通话上行注入：$startStatus")
            } else {
                CallResponseResult.Failed("CALL_UPLINK_START", startStatus)
            }
        }

        val uplinkGain = AdaptivePcmGain.calculate(speech.pcm16)
        Log.i(TAG, "Call-uplink TTS gain=${uplinkGain}x")

        var failure: CallResponseResult.Failed? = null
        try {
            var sampleOffset = 0
            while (sampleOffset < speech.pcm16.size) {
                val sampleCount = minOf(CHUNK_SAMPLES, speech.pcm16.size - sampleOffset)
                val chunk = speech.pcm16.toLittleEndianBytes(
                    offset = sampleOffset,
                    count = sampleCount,
                    gain = uplinkGain,
                )
                val written = remote.writeUplinkInjection(chunk)
                if (written != chunk.size) {
                    failure = CallResponseResult.Failed(
                        code = "CALL_UPLINK_WRITE",
                        message = "通话上行写入不完整：$written/${chunk.size}",
                    )
                    break
                }
                sampleOffset += sampleCount
            }
        } catch (throwable: Throwable) {
            failure = CallResponseResult.Failed(
                code = "CALL_UPLINK_WRITE",
                message = throwable.message ?: "通话上行写入失败",
            )
        }

        val stopStatus = runCatching { remote.stopUplinkInjection() }
            .getOrElse { error -> "${error.javaClass.simpleName}:${error.message.orEmpty()}" }
        failure ?: if (stopStatus.isEmpty()) {
            CallResponseResult.PlayedToCallUplink
        } else {
            CallResponseResult.Failed("CALL_UPLINK_STOP", stopStatus)
        }
    }

    private fun String.isUnsupportedCapability(): Boolean =
        contains("UnsupportedOperationException") ||
            contains("NoSuchMethodException") ||
            contains("SecurityException") ||
            contains("not accessible", ignoreCase = true) ||
            contains("not available", ignoreCase = true)

    private fun ShortArray.toLittleEndianBytes(
        offset: Int,
        count: Int,
        gain: Float,
    ): ByteArray {
        val output = ByteArray(count * 2)
        var index = 0
        while (index < count) {
            val value = AdaptivePcmGain.apply(this[offset + index], gain).toInt()
            output[index * 2] = (value and 0xff).toByte()
            output[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
            index += 1
        }
        return output
    }

    private companion object {
        const val TAG = "CallUplinkAudioSink"
        const val MIN_SAMPLE_RATE_HZ = 8_000
        const val MAX_SAMPLE_RATE_HZ = 48_000
        const val CHUNK_SAMPLES = 8_192
    }
}
