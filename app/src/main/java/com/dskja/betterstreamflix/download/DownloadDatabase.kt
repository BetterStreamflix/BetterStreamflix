package com.dskja.betterstreamflix.download

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DownloadItemEntity::class,
        DownloadSeasonPackEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var instance: DownloadDatabase? = null

        /**
         * 1→2 adds `subtitleUrlsJson` (pending subtitle downloads) and
         * `smartEnqueued` (Smart-Downloads marker). Rows survive the upgrade —
         * wiping this table would orphan the Media3 download cache.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE download_items ADD COLUMN subtitleUrlsJson TEXT NOT NULL DEFAULT '[]'",
                )
                db.execSQL(
                    "ALTER TABLE download_items ADD COLUMN smartEnqueued INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /** 2→3 persists Media3 StreamKeys for HLS/DASH re-queue after failure. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE download_items ADD COLUMN streamKeysJson TEXT NOT NULL DEFAULT '[]'",
                )
            }
        }

        fun get(context: Context): DownloadDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DownloadDatabase::class.java,
                    "downloads.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
