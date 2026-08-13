package com.example.calldelegate.telecom.recording

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

enum class ShizukuSetupState {
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    READY,
}

object ShizukuStatus {
    fun current(): ShizukuSetupState {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) return ShizukuSetupState.NOT_RUNNING

        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) ShizukuSetupState.READY else ShizukuSetupState.PERMISSION_REQUIRED
    }

    fun requestPermission(requestCode: Int): Boolean {
        if (current() != ShizukuSetupState.PERMISSION_REQUIRED) return false
        return runCatching {
            Shizuku.requestPermission(requestCode)
            true
        }.getOrDefault(false)
    }

    fun managerLaunchIntent(context: Context): Intent? {
        val managerPackage = runCatching {
            context.packageManager
                .getPermissionInfo(ShizukuProvider.PERMISSION, 0)
                .packageName
        }.getOrNull() ?: return null
        return context.packageManager.getLaunchIntentForPackage(managerPackage)
    }
}
