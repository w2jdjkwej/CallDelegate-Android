package com.example.calldelegate.core.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.math.sin

class StreamingPcmResamplerTest {
    @Test
    fun fixedAndIrregularChunksMatchOneShotOutput() {
        val input = ShortArray(22_050) { index ->
            (8_000.0 * sin(2.0 * PI * 1_137.0 * index / 22_050.0)).toInt().toShort()
        }
        val oneShot = processInChunks(input, intArrayOf(input.size))

        listOf(
            processInChunks(input, intArrayOf(64)),
            processInChunks(input, intArrayOf(320)),
            processInChunks(input, intArrayOf(17, 503, 1, 89, 1_024)),
        ).forEach { chunked ->
            assertThat(chunked).hasLength(oneShot.size)
            val largestDifference = oneShot.indices.maxOf { index ->
                abs(oneShot[index].toInt() - chunked[index].toInt())
            }
            assertThat(largestDifference).isAtMost(1)
        }
    }

    @Test
    fun flushOnlyEmitsOnce() {
        val processor = StreamingPcmResampler(22_050, 16_000)

        val first = processor.process(shortArrayOf(1, 2, 3), endOfInput = true)
        val second = processor.process(shortArrayOf(), endOfInput = true)

        assertThat(first).isNotEmpty()
        assertThat(second).isEmpty()
    }

    @Test
    fun separateFragmentsDoNotShareFilterOrPhaseState() {
        val fragmentA = ShortArray(997) { 12_000 }
        val fragmentB = ShortArray(1_103) { index -> (index * 7).toShort() }
        StreamingPcmResampler(22_050, 16_000).process(fragmentA, endOfInput = true)

        val afterA = StreamingPcmResampler(22_050, 16_000).process(fragmentB, endOfInput = true)
        val independent = StreamingPcmResampler(22_050, 16_000).process(fragmentB, endOfInput = true)

        assertThat(afterA).isEqualTo(independent)
    }

    @Test
    fun passbandAndStopbandMeetTheMvpThresholds() {
        val oneKilohertzRms = outputRms(1_000.0)
        val sixKilohertzRms = outputRms(6_000.0)
        val edgeRms = outputRms(7_500.0)
        val nineKilohertzRms = outputRms(9_000.0)
        val tenKilohertzRms = outputRms(10_000.0)
        val inputRms = AMPLITUDE / sqrt(2.0)

        assertThat(decibels(oneKilohertzRms / inputRms)).isAtLeast(-1.0)
        assertThat(decibels(sixKilohertzRms / inputRms)).isAtLeast(-3.0)
        assertThat(decibels(edgeRms / inputRms)).isAtMost(0.5)
        assertThat(decibels(nineKilohertzRms / oneKilohertzRms)).isAtMost(-6.0)
        assertThat(decibels(tenKilohertzRms / oneKilohertzRms)).isAtMost(-10.0)
    }

    @Test
    fun dcSilenceAndFullScaleInputsRemainStable() {
        val dc = StreamingPcmResampler(22_050, 16_000)
            .process(ShortArray(22_050) { 1_000 }, endOfInput = true)
        val settledAverage = dc.copyOfRange(8_000, dc.size).map(Short::toInt).average()
        assertThat(settledAverage).isWithin(2.0).of(1_000.0)

        val silence = StreamingPcmResampler(48_000, 16_000)
            .process(ShortArray(480_000), endOfInput = true)
        assertThat(silence).hasLength(160_000)
        assertThat(silence.all { it == 0.toShort() }).isTrue()

        val fullScale = ShortArray(48_000) { index ->
            if (index % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE
        }
        val converted = StreamingPcmResampler(48_000, 16_000)
            .process(fullScale, endOfInput = true)
        assertThat(converted).hasLength(16_000)
        assertThat(converted.maxOf { abs(it.toInt()) }).isAtMost(32_768)
    }

    private fun processInChunks(input: ShortArray, sizes: IntArray): ShortArray {
        val processor = StreamingPcmResampler(22_050, 16_000)
        val output = ArrayList<Short>()
        var offset = 0
        var sizeIndex = 0
        while (offset < input.size) {
            val size = sizes[sizeIndex % sizes.size].coerceAtMost(input.size - offset)
            val end = offset + size == input.size
            processor.process(input.copyOfRange(offset, offset + size), end).forEach(output::add)
            offset += size
            sizeIndex += 1
        }
        return ShortArray(output.size) { output[it] }
    }

    private fun outputRms(frequencyHz: Double): Double {
        val input = ShortArray(22_050) { index ->
            (AMPLITUDE * sin(2.0 * PI * frequencyHz * index / 22_050.0)).toInt().toShort()
        }
        val output = StreamingPcmResampler(22_050, 16_000).process(input, endOfInput = true)
        val settled = output.copyOfRange(1_600, output.size)
        return sqrt(settled.sumOf { sample -> sample.toDouble() * sample } / settled.size)
    }

    private fun decibels(ratio: Double): Double = 20.0 * log10(ratio)

    private companion object {
        const val AMPLITUDE = 12_000.0
    }
}
