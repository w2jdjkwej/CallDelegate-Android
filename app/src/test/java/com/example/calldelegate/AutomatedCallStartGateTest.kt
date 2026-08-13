package com.example.calldelegate

import com.example.calldelegate.domain.model.CallStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutomatedCallStartGateTest {

    @Test
    fun grantedPermissionStartsOnceUntilSessionEnds() {
        val gate = AutomatedCallStartGate()

        assertThat(gate.onStartRequested(hasRecordAudioPermission = true))
            .isEqualTo(AutomatedCallStartAction.START_CALL)
        assertThat(gate.onStartRequested(hasRecordAudioPermission = true))
            .isEqualTo(AutomatedCallStartAction.NONE)

        gate.onSessionStatus(CallStatus.RINGING)
        gate.onSessionStatus(CallStatus.COMPLETED)

        assertThat(gate.onStartRequested(hasRecordAudioPermission = true))
            .isEqualTo(AutomatedCallStartAction.START_CALL)
    }

    @Test
    fun missingPermissionRequestsOnceWithoutStarting() {
        val gate = AutomatedCallStartGate()

        assertThat(gate.onStartRequested(hasRecordAudioPermission = false))
            .isEqualTo(AutomatedCallStartAction.REQUEST_PERMISSION)
        assertThat(gate.onStartRequested(hasRecordAudioPermission = false))
            .isEqualTo(AutomatedCallStartAction.NONE)
    }

    @Test
    fun grantedResultStartsAndDuplicateResultIsIgnored() {
        val gate = AutomatedCallStartGate()
        gate.onStartRequested(hasRecordAudioPermission = false)

        assertThat(gate.onPermissionResult(granted = true, canAskAgain = true))
            .isEqualTo(AutomatedCallStartAction.START_CALL)
        assertThat(gate.onPermissionResult(granted = true, canAskAgain = true))
            .isEqualTo(AutomatedCallStartAction.NONE)
    }

    @Test
    fun deniedPermissionCanBeRequestedAgain() {
        val gate = AutomatedCallStartGate()
        gate.onStartRequested(hasRecordAudioPermission = false)

        assertThat(gate.onPermissionResult(granted = false, canAskAgain = true))
            .isEqualTo(AutomatedCallStartAction.SHOW_PERMISSION_DENIED)
        assertThat(gate.onStartRequested(hasRecordAudioPermission = false))
            .isEqualTo(AutomatedCallStartAction.REQUEST_PERMISSION)
    }

    @Test
    fun permanentDenialOffersAppSettings() {
        val gate = AutomatedCallStartGate()
        gate.onStartRequested(hasRecordAudioPermission = false)

        assertThat(gate.onPermissionResult(granted = false, canAskAgain = false))
            .isEqualTo(AutomatedCallStartAction.SHOW_APP_SETTINGS)
    }
}
