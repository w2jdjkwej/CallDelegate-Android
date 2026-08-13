package com.example.calldelegate.telecom

import android.telecom.Call
import android.telecom.VideoProfile
import com.example.calldelegate.core.audio.telecom.TelecomCallHandle
import java.util.UUID

/**
 * Android binding of [TelecomCallHandle]. Assigns its own UUID (does NOT rely on the hidden
 * telecom call id via reflection) and forwards `Call.Callback` state changes to a listener.
 */
@Suppress("DEPRECATION")
class AndroidTelecomCallHandle(private val call: Call) : TelecomCallHandle {

    override val id: String = UUID.randomUUID().toString()

    override val callerNumber: String? = call.details?.handle?.schemeSpecificPart

    override val isIncoming: Boolean =
        call.details?.callDirection == Call.Details.DIRECTION_INCOMING

    override val currentState: Int
        get() = call.details?.state ?: call.state

    private var callback: Call.Callback? = null
    private var listener: ((Int) -> Unit)? = null

    override fun answer() {
        runCatching { call.answer(VideoProfile.STATE_AUDIO_ONLY) }
    }

    override fun reject() {
        runCatching { call.reject(false, null) }
    }

    override fun disconnect() {
        runCatching { call.disconnect() }
    }

    override fun setStateListener(listener: ((Int) -> Unit)?) {
        this.listener = listener
        if (listener == null) {
            callback?.let { runCatching { call.unregisterCallback(it) } }
            callback = null
            return
        }
        if (callback != null) return
        val cb = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                this@AndroidTelecomCallHandle.listener?.invoke(state)
            }

            override fun onDetailsChanged(call: Call, details: Call.Details) {
                this@AndroidTelecomCallHandle.listener?.invoke(details.state)
            }
        }
        callback = cb
        call.registerCallback(cb)
    }

    fun dispose() = setStateListener(null)
}
