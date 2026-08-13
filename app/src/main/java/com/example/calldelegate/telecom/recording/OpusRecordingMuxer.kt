package com.example.calldelegate.telecom.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.Closeable
import java.io.FileDescriptor
import java.nio.ByteBuffer

class OpusRecordingMuxer(
    output: FileDescriptor,
) : Closeable {
    private var muxer: MediaMuxer? =
        MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
    private var trackIndex = -1
    private var started = false
    private var firstPacketTimeNanos: Long? = null
    private var lastPresentationTimeUs = -1L

    var mediaPacketCount: Long = 0
        private set
    var durationMillis: Long = 0
        private set
    var closeFailure: Throwable? = null
        private set

    fun write(packet: ScrcpyOpusPacket) {
        if (packet.isConfig) {
            if (!started) addTrack(packet.data)
            return
        }
        if (!started || trackIndex < 0) return

        // VOICE_CALL may stop emitting packets during silence. A monotonic wall-clock timeline
        // preserves that real gap instead of collapsing the recording around discontinuities.
        val nowNanos = System.nanoTime()
        val first = firstPacketTimeNanos ?: nowNanos.also {
            firstPacketTimeNanos = it
        }
        val relative = ((nowNanos - first) / 1_000L).coerceAtLeast(0L)
        val normalized = maxOf(relative, lastPresentationTimeUs + 1L)
        val info = MediaCodec.BufferInfo().apply {
            offset = 0
            size = packet.data.size
            presentationTimeUs = normalized
            flags = 0
        }
        muxer?.writeSampleData(trackIndex, ByteBuffer.wrap(packet.data), info)
        lastPresentationTimeUs = normalized
        mediaPacketCount++
        durationMillis = normalized / 1_000L
    }

    private fun addTrack(codecSpecificData: ByteArray) {
        require(codecSpecificData.isNotEmpty()) { "Empty Opus codec configuration" }
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            ScrcpyServerSpec.SAMPLE_RATE_HZ,
            ScrcpyServerSpec.CHANNEL_COUNT,
        ).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(codecSpecificData))
        }
        trackIndex = requireNotNull(muxer).addTrack(format)
        require(trackIndex >= 0) { "Unable to add Opus track" }
        muxer?.start()
        started = true
    }

    override fun close() {
        val activeMuxer = muxer
        muxer = null
        if (started) {
            runCatching { activeMuxer?.stop() }
                .onFailure { closeFailure = it }
        }
        runCatching { activeMuxer?.release() }
            .onFailure { if (closeFailure == null) closeFailure = it }
        started = false
        trackIndex = -1
    }
}
