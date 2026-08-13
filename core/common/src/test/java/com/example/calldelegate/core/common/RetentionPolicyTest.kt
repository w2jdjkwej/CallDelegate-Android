package com.example.calldelegate.core.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionPolicyTest {
    @Test fun audioAndRecordUseDifferentCutoffs() {
        val day = 86_400_000L
        val now = 40 * day
        val endedAt = 20 * day

        assertTrue(RetentionPolicy.audioExpired(endedAt, now, 7))
        assertFalse(RetentionPolicy.recordExpired(endedAt, now, 30))
    }

    @Test fun cutoffIsInclusive() {
        val now = 31 * 86_400_000L
        assertTrue(RetentionPolicy.recordExpired(86_400_000L, now, 30))
    }
}
