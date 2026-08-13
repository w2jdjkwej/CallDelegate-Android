package com.example.calldelegate.data.local

import com.example.calldelegate.data.local.db.CallDao
import com.example.calldelegate.data.local.db.CallEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import java.nio.file.Files

class RoomCallRepositoryCleanupTest {
    @Test fun cleanupDeletesAudioAtSevenDaysAndRecordAtThirtyDays() = runTest {
        val directory = Files.createTempDirectory("call-cleanup").toFile()
        val audio10 = directory.resolve("10-days.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val audio31 = directory.resolve("31-days.wav").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val day = 86_400_000L
        val now = 40 * day
        val dao = FakeCallDao(
            mutableListOf(
                entity("ten", 30 * day, audio10.absolutePath),
                entity("thirty-one", 9 * day, audio31.absolutePath),
            ),
        )
        val repository = RoomCallRepository(dao, CallEntityMapper(Json { ignoreUnknownKeys = true }))

        val report = repository.cleanup(now, audioDays = 7, recordDays = 30)

        assertThat(report.audioFilesDeleted).isEqualTo(2)
        assertThat(report.recordsDeleted).isEqualTo(1)
        assertThat(audio10.exists()).isFalse()
        assertThat(audio31.exists()).isFalse()
        assertThat(dao.values.map { it.id }).containsExactly("ten")
        assertThat(dao.values.single().audioPath).isNull()
        directory.deleteRecursively()
    }

    @Test fun deleteToleratesAlreadyMissingAudioFile() = runTest {
        val dao = FakeCallDao(mutableListOf(entity("missing", 1L, "/not/present/file.wav")))
        val repository = RoomCallRepository(dao, CallEntityMapper(Json))
        repository.delete("missing")
        assertThat(dao.values).isEmpty()
    }

    private fun entity(id: String, endedAt: Long, path: String?) = CallEntity(
        id, null, "10086", "delivery", "summary", "{}", "[]", path,
        endedAt - 1_000, endedAt, "COMPLETED", "TEXT", false, false,
    )
}

private class FakeCallDao(initial: MutableList<CallEntity>) : CallDao {
    val values = initial
    private val flow = MutableStateFlow(values.toList())
    override fun observeAll(): Flow<List<CallEntity>> = flow
    override fun observeById(id: String): Flow<CallEntity?> = MutableStateFlow(values.firstOrNull { it.id == id })
    override suspend fun getById(id: String): CallEntity? = values.firstOrNull { it.id == id }
    override suspend fun upsert(entity: CallEntity) { values.removeAll { it.id == entity.id }; values += entity; emit() }
    override suspend fun insertAll(entities: List<CallEntity>) { entities.forEach { if (values.none { old -> old.id == it.id }) values += it }; emit() }
    override suspend fun deleteById(id: String) { values.removeAll { it.id == id }; emit() }
    override suspend fun count(): Int = values.size
    override suspend fun recordsEndedBefore(cutoff: Long): List<CallEntity> = values.filter { it.endedAtMillis <= cutoff }
    override suspend fun recordsWithAudioEndedBefore(cutoff: Long): List<CallEntity> = values.filter { it.audioPath != null && it.endedAtMillis <= cutoff }
    override suspend fun clearAudioPath(id: String) {
        val index = values.indexOfFirst { it.id == id }
        if (index >= 0) values[index] = values[index].copy(audioPath = null)
        emit()
    }
    private fun emit() { flow.value = values.toList() }
}
