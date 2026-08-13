package com.example.calldelegate.telecom.recording

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.example.calldelegate.core.audio.AdaptivePcmGain
import com.example.calldelegate.domain.api.CallResponseAudioSink
import com.example.calldelegate.domain.api.CallResponseResult
import com.example.calldelegate.domain.model.SynthesizedSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Makes the assistant audible to the far end by playing it out loud, so the handset's own
 * microphone carries it up the call.
 *
 * This is not the path anyone would choose first. Android has a real one -- USAGE_CALL_ASSISTANT
 * with CALL_REDIRECT_PSTN, writing straight into the uplink -- and
 * [ShizukuCallUplinkAudioSink] implements it correctly: the permission is held, the flag arrives
 * set (0x10800 carries AUDIO_FLAG_CALL_REDIRECTION), and the telephony_tx port exists on this
 * device. It still does not work here. The audio policy engine reports no registered call
 * assistant at all, so the track is treated as ordinary playback and routed to the earpiece, on
 * every turn of every call recorded on the validation device. Becoming a registered call assistant needs a
 * platform signature or a preinstall; there is no shell entry point, and the call-screening role
 * that might carry it refuses to be granted. docs/LIMITATIONS.md records this boundary for the
 * of this was tried.
 *
 * What is given up by going acoustic, stated plainly:
 *
 * - The room is on the call. Whatever else is audible near the phone goes up the line too.
 * - The uplink is whatever the microphone made of the speaker, not the PCM that was synthesized.
 * - Voice-call echo cancellation runs on the uplink with the speaker as its reference, and removing
 *   the speaker from the microphone signal is precisely its job. It is imperfect enough at speaker
 *   volume that speech gets through, which is why this works at all, but it is a fight with the
 *   platform rather than a contract with it, and it will vary by device.
 *
 * Capture is already paused while the assistant speaks (CaptureGate, "TTS 期间停采"), so the
 * recognizer does not hear this playback and no echo reaches the transcript.
 */
