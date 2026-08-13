package com.example.calldelegate.telecom

import android.content.pm.ServiceInfo
import com.example.calldelegate.domain.api.CallTransport
import com.example.calldelegate.domain.model.CallStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionKeepAlivePolicyTest {

    @Test
    fun initialIdleDoesNotStopService() {
        val policy = SessionKeepAlivePolicy()

        assertThat(policy.onStatus(null)).isFalse()
        assertThat(policy.onStatus(null)).isFalse()
    }

    @Test
    fun serviceStopsOnceAfterActiveSessionEnds() {
        val policy = SessionKeepAlivePolicy()

        assertThat(policy.onStatus(null)).isFalse()
        assertThat(policy.onStatus(CallStatus.ACTIVE_AI)).isFalse()
        assertThat(policy.onStatus(CallStatus.COMPLETED)).isTrue()
        assertThat(policy.onStatus(CallStatus.COMPLETED)).isFalse()
    }

    @Test
    fun takeoverRequestKeepsServiceAlive() {
        val policy = SessionKeepAlivePolicy()

        assertThat(policy.onStatus(CallStatus.REQUESTING_TAKEOVER)).isFalse()
        assertThat(policy.onStatus(CallStatus.HUMAN_TAKEOVER)).isTrue()
    }
}

class AutomatedSessionStartPolicyTest {

    @Test
    fun validTransportStartsOnlyOnce() {
        val policy = AutomatedSessionStartPolicy()

        assertThat(policy.shouldStart(CallTransport.SIMULATED)).isTrue()
        assertThat(policy.shouldStart(CallTransport.SIMULATED)).isFalse()
        assertThat(policy.shouldStart(CallTransport.TELECOM)).isFalse()
    }

    @Test
    fun missingTransportDoesNotConsumeStart() {
        val policy = AutomatedSessionStartPolicy()

        assertThat(policy.shouldStart(null)).isFalse()
        assertThat(policy.shouldStart(CallTransport.SIMULATED)).isTrue()
    }

    @Test
    fun freshServiceCanStartTelecomSession() {
        val policy = AutomatedSessionStartPolicy()

        assertThat(policy.shouldStart(CallTransport.TELECOM)).isTrue()
        assertThat(policy.shouldStart(CallTransport.TELECOM)).isFalse()
    }
}

class SessionForegroundServiceTypeTest {

    @Test
    fun telecomUsesPhoneCallTypeWithoutMicrophonePermission() {
        assertThat(foregroundServiceTypeFor(CallTransport.TELECOM))
            .isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
    }

    @Test
    fun simulatedAndMissingTransportUseMicrophoneType() {
        assertThat(foregroundServiceTypeFor(CallTransport.SIMULATED))
            .isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        assertThat(foregroundServiceTypeFor(null))
            .isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }
}

class BackgroundReleaseGuardTest {

    @Test
    fun releasesOnlyLegacyActiveSessionInBackground() {
        assertThat(shouldReleaseOnBackground(false, false, CallStatus.ACTIVE_AI)).isTrue()
        assertThat(shouldReleaseOnBackground(false, false, CallStatus.REQUESTING_TAKEOVER)).isTrue()

        assertThat(shouldReleaseOnBackground(true, false, CallStatus.ACTIVE_AI)).isFalse()
        assertThat(shouldReleaseOnBackground(false, true, CallStatus.ACTIVE_AI)).isFalse()
        assertThat(shouldReleaseOnBackground(false, false, CallStatus.COMPLETED)).isFalse()
        assertThat(shouldReleaseOnBackground(false, false, null)).isFalse()
    }
}

class CallNotificationPolicyTest {

    @Test
    fun notificationPermissionIsRequiredStartingWithAndroid13() {
        assertThat(shouldPostCallNotification(32, false)).isTrue()
        assertThat(shouldPostCallNotification(33, false)).isFalse()
        assertThat(shouldPostCallNotification(34, false)).isFalse()
        assertThat(shouldPostCallNotification(36, true)).isTrue()
    }

    @Test
    fun staleForegroundNotificationIsClearedOnlyWithoutAnActiveOwner() {
        assertThat(shouldClearStaleForegroundNotification(false, null)).isTrue()
        assertThat(shouldClearStaleForegroundNotification(false, CallStatus.COMPLETED)).isTrue()

        assertThat(shouldClearStaleForegroundNotification(true, null)).isFalse()
        assertThat(shouldClearStaleForegroundNotification(false, CallStatus.ACTIVE_AI)).isFalse()
        assertThat(shouldClearStaleForegroundNotification(false, CallStatus.REQUESTING_TAKEOVER)).isFalse()
    }
}
