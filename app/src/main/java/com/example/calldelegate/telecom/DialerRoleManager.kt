package com.example.calldelegate.telecom

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.TelecomManager

/**
 * Default-dialer (ROLE_DIALER) request helper. Prefers RoleManager (API 29+) and falls back to
 * TelecomManager.ACTION_CHANGE_DEFAULT_DIALER. The final consent is always a system dialog; this
 * helper only builds the intent and reports status.
 */
object DialerRoleManager {

    fun isDefaultDialer(context: Context): Boolean {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return false
        return runCatching { telecom.defaultDialerPackage == context.packageName }.getOrDefault(false)
    }

    /** Returns an intent to launch for requesting the default-dialer role, or null if unavailable. */
    fun createRequestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
            ) {
                return runCatching { roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER) }.getOrNull()
            }
        }
        val fallback = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
            .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
        return if (fallback.resolveActivity(context.packageManager) != null) fallback else null
    }
}
