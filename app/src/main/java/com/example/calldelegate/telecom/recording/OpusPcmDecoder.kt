package com.example.calldelegate.telecom.recording

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DecodedPcmFrame(
    val samples: ShortArray,
    val sampleRateHz: Int,
    val channelCount: Int,
    val presentationTimeUs: Long,
)

/**
 * Synchronous low-latency decoder for the Opus access units emitted by scrcpy-server.
 *
 * The codec configuration packet is also kept by [OpusRecordingMuxer], so one stream can be
 * decoded for AI while the original compressed packets continue to be saved without quality loss.
 */
class OpusPcmDecoder(
    private val onPcmFrame: (DecodedPcmFrame) -> Unit,
) : Closeable {
    private var codec: MediaCodec? = null
    private var outputSampleRateHz = ScrcpyServerSpec.SAMPLE_RATE_HZ
    private var outputChannelCount = ScrcpyServerSpec.CHANNEL_COUNT
    private var firstPcmFrameLogged = false
    private val bufferInfo = MediaCodec.BufferInfo()

    fun accept(packet: ScrcpyOpusPacket) {
        if (packet.isConfig) {
            if (codec == null) initialize(packet.data)
            return
        }
        val activeCodec = codec ?: error("Opus decoder has not received codec configuration")
        queueInput(activeCodec, packet)
        drainOutput(activeCodec)
    }

    private fun initialize(codecSpecificData: ByteArray) {
        require(codecSpecificData.isNotEmpty()) { "Empty Opus codec configuration" }
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            ScrcpyServerSpec.SAMPLE_RATE_HZ,
            ScrcpyServerSpec.CHANNEL_COUNT,
        ).apply {
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setByteBuffer("csd-0", ByteBuffer.wrap(codecSpecificData))
            setByteBuffer("csd-1", nativeLongBuffer(opusPreSkipNanos(codecSpecificData)))
            setByteBuffer("csd-2", nativeLongBuffer(OPUS_SEEK_PRE_ROLL_NANOS))
        }
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            codec = decoder
            Log.i(TAG, "Opus decoder started: configBytes=${codecSpecificData.size}")
        } catch (throwable: Throwable) {
            runCatching { decoder.release() }
            throw throwable
        }
    }

    private fun queueInput(activeCodec: MediaCodec, packet: ScrcpyOpusPacket) {
        var attempt = 0
        while (attempt < INPUT_DEQUEUE_ATTEMPTS) {
            val inputIndex = activeCodec.dequeueInputBuffer(INPUT_DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
                val input = requireNotNull(activeCodec.getInputBuffer(inputIndex))
                input.clear()
                require(packet.data.size <= input.remaining()) {
                    "Opus packet exceeds decoder input capacity"
                }
                input.put(packet.data)
                activeCodec.queueInputBuffer(
                    inputIndex,
                    0,
                    packet.data.size,
                    packet.presentationTimeUs,
                    0,
                )
                return
            }
            drainOutput(activeCodec)
            attempt += 1
        }
        error("Timed out waiting for an Opus decoder input buffer")
    }

    private fun drainOutput(activeCodec: MediaCodec) {
        while (true) {
            when (val outputIndex = activeCodec.dequeueOutputBuffer(bufferInfo, 0L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = activeCodec.outputFormat
                    outputSampleRateHz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    outputChannelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                    require(encoding == AudioFormat.ENCODING_PCM_16BIT) {
                        "Unsupported decoded PCM encoding: $encoding"
                    }
                    Log.i(
                        TAG,
                        "Opus output format: ${outputSampleRateHz}Hz " +
                            "channels=$outputChannelCount encoding=$encoding",
                    )
                }
                else -> {
                    if (outputIndex < 0) continue
                    val output = activeCodec.getOutputBuffer(outputIndex)
                    if (output != null && bufferInfo.size > 0) {
                        output.position(bufferInfo.offset)
                        output.limit(bufferInfo.offset + bufferInfo.size)
                        val bytes = ByteArray(bufferInfo.size)
                        output.get(bytes)
                        if (!firstPcmFrameLogged) {
                            firstPcmFrameLogged = true
                            Log.i(TAG, "First decoded PCM frame: bytes=${bytes.size}")
                        }
                        onPcmFrame(
                            DecodedPcmFrame(
                                samples = bytes.toLittleEndianShorts(),
                                sampleRateHz = outputSampleRateHz,
                                channelCount = outputChannelCount,
                                presentationTimeUs = bufferInfo.presentationTimeUs,
                            ),
                        )
                    }
                    activeCodec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    }

    override fun close() {
        val activeCodec = codec
        codec = null
        if (activeCodec != null) {
            runCatching { activeCodec.stop() }
            runCatching { activeCodec.release() }
        }
    }

    private fun ByteArray.toLittleEndianShorts(): ShortArray {
        val output = ShortArray(size / 2)
        var index = 0
        while (index < output.size) {
            val low = this[index * 2].toInt() and 0xff
            val high = this[index * 2 + 1].toInt()
            output[index] = ((high shl 8) or low).toShort()
            index += 1
        }
        return output
    }

    private fun opusPreSkipNanos(header: ByteArray): Long {
        if (header.size < OPUS_HEAD_MIN_BYTES ||
            !header.copyOfRange(0, OPUS_HEAD_MAGIC.size).contentEquals(OPUS_HEAD_MAGIC)
        ) {
            return 0L
        }
        val preSkipSamples =
            (header[10].toInt() and 0xff) or ((header[11].toInt() and 0xff) shl 8)
        return preSkipSamples * NANOS_PER_SECOND / ScrcpyServerSpec.SAMPLE_RATE_HZ
    }

    private fun nativeLongBuffer(value: Long): ByteBuffer =
        ByteBuffer.allocate(Long.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .putLong(value)
            .apply { flip() }

    private companion object {
        const val INPUT_DEQUEUE_TIMEOUT_US = 10_000L
        const val INPUT_DEQUEUE_ATTEMPTS = 5
        const val OPUS_HEAD_MIN_BYTES = 19
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val TAG = "CallOpusDecoder"
        val OPUS_HEAD_MAGIC = "OpusHead".toByteArray(Charsets.US_ASCII)
    }
}