class SpeakerphoneCallResponseSink(
    private val context: Context,
) : CallResponseAudioSink {

    /** The call whose speaker route is currently held, so it is taken once and not per reply. */
    private var routedCallId: String? = null
    private var callVolumeBeforeRouting: Int? = null

    override suspend fun playToCall(
        callId: String,
        speech: SynthesizedSpeech,
    ): CallResponseResult = withContext(Dispatchers.IO) {
        if (speech.pcm16.isEmpty()) {
            return@withContext CallResponseResult.Failed(
                code = "CALL_SPEAKER_EMPTY",
                message = "TTS 没有生成可播放的 PCM",
            )
        }
        val audioManager = context.getSystemService(AudioManager::class.java)
            ?: return@withContext CallResponseResult.Failed(
                code = "CALL_SPEAKER_NO_AUDIO_MANAGER",
                message = "系统未提供 AudioManager",
            )

        acquireSpeakerRoute(audioManager, callId)?.let { return@withContext it }

        var track: AudioTrack? = null
        try {
            track = buildTrack(speech)
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                return@withContext CallResponseResult.Failed(
                    code = "CALL_SPEAKER_TRACK",
                    message = "扬声器播放轨道初始化失败",
                )
            }
            val gain = AdaptivePcmGain.calculate(speech.pcm16)
            val samples = ShortArray(speech.pcm16.size) { index ->
                AdaptivePcmGain.apply(speech.pcm16[index], gain)
            }
            runCatching { track.setVolume(AudioTrack.getMaxVolume()) }
            track.play()
            val written = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            if (written < samples.size) {
                return@withContext CallResponseResult.Failed(
                    code = "CALL_SPEAKER_WRITE",
                    message = "扬声器写入不完整：$written/${samples.size}",
                )
            }
            awaitPlaybackTail(track, samples.size, speech.sampleRateHz)
            val routedType = track.routedDevice?.type
            Log.i(
                TAG,
                "Call-speaker TTS played: frames=${samples.size} gain=${gain}x " +
                    "callVolume=${audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}" +
                    "/${audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)} " +
                    "routedType=$routedType",
            )
            // Reporting the requested route rather than the achieved one is how earlier attempts
            // looked successful while playing to the earpiece the whole time.
            if (routedType != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                return@withContext CallResponseResult.Failed(
                    code = "CALL_SPEAKER_NOT_ROUTED",
                    message = "通话音频没有切到扬声器，实际输出设备类型=$routedType，远端可能听不到",
                )
            }
            // Acoustically, this did reach the call, and the person holding the phone hears it too,
            // so the session must not also play it through the local monitor.
            CallResponseResult.PlayedToCallUplink
        } catch (throwable: Throwable) {
            CallResponseResult.Failed(
                code = "CALL_SPEAKER_PLAY",
                message = throwable.message ?: "扬声器播放失败",
            )
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
        }
    }

    /**
     * Takes the speaker for the whole call, once, and waits until the switch has actually happened.
     *
     * Doing this around each reply instead is what made the assistant inaudible: the route was
     * restored to the earpiece after every utterance, so the next track was built while the switch
     * back to the speaker was still in flight and bound to the earpiece anyway. The device log shows
     * both moves, 1.6 seconds apart, both from this process.
     */
    private suspend fun acquireSpeakerRoute(
        audioManager: AudioManager,
        callId: String,
    ): CallResponseResult? {
        if (routedCallId == callId &&
            audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        ) {
            return null
        }
        val speaker = audioManager.availableCommunicationDevices
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: return CallResponseResult.Unsupported("设备没有可用于通话的扬声器")
        if (!audioManager.setCommunicationDevice(speaker)) {
            return CallResponseResult.Failed(
                code = "CALL_SPEAKER_ROUTE",
                message = "无法把通话音频切到扬声器",
            )
        }
        // The call stream carries the reply out of the speaker; the samples are already near full
        // scale, so this is the only loudness left. Restored in releaseCall.
        if (callVolumeBeforeRouting == null) {
            callVolumeBeforeRouting = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        }
        audioManager.setStreamVolume(
            AudioManager.STREAM_VOICE_CALL,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
            0,
        )
        val settled = awaitRoute(audioManager, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        routedCallId = callId
        Log.i(TAG, "Call-speaker route acquired: settled=$settled callId=$callId")
        return if (settled) {
            null
        } else {
            CallResponseResult.Failed(
                code = "CALL_SPEAKER_ROUTE_TIMEOUT",
                message = "切换到扬声器超时，未在 ${ROUTE_TIMEOUT_MILLIS}ms 内生效",
            )
        }
    }

    override suspend fun releaseCall(callId: String) {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        callVolumeBeforeRouting?.let { previous ->
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, previous, 0) }
        }
        callVolumeBeforeRouting = null
        routedCallId = null
        runCatching { audioManager.clearCommunicationDevice() }
        Log.i(TAG, "Call-speaker route released: callId=$callId")
    }

    /** A route change is asynchronous; binding a track before it lands is the whole bug. */
    private suspend fun awaitRoute(audioManager: AudioManager, type: Int): Boolean {
        val deadline = System.currentTimeMillis() + ROUTE_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (audioManager.communicationDevice?.type == type) return true
            delay(ROUTE_POLL_MILLIS)
        }
        return audioManager.communicationDevice?.type == type
    }

    private fun buildTrack(speech: SynthesizedSpeech): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            speech.sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(MINIMUM_BUFFER_BYTES)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Voice communication, not accessibility: this has to follow the call's own
                    // route, which is what the accessibility usage failed to do -- it was placed on
                    // the earpiece alongside the injection track.
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(speech.sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * BUFFER_MULTIPLIER)
            .build()
    }

    /**
     * Returning before the speaker has finished would reopen capture while the assistant is still
     * being heard, which is the one thing the capture gate exists to prevent.
     */
    private suspend fun awaitPlaybackTail(track: AudioTrack, totalFrames: Int, sampleRateHz: Int) {
        val deadline = System.nanoTime() +
            (totalFrames.toLong() * NANOS_PER_SECOND / sampleRateHz) + TAIL_GRACE_NANOS
        while (System.nanoTime() < deadline) {
            val played = track.playbackHeadPosition.toLong() and UINT32_MASK
            if (played >= totalFrames) return
            delay(TAIL_POLL_MILLIS)
        }
    }

    private companion object {
        const val TAG = "CallSpeakerResponseSink"
        const val ROUTE_TIMEOUT_MILLIS = 1500L
        const val ROUTE_POLL_MILLIS = 25L
        const val MINIMUM_BUFFER_BYTES = 4096
        const val BUFFER_MULTIPLIER = 4
        const val TAIL_POLL_MILLIS = 20L
        const val TAIL_GRACE_NANOS = 300_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val UINT32_MASK = 0xFFFFFFFFL
    }
}
