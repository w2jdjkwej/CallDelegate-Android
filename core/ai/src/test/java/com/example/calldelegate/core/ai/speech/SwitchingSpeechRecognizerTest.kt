package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.core.ai.mock.MockSpeechRecognizer
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.SpeechRecognizer
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.RecognitionResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SwitchingSpeechRecognizerTest {
    @Test
    fun configure_selectsRealBackendOnlyWhenRequested() = runTest {
        val real = FakeRecognizer(isMock = false)
        val switching = SwitchingSpeechRecognizer(MockSpeechRecognizer(), real)

        switching.configure(mockMode = false)
        val result = switching.recognize(audio()) as AppResult.Success

        assertThat(result.value.isMock).isFalse()
        assertThat(real.initializeCount).isEqualTo(1)
    }

    @Test
    fun configure_realFailureIsNotHiddenByMockFallback() = runTest {
        val switching = SwitchingSpeechRecognizer(MockSpeechRecognizer(), FakeRecognizer(false, failInitialize = true))

        val result = switching.configure(mockMode = false)

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat(switching.isMock).isFalse()
    }

    private fun audio() = CapturedAudio(shortArrayOf(1), 16_000, 1, null)
}

private class FakeRecognizer(
    private val isMock: Boolean,
    private val failInitialize: Boolean = false,
) : SpeechRecognizer {
    var initializeCount = 0
    override suspend fun initialize(): AppResult<Unit> {
        initializeCount++
        return if (failInitialize) AppResult.Failure(com.example.calldelegate.core.common.AppError("FAIL", "failed"))
        else AppResult.Success(Unit)
    }
    override suspend fun recognize(audio: CapturedAudio) = AppResult.Success(RecognitionResult("text", 1f, isMock))
    override suspend fun release() = Unit
}
