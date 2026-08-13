package com.example.calldelegate.core.audio.capture

import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.core.common.PerformanceTrace
import com.example.calldelegate.domain.api.AudioCaptureResult
import com.example.calldelegate.domain.api.CallAudioSource
import com.example.calldelegate.domain.api.CaptureProvenance
import com.example.calldelegate.domain.api.PcmAudioFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Streaming call-audio capture that implements [CallAudioSource] and fixes the Demo's four hazards:
 *
 *  1. **Stop/read race** — [stop] unblocks the reader then `cancelAndJoin`s the capture coroutine,
 *     so teardown never overlaps an in-flight read.
 *  2. **Uncancelable delayed start** — start is a structured coroutine on an injected [scope];
 *     there is no fire-and-forget `postDelayed`, so it is always cancelable.
 *  3. **Single-recorder / multi-call conflict** — a strict single-active-call policy rejects a
 *     second [start] with a different callId instead of clobbering the live recorder.
 *  4. **Uncertain audio source** — every capture is measured by [AudioFrameAnalyzer] and reported
 *     with an honest [CaptureProvenance] (downgraded to SILENCED when no signal is present).
 *
 * All AudioRecord I/O is confined to [captureDispatcher] (a dedicated background thread); frames are
 * copied into consumer-owned arrays and published on a bounded [SharedFlow] with drop accounting.
 */
