package com.example.calldelegate.core.common

import android.os.Trace

/** Uses system trace sections without retaining user content or benchmark samples. */
object PerformanceTrace {
    inline fun <T> section(name: String, block: () -> T): T {
        val started = begin(name)
        return try {
            block()
        } finally {
            end(started)
        }
    }

    suspend inline fun <T> suspendSection(name: String, block: suspend () -> T): T {
        val started = begin(name)
        return try {
            block()
        } finally {
            end(started)
        }
    }

    @PublishedApi
    internal fun begin(name: String): Boolean = runCatching {
        Trace.beginSection(name.take(MAX_SECTION_NAME_LENGTH))
    }.isSuccess

    @PublishedApi
    internal fun end(started: Boolean) {
        if (started) runCatching { Trace.endSection() }
    }

    @PublishedApi
    internal const val MAX_SECTION_NAME_LENGTH = 127
}
