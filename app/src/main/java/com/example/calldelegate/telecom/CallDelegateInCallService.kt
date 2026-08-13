package com.example.calldelegate.telecom

import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import com.example.calldelegate.core.audio.telecom.TelecomCallRegistry
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.SettingsRepository
import com.example.calldelegate.domain.api.CallTransport
import com.example.calldelegate.domain.api.ExternalCallState
import com.example.calldelegate.telecom.recording.CarrierRecordingNotifier
import com.example.calldelegate.telecom.recording.ShizukuCarrierCallRecorder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.IdentityHashMap
import javax.inject.Inject

/**
 * Telecom entry point for call control, privileged audio capture, and real incoming AI sessions.
 *
 * Owns the `Call` <-> [AndroidTelecomCallHandle] identity mapping and forwards lifecycle events to
 * the process-wide [TelecomCallRegistry]. Audio processing remains in the injected recorder/bridge;
 * this service only starts their lifecycle from real Telecom state.
 */
@AndroidEntryPoint
class CallDelegateInCallService : InCallService() {

    @Inject lateinit var registry: TelecomCallRegistry
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var carrierCallRecorder: ShizukuCarrierCallRecorder

    private val handles = IdentityHashMap<Call, AndroidTelecomCallHandle>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var carrierRecordingCallId: String? = null

    override fun onCreate() {
        super.onCreate()
        observeForCarrierRecording()
    }

    private fun observeForCarrierRecording() {
        serviceScope.launch {
            registry.callState.collect { snapshot ->
                val active = snapshot?.takeIf { it.state == ExternalCallState.ACTIVE }
                if (active == null) {
                    stopCarrierRecording()
                    return@collect
                }

                val currentCallId = carrierRecordingCallId
                if (currentCallId != null && currentCallId != active.callId) {
                    stopCarrierRecording()
                }
                if (carrierRecordingCallId == null &&
                    settings.current().carrierCallRecordingEnabled
                ) {
                    when (val result = carrierCallRecorder.start(active.callId)) {
                        is AppResult.Success -> {
                            carrierRecordingCallId = active.callId
                            Log.i(TAG, "Shizuku carrier recording started")
                        }
                        is AppResult.Failure -> {
                            Log.w(TAG, "carrier recording start failed: ${result.error.code}")
                            CarrierRecordingNotifier.failed(this@CallDelegateInCallService, result.error.userMessage)
                        }
                    }
                }
            }
        }
    }

    private suspend fun stopCarrierRecording() {
        val callId = carrierRecordingCallId ?: return
        carrierRecordingCallId = null
        when (val result = carrierCallRecorder.stop(callId)) {
            is AppResult.Success -> {
                Log.i(
                    TAG,
                    "carrier recording saved: uri=${result.value.contentUri} " +
                        "durationMs=${result.value.durationMillis} packets=${result.value.packetCount}",
                )
                CarrierRecordingNotifier.saved(this, result.value)
            }
            is AppResult.Failure -> {
                Log.w(TAG, "carrier recording stop failed: ${result.error.code}")
                CarrierRecordingNotifier.failed(this, result.error.userMessage)
            }
        }
    }

    override fun onCallAdded(call: Call) {
        val handle = AndroidTelecomCallHandle(call)
        handles[call] = handle
        Log.i(TAG, "onCallAdded callId=${handle.id.take(8)} incoming=${handle.isIncoming}")
        registry.onCallAdded(handle)
        // Bare startActivity from a service is blocked by BAL / OEM background-launch gates, so use
        // the sanctioned full-screen-intent notification to surface the in-call UI.
        CallNotifier.showActiveCall(this)
        if (handle.isIncoming) startAutomatedSessionIfEnabled(handle)
    }

    /**
     * Raises the automated session for an incoming call, so that nobody has to press anything.
     *
     * This is only the trigger. The wait before the call is picked up belongs to
     * [com.example.calldelegate.core.ai.coordination.AutomatedCallSessionBridge], which is also
     * what re-checks that the call is still ringing when the wait is over -- starting the session
     * here does not by itself answer anything.
     *
     * Carrier recording is turned on for the same reason the manual AI-answer button turns it on:
     * on this device the assistant hears the caller through the privileged capture path, so a
     * session raised without it would answer the call and then have nothing to listen to.
     */
    private fun startAutomatedSessionIfEnabled(handle: AndroidTelecomCallHandle) {
        serviceScope.launch {
            if (!settings.current().autoAnswerEnabled) {
                Log.i(TAG, "auto-answer: off in settings, leaving the call to be answered by hand")
                return@launch
            }
            if (!settings.current().carrierCallRecordingEnabled) {
                val enabled = settings.update { it.copy(carrierCallRecordingEnabled = true) }
                if (enabled is AppResult.Failure) {
                    Log.w(TAG, "auto-answer: cannot enable carrier recording: ${enabled.error.code}")
                    return@launch
                }
            }
            val started = runCatching {
                CallSessionService.startAutomated(
                    context = this@CallDelegateInCallService,
                    transport = CallTransport.TELECOM,
                    callerNumber = handle.callerNumber,
                )
            }
            if (started.isFailure) {
                Log.w(TAG, "auto-answer: session did not start: ${started.exceptionOrNull()}")
            } else {
                Log.i(TAG, "auto-answer: automated session raised for callId=${handle.id.take(8)}")
            }
        }
    }

    override fun onBringToForeground(showDialpad: Boolean) {
        Log.i(TAG, "onBringToForeground showDialpad=$showDialpad calls=${handles.size}")
        if (!CallNotifier.openActiveCall(this, showDialpad)) {
            // Keep a tappable recovery path if an OEM background-launch restriction rejects the
            // direct system-requested launch.
            CallNotifier.showActiveCall(this)
        }
    }

    override fun onCallRemoved(call: Call) {
        val handle = handles.remove(call) ?: return
        Log.i(TAG, "onCallRemoved callId=${handle.id.take(8)}")
        registry.onCallRemoved(handle)
        handle.dispose()
        if (handles.isEmpty()) {
            CallNotifier.clear(this)
        }
    }

    override fun onDestroy() {
        carrierCallRecorder.stopActiveAsync()
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "CallDelegateICS"
    }
}
