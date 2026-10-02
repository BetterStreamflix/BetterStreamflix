package com.dskja.betterstreamflix.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.Serializable

data class Video(
    val source: String,
    val subtitles: List<Subtitle> = listOf(),
    val headers: Map<String, String>? = null,
    val type: String? = null,
    val extraBuffering: Boolean = false,
    val useServerSubtitleSetting: Boolean = false,
    val maintainToken: Boolean = false,
    /**
     * Media3 download id for offline playback. When set, the player must build the
     * [androidx.media3.common.MediaItem] from the matching [androidx.media3.exoplayer.offline.DownloadRequest]
     * (stream keys + cache key) so HLS/DASH variants hit the download cache instead of the network.
     */
    val offlineMedia3Id: String? = null,
) : Serializable {

    sealed class Type : Parcelable, Serializable {
        @Parcelize
        data class Movie(
            val id: String,
            val title: String,
            val releaseDate: String,
            val poster: String,
            val imdbId: String?,
        ) : Type(), Serializable

        @Parcelize
        data class Episode(
            val id: String,
            val number: Int,
            val title: String?,
            val poster: String?,
            val overview: String?,
            val tvShow: TvShow,
            val season: Season,
        ) : Type(), Serializable {
            @Parcelize
            data class TvShow(
                val id: String,
                val title: String,
                val poster: String?,
                val banner: String?,
                val releaseDate: String?,
                val imdbId: String?,
            ) : Parcelable, Serializable

            @Parcelize
            data class Season(
                val number: Int,
                val title: String?,
            ) : Parcelable, Serializable
        }
    }

    data class Subtitle(
        val label: String,
        val file: String,
        var default: Boolean = false,
        val initialDefault: Boolean = false
    ) : Serializable

    data class Server(
        val id: String,
        val name: String,
        val src: String = "",
    ) : Serializable {
        var video: Video? = null
    }
}
