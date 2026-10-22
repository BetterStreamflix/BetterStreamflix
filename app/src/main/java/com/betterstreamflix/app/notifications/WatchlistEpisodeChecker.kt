package com.betterstreamflix.app.notifications

import android.content.Context
import android.util.Log
import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.models.Season
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.providers.IptvProvider
import com.betterstreamflix.app.providers.Provider
import com.betterstreamflix.app.utils.CrossProviderLibrary
import com.betterstreamflix.app.utils.LiveHubCatalog
import com.betterstreamflix.app.utils.UserPreferences
import org.json.JSONObject

/**
 * Polls My List (favorite) series for a newly published latest episode.
 * First fingerprint per show is stored silently (opt-in must never spam on enable).
 */
object WatchlistEpisodeChecker {
    private const val TAG = "WatchlistEpNotify"
    private const val MAX_SHOWS_PER_RUN = 8

    suspend fun check(context: Context, budget: Int): Int {
        if (budget <= 0) return 0
        if (!UserPreferences.notifyWatchlistNewEpisode) return 0

        val favorites = runCatching {
            CrossProviderLibrary.loadFavoriteTvShows(context)
        }.getOrElse { emptyList() }
            .filter { !LiveHubCatalog.isLiveFavorite(it) }
            .sortedByDescending { it.favoritedAtMillis ?: 0L }
            .take(MAX_SHOWS_PER_RUN)

        if (favorites.isEmpty()) return 0

        val fingerprints = readFingerprints()
        var sent = 0
        for (show in favorites) {
            if (sent >= budget) break
            val key = LiveHubCatalog.identityKey(show)
            val provider = Provider.findByName(show.providerName.orEmpty()) ?: continue
            if (provider is IptvProvider) continue

            val tip = runCatching { resolveLatestTip(provider, show) }.getOrNull() ?: continue
            val fp = tip.fingerprint
            val previous = fingerprints.optString(key, "")
            if (previous.isBlank()) {
                fingerprints.put(key, fp)
                continue
            }
            if (previous == fp) continue
            if (!isNewer(fp, previous)) {
                fingerprints.put(key, fp)
                continue
            }

            ProductNotifier.notifyNewEpisode(
                context = context,
                showTitle = show.title,
                episodeLabel = tip.label,
                identityKey = key,
            )
            fingerprints.put(key, fp)
            sent++
            Log.i(TAG, "new episode for ${show.title}: ${tip.label}")
        }
        writeFingerprints(fingerprints)
        return sent
    }

    private data class LatestTip(
        val fingerprint: String,
        val label: String,
    )

    private suspend fun resolveLatestTip(provider: Provider, show: TvShow): LatestTip? {
        val remote = provider.getTvShow(show.id)
        val seasons = remote.seasons.orEmpty().sortedBy { it.number }
        if (seasons.isEmpty()) return null
        val lastSeason = seasons.last()
        val episodes = runCatching { provider.getEpisodesBySeason(lastSeason.id) }
            .getOrNull()
            .orEmpty()
            .sortedBy { it.number }
        if (episodes.isEmpty()) {
            return LatestTip(
                fingerprint = "S${lastSeason.number}:E0:0",
                label = "S${lastSeason.number}",
            )
        }
        val lastEp = episodes.last()
        return LatestTip(
            fingerprint = "S${lastSeason.number}:E${lastEp.number}:${episodes.size}",
            label = formatEpisode(lastSeason, lastEp),
        )
    }

    private fun formatEpisode(season: Season, episode: Episode): String {
        val s = season.number.toString().padStart(2, '0')
        val e = episode.number.toString().padStart(2, '0')
        val title = episode.title?.trim().orEmpty()
        return if (title.isNotEmpty()) "S${s}E${e} · $title" else "S${s}E${e}"
    }

    /** Fingerprint order: season, then episode number, then episode count. */
    private fun isNewer(candidate: String, previous: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(previous) ?: return true
        return when {
            a.season != b.season -> a.season > b.season
            a.episode != b.episode -> a.episode > b.episode
            else -> a.count > b.count
        }
    }

    private data class Parsed(val season: Int, val episode: Int, val count: Int)

    private fun parse(fp: String): Parsed? {
        // S2:E10:12
        val match = Regex("""^S(\d+):E(\d+):(\d+)$""").matchEntire(fp) ?: return null
        return Parsed(
            season = match.groupValues[1].toIntOrNull() ?: return null,
            episode = match.groupValues[2].toIntOrNull() ?: return null,
            count = match.groupValues[3].toIntOrNull() ?: return null,
        )
    }

    private fun readFingerprints(): JSONObject {
        val raw = UserPreferences.notifyEpisodeFingerprintsJson
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun writeFingerprints(json: JSONObject) {
        if (json.length() > 120) {
            val keys = json.keys().asSequence().toList()
            keys.take(json.length() - 100).forEach { json.remove(it) }
        }
        UserPreferences.notifyEpisodeFingerprintsJson = json.toString()
    }
}
