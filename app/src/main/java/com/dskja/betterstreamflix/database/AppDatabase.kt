package com.dskja.betterstreamflix.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dskja.betterstreamflix.database.dao.EpisodeDao
import com.dskja.betterstreamflix.database.dao.MovieDao
import com.dskja.betterstreamflix.database.dao.SeasonDao
import com.dskja.betterstreamflix.database.dao.TvShowDao
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.UserPreferences

/** Thrown when [AppDatabase.getInstance] is called with no resolved provider. */
class ProviderNotSetException :
    IllegalStateException("Current provider is not set")

@Database(
    entities = [
        Episode::class,
        Movie::class,
        Season::class,
        TvShow::class,
    ],
    version = 10,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun movieDao(): MovieDao

    abstract fun tvShowDao(): TvShowDao

    abstract fun seasonDao(): SeasonDao

    abstract fun episodeDao(): EpisodeDao

    companion object {
        private const val TAG = "AppDatabase"

        @Volatile
        private var INSTANCE: AppDatabase? = null
        @Volatile
        private var currentProviderName: String? = null
        @Volatile
        private var currentProfileId: String? = null

        private fun sanitizeProviderName(name: String): String {
            // Rimuove caratteri non validi per i nomi dei file DB, 
            // come spazi, parentesi, e li converte in lowercase.
            return name.lowercase()
                .replace("[^a-z0-9]".toRegex(), "_")
                .replace("__+".toRegex(), "_") // Sostituisce doppie underscore con una singola
                .trim('_') // Rimuove underscore iniziale/finale
        }

        fun databaseNameFor(
            providerName: String,
            profileId: String = ProfileManager.activeProfileId,
        ): String {
            val sanitized = sanitizeProviderName(providerName)
            return if (profileId == ProfileManager.DEFAULT_PROFILE_ID) {
                "$sanitized.db"
            } else {
                "${profileId}__${sanitized}.db"
            }
        }

        fun setup(context: Context) {
            if (UserPreferences.currentProvider == null) return

            getInstanceOrNull(context)
        }

        /**
         * Soft open for UI / ViewModels. Returns null when no provider is set or
         * the provider DB cannot be opened after one wipe-and-retry.
         * Prefer this from fragments / adapters (SS-B10-08).
         */
        @Suppress("DEPRECATION")
        fun getInstanceOrNull(context: Context): AppDatabase? =
            runCatching { getInstance(context) }.getOrNull()

        /**
         * Soft-open alias for UI call sites mid provider-clear / TV restore.
         * Same as [getInstanceOrNull] — never throws.
         */
        fun softOpen(context: Context): AppDatabase? = getInstanceOrNull(context)

        /**
         * Hard open for the active provider singleton. External callers should use
         * [getInstanceOrNull] / [softOpen] instead (SS-B10-08).
         */
        @Deprecated(
            message = "Prefer getInstanceOrNull / softOpen — never throws on provider-clear",
            replaceWith = ReplaceWith("getInstanceOrNull(context)"),
        )
        fun getInstance(context: Context): AppDatabase {
            val providerName = UserPreferences.currentProvider?.name
                ?: currentProviderName
                ?: throw ProviderNotSetException()
            val profileId = ProfileManager.activeProfileId

            return INSTANCE?.takeIf {
                currentProviderName == providerName && currentProfileId == profileId
            } ?: synchronized(this) {
                INSTANCE?.takeIf {
                    currentProviderName == providerName && currentProfileId == profileId
                } ?: run {
                    INSTANCE?.close()
                    val opened = openWithRecovery(providerName, profileId, context)
                    INSTANCE = opened
                    currentProviderName = providerName
                    currentProfileId = profileId
                    opened
                }
            }
        }

        // Metodo per forzare il cambio di database quando cambia il provider
        fun resetInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
                currentProviderName = null
                currentProfileId = null
            }
            runCatching {
                com.dskja.betterstreamflix.download.DownloadDatabase.resetInstance()
                com.dskja.betterstreamflix.download.DownloadRepository.resetInstance()
                com.dskja.betterstreamflix.download.OfflineBadgeStore.reset()
                // Re-open SimpleCache under the next profile's downloads folder.
                com.dskja.betterstreamflix.download.StreamflixDownloadManager.release()
            }
        }

        /**
         * Soft open for a specific provider DB. Returns null when Room cannot open
         * after one wipe-and-retry (SS-B10-08). Prefer this from UI / resume paths.
         */
        fun getInstanceForProviderOrNull(
            providerName: String,
            context: Context,
            profileId: String = ProfileManager.activeProfileId,
        ): AppDatabase? =
            runCatching { getInstanceForProvider(providerName, context, profileId) }.getOrNull()

        /**
         * Soft-open alias for cross-provider UI sites. Same as [getInstanceForProviderOrNull].
         */
        fun softOpenForProvider(
            providerName: String,
            context: Context,
            profileId: String = ProfileManager.activeProfileId,
        ): AppDatabase? = getInstanceForProviderOrNull(providerName, context, profileId)

        fun getInstanceForProvider(
            providerName: String,
            context: Context,
            profileId: String = ProfileManager.activeProfileId,
        ): AppDatabase {
            return openWithRecovery(providerName, profileId, context)
        }

        /**
         * Opens the provider database for [profileId].
         * Reuses the process singleton when it already points at that file so sync
         * cannot close the live connection out from under the UI.
         * Close the database only when [OpenedDatabase.ownsConnection] is true.
         */
        fun openForProfile(
            providerName: String,
            context: Context,
            profileId: String = ProfileManager.activeProfileId,
        ): OpenedDatabase {
            val live = INSTANCE?.takeIf {
                currentProviderName == providerName && currentProfileId == profileId
            }
            if (live != null) return OpenedDatabase(live, ownsConnection = false)
            return OpenedDatabase(
                openWithRecovery(providerName, profileId, context),
                ownsConnection = true,
            )
        }

        class OpenedDatabase(
            val database: AppDatabase,
            val ownsConnection: Boolean,
        )

        /**
         * Open Room and force a writable touch. On corruption, delete that provider
         * DB file once and retry; on second failure clear the preferred provider so
         * the next cold start lands on Providers instead of looping.
         */
        private fun openWithRecovery(
            providerName: String,
            profileId: String,
            context: Context,
        ): AppDatabase {
            val app = context.applicationContext
            val dbName = databaseNameFor(providerName, profileId)
            return try {
                buildDatabase(providerName, profileId, app).also { forceOpen(it) }
            } catch (first: Throwable) {
                Log.e(TAG, "Room open failed for $dbName — wiping once and retrying", first)
                runCatching { app.deleteDatabase(dbName) }
                try {
                    buildDatabase(providerName, profileId, app).also { forceOpen(it) }
                } catch (second: Throwable) {
                    Log.e(TAG, "Room open failed again for $dbName — clearing provider", second)
                    runCatching { UserPreferences.clearCurrentProvider() }
                    throw second
                }
            }
        }

        private fun forceOpen(db: AppDatabase) {
            // Room.build() is lazy; touching the open helper surfaces corruption now.
            db.openHelper.writableDatabase
        }

        private fun buildDatabase(
            providerName: String,
            profileId: String,
            context: Context,
        ): AppDatabase {
            return Room.databaseBuilder(
                context = context.applicationContext,
                klass = AppDatabase::class.java,
                name = databaseNameFor(providerName, profileId),
            )
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .addMigrations(MIGRATION_4_5)
                .addMigrations(MIGRATION_5_6)
                .addMigrations(MIGRATION_6_7)
                .addMigrations(MIGRATION_7_8)
                .addMigrations(MIGRATION_8_9)
                .addMigrations(MIGRATION_9_10)
                .build()
        }


        private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN watchedDate TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN lastEngagementTimeUtcMillis INTEGER")
                db.execSQL("ALTER TABLE episodes ADD COLUMN lastPlaybackPositionMillis INTEGER")
                db.execSQL("ALTER TABLE episodes ADD COLUMN durationMillis INTEGER")

                db.execSQL("ALTER TABLE movies ADD COLUMN watchedDate TEXT")
                db.execSQL("ALTER TABLE movies ADD COLUMN lastEngagementTimeUtcMillis INTEGER")
                db.execSQL("ALTER TABLE movies ADD COLUMN lastPlaybackPositionMillis INTEGER")
                db.execSQL("ALTER TABLE movies ADD COLUMN durationMillis INTEGER")
            }
        }

        private val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE `episodes_temp` (`id` TEXT NOT NULL, `number` INTEGER NOT NULL, `title` TEXT, `poster` TEXT, `tvShow` TEXT, `season` TEXT, `released` TEXT, `isWatched` INTEGER NOT NULL, `watchedDate` TEXT, `lastEngagementTimeUtcMillis` INTEGER, `lastPlaybackPositionMillis` INTEGER, `durationMillis` INTEGER, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO episodes_temp SELECT * FROM episodes")
                db.execSQL("DROP TABLE episodes")
                db.execSQL("ALTER TABLE episodes_temp RENAME TO episodes")

                db.execSQL("CREATE TABLE `movies_temp` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `overview` TEXT, `runtime` INTEGER, `trailer` TEXT, `quality` TEXT, `rating` REAL, `poster` TEXT, `banner` TEXT, `released` TEXT, `isFavorite` INTEGER NOT NULL, `isWatched` INTEGER NOT NULL, `watchedDate` TEXT, `lastEngagementTimeUtcMillis` INTEGER, `lastPlaybackPositionMillis` INTEGER, `durationMillis` INTEGER, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO movies_temp SELECT * FROM movies")
                db.execSQL("DROP TABLE movies")
                db.execSQL("ALTER TABLE movies_temp RENAME TO movies")

                db.execSQL("CREATE TABLE `seasons_temp` (`id` TEXT NOT NULL, `number` INTEGER NOT NULL, `title` TEXT, `poster` TEXT, `tvShow` TEXT, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO seasons_temp SELECT * FROM seasons")
                db.execSQL("DROP TABLE seasons")
                db.execSQL("ALTER TABLE seasons_temp RENAME TO seasons")

                db.execSQL("CREATE TABLE `tv_shows_temp` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `overview` TEXT, `runtime` INTEGER, `trailer` TEXT, `quality` TEXT, `rating` REAL, `poster` TEXT, `banner` TEXT, `released` TEXT, `isFavorite` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("INSERT INTO tv_shows_temp SELECT * FROM tv_shows")
                db.execSQL("DROP TABLE tv_shows")
                db.execSQL("ALTER TABLE tv_shows_temp RENAME TO tv_shows")
            }
        }

        private val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tv_shows ADD COLUMN isWatching INTEGER DEFAULT 1 NOT NULL")
            }
        }

        private val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN overview TEXT")
            }
        }

        private val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create indexes for query optimization
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_tvShow_isWatched` ON `episodes` (`tvShow`, `isWatched`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_tvShow_lastEngagementTimeUtcMillis` ON `episodes` (`tvShow`, `lastEngagementTimeUtcMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_season_number` ON `episodes` (`season`, `number`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_seasons_tvShow_number` ON `seasons` (`tvShow`, `number`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tv_shows_isWatching` ON `tv_shows` (`isWatching`)")
            }
        }

        private val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN favoritedAtMillis INTEGER")
                db.execSQL("ALTER TABLE tv_shows ADD COLUMN favoritedAtMillis INTEGER")
            }
        }

        private val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No SQL changes needed as indices were already created in previous migrations 
                // but are now formally declared in Entity classes, requiring a version bump.
            }
        }

        private val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN lastPlayedAtMillis INTEGER")
                db.execSQL("ALTER TABLE tv_shows ADD COLUMN lastPlayedAtMillis INTEGER")
                db.execSQL("ALTER TABLE tv_shows ADD COLUMN lastPlayedEpisodeId TEXT")

                db.execSQL(
                    """
                    UPDATE movies
                    SET lastPlayedAtMillis = CASE
                        WHEN lastEngagementTimeUtcMillis IS NOT NULL
                             AND COALESCE(lastPlaybackPositionMillis, 0) > 0
                            THEN lastEngagementTimeUtcMillis
                        WHEN watchedDate IS NOT NULL
                            THEN CAST(strftime('%s', watchedDate) AS INTEGER) * 1000
                        ELSE NULL
                    END
                    """.trimIndent()
                )

                val episodePlayedAt = """
                    CASE
                        WHEN e.lastEngagementTimeUtcMillis IS NOT NULL
                             AND COALESCE(e.lastPlaybackPositionMillis, 0) > 0
                            THEN e.lastEngagementTimeUtcMillis
                        WHEN e.watchedDate IS NOT NULL
                            THEN CAST(strftime('%s', e.watchedDate) AS INTEGER) * 1000
                        ELSE NULL
                    END
                """.trimIndent()
                val recentEpisode = """
                    SELECT e.id
                    FROM episodes e
                    WHERE e.tvShow = tv_shows.id
                      AND ($episodePlayedAt) IS NOT NULL
                    ORDER BY ($episodePlayedAt) DESC
                    LIMIT 1
                """.trimIndent()

                db.execSQL("UPDATE tv_shows SET lastPlayedEpisodeId = ($recentEpisode)")
                db.execSQL(
                    """
                    UPDATE tv_shows
                    SET lastPlayedAtMillis = (
                        SELECT $episodePlayedAt
                        FROM episodes e
                        WHERE e.id = tv_shows.lastPlayedEpisodeId
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN logo TEXT")
                db.execSQL("ALTER TABLE movies ADD COLUMN logoLanguage TEXT")
                db.execSQL("ALTER TABLE tv_shows ADD COLUMN logo TEXT")
                db.execSQL("ALTER TABLE tv_shows ADD COLUMN logoLanguage TEXT")
            }
        }
    }
}
