package com.example.calldelegate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.calldelegate.core.audio.WavSessionRecordingStore
import com.example.calldelegate.core.audio.BuiltInPresetRepository
import com.example.calldelegate.core.audio.PresetAudioInputSource
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.CaptureRequest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AudioLifecycleTest {
    @Test fun finalizeProducesWavAndDiscardReleasesTemporaryFiles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = WavSessionRecordingStore(context)
        val firstId = "audio-test-${System.nanoTime()}"
        store.appendPcm(firstId, ShortArray(16_000) { 100 }, 16_000)
        val finalized = store.finalizeSession(firstId)
        assertThat(finalized).isInstanceOf(AppResult.Success::class.java)
        val path = when (finalized) {
            is AppResult.Success -> finalized.value
            is AppResult.Failure -> error(finalized.error.userMessage)
        }
        assertThat(File(path!!).length()).isEqualTo(32_044L)
        File(path).delete()

        val interruptedId = "audio-interrupted-${System.nanoTime()}"
        store.appendPcm(interruptedId, ShortArray(400), 16_000)
        store.discardSession(interruptedId)
        assertThat(File(context.filesDir, "recordings/$interruptedId.pcm.tmp").exists()).isFalse()
    }

    @Test fun cancellingPresetCaptureUnblocksWithExplicitCancellation() = runBlocking {
        val source = PresetAudioInputSource(BuiltInPresetRepository())
        val capture = async { source.capture(CaptureRequest("cancel-test", presetId = "delivery_arrived")) }
        delay(20)
        source.cancel()

        val result = capture.await()

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat((result as AppResult.Failure).error.code).isEqualTo("AUDIO_CANCELLED")
    }
}
