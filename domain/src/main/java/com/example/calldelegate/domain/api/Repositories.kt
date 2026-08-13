package com.example.calldelegate.domain.api

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.model.AppSettings
import com.example.calldelegate.domain.model.CallRecord
import com.example.calldelegate.domain.model.CleanupReport
import com.example.calldelegate.domain.model.HistoryFilter
import kotlinx.coroutines.flow.Flow

interface CallRepository {
    fun observeHistory(filter: HistoryFilter = HistoryFilter()): Flow<List<CallRecord>>
    fun observeById(id: String): Flow<CallRecord?>
    suspend fun getById(id: String): CallRecord?
    suspend fun save(record: CallRecord): AppResult<Unit>
    suspend fun delete(id: String): AppResult<Unit>
    suspend fun cleanup(nowMillis: Long, audioDays: Int, recordDays: Int): CleanupReport
    suspend fun seedExamplesIfEmpty(): AppResult<Unit>
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings): AppResult<Unit>
    suspend fun current(): AppSettings
}

/** Future encryption implementations can wrap the private-file repository without changing callers. */
interface PrivateFileCipher {
    suspend fun encrypt(path: String): AppResult<String>
    suspend fun decrypt(path: String): AppResult<String>
}