class CallAudioCaptureEngine(
    private val readerFactory: () -> PcmReader,
    private val scope: CoroutineScope,
    private val captureDispatcher: CoroutineDispatcher,
    private val wavDirectory: File? = null,
    private val frameBytes: Int = DEFAULT_FRAME_BYTES,
    bufferedFrames: Int = DEFAULT_BUFFERED_FRAMES,
    private val now: () -> Long = System::currentTimeMillis,
    private val monotonicNanos: () -> Long = System::nanoTime,
) : CallAudioSource {

    private val _frames = MutableSharedFlow<PcmAudioFrame>(
        replay = 0,
        extraBufferCapacity = bufferedFrames,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val audioFrames: SharedFlow<PcmAudioFrame> = _frames.asSharedFlow()

    private val mutex = Mutex()
    private var activeCallId: String? = null
    private var captureJob: Job? = null
    private var reader: PcmReader? = null
    private var analyzer: AudioFrameAnalyzer? = null
    private var wav: WavWriter? = null
    private var startedAtMs: Long = 0
    private val dropped = AtomicLong(0)
    private val zeroByteReads = AtomicLong(0)
    private val readErrorCount = AtomicLong(0)
    @Volatile private var captureError: String? = null

    override suspend fun start(callId: String): AppResult<Unit> = mutex.withLock {
        if (activeCallId == callId) return@withLock AppResult.Success(Unit) // idempotent
        if (activeCallId != null) {
            return@withLock AppResult.Failure(
                AppError("CAPTURE_BUSY", "已有通话正在采集，MVP 仅支持单路通话", recoverable = false),
            )
        }
        val newReader = readerFactory()
        val started = withContext(captureDispatcher) { runCatching { newReader.start() }.getOrDefault(false) }
        if (!started) {
            withContext(captureDispatcher) { runCatching { newReader.release() } }
            return@withLock AppResult.Failure(
                AppError("CAPTURE_INIT", "无法启动音频采集（可能缺少麦克风权限或音源不可用）"),
            )
        }
        val frameAnalyzer = AudioFrameAnalyzer(newReader.sampleRate, newReader.channelCount)
        val wavWriter = wavDirectory?.let { dir ->
            runCatching {
                WavWriter(File(dir, "capture_${safeId(callId)}.wav"), newReader.sampleRate, newReader.channelCount)
                    .also { it.open() }
            }.getOrNull()
        }
        reader = newReader
        analyzer = frameAnalyzer
        wav = wavWriter
        dropped.set(0)
        zeroByteReads.set(0)
        readErrorCount.set(0)
        captureError = null
        startedAtMs = now()
        activeCallId = callId
        captureJob = scope.launch(captureDispatcher) {
            PerformanceTrace.suspendSection("audio_capture") {
                captureLoop(callId, newReader, frameAnalyzer, wavWriter)
            }
        }
        AppResult.Success(Unit)
    }

    override suspend fun stop(callId: String): AppResult<AudioCaptureResult> = mutex.withLock {
        val current = activeCallId
            ?: return@withLock AppResult.Failure(AppError("CAPTURE_NONE", "当前没有进行中的采集"))
        if (current != callId) {
            return@withLock AppResult.Failure(
                AppError("CAPTURE_CALLID_MISMATCH", "停止采集的通话与当前采集不一致"),
            )
        }
        val activeReader = reader
        val activeAnalyzer = analyzer
        val activeWav = wav
        // Unblock the reader FIRST (safe from any thread), then join. We must NOT hop onto
        // captureDispatcher here: the capture loop monopolizes that single thread until it exits,
        // so scheduling teardown work on it would deadlock. Joining guarantees no in-flight read.
        runCatching { activeReader?.stop() }
        captureJob?.cancelAndJoin()
        runCatching { activeReader?.release() }
        val wavPath = runCatching { activeWav?.close() }.getOrNull()
        val wallClock = (now() - startedAtMs).coerceAtLeast(0)
        val diagnostics = activeAnalyzer!!.toDiagnostics(
            audioSourceLabel = activeReader?.sourceLabel ?: "UNKNOWN",
            initialized = true,
            wallClockMs = wallClock,
            droppedFrames = dropped.get(),
            zeroByteReads = zeroByteReads.get(),
            readErrorCount = readErrorCount.get(),
            error = captureError,
        )
        val provenance = when {
            captureError != null -> CaptureProvenance.UNKNOWN
            activeAnalyzer.isEffectivelySilent() -> CaptureProvenance.SILENCED
            else -> activeReader?.declaredProvenance ?: CaptureProvenance.UNKNOWN
        }
        val result = AudioCaptureResult(
            callId = callId,
            wavPath = wavPath,
            durationMs = activeAnalyzer.audioDurationMs(),
            totalBytes = activeAnalyzer.bytesCaptured,
            provenance = provenance,
            diagnostics = diagnostics,
        )
        clearState()
        AppResult.Success(result)
    }

    private suspend fun captureLoop(
        callId: String,
        activeReader: PcmReader,
        activeAnalyzer: AudioFrameAnalyzer,
        activeWav: WavWriter?,
    ) {
        val buffer = ByteArray(frameBytes)
        try {
            while (currentCoroutineContext().isActive) {
                val read = activeReader.read(buffer)
                if (read < 0) {
                    readErrorCount.incrementAndGet()
                    captureError = "READ_ERROR_$read"
                    break
                }
                if (read == 0) {
                    zeroByteReads.incrementAndGet()
                    continue
                }
                activeAnalyzer.accept(buffer, read)
                activeWav?.write(buffer, read)
                // Only publish (and count drops) when someone is actually consuming frames.
                if (_frames.subscriptionCount.value > 0) {
                    val owned = buffer.copyOf(read)
                    val frame = PcmAudioFrame(
                        callId = callId,
                        data = owned,
                        sampleRate = activeReader.sampleRate,
                        channelCount = activeReader.channelCount,
                        timestampMs = now(),
                        emittedAtElapsedRealtimeNanos = monotonicNanos(),
                    )
                    if (!_frames.tryEmit(frame)) dropped.incrementAndGet()
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            captureError = throwable.message ?: "CAPTURE_FAILURE"
        }
    }

    private fun clearState() {
        activeCallId = null
        captureJob = null
        reader = null
        analyzer = null
        wav = null
        startedAtMs = 0
        captureError = null
        dropped.set(0)
        zeroByteReads.set(0)
        readErrorCount.set(0)
    }

    private fun safeId(id: String): String =
        id.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "call" }

    private companion object {
        const val DEFAULT_FRAME_BYTES = 3_200 // 100 ms @ 16 kHz mono 16-bit
        const val DEFAULT_BUFFERED_FRAMES = 32
    }
}
