package com.example.calldelegate.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CallDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationFromOneMarksOldRecordAsLegacyUnverified() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL(
                """
                INSERT INTO call_records (
                    id, callerName, callerNumber, sceneId, summary,
                    structuredResultJson, transcriptJson, audioPath,
                    startedAtMillis, endedAtMillis, status, inputMode,
                    recognitionFailed, takeoverRequested
                ) VALUES (
                    'legacy', NULL, '10086', 'work', 'summary',
                    '{}', '[]', '/recordings/legacy.wav',
                    1, 2, 'COMPLETED', 'MICROPHONE', 0, 0
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            CallDatabase.MIGRATION_1_2,
        )
        val cursor = migrated.query(
            "SELECT recordingIntegrity, recordingErrorCode, playbackErrorCode " +
                "FROM call_records WHERE id = 'legacy'",
        )
        cursor.use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("LEGACY_UNVERIFIED")
            assertThat(it.isNull(1)).isTrue()
            assertThat(it.isNull(2)).isTrue()
        }
        migrated.close()
    }

    private companion object {
        const val DATABASE_NAME = "recording-migration-test"
    }
}
