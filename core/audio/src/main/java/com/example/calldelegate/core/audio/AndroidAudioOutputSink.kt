package com.example.calldelegate.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.core.common.PerformanceTrace
import com.example.calldelegate.domain.api.AudioPlaybackMetrics
import com.example.calldelegate.domain.api.AudioOutputSink
import com.example.calldelegate.domain.api.AudioState
import com.example.calldelegate.domain.api.PlaybackMetricsSource
import com.example.calldelegate.domain.model.SynthesizedSpeech
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class AndroidAudioOutputSink : AudioOutputSink, PlaybackMetricsSource {
    private val mutableState = MutableStateFlow<AudioState>(AudioState.Idle)
    override val state: StateFlow<AudioState> = mutableState.asStateFlow()
    private val mutableLatestPlaybackMetrics = MutableStateFlow<AudioPlaybackMetrics?>(null)
    override val latestPlaybackMetrics: StateFlow<AudioPlaybackMetrics?> = mutableLatestPlaybackMetrics.asStateFlow()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var player: MediaPlayer? = null
    private var playerCompletion: CancellableContinuation<AppResult<Unit>>? = null
    private val trackLock = Any()
    private val playerLock = Any()

    override suspend fun play(speech: SynthesizedSpeech): AppResult<Unit> {
        speech.audioPath?.let { return playFile(it) }
        if (speech.pcm16.isEmpty()) return AppResult.Success(Unit)
        val requestedAt = SystemClock.elapsedRealtime()
        stop()
        return PerformanceTrace.suspendSection("audio_playback") {
            withContext(Dispatchers.IO) {
                mutableState.value = AudioState.Playing
                var firstWriteAt: Long? = null
                var playbackStartedAt: Long? = null
                val result = runCatching {
                    val minBuffer = AudioTrack.getMinBufferSize(
                        speech.sampleRateHz,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                    val streamBufferSizeBytes = streamBufferSizeBytes(minBuffer, speech.sampleRateHz)
                    val audioTrack = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
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
                        .setBufferSizeInBytes(streamBufferSizeBytes)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    try {
                        synchronized(trackLock) {
                            track = audioTrack
                            audioTrack.play()
                        }
                        playbackStartedAt = SystemClock.elapsedRealtime()
                        val writeChunkSamples = streamWriteChunkSamples(streamBufferSizeBytes)
                        val playbackGain = AdaptivePcmGain.calculate(speech.pcm16)
                        val amplifiedChunk = if (playbackGain > 1f) {
                            ShortArray(writeChunkSamples)
                        } else {
                            null
                        }
                        Log.i(TAG, "Local TTS gain=${playbackGain}x")
                        var writeOffset = 0
                        while (
                            writeOffset < speech.pcm16.size &&
                            synchronized(trackLock) { track === audioTrack }
                        ) {
                            val sampleCount = minOf(writeChunkSamples, speech.pcm16.size - writeOffset)
                            if (amplifiedChunk != null) {
                                var index = 0
                                while (index < sampleCount) {
                                    amplifiedChunk[index] = AdaptivePcmGain.apply(
                                        speech.pcm16[writeOffset + index],
                                        playbackGain,
                                    )
                                    index += 1
                                }
                            }
                            val written = try {
                                if (amplifiedChunk == null) {
                                    audioTrack.write(
                                        speech.pcm16,
                                        writeOffset,
                                        sampleCount,
                                        AudioTrack.WRITE_BLOCKING,
                                    )
                                } else {
                                    audioTrack.write(
                                        amplifiedChunk,
                                        0,
                                        sampleCount,
                                        AudioTrack.WRITE_BLOCKING,
                                    )
                                }
                            } catch (error: Exception) {
                                if (synchronized(trackLock) { track !== audioTrack }) break
                                throw error
                            }
                            if (written <= 0) {
                                if (synchronized(trackLock) { track !== audioTrack }) break
                                error("AudioTrack stream write failed: $written")
                            }
                            if (firstWriteAt == null) firstWriteAt = SystemClock.elapsedRealtime()
                            writeOffset += written
                        }
                        val elapsedPlaybackMillis = playbackStartedAt?.let {
                            SystemClock.elapsedRealtime() - it
                        } ?: 0L
                        var remainingMillis = remainingPlaybackWaitMillis(
                            speech.durationMillis,
                            elapsedPlaybackMillis,
                        )
                        while (remainingMillis > 0L && synchronized(trackLock) { track === audioTrack }) {
                            val slice = minOf(remainingMillis, 25L)
                            delay(slice)
                            remainingMillis -= slice
                        }
                    } finally {
                        synchronized(trackLock) {
                            // stop() may already have released this instance while the coroutine was suspended.
                            if (track === audioTrack) track = null
                            runCatching { audioTrack.stop() }
                            runCatching { audioTrack.release() }
                        }
                    }
                }.fold(
                    onSuccess = { mutableState.value = AudioState.Idle; AppResult.Success(Unit) },
                    onFailure = {
                        if (it is CancellationException) throw it
                        mutableState.value = AudioState.Error("播放失败")
                        AppResult.Failure(AppError("AUDIO_PLAY", "语音播放失败", it.message))
                    },
                )
                mutableLatestPlaybackMetrics.value = AudioPlaybackMetrics(
                    requestedAtElapsedRealtimeMs = requestedAt,
                    firstAudioWriteAtElapsedRealtimeMs = firstWriteAt,
                    playbackStartedAtElapsedRealtimeMs = playbackStartedAt,
                    playbackCompletedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                    outputMode = "PCM_STREAM",
                    errorCode = (result as? AppResult.Failure)?.error?.code,
                )
                result
            }
        }
    }

    override suspend fun playFile(path: String): AppResult<Unit> {
        if (!File(path).isFile) return AppResult.Failure(AppError("AUDIO_MISSING", "录音文件不存在"))
        val requestedAt = SystemClock.elapsedRealtime()
        stop()
        mutableState.value = AudioState.Playing
        return PerformanceTrace.suspendSection("audio_playback") {
            suspendCancellableCoroutine { continuation ->
                val mediaPlayer = MediaPlayer()
                synchronized(playerLock) {
                    player = mediaPlayer
                    playerCompletion = continuation
                }
                continuation.invokeOnCancellation {
                    synchronized(playerLock) {
                        if (player === mediaPlayer) player = null
                        if (playerCompletion === continuation) playerCompletion = null
                    }
                    runCatching { mediaPlayer.release() }
                }
                runCatching {
                    mediaPlayer.setDataSource(path)
                    mediaPlayer.setOnPreparedListener {
                        runCatching {
                            it.start()
                            mutableLatestPlaybackMetrics.value = AudioPlaybackMetrics(
                                requestedAtElapsedRealtimeMs = requestedAt,
                                firstAudioWriteAtElapsedRealtimeMs = null,
                                playbackStartedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                                playbackCompletedAtElapsedRealtimeMs = null,
                                outputMode = "MEDIA_FILE",
                            )
                        }
                    }
                    mediaPlayer.setOnCompletionListener {
                        val owned = synchronized(playerLock) {
                            if (player === it && playerCompletion === continuation) {
                                player = null
                                playerCompletion = null
                                true
                            } else false
                        }
                        if (!owned) return@setOnCompletionListener
                        it.release()
                        mutableState.value = AudioState.Idle
                        mutableLatestPlaybackMetrics.value = latestFileMetrics(requestedAt, null)
                        if (continuation.isActive) continuation.resume(AppResult.Success(Unit))
                    }
                    mediaPlayer.setOnErrorListener { mp, what, extra ->
                        val owned = synchronized(playerLock) {
                            if (player === mp && playerCompletion === continuation) {
                                player = null
                                playerCompletion = null
                                true
                            } else false
                        }
                        if (!owned) return@setOnErrorListener true
                        mp.release()
                        mutableState.value = AudioState.Error("播放失败")
                        mutableLatestPlaybackMetrics.value = latestFileMetrics(requestedAt, "AUDIO_PLAY")
                        if (continuation.isActive) continuation.resume(
                            AppResult.Failure(AppError("AUDIO_PLAY", "录音播放失败", "$what/$extra")),
                        )
                        true
                    }
                    mediaPlayer.prepareAsync()
                }.onFailure { throwable ->
                    val owned = synchronized(playerLock) {
                        if (player === mediaPlayer && playerCompletion === continuation) {
                            player = null
                            playerCompletion = null
                            true
                        } else false
                    }
                    if (owned) {
                        mediaPlayer.release()
                        mutableState.value = AudioState.Error("播放失败")
                        mutableLatestPlaybackMetrics.value = latestFileMetrics(requestedAt, "AUDIO_PLAY")
                        if (continuation.isActive) continuation.resume(
                            AppResult.Failure(AppError("AUDIO_PLAY", "录音播放失败", throwable.message)),
                        )
                    }
                }
            }
        }
    }

    private fun latestFileMetrics(requestedAt: Long, errorCode: String?): AudioPlaybackMetrics {
        val current = mutableLatestPlaybackMetrics.value
        return AudioPlaybackMetrics(
            requestedAtElapsedRealtimeMs = requestedAt,
            firstAudioWriteAtElapsedRealtimeMs = null,
            playbackStartedAtElapsedRealtimeMs = current?.playbackStartedAtElapsedRealtimeMs,
            playbackCompletedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            outputMode = "MEDIA_FILE",
            errorCode = errorCode,
        )
    }

    override suspend fun stop() {
        synchronized(trackLock) {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            track = null
        }
        val (activePlayer, completion) = synchronized(playerLock) {
            val values = player to playerCompletion
            player = null
            playerCompletion = null
            values
        }
        runCatching { activePlayer?.stop() }
        runCatching { activePlayer?.release() }
        if (completion?.isActive == true) completion.resume(AppResult.Success(Unit))
        mutableState.value = AudioState.Idle
    }

    override suspend fun release() = stop()
}

private const val STREAM_BUFFER_DURATION_MILLIS = 100L
private const val TAG = "AndroidAudioOutput"

internal fun streamBufferSizeBytes(minBufferSizeBytes: Int, sampleRateHz: Int): Int {
    val targetBytes = (
        sampleRateHz.coerceAtLeast(1).toLong() * Short.SIZE_BYTES * STREAM_BUFFER_DURATION_MILLIS / 1_000L
        ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return maxOf(minBufferSizeBytes, targetBytes, Short.SIZE_BYTES)
}

internal fun streamWriteChunkSamples(streamBufferSizeBytes: Int): Int =
    (streamBufferSizeBytes / Short.SIZE_BYTES).coerceAtLeast(1)

internal fun remainingPlaybackWaitMillis(durationMillis: Long, elapsedPlaybackMillis: Long): Long =
    (durationMillis.coerceAtLeast(0L) - elapsedPlaybackMillis.coerceAtLeast(0L)).coerceAtLeast(0L)
