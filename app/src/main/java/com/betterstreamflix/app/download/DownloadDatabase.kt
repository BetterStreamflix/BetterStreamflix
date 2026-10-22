package com.betterstreamflix.app.download

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.betterstreamflix.app.profiles.ProfileManager

@Database(
    entities = [
        DownloadItemEntity::class,
        DownloadSeasonPackEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var instance: DownloadDatabase? = null
        @Volatile
        private var currentProfileId: String? = null

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

        /**
         * 3→4 scopes download rows to a profile. Legacy rows keep `profileId = ''`
         * and remain visible only on the default profile.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE download_items ADD COLUMN profileId TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL("DROP INDEX IF EXISTS `index_download_items_contentKey`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_download_items_contentKey_profileId` " +
                        "ON `download_items` (`contentKey`, `profileId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_download_items_profileId` " +
                        "ON `download_items` (`profileId`)",
                )
            }
        }

        /** 4→5 stamps season packs with profileId (per-profile DB already isolates rows). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE download_season_packs ADD COLUMN profileId TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        fun databaseNameFor(profileId: String = ProfileManager.activeProfileId): String {
            return if (profileId == ProfileManager.DEFAULT_PROFILE_ID) {
                "downloads.db"
            } else {
                "downloads__${profileId}.db"
            }
        }

        fun get(context: Context): DownloadDatabase {
            val profileId = ProfileManager.activeProfileId
            return instance?.takeIf { currentProfileId == profileId } ?: synchronized(this) {
                instance?.takeIf { currentProfileId == profileId } ?: run {
                    instance?.close()
                    Room.databaseBuilder(
                        context.applicationContext,
                        DownloadDatabase::class.java,
                        databaseNameFor(profileId),
                    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                        .build()
                        .also {
                            instance = it
                            currentProfileId = profileId
                        }
                }
            }
        }

        fun resetInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
                currentProfileId = null
            }
        }
    }
}
