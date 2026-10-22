package com.betterstreamflix.app.providers

import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.models.Category
import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.models.Genre
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.People
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.models.Video

/**
 * Shared empty / stub responses so providers can fail soft instead of throwing
 * on unsupported surfaces (genre browse, people, empty search pages, …).
 */
object ProviderDefaults {
    fun emptyGenre(id: String): Genre = Genre(id = id, name = "", shows = emptyList())

    fun emptyPeople(id: String): People = People(id = id, name = "", filmography = emptyList())

    fun emptyMovie(id: String, title: String = "", providerName: String? = null): Movie =
        Movie(id = id, title = title.ifBlank { id }, providerName = providerName)

    fun emptyTvShow(id: String, title: String = "", providerName: String? = null): TvShow =
        TvShow(id = id, title = title.ifBlank { id }, providerName = providerName)

    fun emptySearch(): List<AppAdapter.Item> = emptyList()

    fun emptyMovies(): List<Movie> = emptyList()

    fun emptyTvShows(): List<TvShow> = emptyList()

    fun emptyEpisodes(): List<Episode> = emptyList()

    fun emptyHome(): List<Category> = emptyList()

    fun emptyServers(): List<Video.Server> = emptyList()
}
