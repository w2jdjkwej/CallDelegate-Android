package com.example.calldelegate

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.calldelegate.core.ai.mock.MockSpeechRecognizer
import com.example.calldelegate.core.ai.mock.MockSpeechSynthesizer
import com.example.calldelegate.domain.model.CapturedAudio
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
/** Verifies benchmark plumbing with Mock modules only; it is not a real-model performance test. */
class MockPerformanceInstrumentationTest {
    @Test fun capturesMockInitializationTurnLatencyAndMemoryWithoutInventingResults() = runBlocking {
        val asr = MockSpeechRecognizer()
        val tts = MockSpeechSynthesizer()
        val memoryBefore = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }

        val asrStart = SystemClock.elapsedRealtimeNanos()
        asr.initialize()
        val asrInitMs = (SystemClock.elapsedRealtimeNanos() - asrStart) / 1_000_000L
        val ttsStart = SystemClock.elapsedRealtimeNanos()
        tts.initialize()
        val ttsInitMs = (SystemClock.elapsedRealtimeNanos() - ttsStart) / 1_000_000L

        val turnStart = SystemClock.elapsedRealtimeNanos()
        asr.recognize(CapturedAudio(ShortArray(16_000), 16_000, 1_000, null, "快递到了", true))
        tts.synthesize("好的，我已经记录。", "benchmark")
        val turnMs = (SystemClock.elapsedRealtimeNanos() - turnStart) / 1_000_000L
        val memoryAfter = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }

        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putLong("asr_init_ms", asrInitMs)
                putLong("tts_init_ms", ttsInitMs)
                putLong("mock_turn_ms", turnMs)
                putInt("pss_before_kb", memoryBefore.totalPss)
                putInt("pss_after_kb", memoryAfter.totalPss)
            },
        )
        assertTrue(asrInitMs >= 0 && ttsInitMs >= 0 && turnMs >= 0)
        asr.release()
        tts.release()
    }
}
