package com.example.calldelegate.core.audio.capture

import com.example.calldelegate.domain.api.CaptureProvenance

/**
 * Thin blocking source of raw PCM, sitting exactly where `AudioRecord` would. Extracting this seam
 * keeps [CallAudioCaptureEngine]'s lifecycle/backpressure/race logic pure-JVM testable with a fake
 * reader, while the Android specifics live only in [AudioRecordPcmReader].
 */
interface PcmReader {
    val sampleRate: Int
    val channelCount: Int

    /** Human-readable label of the underlying audio source, e.g. "VOICE_RECOGNITION". */
    val sourceLabel: String

    /**
     * What this source can honestly claim about its content. A microphone-family source can only
     * ever be [CaptureProvenance.LOCAL_MIC]; it must NEVER be reported as REMOTE_CONFIRMED.
     */
    val declaredProvenance: CaptureProvenance

    /** Initialize and begin recording. @return true on success. */
    fun start(): Boolean

    /**
     * Blocking read of up to [buffer].size bytes. Returns the number of bytes read (> 0), 0 when no
     * data was available, or a negative value on error. Must return promptly after [stop].
     */
    fun read(buffer: ByteArray): Int

    /** Signal the blocking [read] to unblock/return; safe to call from another thread. */
    fun stop()

    /** Release native resources. Idempotent. */
    fun release()
}
