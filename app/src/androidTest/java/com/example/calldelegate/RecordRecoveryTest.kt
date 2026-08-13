package com.example.calldelegate

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.calldelegate.data.local.CallEntityMapper
import com.example.calldelegate.data.local.RoomCallRepository
import com.example.calldelegate.data.local.db.CallDatabase
import com.example.calldelegate.domain.model.CallRecord
import com.example.calldelegate.domain.model.CallStatus
import com.example.calldelegate.domain.model.AudioFailure
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.model.SceneType
import com.example.calldelegate.domain.model.RecordingIntegrity
import com.example.calldelegate.domain.model.StructuredResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordRecoveryTest {
    @Test
    fun persistedRecordSurvivesDatabaseReopen() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "recovery-${System.nanoTime()}.db"
            val mapper = CallEntityMapper(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            var database = Room.databaseBuilder(context, CallDatabase::class.java, name).build()

            try {
                val expected = CallRecord(
                    "recovery", "测试来电", "13800000000", SceneType.WORK, "重启恢复测试",
                    StructuredResult(purpose = "重启恢复测试"), emptyList(), null,
                    1L, 2L, CallStatus.COMPLETED, InputMode.TEXT, false, false,
                    RecordingIntegrity.PARTIAL,
                    AudioFailure("AUDIO_SAVE", "写入失败"),
                    AudioFailure("AUDIO_PLAY", "播放失败"),
                )
                RoomCallRepository(database.callDao(), mapper).save(expected)
                database.close()

                database = Room.databaseBuilder(context, CallDatabase::class.java, name).build()
                val actual = RoomCallRepository(database.callDao(), mapper).getById("recovery")

                assertThat(actual?.summary).isEqualTo(expected.summary)
                assertThat(actual?.recordingIntegrity).isEqualTo(RecordingIntegrity.PARTIAL)
                assertThat(actual?.recordingFailure).isEqualTo(expected.recordingFailure)
                assertThat(actual?.playbackFailure).isEqualTo(expected.playbackFailure)
            } finally {
                database.close()
                context.deleteDatabase(name)
            }
        }
    }
}
