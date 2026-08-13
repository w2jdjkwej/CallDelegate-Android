package com.example.calldelegate.telecom

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.calldelegate.MainActivity
import com.example.calldelegate.R
import com.example.calldelegate.domain.api.CallTransport

/**
 * Sanctioned in-call UI launcher for the default dialer.
 *
 * A bare `startActivity` from [CallDelegateInCallService.onCallAdded] is blocked by background
 * activity-launch (BAL) restrictions on Android 14+ and by OEM "background pop-up" gates (e.g.
 * MIUI). The supported path is a high-importance notification carrying a full-screen intent with
 * [NotificationCompat.CATEGORY_CALL]; the system then launches [CallActivity] itself (over the
 * lockscreen when needed) or shows a heads-up the user can tap.
 */
object CallNotifier {
    private const val CHANNEL_ID = "call_delegate_active_call"
    private const val NOTIFICATION_ID = 4610
    private const val TAG = "CallNotifier"

    /** Foreground-service notification id for [CallSessionService]. */
    const val FOREGROUND_ID = 4611

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.call_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.call_channel_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun showActiveCall(context: Context) {
        val permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!shouldPostCallNotification(Build.VERSION.SDK_INT, permissionGranted)) {
            return
        }
        ensureChannel(context)
        val fullScreenIntent = activeCallIntent(context)
        val pending = PendingIntent.getActivity(
            context,
            0,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.call_notification_title))
            .setContentText(context.getString(R.string.call_notification_text))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission can still be revoked between the explicit check and notify().
        }
    }

    /**
     * Brings our ongoing-call UI to the foreground when Telecom asks the default dialer to do so.
     */
    fun openActiveCall(context: Context, showDialpad: Boolean): Boolean {
        return try {
            context.startActivity(activeCallIntent(context, showDialpad))
            true
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to bring the in-call UI to the foreground", error)
            false
        }
    }

    private fun activeCallIntent(context: Context, showDialpad: Boolean = false): Intent =
        Intent(context, CallActivity::class.java)
            .putExtra(CallActivity.EXTRA_SHOW_DIALPAD, showDialpad)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )

    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    fun clearForeground(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(FOREGROUND_ID) }
    }

    /**
     * Ongoing notification for the [CallSessionService] foreground state. Tapping it opens the
     * in-call UI. The service keeps the AI session alive independent of the Activity lifecycle.
     */
    fun foregroundNotification(context: Context, transport: CallTransport?): Notification {
        ensureChannel(context)
        val open = if (transport == CallTransport.TELECOM) {
            activeCallIntent(context)
        } else {
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_AUTOMATED_CALL, true)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
        }
        val pending = PendingIntent.getActivity(
            context,
            1,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.call_notification_title))
            .setContentText(context.getString(R.string.call_notification_text))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    /**
     * Whether the OS will honor our full-screen intent (auto-launch [CallActivity]) rather than
     * demoting it to a heads-up. Always true before Android 14, where the appop did not exist.
     */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canUseFullScreenIntent()
    }

    /** Settings intent to let the user grant full-screen-intent presentation (Android 14+). */
    fun fullScreenIntentSettings(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.parse("package:${context.packageName}"),
        )
    }
}
