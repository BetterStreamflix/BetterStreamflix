package com.betterstreamflix.app.download

import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import java.util.Locale

/**
 * Offline-Bibliothek: completed downloads as a poster-grid library (movies +
 * series folders). Pure local data — no network / provider search.
 */
object OfflineLibrary {

    sealed class Entry {
        abstract val stableId: String
        abstract val title: String
        abstract val posterUrl: String
        abstract val sortKey: Long
        abstract val searchBlob: String

        data class MovieItem(
            val entity: DownloadItemEntity,
        ) : Entry() {
            override val stableId: String get() = entity.contentKey
            override val title: String get() = entity.title
            override val posterUrl: String get() = entity.posterUrl
            override val sortKey: Long
                get() = maxOf(entity.completedAt, entity.updatedAt, entity.createdAt)
            override val searchBlob: String
                get() = listOf(entity.title, entity.subtitle, entity.providerName)
                    .joinToString(" ")
        }

        data class SeriesItem(
            val seriesKey: String,
            override val title: String,
            override val posterUrl: String,
            val providerName: String,
            val episodes: List<DownloadItemEntity>,
        ) : Entry() {
            override val stableId: String get() = seriesKey
            override val sortKey: Long
                get() = episodes.maxOfOrNull {
                    maxOf(it.completedAt, it.updatedAt, it.createdAt)
                } ?: 0L
            override val searchBlob: String
                get() = buildString {
                    append(title)
                    append(' ')
                    append(providerName)
                    episodes.forEach { ep ->
                        append(' ')
                        append(ep.title)
                        append(' ')
                        append(ep.subtitle)
                    }
                }
            val episodeCount: Int get() = episodes.size
        }
    }

    /**
     * Build library entries from completed download rows.
     * Episodes collapse to one series poster; movies stay one poster each.
     */
    fun build(completedItems: List<DownloadItemEntity>): List<Entry> {
        val deduped = completedItems
            .filter { it.state == DownloadItemState.COMPLETED.name }
            .groupBy { it.contentKey }
            .map { (_, rows) -> rows.maxBy { it.updatedAt } }

        val movies = mutableListOf<Entry.MovieItem>()
        val seriesBuckets = linkedMapOf<String, MutableList<DownloadItemEntity>>()
        val seriesMeta = linkedMapOf<String, DownloadSeriesFolders.SeriesRef>()

        deduped.forEach { item ->
            val ref = DownloadSeriesFolders.seriesRef(item)
            if (ref != null) {
                seriesBuckets.getOrPut(ref.key) { mutableListOf() }.add(item)
                seriesMeta.putIfAbsent(ref.key, ref)
            } else {
                movies.add(Entry.MovieItem(item))
            }
        }

        val series = seriesBuckets.map { (key, eps) ->
            val ref = seriesMeta[key]!!
            val ordered = eps.sortedWith(
                compareBy<DownloadItemEntity> { seasonNumberOf(it) }
                    .thenBy { episodeNumberOf(it) }
                    .thenBy { it.sortIndex },
            )
            Entry.SeriesItem(
                seriesKey = key,
                title = ref.title,
                posterUrl = ref.posterUrl.ifBlank {
                    ordered.firstOrNull()?.posterUrl.orEmpty()
                },
                providerName = ordered.firstOrNull()?.providerName.orEmpty(),
                episodes = ordered,
            )
        }

        return (movies + series).sortedWith(
            compareByDescending<Entry> { it.sortKey }
                .thenBy { it.title.lowercase(Locale.ROOT) },
        )
    }

    fun filter(entries: List<Entry>, query: String): List<Entry> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return entries
        return entries.filter { it.searchBlob.lowercase(Locale.ROOT).contains(q) }
    }

    fun toAppItems(entries: List<Entry>, mobile: Boolean): List<AppAdapter.Item> {
        val movieType = if (mobile) {
            AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
        } else {
            AppAdapter.Type.MOVIE_GRID_TV_ITEM
        }
        val showType = if (mobile) {
            AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
        } else {
            AppAdapter.Type.TV_SHOW_GRID_TV_ITEM
        }
        return entries.map { entry ->
            when (entry) {
                is Entry.MovieItem -> Movie(
                    id = entry.stableId,
                    title = entry.title,
                    poster = entry.posterUrl.takeIf { it.isNotBlank() },
                    overview = entry.entity.subtitle.takeIf { it.isNotBlank() },
                    providerName = entry.entity.providerName,
                ).also { it.itemType = movieType }
                is Entry.SeriesItem -> TvShow(
                    id = entry.stableId,
                    title = entry.title,
                    poster = entry.posterUrl.takeIf { it.isNotBlank() },
                    overview = entry.episodeCount.toString(),
                    providerName = entry.providerName,
                ).also { it.itemType = showType }
            }
        }
    }

    fun findMovieEntity(entries: List<Entry>, movieId: String): DownloadItemEntity? =
        entries.filterIsInstance<Entry.MovieItem>()
            .firstOrNull { it.stableId == movieId }
            ?.entity

    fun findSeries(entries: List<Entry>, seriesId: String): Entry.SeriesItem? =
        entries.filterIsInstance<Entry.SeriesItem>()
            .firstOrNull { it.stableId == seriesId }

    fun episodeLabel(entity: DownloadItemEntity): String {
        val season = seasonNumberOf(entity)
        val episode = episodeNumberOf(entity)
        val base = when {
            season > 0 && episode > 0 ->
                "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
            else -> entity.subtitle.trim().ifBlank { "" }
        }
        val title = entity.title.trim()
        return when {
            base.isNotBlank() && title.isNotBlank() && !title.contains(base, ignoreCase = true) ->
                "$base · $title"
            title.isNotBlank() -> title
            base.isNotBlank() -> base
            else -> entity.contentKey
        }
    }

    private fun seasonNumberOf(item: DownloadItemEntity): Int {
        val fromJson = runCatching {
            org.json.JSONObject(item.videoTypeJson.ifBlank { "{}" }).optInt("seasonNumber", -1)
        }.getOrDefault(-1)
        if (fromJson > 0) return fromJson
        // contentKey: episode|provider|tvShowId|season|ep|episodeId
        return item.contentKey.split('|').getOrNull(3)?.toIntOrNull() ?: 0
    }

    private fun episodeNumberOf(item: DownloadItemEntity): Int {
        val fromJson = runCatching {
            org.json.JSONObject(item.videoTypeJson.ifBlank { "{}" }).optInt("number", -1)
        }.getOrDefault(-1)
        if (fromJson > 0) return fromJson
        return item.contentKey.split('|').getOrNull(4)?.toIntOrNull() ?: 0
    }
}
