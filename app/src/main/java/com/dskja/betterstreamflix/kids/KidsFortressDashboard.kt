package com.dskja.betterstreamflix.kids

import android.content.Context
import androidx.room.Room
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.download.DownloadDatabase
import com.dskja.betterstreamflix.download.DownloadStorage
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.profiles.UserProfile
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Builds Parent Dashboard snapshots — kids PIN/bedtime/age + isolated CW/DL stats
 * without switching the active profile.
 */
object KidsFortressDashboard {

    data class Snapshot(
        val eligible: Boolean,
        val householdPinSet: Boolean,
        val kids: List<KidsFortressPolicy.KidsProfileCard>,
    )

    suspend fun load(context: Context): Snapshot = withContext(Dispatchers.IO) {
        val eligible = KidsFortress.parentDashboardEligible()
        val householdPin = UserPreferences.parentalControlPin.isNotBlank()
        if (!eligible) {
            return@withContext Snapshot(
                eligible = false,
                householdPinSet = householdPin,
                kids = emptyList(),
            )
        }
        val providerName = UserPreferences.currentProvider?.name
        val cards = KidsFortress.kidsProfiles().map { profile ->
            buildCard(context.applicationContext, profile, providerName)
        }
        Snapshot(
            eligible = true,
            householdPinSet = householdPin,
            kids = cards,
        )
    }

    private suspend fun buildCard(
        context: Context,
        profile: UserProfile,
        providerName: String?,
    ): KidsFortressPolicy.KidsProfileCard {
        val ceiling = KidsFortressPolicy.effectiveCeiling(
            isKids = true,
            profileMaxAge = profile.maxAgeRating,
            parentalMaxAge = null,
        ) ?: KidsFortressPolicy.DEFAULT_MAX_AGE
        val (cw, dlCount, dlBytes) = libraryStats(context, profile, providerName)
        return KidsFortressPolicy.KidsProfileCard(
            profileId = profile.id,
            displayName = profile.displayName,
            maxAgeRating = ceiling,
            hasPin = !profile.pinHash.isNullOrBlank() ||
                UserPreferences.parentalControlPin.isNotBlank(),
            bedtimeEnabled = UserPreferences.kidsBedtimeEnabledFor(profile.id),
            bedtimeLocked = UserPreferences.kidsBedtimeLockedFor(profile.id),
            bedtimeMinuteOfDay = UserPreferences.kidsBedtimeMinuteOfDayFor(profile.id),
            continueCount = cw,
            downloadCount = dlCount,
            downloadBytes = dlBytes,
            spoilerForced = true,
        )
    }

    private suspend fun libraryStats(
        context: Context,
        profile: UserProfile,
        providerName: String?,
    ): Triple<Int, Int, Long> {
        val continueCount = if (providerName.isNullOrBlank()) {
            0
        } else {
            countContinueWatching(context, providerName, profile.id)
        }
        val (dlCount, dlBytes) = countDownloads(context, profile)
        return Triple(continueCount, dlCount, dlBytes)
    }

    private suspend fun countContinueWatching(
        context: Context,
        providerName: String,
        profileId: String,
    ): Int {
        val active = ProfileManager.activeProfileId
        return runCatching {
            if (profileId == active) {
                val db = AppDatabase.getInstanceOrNull(context) ?: return 0
                val movies = db.movieDao().getWatchingMoviesCapped().first().size
                val episodes = db.episodeDao().getWatchingEpisodesCapped().first().size
                movies + episodes
            } else {
                val db = AppDatabase.getInstanceForProvider(providerName, context, profileId)
                try {
                    val movies = db.movieDao().getWatchingMoviesCapped().first().size
                    val episodes = db.episodeDao().getWatchingEpisodesCapped().first().size
                    movies + episodes
                } finally {
                    runCatching { db.close() }
                }
            }
        }.getOrDefault(0)
    }

    private suspend fun countDownloads(
        context: Context,
        profile: UserProfile,
    ): Pair<Int, Long> {
        val active = ProfileManager.activeProfileId
        return runCatching {
            if (profile.id == active) {
                val items = DownloadDatabase.get(context).downloadDao().observeAll().first()
                items.size to items.sumOf { max(it.contentLength, it.bytesDownloaded) }
            } else {
                val db = openDownloadsTemporary(context, profile.id)
                try {
                    val items = db.downloadDao().observeAll().first()
                    items.size to items.sumOf { max(it.contentLength, it.bytesDownloaded) }
                } finally {
                    runCatching { db.close() }
                }
            }
        }.getOrElse {
            // Fall back to on-disk folder estimate when DB missing.
            val dir = DownloadStorage.profileDownloadsDir(context, profile.id, profile.displayName)
            val bytes = folderBytes(dir)
            (if (bytes > 0L) 1 else 0) to bytes
        }
    }

    private fun openDownloadsTemporary(context: Context, profileId: String): DownloadDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            DownloadDatabase::class.java,
            DownloadDatabase.databaseNameFor(profileId),
        ).addMigrations(
            DownloadDatabase.MIGRATION_1_2,
            DownloadDatabase.MIGRATION_2_3,
            DownloadDatabase.MIGRATION_3_4,
        ).build()

    private fun folderBytes(root: java.io.File): Long {
        if (!root.exists()) return 0L
        if (root.isFile) return root.length().coerceAtLeast(0L)
        var total = 0L
        root.walkTopDown().forEach { file ->
            if (file.isFile) total += file.length().coerceAtLeast(0L)
        }
        return total
    }
}
