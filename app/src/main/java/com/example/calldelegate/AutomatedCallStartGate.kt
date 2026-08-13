package com.example.calldelegate

import com.example.calldelegate.domain.model.CallStatus

/** Actions the Activity performs after evaluating an automated-call start request. */
internal enum class AutomatedCallStartAction {
    NONE,
    REQUEST_PERMISSION,
    START_CALL,
    SHOW_PERMISSION_DENIED,
    SHOW_APP_SETTINGS,
}

/**
 * Pure state holder for microphone permission and duplicate-start handling.
 *
 * Android permission launchers and navigation stay in [MainActivity]; this class only decides which
 * single action is allowed next, which keeps repeated taps and stale permission results testable.
 */
internal class AutomatedCallStartGate {
    private var permissionRequestInFlight = false
    private var sessionStartIssued = false
    private var sessionObserved = false

    fun onStartRequested(hasRecordAudioPermission: Boolean): AutomatedCallStartAction {
        if (permissionRequestInFlight || sessionStartIssued) {
            return AutomatedCallStartAction.NONE
        }
        if (!hasRecordAudioPermission) {
            permissionRequestInFlight = true
            return AutomatedCallStartAction.REQUEST_PERMISSION
        }
        sessionStartIssued = true
        return AutomatedCallStartAction.START_CALL
    }

    fun onPermissionResult(granted: Boolean, canAskAgain: Boolean): AutomatedCallStartAction {
        if (!permissionRequestInFlight) {
            return AutomatedCallStartAction.NONE
        }
        permissionRequestInFlight = false
        if (granted) {
            sessionStartIssued = true
            return AutomatedCallStartAction.START_CALL
        }
        return if (canAskAgain) {
            AutomatedCallStartAction.SHOW_PERMISSION_DENIED
        } else {
            AutomatedCallStartAction.SHOW_APP_SETTINGS
        }
    }

    /** Allow another start only after the issued session was observed and then reached a terminal state. */
    fun onSessionStatus(status: CallStatus?) {
        if (status == CallStatus.RINGING || status == CallStatus.ACTIVE_AI ||
            status == CallStatus.REQUESTING_TAKEOVER || status == CallStatus.HUMAN_TAKEOVER
        ) {
            sessionObserved = true
            return
        }
        if (sessionObserved) {
            sessionObserved = false
            sessionStartIssued = false
        }
    }
}
