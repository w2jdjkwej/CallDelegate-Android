package com.example.calldelegate.telecom.recording

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.calldelegate.core.audio.telecom.TelecomCallAudioBridge
import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class SavedCarrierRecording(
    val contentUri: String,
    val displayName: String,
    val durationMillis: Long,
    val packetCount: Long,
)

class ShizukuCarrierCallRecorder(
    context: Context,
    private val connector: ShizukuCaptureConnector,
    private val audioBridge: TelecomCallAudioBridge,
) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var activeCapture: ActiveCapture? = null

    val recording: Boolean
        get() = activeCapture != null

    suspend fun start(callId: String): AppResult<Unit> = mutex.withLock {
        val current = activeCapture
        if (current != null) {
            return@withLock if (current.callId == callId) {
                AppResult.Success(Unit)
            } else {
                AppResult.Failure(
                    AppError("CARRIER_RECORDING_BUSY", "已有真实通话正在录音"),
                )
            }
        }
        if (ShizukuStatus.current() != ShizukuSetupState.READY) {
            return@withLock AppResult.Failure(
                AppError("SHIZUKU_NOT_READY", "Shizuku 未运行或尚未授权"),
            )
        }

        val displayName = buildDisplayName()
        var target: MediaStoreRecordingTarget? = null
        var remote: IShizukuCaptureService? = null
        var pipe: ParcelFileDescriptor? = null
        var muxer: OpusRecordingMuxer? = null
        var decoder: OpusPcmDecoder? = null
        var readerJob: Job? = null
        val captureOpen = AtomicBoolean(true)
        val streamError = AtomicReference<String?>(null)

        try {
            val serverFile = when (
                val asset = withContext(Dispatchers.IO) {
                    ScrcpyServerAsset.ensureAvailable(applicationContext)
                }
            ) {
                is AppResult.Failure -> return@withLock AppResult.Failure(asset.error)
                is AppResult.Success -> asset.value
            }
            val recordingTarget = withContext(Dispatchers.IO) {
                MediaStoreRecordingTarget.create(applicationContext, displayName)
            }
            target = recordingTarget
            val activeMuxer = OpusRecordingMuxer(recordingTarget.descriptor.fileDescriptor)
            muxer = activeMuxer
            val connectedRemote = connector.connect()
            remote = connectedRemote
            val activePipe = withContext(Dispatchers.IO) {
                connectedRemote.startCapture(
                    serverFile.absolutePath,
                    ScrcpyServerSpec.newSocketId(),
                    ScrcpyServerSpec.DEFAULT_AUDIO_SOURCE,
                    ScrcpyServerSpec.DEFAULT_BIT_RATE,
                )
            } ?: error("Privileged capture service rejected the recording request")
            pipe = activePipe

            val ready = CompletableDeferred<Unit>()
            val packetReader = ScrcpyOpusPacketReader()
            var activeDecoder: OpusPcmDecoder? = OpusPcmDecoder { frame ->
                audioBridge.pushDecodedPcm(
                    callId = callId,
                    samples = frame.samples,
                    sampleRateHz = frame.sampleRateHz,
                    channelCount = frame.channelCount,
                    timestampMs = frame.presentationTimeUs / 1_000L,
                )
            }
            decoder = activeDecoder
            readerJob = scope.launch {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(activePipe).use { input ->
                        packetReader.read(
                            input = input,
                            shouldContinue = captureOpen::get,
                            onPacket = { packet ->
                                activeMuxer.write(packet)
                                val currentDecoder = activeDecoder
                                if (currentDecoder != null) {
                                    runCatching { currentDecoder.accept(packet) }
                                        .onFailure { error ->
                                            Log.w(TAG, "real-time Opus decoding disabled", error)
                                            currentDecoder.close()
                                            activeDecoder = null
                                        }
                                }
                                if (packet.isConfig && !ready.isCompleted) ready.complete(Unit)
                            },
                        )
                    }
                } catch (throwable: Throwable) {
                    streamError.set(throwable.message ?: "scrcpy audio stream failed")
                    if (!ready.isCompleted) ready.completeExceptionally(throwable)
                } finally {
                    activeDecoder?.close()
                    activeDecoder = null
                    if (!ready.isCompleted) {
                        ready.completeExceptionally(
                            IllegalStateException("scrcpy audio stream ended before initialization"),
                        )
                    }
                }
            }
            withTimeout(STREAM_START_TIMEOUT_MILLIS) { ready.await() }

            activeCapture = ActiveCapture(
                callId = callId,
                remote = connectedRemote,
                pipe = activePipe,
                target = recordingTarget,
                muxer = activeMuxer,
                readerJob = readerJob,
                captureOpen = captureOpen,
                streamError = streamError,
            )
            AppResult.Success(Unit)
        } catch (throwable: Throwable) {
            captureOpen.set(false)
            runCatching { remote?.stopCapture() }
            runCatching { pipe?.close() }
            readerJob?.cancelAndJoin()
            runCatching { decoder?.close() }
            runCatching { muxer?.close() }
            target?.discard()
            if (throwable is CancellationException) throw throwable
            AppResult.Failure(
                AppError(
                    code = "CARRIER_RECORDING_START",
                    userMessage = "真实通话录音启动失败",
                    detail = throwable.message,
                ),
            )
        }
    }

    suspend fun stop(callId: String): AppResult<SavedCarrierRecording> = mutex.withLock {
        val capture = activeCapture
            ?: return@withLock AppResult.Failure(
                AppError("CARRIER_RECORDING_NONE", "当前没有真实通话录音"),
            )
        if (capture.callId != callId) {
            return@withLock AppResult.Failure(
                AppError("CARRIER_RECORDING_MISMATCH", "停止录音的通话与当前通话不一致"),
            )
        }
        activeCapture = null
        capture.captureOpen.set(false)

        withContext(Dispatchers.IO) {
            runCatching { capture.remote.stopCapture() }
        }
        val finished = withTimeoutOrNull(STREAM_STOP_TIMEOUT_MILLIS) {
            capture.readerJob.join()
            true
        } ?: false
        if (!finished) {
            runCatching { capture.pipe.close() }
            capture.readerJob.cancelAndJoin()
        }
        capture.muxer.close()

        val usable = capture.muxer.mediaPacketCount > 0 &&
            capture.muxer.closeFailure == null
        if (!usable) {
            capture.target.discard()
            return@withLock AppResult.Failure(
                AppError(
                    code = "CARRIER_RECORDING_EMPTY",
                    userMessage = "真实通话录音没有获得可保存的音频",
                    detail = capture.streamError.get() ?: capture.muxer.closeFailure?.message,
                ),
            )
        }

        val publishError = runCatching { capture.target.publish() }.exceptionOrNull()
        if (publishError != null) {
            capture.target.discard()
            return@withLock AppResult.Failure(
                AppError(
                    code = "CARRIER_RECORDING_PUBLISH",
                    userMessage = "真实通话录音保存失败",
                    detail = publishError.message,
                ),
            )
        }
        AppResult.Success(
            SavedCarrierRecording(
                contentUri = capture.target.uri.toString(),
                displayName = capture.target.displayName,
                durationMillis = capture.muxer.durationMillis,
                packetCount = capture.muxer.mediaPacketCount,
            ),
        )
    }

    fun stopActiveAsync() {
        val callId = activeCapture?.callId ?: return
        scope.launch { stop(callId) }
    }

    private fun buildDisplayName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "CallDelegate_$timestamp.ogg"
    }

    private data class ActiveCapture(
        val callId: String,
        val remote: IShizukuCaptureService,
        val pipe: ParcelFileDescriptor,
        val target: MediaStoreRecordingTarget,
        val muxer: OpusRecordingMuxer,
        val readerJob: Job,
        val captureOpen: AtomicBoolean,
        val streamError: AtomicReference<String?>,
    )

    private companion object {
        const val TAG = "ShizukuCallRecorder"
        const val STREAM_START_TIMEOUT_MILLIS = 8_000L
        const val STREAM_STOP_TIMEOUT_MILLIS = 3_000L
    }
}
