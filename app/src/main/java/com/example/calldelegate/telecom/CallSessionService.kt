package com.example.calldelegate.telecom

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.calldelegate.core.ai.coordination.AutomatedCallSessionBridge
import com.example.calldelegate.core.ai.coordination.ExternalCallCoordinator
import com.example.calldelegate.core.audio.SimulatedCallSource
import com.example.calldelegate.domain.api.CallSessionController
import com.example.calldelegate.domain.api.CallTransport
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground owner of an automated AI call session (Stage 4 M4).
 *
 * Moving ownership off the Activity is what fixes "backgrounding kills the call": while this service
 * runs foreground (with a `microphone` service type), the [ExternalCallCoordinator] + AI turn loop
 * keep running regardless of whether any Activity is visible. The service self-stops once the
 * session leaves the active state (dialogue end, user hang up, or error).
 *
 * It does NOT auto-end anything itself — hang-up is driven through the coordinator/controller.
 */
@AndroidEntryPoint
class CallSessionService : Service() {

    @Inject lateinit var coordinator: ExternalCallCoordinator
    @Inject lateinit var bridge: AutomatedCallSessionBridge
    @Inject lateinit var controller: CallSessionController
    @Inject lateinit var simulatedSource: SimulatedCallSource

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null
    private var started = false
    private var activeTransport: CallTransport? = null
    private val keepAlivePolicy = SessionKeepAlivePolicy()
    private val sessionStartPolicy = AutomatedSessionStartPolicy()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val transport = intent?.getStringExtra(EXTRA_TRANSPORT)
            ?.let { name -> runCatching { CallTransport.valueOf(name) }.getOrNull() }
        if (transport != null) activeTransport = transport
        startForegroundCompat(activeTransport)
        if (!started) {
            started = true
            bridge.start()
            observeUntilSessionEnds()
        }
        // A transport extra means "begin an automated call on this transport".
        if (transport != null && sessionStartPolicy.shouldStart(transport)) {
            coordinator.start(transport)
            if (transport == CallTransport.SIMULATED) {
                simulatedSource.ringIncoming(callerNumber = intent.getStringExtra(EXTRA_NUMBER))
            }
        }
        return START_NOT_STICKY
    }

    /** Stop the service once the session has been active and then left the active state. */
    private fun observeUntilSessionEnds() {
        observerJob = scope.launch {
            controller.state.collect { snapshot ->
                if (keepAlivePolicy.onStatus(snapshot.callStatus)) {
                    Log.i(TAG, "session ended -> stopping foreground service")
                    stopSelf()
                }
            }
        }
    }

    private fun startForegroundCompat(transport: CallTransport?) {
        val notification = CallNotifier.foregroundNotification(this, transport)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = foregroundServiceTypeFor(transport)
            startForeground(
                CallNotifier.FOREGROUND_ID,
                notification,
                serviceType,
            )
            Log.i(TAG, "foreground session started: transport=$transport type=$serviceType")
        } else {
            startForeground(CallNotifier.FOREGROUND_ID, notification)
        }
    }

    override fun onDestroy() {
        observerJob?.cancel()
        scope.cancel()
        bridge.stop()
        coordinator.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CallSessionService"
        private const val ACTION_START = "com.example.calldelegate.action.START_SESSION"
        private const val ACTION_STOP = "com.example.calldelegate.action.STOP_SESSION"
        private const val EXTRA_TRANSPORT = "transport"
        private const val EXTRA_NUMBER = "number"

        /** Start (or ramp up) an automated AI call on [transport]. Safe to call from the foreground. */
        fun startAutomated(context: Context, transport: CallTransport, callerNumber: String? = null) {
            val intent = Intent(context, CallSessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TRANSPORT, transport.name)
                putExtra(EXTRA_NUMBER, callerNumber)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallSessionService::class.java))
        }
    }
}

internal fun foregroundServiceTypeFor(transport: CallTransport?): Int =
    if (transport == CallTransport.TELECOM) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
    } else {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    }
