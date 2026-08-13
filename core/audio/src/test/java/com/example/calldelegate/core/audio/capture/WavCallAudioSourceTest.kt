package com.example.calldelegate.core.audio.capture

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.VadDecision
import com.example.calldelegate.domain.api.VoiceActivityDetector
import com.example.calldelegate.domain.model.CaptureRequest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class WavCallAudioSourceTest {

    @Test
    fun streamsDataAfterUnknownOddSizedChunkAndPadsFinalVadFrame() = runBlocking {
        val wav = writeWav(
            chunks = listOf(
                Chunk("JUNK", byteArrayOf(1, 2, 3)),
                Chunk("data", pcm16Samples(321)),
                Chunk("fmt ", pcmFormat()),
            ),
        )
        val source = WavCallAudioSource(
            wavFile = wav,
            injectionMode = WavInjectionMode.AS_FAST_AS_POSSIBLE,
            tailSilenceMs = 0L,
        )

        assertThat(source.start("wav-call")).isInstanceOf(AppResult.Success::class.java)
        val frames = mutableListOf<ByteArray>()
        source.audioFrames.collect { frames += it.data }

        assertThat(frames).hasSize(2)
        assertThat(frames[0]).hasLength(640)
        assertThat(frames[1]).hasLength(640)
        assertThat(readSample(frames[0], 0)).isEqualTo(0)
        assertThat(readSample(frames[0], 319)).isEqualTo(319)
        assertThat(readSample(frames[1], 0)).isEqualTo(320)
        assertThat(readSample(frames[1], 1)).isEqualTo(0)
        val metrics = checkNotNull(source.latestInjectionMetrics())
        assertThat(metrics.originalAudioSamples).isEqualTo(321L)
        assertThat(metrics.framePaddingSamples).isEqualTo(319L)
        assertThat(metrics.emittedSamples).isEqualTo(640L)
        assertThat(metrics.completed).isTrue()
        assertThat(metrics.cancelled).isFalse()
        assertThat(source.stop("wav-call")).isInstanceOf(AppResult.Success::class.java)
    }

    @Test
    fun realTimeModePacesOnlyInputFrames() = runBlocking {
        var nowNanos = 0L
        val delayedMillis = mutableListOf<Long>()
        val source = WavCallAudioSource(
            wavFile = writeWav(listOf(Chunk("fmt ", pcmFormat()), Chunk("data", pcm16Samples(640)))),
            injectionMode = WavInjectionMode.REAL_TIME,
            tailSilenceMs = 0L,
            delayForMillis = { millis ->
                delayedMillis += millis
                nowNanos += millis * 1_000_000L
            },
            monotonicNanos = { nowNanos },
        )

        assertThat(source.start("paced-call")).isInstanceOf(AppResult.Success::class.java)
        source.audioFrames.collect { }

        assertThat(delayedMillis).containsExactly(20L).inOrder()
    }

    @Test
    fun asFastAsPossibleModeDoesNotDelayInputFrames() = runBlocking {
        val delayedMillis = mutableListOf<Long>()
        val source = WavCallAudioSource(
            wavFile = writeWav(listOf(Chunk("fmt ", pcmFormat()), Chunk("data", pcm16Samples(640)))),
            injectionMode = WavInjectionMode.AS_FAST_AS_POSSIBLE,
            tailSilenceMs = 0L,
            delayForMillis = { millis -> delayedMillis += millis },
        )

        assertThat(source.start("fast-call")).isInstanceOf(AppResult.Success::class.java)
        source.audioFrames.collect { }

        assertThat(delayedMillis).isEmpty()
    }

    @Test
    fun stoppingTheSourceIsIdempotentAndPreventsLaterFrames() = runBlocking {
        val source = WavCallAudioSource(
            wavFile = writeWav(listOf(Chunk("fmt ", pcmFormat()), Chunk("data", pcm16Samples(960)))),
            injectionMode = WavInjectionMode.AS_FAST_AS_POSSIBLE,
            tailSilenceMs = 0L,
        )
        var receivedFrames = 0

        assertThat(source.start("cancel-call")).isInstanceOf(AppResult.Success::class.java)
        source.audioFrames.collect {
            receivedFrames += 1
            if (receivedFrames == 1) source.stop("cancel-call")
        }

        assertThat(receivedFrames).isEqualTo(1)
        assertThat(source.stop("cancel-call")).isInstanceOf(AppResult.Success::class.java)
        assertThat(checkNotNull(source.latestInjectionMetrics()).cancelled).isTrue()
    }

    @Test
    fun feedsWavFramesIntoTheExistingVadBridge() = runBlocking {
        val source = WavCallAudioSource(
            wavFile = writeWav(listOf(Chunk("fmt ", pcmFormat()), Chunk("data", pcm16Samples(320)))),
            injectionMode = WavInjectionMode.AS_FAST_AS_POSSIBLE,
        )
        val vad = object : VoiceActivityDetector {
            private var accepts = 0

            override fun reset() {
                accepts = 0
            }

            override fun accept(samples: ShortArray, sampleRateHz: Int): VadDecision {
                accepts += 1
                return VadDecision(
                    speechDetected = accepts == 1,
                    endOfSpeech = accepts == 2,
                    probability = 1f,
                )
            }
        }
        val bridge = StreamingTurnAudioInputSource(source, vad, endpointGraceMs = 0L)

        assertThat(source.start("bridge-call")).isInstanceOf(AppResult.Success::class.java)
        val captured = bridge.capture(CaptureRequest("session", maxDurationMillis = 30_000)) as AppResult.Success

        assertThat(captured.value.pcm16.size).isEqualTo(640)
        assertThat(captured.value.speechDetected).isTrue()
        assertThat(checkNotNull(source.latestInjectionMetrics()).consumerStoppedEarly).isTrue()
        assertThat(source.stop("bridge-call")).isInstanceOf(AppResult.Success::class.java)
    }

    @Test
    fun rejectsNon16kPcmBeforeFramesAreExposed() = runBlocking {
        val source = WavCallAudioSource(
            wavFile = writeWav(listOf(Chunk("fmt ", pcmFormat(sampleRate = 8_000)), Chunk("data", pcm16Samples(320)))),
            injectionMode = WavInjectionMode.AS_FAST_AS_POSSIBLE,
        )

        val result = source.start("invalid-rate")

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat((result as AppResult.Failure).error.code).isEqualTo("WAV_FORMAT")
    }

    @Test
    fun rejectsWaveFormatExtensibleAndMissingOddChunkPadding() = runBlocking {
        val extensible = WavCallAudioSource(
            wavFile = writeWav(
                listOf(Chunk("fmt ", pcmFormat(audioFormat = 0xfffe)), Chunk("data", pcm16Samples(320))),
            ),
            injectionMode = WavInjectionMode.AS_FAST_AS_POSSIBLE,
        )
        val missingPadding = WavCallAudioSource(
            wavFile = writeWav(
                listOf(Chunk("fmt ", pcmFormat()), Chunk("JUNK", byteArrayOf(1), writePadding = false), Chunk("data", pcm16Samples(320))),
            ),
            injectionMode = WavInjectionMode.AS_FAST_AS_POSSIBLE,
        )

        assertThat(extensible.start("extensible")).isInstanceOf(AppResult.Failure::class.java)
        assertThat(missingPadding.start("missing-padding")).isInstanceOf(AppResult.Failure::class.java)
    }

    private data class Chunk(
        val id: String,
        val payload: ByteArray,
        val writePadding: Boolean = true,
    )

    private fun writeWav(chunks: List<Chunk>): File {
        val body = ByteArrayOutputStream()
        for (chunk in chunks) {
            body.write(chunk.id.encodeToByteArray())
            body.write(littleEndianInt(chunk.payload.size.toLong()))
            body.write(chunk.payload)
            if (chunk.payload.size % 2 == 1 && chunk.writePadding) body.write(0)
        }
        val fileBytes = ByteArrayOutputStream()
        fileBytes.write("RIFF".encodeToByteArray())
        fileBytes.write(littleEndianInt(4L + body.size().toLong()))
        fileBytes.write("WAVE".encodeToByteArray())
        fileBytes.write(body.toByteArray())
        return File.createTempFile("wav-call-source", ".wav").also { file ->
            file.writeBytes(fileBytes.toByteArray())
            file.deleteOnExit()
        }
    }

    private fun pcmFormat(
        sampleRate: Int = 16_000,
        audioFormat: Int = 1,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(littleEndianShort(audioFormat))
        output.write(littleEndianShort(1))
        output.write(littleEndianInt(sampleRate.toLong()))
        output.write(littleEndianInt((sampleRate * 2).toLong()))
        output.write(littleEndianShort(2))
        output.write(littleEndianShort(16))
        return output.toByteArray()
    }

    private fun pcm16Samples(count: Int): ByteArray {
        val output = ByteArrayOutputStream()
        repeat(count) { output.write(littleEndianShort(it)) }
        return output.toByteArray()
    }

    private fun littleEndianShort(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
    )

    private fun littleEndianInt(value: Long): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 24) and 0xff).toByte(),
    )

    private fun readSample(bytes: ByteArray, sampleIndex: Int): Int {
        val offset = sampleIndex * 2
        val low = bytes[offset].toInt() and 0xff
        return ((bytes[offset + 1].toInt() shl 8) or low).toShort().toInt()
    }
}
