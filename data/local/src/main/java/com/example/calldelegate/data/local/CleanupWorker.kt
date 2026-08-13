package com.example.calldelegate.data.local

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

class CleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        val database = com.example.calldelegate.data.local.db.CallDatabase.get(applicationContext)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val repository = RoomCallRepository(
            database.callDao(),
            CallEntityMapper(json),
            java.io.File(applicationContext.filesDir, "recordings"),
        )
        val settings = DataStoreSettingsRepository(applicationContext).current()
        val report = repository.cleanup(
            nowMillis = System.currentTimeMillis(),
            audioDays = settings.audioRetentionDays,
            recordDays = settings.transcriptRetentionDays,
        )
        if (report.errors.isEmpty()) Result.success() else Result.retry()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        Result.retry()
    }
}
