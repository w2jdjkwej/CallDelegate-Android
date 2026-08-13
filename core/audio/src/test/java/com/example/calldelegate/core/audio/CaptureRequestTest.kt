package com.example.calldelegate.core.audio

import com.example.calldelegate.domain.model.CaptureRequest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CaptureRequestTest {
    @Test fun microphoneTurnAllowsUpToThirtySeconds() {
        assertThat(CaptureRequest("session").maxDurationMillis).isEqualTo(30_000L)
    }
}
