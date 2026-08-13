package com.example.calldelegate.telecom.recording

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.WorkerThread
import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import java.io.File

object ScrcpyServerAsset {
    @SuppressLint("SetWorldReadable")
    @WorkerThread
    fun ensureAvailable(context: Context): AppResult<File> {
        val sharedDirectory = context.getExternalFilesDir(null)
            ?: context.externalCacheDir
            ?: return AppResult.Failure(
                AppError("SCRCPY_STORAGE", "共享存储不可用，无法准备通话录音组件"),
            )
        val target = File(sharedDirectory, ScrcpyServerSpec.ASSET_NAME)
        if (ScrcpyServerSpec.verify(target)) return AppResult.Success(target)

        val temporary = File(sharedDirectory, "${ScrcpyServerSpec.ASSET_NAME}.tmp")
        return try {
            temporary.delete()
            context.assets.open(ScrcpyServerSpec.ASSET_NAME).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output, 8 * 1024) }
            }
            if (!ScrcpyServerSpec.verify(temporary)) {
                temporary.delete()
                AppResult.Failure(
                    AppError("SCRCPY_HASH", "通话录音组件完整性校验失败"),
                )
            } else {
                target.delete()
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
                target.setReadable(true, false)
                AppResult.Success(target)
            }
        } catch (throwable: Throwable) {
            temporary.delete()
            AppResult.Failure(
                AppError(
                    code = "SCRCPY_EXTRACT",
                    userMessage = "通话录音组件准备失败",
                    detail = throwable.message,
                ),
            )
        }
    }
}
