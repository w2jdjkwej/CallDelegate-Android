package com.example.calldelegate.testing.wav

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small non-queueing gate used to enforce one active WAV test task. */
internal class WavCallRunGate {
    private val mutex = Mutex()
    private var occupied = false

    suspend fun tryAcquire(): Boolean = mutex.withLock {
        if (occupied) return@withLock false
        occupied = true
        true
    }

    suspend fun release() = mutex.withLock {
        occupied = false
    }
}
