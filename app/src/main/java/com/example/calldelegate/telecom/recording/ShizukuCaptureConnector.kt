package com.example.calldelegate.telecom.recording

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.example.calldelegate.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ShizukuCaptureConnector(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val mutex = Mutex()
    @Volatile private var service: IShizukuCaptureService? = null
    @Volatile private var connection: ServiceConnection? = null

    private val serviceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(
                applicationContext.packageName,
                ShizukuCaptureUserService::class.java.name,
            ),
        )
            .daemon(false)
            .processNameSuffix("CallCapture")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    suspend fun connect(): IShizukuCaptureService = mutex.withLock {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
        check(ShizukuStatus.current() == ShizukuSetupState.READY) {
            "Shizuku is not running or permission has not been granted"
        }

        suspendCancellableCoroutine { continuation ->
            val newConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                    if (binder == null) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException("Shizuku returned an empty service binder"),
                            )
                        }
                        return
                    }
                    val connected = IShizukuCaptureService.Stub.asInterface(binder)
                    service = connected
                    if (continuation.isActive) continuation.resume(connected)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                    connection = null
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("Shizuku capture service disconnected"),
                        )
                    }
                }
            }
            connection = newConnection
            try {
                Shizuku.bindUserService(serviceArgs, newConnection)
            } catch (throwable: Throwable) {
                connection = null
                continuation.resumeWithException(throwable)
            }
            continuation.invokeOnCancellation {
                runCatching {
                    Shizuku.unbindUserService(serviceArgs, newConnection, false)
                }
                if (connection === newConnection) connection = null
            }
        }
    }

    fun disconnect() {
        val activeConnection = connection ?: return
        runCatching { service?.stopCapture() }
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            runCatching {
                Shizuku.unbindUserService(serviceArgs, activeConnection, false)
            }
        }
        service = null
        connection = null
    }
}
