package com.example.calldelegate.telecom

import com.example.calldelegate.domain.api.CallTransport
import com.example.calldelegate.domain.model.CallStatus

/** Stops the foreground service exactly once, after its session has left an active state. */
internal class SessionKeepAlivePolicy {
    private var wasActive = false
    private var stopRequested = false

    fun onStatus(status: CallStatus?): Boolean {
        if (stopRequested) {
            return false
        }
        val active = status == CallStatus.ACTIVE_AI || status == CallStatus.REQUESTING_TAKEOVER
        if (active) {
            wasActive = true
            return false
        }
        if (!wasActive) {
            return false
        }
        stopRequested = true
        return true
    }
}

/** Prevents duplicate start intents from replacing an already-owned automated session. */
internal class AutomatedSessionStartPolicy {
    private var started = false

    fun shouldStart(transport: CallTransport?): Boolean {
        if (transport == null || started) {
            return false
        }
        started = true
        return true
    }
}

internal fun shouldReleaseOnBackground(
    isChangingConfigurations: Boolean,
    serviceManaged: Boolean,
    status: CallStatus?,
): Boolean {
    val active = status == CallStatus.ACTIVE_AI || status == CallStatus.REQUESTING_TAKEOVER
    return !isChangingConfigurations && !serviceManaged && active
}

/** Android 13 and newer require POST_NOTIFICATIONS before posting the call notification. */
internal fun shouldPostCallNotification(
    sdkInt: Int,
    notificationPermissionGranted: Boolean,
): Boolean = sdkInt < 33 || notificationPermissionGranted

/** Clears a foreground notification left by an interrupted process when no session owns it. */
internal fun shouldClearStaleForegroundNotification(
    serviceManaged: Boolean,
    status: CallStatus?,
): Boolean {
    val active = status == CallStatus.ACTIVE_AI || status == CallStatus.REQUESTING_TAKEOVER
    return !serviceManaged && !active
}
