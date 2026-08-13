package com.example.calldelegate.core.audio.capture

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.calldelegate.domain.api.CaptureProvenance

/**
 * [PcmReader] backed by [AudioRecord]. Canonical format: 16 kHz, mono, 16-bit PCM little-endian.
 *
 * Supports two modes:
 * 1. **Single-source mode** (backward-compatible): pass a single [audioSource], used for
 *    local microphone capture (VOICE_RECOGNITION default).
 * 2. **Fallback-chain mode**: pass multiple sources in [fallbackAudioSources]; they are tried in
 *    order until one initializes successfully. The canonical downlink chain is
 *    VOICE_COMMUNICATION → VOICE_CALL → VOICE_RECOGNITION → MIC.
 *
 * When [microphoneMuteForDownlink] is true and the active source is VOICE_COMMUNICATION, the
 * local microphone is programmatically muted via [AudioManager.setMicrophoneMute] so the captured
 * stream contains primarily remote (downlink) audio. The original mute state is restored on stop.
 *
 * On stock Android a normal app can only obtain the local microphone; carrier downlink/uplink
 * sources are system-privileged and typically fail to start or yield silence, which the engine
 * surfaces via diagnostics rather than pretending success.
 */
class AudioRecordPcmReader(
    // ---- Single-source (backward-compatible) ----
    private val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,

    // ---- Fallback chain ----
    private val fallbackAudioSources: IntArray? = null,

    // ---- Downlink-specific ----
    private val microphoneMuteForDownlink: Boolean = false,
    private val context: android.content.Context? = null,

    override val sampleRate: Int = 16_000,
) : PcmReader {

    override val channelCount: Int = 1

    /**
     * The ordered list of audio sources to try. If [fallbackAudioSources] is provided, it takes
     * precedence; otherwise the single [audioSource] is used as a one-element list.
     */
    private val effectiveSources: IntArray =
        fallbackAudioSources?.takeIf { it.isNotEmpty() } ?: intArrayOf(audioSource)

    /** Set after [start] succeeds — the source that actually initialized. */
    @Volatile override var sourceLabel: String = "unknown"
        private set

    /** Set after [start] succeeds — provenance derived from the active source. */
    @Volatile override var declaredProvenance: CaptureProvenance = CaptureProvenance.UNKNOWN
        private set

    private val minBuffer: Int = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )

    @Volatile private var recorder: AudioRecord? = null

    // Microphone mute state for downlink capture
    private var audioManager: AudioManager? = null
    private var originalMicrophoneMuteState: Boolean? = null
    private var microphoneMuteApplied: Boolean = false

    override fun start(): Boolean {
        if (minBuffer <= 0) return false
        for (source in effectiveSources) {
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (bufferSize <= 0) continue
            val record = runCatching {
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(bufferSize, DEFAULT_BUFFER_BYTES * 2),
                )
            }.getOrNull() ?: continue
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                continue
            }
            val started = runCatching {
                record.startRecording()
                record.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }.getOrDefault(false)
            if (!started) {
                record.release()
                continue
            }
            // Success — set active source metadata
            sourceLabel = labelFor(source)
            declaredProvenance = provenanceFor(source)
            recorder = record

            // Apply microphone mute for downlink capture when using VOICE_COMMUNICATION
            if (microphoneMuteForDownlink && source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                applyMicrophoneMuteForDownlinkCapture()
            }
            return true
        }
        return false
    }

    override fun read(buffer: ByteArray): Int {
        val record = recorder ?: return -1
        return record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
    }

    override fun stop() {
        val record = recorder ?: return
        runCatching {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        }
        restoreMicrophoneMuteState()
    }

    override fun release() {
        val record = recorder
        recorder = null
        if (record != null) {
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            }
            runCatching { record.release() }
        }
        restoreMicrophoneMuteState()
    }

    // ---- Microphone mute for downlink capture ----

    private fun applyMicrophoneMuteForDownlinkCapture() {
        val ctx = context ?: return
        val manager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = manager
        originalMicrophoneMuteState = manager.isMicrophoneMute
        manager.isMicrophoneMute = true
        microphoneMuteApplied = true
    }

    private fun restoreMicrophoneMuteState() {
        if (!microphoneMuteApplied) return
        val manager = audioManager
        val restoreTo = originalMicrophoneMuteState ?: false
        if (manager != null) {
            manager.isMicrophoneMute = restoreTo
        }
        microphoneMuteApplied = false
        originalMicrophoneMuteState = null
        audioManager = null
    }

    companion object {
        const val DEFAULT_BUFFER_BYTES = 3_200 // 100 ms @ 16 kHz mono 16-bit

        /**
         * Canonical downlink fallback chain as used in CallProxyDemo:
         * VOICE_COMMUNICATION → VOICE_CALL → VOICE_RECOGNITION → MIC
         */
        val DOWNLINK_FALLBACK_SOURCES: IntArray = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )

        fun labelFor(source: Int): String = when (source) {
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
            MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
            MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            else -> "SOURCE_$source"
        }

        fun provenanceFor(source: Int): CaptureProvenance = when (source) {
            MediaRecorder.AudioSource.VOICE_DOWNLINK -> CaptureProvenance.REMOTE_CONFIRMED
            MediaRecorder.AudioSource.VOICE_CALL -> CaptureProvenance.MIXED_UNKNOWN
            else -> CaptureProvenance.LOCAL_MIC
        }
    }
}
