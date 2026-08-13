package com.example.calldelegate.telecom.recording

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.calldelegate.R

object CarrierRecordingNotifier {
    fun saved(context: Context, recording: SavedCarrierRecording) {
        ensureChannel(context)
        val uri = Uri.parse(recording.contentUri)
        val openRecording = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/ogg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            SAVED_NOTIFICATION_ID,
            openRecording,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notify(
            context,
            SAVED_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("通话录音已保存")
                .setContentText(recording.displayName)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun failed(context: Context, message: String) {
        ensureChannel(context)
        notify(
            context,
            FAILURE_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("通话录音失败")
                .setContentText(message)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "通话录音",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private const val CHANNEL_ID = "carrier_call_recordings"
    private const val SAVED_NOTIFICATION_ID = 4621
    private const val FAILURE_NOTIFICATION_ID = 4622
}
