package com.dskja.betterstreamflix.models

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.utils.format
import com.dskja.betterstreamflix.utils.toCalendar
import java.util.Calendar

@Entity("movies")
class Movie(
    @PrimaryKey
    var id: String = "",
    var title: String = "",
    var overview: String? = null,
    released: String? = null,
    var runtime: Int? = null,
    var trailer: String? = null,
    var quality: String? = null,
    var rating: Double? = null,
    var poster: String? = null,
    var banner: String? = null,

    @Ignore
    var imdbId: String? = null,

    @Ignore
    var tmdbId: String? = null,

    @Ignore
    var providerName: String? = null,

    @Ignore
    val genres: List<Genre> = listOf(),
    @Ignore
    val directors: List<People> = listOf(),
    @Ignore
    val cast: List<People> = listOf(),
    @Ignore
    val recommendations: List<Show> = listOf(),
    override var isFavorite: Boolean = false,
) : Show, WatchItem, AppAdapter.Item {

    var released = released?.toCalendar()
    var favoritedAtMillis: Long? = null
    var lastPlayedAtMillis: Long? = null

    /** TMDb / provider content rating chip (e.g. PG-13, TV-MA). Not persisted. */
    @Ignore
    var contentRating: String? = null

    /** TMDb / provider title logo artwork (persisted for faster cold start). */
    var logo: String? = null

    /** BCP-47 language tag used when the logo was chosen. */
    var logoLanguage: String? = null

    /** Where [logo] came from (runtime only). */
    @Ignore
    var logoSource: com.dskja.betterstreamflix.logo.LogoSource =
        com.dskja.betterstreamflix.logo.LogoSource.UNKNOWN

    override var isWatched: Boolean = false
    override var watchedDate: Calendar? = null

    @Embedded
    override var watchHistory: WatchItem.WatchHistory? = null


    fun isSame(movie: Movie): Boolean {
        if (isFavorite != movie.isFavorite) return false
        if (favoritedAtMillis != movie.favoritedAtMillis) return false
        if (isWatched != movie.isWatched) return false
        if (watchedDate != movie.watchedDate) return false
        if (watchHistory != movie.watchHistory) return false
        if (lastPlayedAtMillis != movie.lastPlayedAtMillis) return false
        return true
    }

    fun merge(movie: Movie): Movie {
        this.isFavorite = movie.isFavorite
        this.favoritedAtMillis = movie.favoritedAtMillis
        this.isWatched = movie.isWatched
        this.watchedDate = movie.watchedDate
        this.watchHistory = movie.watchHistory
        this.lastPlayedAtMillis = movie.lastPlayedAtMillis
        adoptLogoFrom(movie)
        return this
    }

    /** Soft-adopt a library / Room logo without wiping a better in-memory TMDb URL. */
    private fun adoptLogoFrom(other: Movie) {
        val preferred = com.dskja.betterstreamflix.logo.TmdbLogoPicker.preferResolvedLogo(
            current = logo,
            tmdb = other.logo,
            currentLang = logoLanguage,
            wantedLang = other.logoLanguage,
        )
        if (preferred != null && preferred != logo) {
            logo = preferred
            if (preferred == other.logo) {
                logoLanguage = other.logoLanguage
                logoSource = other.logoSource
            }
        } else if (logo.isNullOrBlank() && !other.logo.isNullOrBlank()) {
            logo = other.logo
            logoLanguage = other.logoLanguage
            logoSource = other.logoSource
        } else if (
            preferred == logo &&
            preferred == other.logo &&
            com.dskja.betterstreamflix.logo.TmdbLogoPicker.primaryLanguage(logoLanguage) !=
            com.dskja.betterstreamflix.logo.TmdbLogoPicker.primaryLanguage(other.logoLanguage)
        ) {
            logoLanguage = other.logoLanguage
            logoSource = other.logoSource
        }
    }


    @Ignore
    override lateinit var itemType: AppAdapter.Type


    fun copy(
        id: String = this.id,
        title: String = this.title,
        overview: String? = this.overview,
        released: String? = this.released?.format("yyyy-MM-dd"),
        runtime: Int? = this.runtime,
        trailer: String? = this.trailer,
        quality: String? = this.quality,
        rating: Double? = this.rating,
        poster: String? = this.poster,
        banner: String? = this.banner,
        imdbId: String? = this.imdbId,
        genres: List<Genre> = this.genres,
        directors: List<People> = this.directors,
        cast: List<People> = this.cast,
        recommendations: List<Show> = this.recommendations,
        isFavorite: Boolean = this.isFavorite,
    ) = Movie(
        id,
        title,
        overview,
        released,
        runtime,
        trailer,
        quality,
        rating,
        poster,
        banner,
        imdbId,
        tmdbId,
        providerName,
        genres,
        directors,
        cast,
        recommendations,
        isFavorite,
    ).apply {
        lastPlayedAtMillis = this@Movie.lastPlayedAtMillis
        contentRating = this@Movie.contentRating
        logo = this@Movie.logo
        logoLanguage = this@Movie.logoLanguage
        logoSource = this@Movie.logoSource
        // copy() must keep adapter type — Featured swiper loop clones crash otherwise
        // (BETTERSTREAMFLIX-1P UninitializedPropertyAccessException on itemType).
        if (this@Movie::itemType.isInitialized) {
            itemType = this@Movie.itemType
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Movie

        if (id != other.id) return false
        if (title != other.title) return false
        if (overview != other.overview) return false
        if (runtime != other.runtime) return false
        if (trailer != other.trailer) return false
        if (quality != other.quality) return false
        if (rating != other.rating) return false
        if (poster != other.poster) return false
        if (banner != other.banner) return false
        if (logo != other.logo) return false
        if (logoLanguage != other.logoLanguage) return false
        if (imdbId != other.imdbId) return false
        if (genres != other.genres) return false
        if (directors != other.directors) return false
        if (cast != other.cast) return false
        if (recommendations != other.recommendations) return false
        if (isFavorite != other.isFavorite) return false
        if (released != other.released) return false
        if (favoritedAtMillis != other.favoritedAtMillis) return false
        if (isWatched != other.isWatched) return false
        if (watchedDate != other.watchedDate) return false
        if (watchHistory != other.watchHistory) return false
        if (lastPlayedAtMillis != other.lastPlayedAtMillis) return false
        if (!::itemType.isInitialized || !other::itemType.isInitialized) return false
        return itemType == other.itemType
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + (overview?.hashCode() ?: 0)
        result = 31 * result + (runtime ?: 0)
        result = 31 * result + (trailer?.hashCode() ?: 0)
        result = 31 * result + (quality?.hashCode() ?: 0)
        result = 31 * result + (rating?.hashCode() ?: 0)
        result = 31 * result + (poster?.hashCode() ?: 0)
        result = 31 * result + (banner?.hashCode() ?: 0)
        result = 31 * result + (logo?.hashCode() ?: 0)
        result = 31 * result + (logoLanguage?.hashCode() ?: 0)
        result = 31 * result + (imdbId?.hashCode() ?: 0)
        result = 31 * result + genres.hashCode()
        result = 31 * result + directors.hashCode()
        result = 31 * result + cast.hashCode()
        result = 31 * result + recommendations.hashCode()
        result = 31 * result + (released?.hashCode() ?: 0)
        result = 31 * result + isFavorite.hashCode()
        result = 31 * result + (favoritedAtMillis?.hashCode() ?: 0)
        result = 31 * result + isWatched.hashCode()
        result = 31 * result + (watchedDate?.hashCode() ?: 0)
        result = 31 * result + (watchHistory?.hashCode() ?: 0)
        result = 31 * result + (lastPlayedAtMillis?.hashCode() ?: 0)
        result = 31 * result + (if (::itemType.isInitialized) itemType.hashCode() else 0)
        return result
    }
}
