package com.example.calldelegate.core.audio.telecom

/**
 * Thin abstraction over `android.telecom.Call` so that the single-active-call / idempotency /
 * control-validation logic in [TelecomCallRegistry] stays pure Kotlin and JVM-testable.
 *
 * The concrete Android binding (registering `Call.Callback`, reading `Call.Details`) lives in the
 * `:app` module next to the `InCallService`.
 */
interface TelecomCallHandle {
    /** Stable per-call id assigned by the binder at onCallAdded (UUID, NOT the hidden telecom id). */
    val id: String

    /** May be null/hidden depending on the device and privacy settings. */
    val callerNumber: String?

    val isIncoming: Boolean

    /** Current `android.telecom.Call.STATE_*` value. */
    val currentState: Int

    fun answer()
    fun reject()
    fun disconnect()

    /** Register/unregister a state listener. Passing null must detach the underlying callback. */
    fun setStateListener(listener: ((Int) -> Unit)?)
}
