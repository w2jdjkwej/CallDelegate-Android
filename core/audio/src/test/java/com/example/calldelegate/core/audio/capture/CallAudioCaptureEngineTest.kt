package com.example.calldelegate.core.audio.capture

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.CaptureProvenance
import com.example.calldelegate.domain.api.PcmAudioFrame
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class CallAudioCaptureEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val executor = Executors.newSingleThreadExecutor()
    private val captureDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
        captureDispatcher.close()
    }

    private class FakePcmReader(
        private val frames: List<ByteArray>,
        override val declaredProvenance: CaptureProvenance = CaptureProvenance.LOCAL_MIC,
        private val startResult: Boolean = true,
        override val sampleRate: Int = 16_000,
        override val channelCount: Int = 1,
        override val sourceLabel: String = "FAKE",
    ) : PcmReader {
        val started = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        private val index = AtomicInteger(0)

        override fun start(): Boolean {
            started.set(true)
            return startResult
        }

        override fun read(buffer: ByteArray): Int {
            val i = index.getAndIncrement()
            if (i < frames.size) {
                val f = frames[i]
                val n = minOf(f.size, buffer.size)
                f.copyInto(buffer, 0, 0, n)
                return n
            }
            // Exhausted: emulate a live mic returning nothing until stopped/cancelled.
            Thread.sleep(2)
            return 0
        }

        override fun stop() { stopped.set(true) }
        override fun release() { released.set(true) }
    }

    private fun loudFrames(count: Int, bytesEach: Int = 320): List<ByteArray> =
        List(count) { ByteArray(bytesEach) { 0x30 } } // 0x3030 = 12336, well above silence

    private fun silentFrames(count: Int, bytesEach: Int = 320): List<ByteArray> =
        List(count) { ByteArray(bytesEach) }

    private fun newEngine(reader: PcmReader, withWav: Boolean): CallAudioCaptureEngine =
        CallAudioCaptureEngine(
            readerFactory = { reader },
            scope = scope,
            captureDispatcher = captureDispatcher,
            wavDirectory = if (withWav) tempFolder.root else null,
            bufferedFrames = 32,
        )

    @Test
    fun singleActiveCallPolicyRejectsSecondCall() = runBlocking {
        val engine = newEngine(FakePcmReader(loudFrames(3)), withWav = false)

        assertThat(engine.start("A")).isInstanceOf(AppResult.Success::class.java)
        assertThat(engine.start("A")).isInstanceOf(AppResult.Success::class.java) // idempotent
        val busy = engine.start("B")
        assertThat(busy).isInstanceOf(AppResult.Failure::class.java)
        assertThat((busy as AppResult.Failure).error.code).isEqualTo("CAPTURE_BUSY")

        engine.stop("A")
        Unit
    }

    @Test
    fun failedReaderInitReportsErrorAndLeavesEngineIdle() = runBlocking {
        val failing = FakePcmReader(emptyList(), startResult = false)
        val engine = newEngine(failing, withWav = false)

        val result = engine.start("A")
        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat((result as AppResult.Failure).error.code).isEqualTo("CAPTURE_INIT")

        // Engine must be idle again: a subsequent start with a working reader would need a fresh
        // factory, but at minimum stop() reports no active capture.
        val stop = engine.stop("A")
        assertThat(stop).isInstanceOf(AppResult.Failure::class.java)
        assertThat((stop as AppResult.Failure).error.code).isEqualTo("CAPTURE_NONE")
    }

    @Test
    fun stopValidatesCallIdAndReleasesReaderWithoutRace() = runBlocking {
        val reader = FakePcmReader(loudFrames(5))
        val engine = newEngine(reader, withWav = true)

        engine.start("call-A")
        delay(200) // let the capture thread consume the prepared frames

        val mismatch = engine.stop("call-B")
        assertThat(mismatch).isInstanceOf(AppResult.Failure::class.java)
        assertThat((mismatch as AppResult.Failure).error.code).isEqualTo("CAPTURE_CALLID_MISMATCH")

        val stop = engine.stop("call-A")
        assertThat(stop).isInstanceOf(AppResult.Success::class.java)
        val result = (stop as AppResult.Success).value
        assertThat(result.totalBytes).isEqualTo(5 * 320)
        assertThat(result.provenance).isEqualTo(CaptureProvenance.LOCAL_MIC)
        assertThat(result.wavPath).isNotNull()
        assertThat(result.diagnostics.maxAbsAmplitude).isGreaterThan(0)
        // stop() must have torn the reader down after joining the capture loop.
        assertThat(reader.stopped.get()).isTrue()
        assertThat(reader.released.get()).isTrue()
    }

    @Test
    fun silentCaptureIsReportedAsSilenced() = runBlocking {
        val reader = FakePcmReader(silentFrames(5))
        val engine = newEngine(reader, withWav = false)

        engine.start("A")
        delay(200)
        val result = (engine.stop("A") as AppResult.Success).value

        assertThat(result.provenance).isEqualTo(CaptureProvenance.SILENCED)
        assertThat(result.diagnostics.silenceRatio).isEqualTo(1.0)
    }

    @Test
    fun zeroLengthReadsAreReportedWithoutTurningTheCaptureIntoAnError() = runBlocking {
        val reader = FakePcmReader(loudFrames(1))
        val engine = newEngine(reader, withWav = false)

        engine.start("A")
        delay(100)
        val result = (engine.stop("A") as AppResult.Success).value

        assertThat(result.diagnostics.zeroByteReads).isGreaterThan(0L)
        assertThat(result.diagnostics.readErrorCount).isEqualTo(0L)
        assertThat(result.diagnostics.error).isNull()
    }

    @Test
    fun framesAreDeliveredToSubscriber() = runBlocking {
        val reader = FakePcmReader(loudFrames(10))
        val engine = newEngine(reader, withWav = false)
        val received = CopyOnWriteArrayList<PcmAudioFrame>()

        val collector = scope.launch { engine.audioFrames.collect { received.add(it) } }
        delay(100) // ensure the subscription is registered before capture starts

        engine.start("A")
        delay(300)
        engine.stop("A")
        collector.cancel()

        assertThat(received).isNotEmpty()
        assertThat(received.first().callId).isEqualTo("A")
        // Each delivered frame owns its own byte array (no shared/aliased buffer).
        assertThat(received.map { it.data }.toSet().size).isEqualTo(received.size)
    }

    @Test
    fun backpressureDropsFramesForSlowSubscriberAndCounts() = runBlocking {
        val reader = FakePcmReader(loudFrames(80))
        val engine = CallAudioCaptureEngine(
            readerFactory = { reader },
            scope = scope,
            captureDispatcher = captureDispatcher,
            wavDirectory = null,
            bufferedFrames = 1,
        )
        // Slow subscriber that cannot keep up with the burst.
        val collector = scope.launch {
            engine.audioFrames.collect { delay(40) }
        }
        delay(100)

        engine.start("A")
        delay(400)
        val result = (engine.stop("A") as AppResult.Success).value
        collector.cancel()

        // WAV/analyzer captured everything, but the bounded live stream dropped for the slow consumer.
        assertThat(result.totalBytes).isEqualTo(80 * 320)
        assertThat(result.diagnostics.droppedFrames).isGreaterThan(0L)
    }
}
