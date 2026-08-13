package com.example.calldelegate.data.local

import com.example.calldelegate.core.common.AppError
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.core.common.RetentionPolicy
import com.example.calldelegate.data.local.db.CallDao
import com.example.calldelegate.domain.api.CallRepository
import com.example.calldelegate.domain.model.CallRecord
import com.example.calldelegate.domain.model.CallStatus
import com.example.calldelegate.domain.model.CleanupReport
import com.example.calldelegate.domain.model.HistoryFilter
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.RecordingIntegrity
import com.example.calldelegate.domain.model.Speaker
import com.example.calldelegate.domain.model.StructuredResult
import com.example.calldelegate.domain.model.TranscriptTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

class RoomCallRepository(
    private val dao: CallDao,
    private val mapper: CallEntityMapper,
    private val allowedRecordingRoot: File? = null,
) : CallRepository {
    override fun observeHistory(filter: HistoryFilter): Flow<List<CallRecord>> = dao.observeAll().map { entities ->
        entities.map(mapper::fromEntity).filter { record ->
            val matchesScene = filter.scene == null || record.scene == filter.scene
            val query = filter.keyword.trim()
            val matchesKeyword = query.isBlank() || listOf(
                record.callerName.orEmpty(),
                record.callerNumber,
                record.summary,
                record.transcript.joinToString(" ") { it.text },
            ).any { it.contains(query, ignoreCase = true) }
            matchesScene && matchesKeyword
        }
    }.catch { emit(emptyList()) }.flowOn(Dispatchers.Default)

    override fun observeById(id: String): Flow<CallRecord?> = dao.observeById(id)
        .map { it?.let(mapper::fromEntity) }
        .catch { emit(null) }
        .flowOn(Dispatchers.Default)
    override suspend fun getById(id: String): CallRecord? = withContext(Dispatchers.IO) {
        runCatchingCancellable { dao.getById(id)?.let(mapper::fromEntity) }.getOrNull()
    }

    override suspend fun save(record: CallRecord): AppResult<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable { dao.upsert(mapper.toEntity(record)) }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(AppError("DB_SAVE", "通话记录保存失败", it.message)) },
        )
    }

    override suspend fun delete(id: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        val entity = runCatchingCancellable { dao.getById(id) }.getOrElse {
            return@withContext AppResult.Failure(AppError("DB_READ", "通话记录读取失败", it.message))
        } ?: return@withContext AppResult.Success(Unit)
        if (entity.audioPath != null && !isSafeAudioPath(entity.audioPath)) {
            return@withContext AppResult.Failure(AppError("FILE_PATH", "录音路径异常，已停止删除", entity.audioPath, false))
        }
        val audio = entity.audioPath?.let(::File)
        if (audio != null && audio.exists() && !audio.delete()) {
            return@withContext AppResult.Failure(AppError("FILE_DELETE", "录音文件删除失败，请稍后重试", audio.absolutePath))
        }
        runCatchingCancellable { dao.deleteById(id) }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(AppError("DB_DELETE", "记录删除失败", it.message)) },
        )
    }

    override suspend fun cleanup(nowMillis: Long, audioDays: Int, recordDays: Int): CleanupReport = withContext(Dispatchers.IO) {
        try {
            val errors = mutableListOf<String>()
            var audioDeleted = 0
            var recordsDeleted = 0
            var missing = 0
            val recordCutoff = cutoff(nowMillis, recordDays)
            val audioCutoff = cutoff(nowMillis, audioDays)

            val expiredRecords = dao.recordsEndedBefore(recordCutoff)
            val removedIds = mutableSetOf<String>()
            expiredRecords.forEach { entity ->
                if (entity.audioPath != null && !isSafeAudioPath(entity.audioPath)) {
                    errors += "记录 ${entity.id} 的录音路径异常"
                    return@forEach
                }
                val fileOkay = entity.audioPath?.let(::File)?.let { file ->
                    when {
                        !file.exists() -> { missing += 1; true }
                        file.delete() -> { audioDeleted += 1; true }
                        else -> { errors += "无法删除 ${file.name}"; false }
                    }
                } ?: true
                if (fileOkay) {
                    runCatchingCancellable { dao.deleteById(entity.id) }
                        .onSuccess { recordsDeleted += 1; removedIds += entity.id }
                        .onFailure { errors += "记录 ${entity.id} 删除失败" }
                }
            }

            dao.recordsWithAudioEndedBefore(audioCutoff)
                .filterNot { it.id in removedIds }
                .forEach { entity ->
                    if (entity.audioPath != null && !isSafeAudioPath(entity.audioPath)) {
                        errors += "记录 ${entity.id} 的录音路径异常"
                        return@forEach
                    }
                    val file = entity.audioPath?.let(::File)
                    val fileOkay = when {
                        file == null -> true
                        !file.exists() -> { missing += 1; true }
                        file.delete() -> { audioDeleted += 1; true }
                        else -> { errors += "无法删除 ${file.name}"; false }
                    }
                    if (fileOkay) runCatchingCancellable { dao.clearAudioPath(entity.id) }
                        .onFailure { errors += "记录 ${entity.id} 的音频状态更新失败" }
                }
            CleanupReport(audioDeleted, recordsDeleted, missing, errors)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            CleanupReport(errors = listOf("过期数据清理失败：${throwable.message ?: "未知错误"}"))
        }
    }

    override suspend fun seedExamplesIfEmpty(): AppResult<Unit> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val examples = listOf(
            example("example-delivery", "演示：快递员", "13800000001", SceneType.DELIVERY, "快递已放在北门驿站，无需回电。", now - 3_600_000L),
            example("example-work", "演示：张工", "13800000002", SceneType.WORK, "研发部通知明天下午三点项目评审，需要回电确认。", now - 86_400_000L),
            example("example-sales", null, "95000", SceneType.SPAM_RISK, "疑似贷款推广，已礼貌拒绝。", now - 172_800_000L),
        )
        runCatchingCancellable {
            if (dao.count() == 0) dao.insertAll(examples.map { mapper.toEntity(it) })
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(AppError("DB_SEED", "示例记录初始化失败", it.message)) },
        )
    }

    private fun example(id: String, name: String?, number: String, scene: SceneType, summary: String, endedAt: Long): CallRecord =
        CallRecord(
            id = id,
            callerName = name,
            callerNumber = number,
            scene = scene,
            summary = summary,
            structuredResult = StructuredResult(purpose = summary),
            transcript = listOf(
                TranscriptTurn(Speaker.ASSISTANT, "您好，请问您有什么事？", endedAt - 60_000L),
                TranscriptTurn(Speaker.CALLER, summary, endedAt - 30_000L),
            ),
            audioPath = null,
            startedAtMillis = endedAt - 90_000L,
            endedAtMillis = endedAt,
            status = CallStatus.COMPLETED,
            inputMode = InputMode.TEXT,
            recognitionFailed = false,
            takeoverRequested = false,
            recordingIntegrity = RecordingIntegrity.FAILED,
            recordingFailure = null,
            playbackFailure = null,
        )

    private fun cutoff(now: Long, days: Int): Long = now - days.coerceAtLeast(1) * 86_400_000L

    private fun isSafeAudioPath(path: String): Boolean {
        val root = allowedRecordingRoot ?: return true
        return runCatching {
            File(path).canonicalPath.startsWith(root.canonicalPath + File.separator)
        }.getOrDefault(false)
    }
}

private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (throwable: Throwable) {
    Result.failure(throwable)
}
