package com.example.calldelegate.core.common

fun interface Clock {
    fun nowEpochMillis(): Long
}

object SystemClock : Clock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}

object RetentionPolicy {
    const val DEFAULT_AUDIO_DAYS = 7
    const val DEFAULT_RECORD_DAYS = 30
    private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L

    fun audioExpired(endedAt: Long, now: Long, retentionDays: Int): Boolean =
        endedAt <= now - retentionDays.coerceAtLeast(1) * DAY_MILLIS

    fun recordExpired(endedAt: Long, now: Long, retentionDays: Int): Boolean =
        endedAt <= now - retentionDays.coerceAtLeast(1) * DAY_MILLIS

    fun remainingDays(endedAt: Long, now: Long, retentionDays: Int): Int {
        val expiresAt = endedAt + retentionDays.coerceAtLeast(1) * DAY_MILLIS
        return ((expiresAt - now).coerceAtLeast(0L) + DAY_MILLIS - 1L).div(DAY_MILLIS).toInt()
    }
}
