package com.example.calldelegate.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CallEntity::class], version = 2, exportSchema = true)
abstract class CallDatabase : RoomDatabase() {
    abstract fun callDao(): CallDao

    companion object {
        @Volatile private var instance: CallDatabase? = null

        fun get(context: Context): CallDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CallDatabase::class.java,
                "call_delegate.db",
            ).addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE call_records ADD COLUMN recordingIntegrity " +
                        "TEXT NOT NULL DEFAULT 'LEGACY_UNVERIFIED'",
                )
                db.execSQL("ALTER TABLE call_records ADD COLUMN recordingErrorCode TEXT")
                db.execSQL("ALTER TABLE call_records ADD COLUMN recordingErrorMessage TEXT")
                db.execSQL("ALTER TABLE call_records ADD COLUMN playbackErrorCode TEXT")
                db.execSQL("ALTER TABLE call_records ADD COLUMN playbackErrorMessage TEXT")
            }
        }
    }
}
