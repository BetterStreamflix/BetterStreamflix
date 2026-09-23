package com.dskja.betterstreamflix.fragments.player

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskja.betterstreamflix.BetterStreamflixApp
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.providers.ProviderSmoke
import com.dskja.betterstreamflix.utils.CrashReporter
import com.dskja.betterstreamflix.utils.CustomTabHelper
import com.dskja.betterstreamflix.utils.EpisodeManager
import com.dskja.betterstreamflix.utils.OpenSubtitles
import com.dskja.betterstreamflix.utils.SubDL
import com.dskja.betterstreamflix.utils.SubtitleFileCache
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val videoType: Video.Type,
    id: String,
    private val preferredServerName: String? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.LoadingServers)
    val state: Flow<State> = _state

    private val _subtitleState = MutableSharedFlow<SubtitleState>()
    val subtitleState: SharedFlow<SubtitleState> = _subtitleState

    private val _playPreviousOrNextEpisode = MutableSharedFlow<Video.Type.Episode>()
    val playPreviousOrNextEpisode: SharedFlow<Video.Type.Episode> = _playPreviousOrNextEpisode
    init {
        getServers(videoType, id)
        getSubtitles(videoType)
    }

    fun playEpisode(direction: Direction) {
        val hasEpisode = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.hasPreviousEpisode()
            Direction.NEXT -> EpisodeManager.hasNextEpisode()
        }

        if (!hasEpisode) return

        val ep = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.getPreviousEpisode()
            Direction.NEXT -> EpisodeManager.getNextEpisode()
        } ?: return

        val nextEpisode = Video.Type.Episode(
            id = ep.id,
            number = ep.number,
            title = ep.title,
            poster = ep.poster,
            overview = ep.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = ep.tvShow.id,
                title = ep.tvShow.title,
                poster = ep.tvShow.poster,
                banner = ep.tvShow.banner,
                releaseDate = ep.tvShow.releaseDate,
                imdbId = ep.tvShow.imdbId
            ),
            season = Video.Type.Episode.Season(
                number = ep.season.number,
                title = ep.season.title
            )
        )

        playEpisode(nextEpisode)

        viewModelScope.launch {
            _playPreviousOrNextEpisode.emit(nextEpisode)
        }
    }

    enum class Direction { PREVIOUS, NEXT }
    fun playPreviousEpisode() =
        playEpisode(Direction.PREVIOUS)

    fun playNextEpisode() =
        playEpisode(Direction.NEXT)

    fun playLiveChannel(channel: com.dskja.betterstreamflix.iptv.IptvLiveSession.Channel) {
        val episode = com.dskja.betterstreamflix.iptv.IptvLiveSession.toEpisodeType(channel)
        com.dskja.betterstreamflix.iptv.IptvLiveSession.setCurrent(channel.id)
        playEpisode(episode)
        viewModelScope.launch {
            _playPreviousOrNextEpisode.emit(episode)
        }
    }

    fun autoplayNextEpisode() {
        if (UserPreferences.autoplay) {
            playEpisode(Direction.NEXT)
        }
    }
    fun playEpisode(episode: Video.Type.Episode) {
        getServers(episode, episode.id)
        getSubtitles(episode)
    }

    private fun getServers(videoType: Video.Type, id: String) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio ricerca server per ID: $id")
        lastVideoType = videoType
        lastId = id
        _state.emit(State.LoadingServers)
        try {
            // Only prefer offline when the caller explicitly requested offline play.
            // A completed download must not block Online Play from fetching streaming servers.
            val preferOffline = preferredServerName.equals(OFFLINE_SERVER_NAME, ignoreCase = true)
            val offline = if (preferOffline) resolveOffline(videoType) else null
            if (preferOffline) {
                if (offline == null) {
                    val message = BetterStreamflixApp.instance
                        .getString(com.dskja.betterstreamflix.R.string.player_offline_missing)
                    _state.emit(State.FailedLoadingServers(Exception(message)))
                    return@launch
                }
                val server = Video.Server(
                    id = OFFLINE_SERVER_ID,
                    name = OFFLINE_SERVER_NAME,
                ).also { it.video = offline }
                _state.emit(State.SuccessLoadingServers(listOf(server)))
                return@launch
            }

            val provider = UserPreferences.currentProvider
                ?: throw Exception("No provider selected")
            val servers = provider.getServers(id, videoType)
            if (servers.isEmpty()) throw Exception("No servers found")
            
            // LOG POTENZIATO: Mostra tutti i server disponibili per il player
            Log.i("BetterStreamflix", "[SERVERS LIST] -> Provider: ${provider.name}")
            Log.i("BetterStreamflix", "[SERVERS LIST] -> Found ${servers.size} servers: ${servers.joinToString { it.name }}")

            Log.d("PlayerViewModel", "Ricerca server completata: ${servers.size} server trovati")
            _state.emit(State.SuccessLoadingServers(servers))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore ricerca server: ", e)
            _state.emit(State.FailedLoadingServers(e))
        }
    }

    fun getVideo(server: Video.Server) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio estrazione video dal server: ${server.name}")
        _state.emit(State.LoadingVideo(server))
        try {
            if (server.id.equals(OFFLINE_SERVER_ID, ignoreCase = true) ||
                server.name.equals(OFFLINE_SERVER_NAME, ignoreCase = true)
            ) {
                val cached = server.video
                    ?: lastVideoType?.let { resolveOffline(it) }
                    ?: throw Exception(
                        BetterStreamflixApp.instance
                            .getString(com.dskja.betterstreamflix.R.string.player_offline_missing),
                    )
                _state.emit(State.SuccessLoadingVideo(cached, server))
                return@launch
            }
            val provider = UserPreferences.currentProvider
                ?: throw Exception("No provider selected")
            val video = ProviderSmoke.withProviderTimeout(
                timeoutMs = ProviderSmoke.SERVERS_TIMEOUT_MS,
                label = "getVideo(${server.name})",
            ) {
                provider.getVideo(server)
            }
            if (video.source.isBlank()) throw Exception("No source found")

            // LOGICA SOTTOTITOLI GLOBALE: 
            // Se il provider non ha già impostato un default (es. i "forced" in spagnolo),
            // allora proviamo ad attivare l'ultimo sottotitolo usato dall'utente.
            // MA: se siamo su un provider spagnolo e non ci sono forced, non dobbiamo attivare nulla.
            val currentProviderLang = provider.language
            val hasDefaultAlready = video.subtitles.any { it.default }

            if (!hasDefaultAlready && currentProviderLang != "es") {
                if (!(video.useServerSubtitleSetting && UserPreferences.serverAutoSubtitlesDisabled)) {
                    video.subtitles
                        .firstOrNull { it.label.startsWith(UserPreferences.subtitleName ?: "") }
                        ?.default = true
		}
            }

            Log.d("PlayerViewModel", "Estrazione video completata con successo")
            _state.emit(State.SuccessLoadingVideo(video, server))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore estrazione video: ", e)
            // Permanent hoster misses (404 / unpack / deleted) are expected failover noise —
            // keep local logs but do not flood Sentry (BETTERSTREAMFLIX-10 / -12).
            if (!com.dskja.betterstreamflix.extractors.ExtractorFailureClassifier.isPermanent(e)) {
                CrashReporter.logNonFatal("PlayerViewModel", "getVideo failed: ${server.name}", e)
            }
            _state.emit(State.FailedLoadingVideo(e, server))
        }
    }

    private suspend fun resolveOffline(videoType: Video.Type): Video? {
        val context = BetterStreamflixApp.instance
        val providerName = UserPreferences.currentProvider?.name
        val item = com.dskja.betterstreamflix.download.OfflinePlayback.findCompleted(context, videoType)
            ?: com.dskja.betterstreamflix.download.OfflinePlayback.findCompletedAnyProvider(context, videoType)
            ?: return null
        // Always rebuild via Media3 so a stale OfflineVideoCache cannot skip validation.
        val built = com.dskja.betterstreamflix.download.OfflinePlayback.buildLocalVideo(context, item)
        if (providerName != null) {
            val key = com.dskja.betterstreamflix.download.OfflinePlayback.contentKeyFor(videoType, providerName)
            // Drop any warmed cache entry; buildLocalVideo is the source of truth.
            com.dskja.betterstreamflix.fragments.downloads.OfflineVideoCache.take(key)
            if (built != null) {
                com.dskja.betterstreamflix.fragments.downloads.OfflineVideoCache.put(key, built)
            } else {
                com.dskja.betterstreamflix.fragments.downloads.OfflineVideoCache.remove(key)
            }
        }
        return built
    }

    companion object {
        const val OFFLINE_SERVER_ID = "__offline__"
        const val OFFLINE_SERVER_NAME = "__offline__"

        fun isOfflineServer(server: Video.Server): Boolean =
            server.id.equals(OFFLINE_SERVER_ID, ignoreCase = true) ||
                server.name.equals(OFFLINE_SERVER_NAME, ignoreCase = true)

        fun mediaServerLabel(context: android.content.Context, server: Video.Server): String =
            if (isOfflineServer(server)) {
                context.getString(com.dskja.betterstreamflix.R.string.downloads_play_offline)
            } else {
                server.name
            }
    }

    fun getSubtitles(videoType: Video.Type) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio ricerca sottotitoli")
        _subtitleState.emit(SubtitleState.Loading)

        // Offline playback already carries sidecar subs — skip OpenSubtitles/SubDL net.
        if (preferredServerName.equals(OFFLINE_SERVER_NAME, ignoreCase = true)) {
            Log.d("PlayerViewModel", "Offline playback — skipping remote subtitle search")
            _subtitleState.emit(SubtitleState.SuccessOpenSubtitles(emptyList()))
            _subtitleState.emit(SubtitleState.SuccessSubDLSubtitles(emptyList()))
            return@launch
        }

        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca OpenSubtitles")
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> {
                        com.dskja.betterstreamflix.platform.subtitles.OpenSubtitlesBridge.search(
                            imdbId = videoType.tvShow.imdbId,
                            query = videoType.tvShow.title,
                            season = videoType.season.number,
                            episode = videoType.number,
                        )
                    }
                    is Video.Type.Movie -> {
                        com.dskja.betterstreamflix.platform.subtitles.OpenSubtitlesBridge.search(
                            imdbId = videoType.imdbId,
                            query = videoType.title,
                        )
                    }
                }.sortedWith(compareBy({ it.languageName }, { it.subDownloadsCnt }))
                
                Log.d("PlayerViewModel", "Ricerca OpenSubtitles completata: ${subtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessOpenSubtitles(subtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore OpenSubtitles: ", e)
                _subtitleState.emit(SubtitleState.FailedOpenSubtitles(e))
            }
        }

        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca SubDL")
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> {
                        SubDL.search(
                            filmName = videoType.tvShow.title,
                            seasonNumber = videoType.season.number,
                            episodeNumber = videoType.number,
                            type = "tv"
                        )
                    }
                    is Video.Type.Movie -> {
                        SubDL.search(
                            filmName = videoType.title,
                            type = "movie"
                        )
                    }
                }
                
                Log.d("PlayerViewModel", "Ricerca SubDL completata: ${subtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessSubDLSubtitles(subtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore SubDL: ", e)
                _subtitleState.emit(SubtitleState.FailedSubDLSubtitles(e))
            }
        }
    }

    fun downloadSubtitle(subtitle: OpenSubtitles.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio download sottotitolo OpenSubtitles: ${subtitle.subFileName}")
        _subtitleState.emit(SubtitleState.DownloadingOpenSubtitle)
        try {
            val contentKey = SubtitleFileCache.contentKey(videoType)
            val uri = com.dskja.betterstreamflix.platform.subtitles.OpenSubtitlesBridge.download(
                context = BetterStreamflixApp.instance,
                subtitle = subtitle,
                contentKey = contentKey,
            )
            Log.d("PlayerViewModel", "Download OpenSubtitles completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingOpenSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download OpenSubtitles: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingOpenSubtitle(e, subtitle))
        }
    }

    fun downloadSubDLSubtitle(subtitle: SubDL.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio download sottotitolo SubDL: ${subtitle.name}")
        _subtitleState.emit(SubtitleState.DownloadingSubDLSubtitle)
        try {
            val contentKey = SubtitleFileCache.contentKey(videoType)
            val uri = SubDL.download(
                context = BetterStreamflixApp.instance,
                subtitle = subtitle,
                contentKey = contentKey,
            )
            Log.d("PlayerViewModel", "Download SubDL completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingSubDLSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download SubDL: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingSubDLSubtitle(e, subtitle))
        }
    }

    sealed class State {
        data object LoadingServers : State()
        data class SuccessLoadingServers(val servers: List<Video.Server>) : State()
        data class FailedLoadingServers(val error: Exception) : State()
        data class LoadingVideo(val server: Video.Server) : State()
        data class SuccessLoadingVideo(val video: Video, val server: Video.Server) : State()
        data class FailedLoadingVideo(val error: Exception, val server: Video.Server) : State()
    }

    sealed class SubtitleState {
        data object Loading : SubtitleState()
        data class SuccessOpenSubtitles(val subtitles: List<OpenSubtitles.Subtitle>) : SubtitleState()
        data class FailedOpenSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingOpenSubtitle : SubtitleState()
        data class SuccessDownloadingOpenSubtitle(val subtitle: OpenSubtitles.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingOpenSubtitle(val error: Exception, val subtitle: OpenSubtitles.Subtitle) : SubtitleState()

        data class SuccessSubDLSubtitles(val subtitles: List<SubDL.Subtitle>) : SubtitleState()
        data class FailedSubDLSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingSubDLSubtitle : SubtitleState()
        data class SuccessDownloadingSubDLSubtitle(val subtitle: SubDL.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingSubDLSubtitle(val error: Exception, val subtitle: SubDL.Subtitle) : SubtitleState()
    }
    private var lastVideoType: Video.Type? = null
    private var lastId: String? = null
    fun reloadServersAfterBypass() {
        val type = lastVideoType ?: return
        val id = lastId ?: return
        getServers(type, id)
    }
}
