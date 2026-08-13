package com.example.calldelegate.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CallDao {
    @Query("SELECT * FROM call_records ORDER BY endedAtMillis DESC")
    fun observeAll(): Flow<List<CallEntity>>

    @Query("SELECT * FROM call_records WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<CallEntity?>

    @Query("SELECT * FROM call_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CallEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CallEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<CallEntity>)

    @Query("DELETE FROM call_records WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM call_records")
    suspend fun count(): Int

    @Query("SELECT * FROM call_records WHERE endedAtMillis <= :cutoff ORDER BY endedAtMillis ASC")
    suspend fun recordsEndedBefore(cutoff: Long): List<CallEntity>

    @Query("SELECT * FROM call_records WHERE audioPath IS NOT NULL AND endedAtMillis <= :cutoff ORDER BY endedAtMillis ASC")
    suspend fun recordsWithAudioEndedBefore(cutoff: Long): List<CallEntity>

    @Query("UPDATE call_records SET audioPath = NULL WHERE id = :id")
    suspend fun clearAudioPath(id: String)
}
