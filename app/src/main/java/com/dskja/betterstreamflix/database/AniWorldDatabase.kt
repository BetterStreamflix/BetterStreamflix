package com.dskja.betterstreamflix.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dskja.betterstreamflix.database.dao.TvShowDao
import com.dskja.betterstreamflix.models.TvShow

@Database(entities = [TvShow::class], version = 7, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AniWorldDatabase: RoomDatabase() {
    abstract fun tvShowDao(): TvShowDao

    companion object {
        @Volatile private var instance: AniWorldDatabase? = null

        fun getInstance(context: Context): AniWorldDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AniWorldDatabase::class.java,
                    "ani_world.db"
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                    .also { instance = it }
            }
        }

        /** Mirror AppDatabase MIGRATION_8_9 tv_shows columns (no episodes table here). */
        private val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                RoomMigrationHelpers.addColumnIfMissing(db, "tv_shows", "lastPlayedAtMillis", "INTEGER")
                RoomMigrationHelpers.addColumnIfMissing(db, "tv_shows", "lastPlayedEpisodeId", "TEXT")
            }
        }

        /** Mirror AppDatabase MIGRATION_9_10 tv_shows logo columns. */
        private val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                RoomMigrationHelpers.addColumnIfMissing(db, "tv_shows", "logo", "TEXT")
                RoomMigrationHelpers.addColumnIfMissing(db, "tv_shows", "logoLanguage", "TEXT")
            }
        }
    }
}
