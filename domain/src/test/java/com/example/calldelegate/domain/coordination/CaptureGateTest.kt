package com.example.calldelegate.domain.coordination

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CaptureGateTest {

    @Test
    fun startsOpen() {
        assertThat(CaptureGate().isOpen.value).isTrue()
    }

    @Test
    fun pauseAndResumeToggleState() {
        val gate = CaptureGate()
        gate.pause()
        assertThat(gate.isOpen.value).isFalse()
        gate.resume()
        assertThat(gate.isOpen.value).isTrue()
    }

    @Test
    fun awaitOpenReturnsImmediatelyWhenOpen() = runTest {
        val gate = CaptureGate()
        var passed: Boolean
        gate.awaitOpen()
        passed = true
        assertThat(passed).isTrue()
    }

    @Test
    fun awaitOpenSuspendsUntilResumed() = runTest {
        val gate = CaptureGate()
        gate.pause()
        var released = false
        val waiter = launch {
            gate.awaitOpen()
            released = true
        }
        // Still closed → the waiter must not have completed.
        assertThat(released).isFalse()
        gate.resume()
        waiter.join()
        assertThat(released).isTrue()
    }
}
