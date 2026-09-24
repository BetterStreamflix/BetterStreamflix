package com.dskja.betterstreamflix.adapters.viewholders

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AlertDialog as AppCompatAlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import androidx.fragment.app.Fragment
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.TvFocusZoom
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.download.DownloadContentKey
import com.dskja.betterstreamflix.download.OfflineBadgeStore
import com.dskja.betterstreamflix.download.ui.DownloadOptionsController
import com.dskja.betterstreamflix.databinding.ContentMovieCastMobileBinding
import com.dskja.betterstreamflix.databinding.ContentMovieCastTvBinding
import com.dskja.betterstreamflix.databinding.ContentMovieMobileBinding
import com.dskja.betterstreamflix.databinding.ContentDetailAboutMobileBinding
import com.dskja.betterstreamflix.databinding.ContentDetailTrailerMobileBinding
import com.dskja.betterstreamflix.databinding.ContentDetailTabsMobileBinding
import com.dskja.betterstreamflix.databinding.ItemDetailTrailerRowMobileBinding
import com.dskja.betterstreamflix.utils.TmdbUtils
import com.dskja.betterstreamflix.databinding.ContentMovieRecommendationsMobileBinding
import com.dskja.betterstreamflix.databinding.ContentMovieRecommendationsTvBinding
import com.dskja.betterstreamflix.databinding.ContentMovieTvBinding
import com.dskja.betterstreamflix.databinding.ItemCategorySwiperMobileBinding
import com.dskja.betterstreamflix.databinding.ItemMovieGridMobileBinding
import com.dskja.betterstreamflix.databinding.ItemMovieGridTvBinding
import com.dskja.betterstreamflix.databinding.ItemMovieMobileBinding
import com.dskja.betterstreamflix.databinding.ItemMovieTvBinding
import com.dskja.betterstreamflix.fragments.favorites.FavoritesMobileFragment
import com.dskja.betterstreamflix.fragments.favorites.FavoritesMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.favorites.FavoritesTvFragment
import com.dskja.betterstreamflix.fragments.favorites.FavoritesTvFragmentDirections
import com.dskja.betterstreamflix.fragments.genre.GenreMobileFragment
import com.dskja.betterstreamflix.fragments.genre.GenreMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.genre.GenreTvFragment
import com.dskja.betterstreamflix.fragments.genre.GenreTvFragmentDirections
import com.dskja.betterstreamflix.fragments.home.HomeMobileFragment
import com.dskja.betterstreamflix.fragments.home.HomeMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.home.HomeTvFragment
import com.dskja.betterstreamflix.fragments.home.HomeTvFragmentDirections
import com.dskja.betterstreamflix.fragments.movie.MovieMobileFragment
import com.dskja.betterstreamflix.fragments.movie.MovieMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.movie.MovieTvFragment
import com.dskja.betterstreamflix.fragments.movie.MovieTvFragmentDirections
import com.dskja.betterstreamflix.fragments.movies.MoviesMobileFragment
import com.dskja.betterstreamflix.fragments.movies.MoviesMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.movies.MoviesTvFragment
import com.dskja.betterstreamflix.fragments.movies.MoviesTvFragmentDirections
import com.dskja.betterstreamflix.fragments.people.PeopleMobileFragment
import com.dskja.betterstreamflix.fragments.people.PeopleMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.people.PeopleTvFragment
import com.dskja.betterstreamflix.fragments.people.PeopleTvFragmentDirections
import com.dskja.betterstreamflix.fragments.search.SearchMobileFragment
import com.dskja.betterstreamflix.fragments.search.SearchMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.search.SearchTvFragment
import com.dskja.betterstreamflix.fragments.search.SearchTvFragmentDirections
import com.dskja.betterstreamflix.fragments.tv_show.TvShowMobileFragment
import com.dskja.betterstreamflix.fragments.tv_show.TvShowMobileFragmentDirections
import com.dskja.betterstreamflix.fragments.tv_show.TvShowTvFragment
import com.dskja.betterstreamflix.fragments.tv_show.TvShowTvFragmentDirections
import com.dskja.betterstreamflix.fragments.tv_shows.TvShowsTvFragment
import com.dskja.betterstreamflix.fragments.tv_shows.TvShowsTvFragmentDirections
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.ui.FeaturedHeroController
import com.dskja.betterstreamflix.ui.FeaturedProviderSwitch
import com.dskja.betterstreamflix.ui.FeaturedSwiperChrome
import com.dskja.betterstreamflix.ui.ShowOptionsMobileDialog
import com.dskja.betterstreamflix.ui.ShowOptionsTvDialog
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.ui.TrailerPlaybackController
import com.dskja.betterstreamflix.ui.DetailTab
import com.dskja.betterstreamflix.ui.TmdbLogoGlide
import androidx.recyclerview.widget.GridLayoutManager
import com.dskja.betterstreamflix.utils.ExpAmbientGlow
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.dp
import androidx.preference.Preference
import com.dskja.betterstreamflix.utils.format
import com.dskja.betterstreamflix.utils.getCurrentFragment
import com.dskja.betterstreamflix.utils.loadMovieBanner
import com.dskja.betterstreamflix.utils.loadMoviePoster
import com.dskja.betterstreamflix.utils.ArtworkRepair
import com.dskja.betterstreamflix.utils.toActivity
import java.util.Locale
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.providers.Provider
import android.view.KeyEvent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.dskja.betterstreamflix.databinding.ContentMovieDirectorsMobileBinding
import com.dskja.betterstreamflix.databinding.ContentMovieDirectorsTvBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dskja.betterstreamflix.databinding.ContentDetailTrailerTvBinding
import com.dskja.betterstreamflix.models.Trailer

class MovieViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private val database: AppDatabase
        get() = AppDatabase.getInstance(context)

    init {
        if (ExperimentalMobileDesign.enabled() &&
            (_binding is ItemMovieMobileBinding ||
                _binding is ItemMovieGridMobileBinding ||
                _binding is ItemCategorySwiperMobileBinding ||
                _binding is ItemMovieTvBinding ||
                _binding is ItemMovieGridTvBinding)
        ) {
            itemView.applyExpPress()
        }
    }
    private lateinit var movie: Movie
    private var onMovieClick: ((Movie) -> Unit)? = null
    private var onMovieLongClick: ((Movie) -> Unit)? = null
    private var onMovieKey: ((Movie, KeyEvent) -> Boolean)? = null
    private var itemSelected: Boolean = false
    private var ribbonStateJob: Job? = null
    private val TAG = "TrailerChoiceDebug" // Logging Tag

    companion object {
        private const val KEY_PREFERRED_PLAYER = "preferred_player"
        private const val KEY_SMARTTUBE_PACKAGE = "preferred_smarttube_package" // New key for saving the exact package
        private const val PLAYER_YOUTUBE = "youtube"
        private const val PLAYER_SMARTTUBE = "smarttube"
        private const val PLAYER_SMARTTUBE_STABLE = "smarttube_stable"
        private const val PLAYER_SMARTTUBE_BETA = "smarttube_beta"
        private const val PLAYER_ASK = "ask"
        private const val SMARTTUBE_STABLE_PACKAGE = "org.smarttube.stable"
        private const val SMARTTUBE_BETA_PACKAGE = "org.smarttube.beta"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val YOUTUBE_TV_PACKAGE = "com.google.android.tv.youtube"

        const val DETAIL_SECTION_RECOMMENDATIONS = "detail_section_recommendations"
        const val DETAIL_SECTION_TRAILER = "detail_section_trailer"
        const val DETAIL_SECTION_ABOUT = "detail_section_about"
    }

    val childRecyclerView: RecyclerView?
        get() = when (_binding) {
            is ContentMovieCastMobileBinding -> _binding.rvMovieCast
            is ContentMovieCastTvBinding -> _binding.hgvMovieCast
            is ContentMovieDirectorsTvBinding -> _binding.hgvMovieDirectors
            is ContentMovieRecommendationsMobileBinding -> _binding.rvMovieRecommendations
            is ContentMovieRecommendationsTvBinding -> _binding.hgvMovieRecommendations
            is ContentDetailTrailerTvBinding -> _binding.hgvDetailTrailers
            else -> null
        }

    fun bind(
        movie: Movie,
        onMovieClick: ((Movie) -> Unit)? = null,
        onMovieLongClick: ((Movie) -> Unit)? = null,
        onMovieKey: ((Movie, KeyEvent) -> Boolean)? = null,
        itemSelected: Boolean = false,
    ) {
        this.movie = movie
        this.onMovieClick = onMovieClick
        this.onMovieLongClick = onMovieLongClick
        this.onMovieKey = onMovieKey
        this.itemSelected = itemSelected

        when (_binding) {
            is ItemMovieMobileBinding -> displayMobileItem(_binding)
            is ItemMovieTvBinding -> displayTvItem(_binding)
            is ItemMovieGridMobileBinding -> displayGridMobileItem(_binding)
            is ItemMovieGridTvBinding -> displayGridTvItem(_binding)
            is ItemCategorySwiperMobileBinding -> displaySwiperMobileItem(_binding)

            is ContentMovieMobileBinding -> displayMovieMobile(_binding)
            is ContentMovieTvBinding -> displayMovieTv(_binding)
            is ContentMovieDirectorsMobileBinding -> displayDirectorsMobile(_binding)
            is ContentMovieDirectorsTvBinding -> displayDirectorsTv(_binding)
            is ContentMovieCastMobileBinding -> displayCastMobile(_binding)
            is ContentMovieCastTvBinding -> displayCastTv(_binding)
            is ContentMovieRecommendationsMobileBinding -> displayRecommendationsMobile(_binding)
            is ContentMovieRecommendationsTvBinding -> displayRecommendationsTv(_binding)
            is ContentDetailTabsMobileBinding -> displayTabsMobile(_binding)
            is ContentDetailTrailerMobileBinding -> displayTrailerMobile(_binding)
            is ContentDetailTrailerTvBinding -> displayTrailerTv(_binding)
            is ContentDetailAboutMobileBinding -> displayAboutMobile(_binding)
            is ContentDetailTabsMobileBinding -> displayTabsMobile(_binding)
            is ContentDetailTrailerMobileBinding -> displayTrailerMobile(_binding)
            is ContentDetailAboutMobileBinding -> displayAboutMobile(_binding)
        }
    }

    fun setItemSelected(selected: Boolean) {
        itemSelected = selected
        when (_binding) {
            is ItemMovieGridMobileBinding -> {
                _binding.root.isActivated = selected
                applyMobileSelection(_binding.root)
            }
            is ItemMovieGridTvBinding -> _binding.root.isActivated = selected
        }
    }

    private fun checkProviderAndRun(action: () -> Unit) {
        val providerName = movie.providerName
        if (!providerName.isNullOrBlank() && providerName != UserPreferences.currentProvider?.name) {
            Provider.findByName(providerName)?.let {
                UserPreferences.setCurrentProviderForPlayback(it)
            }
        }
        action()
    }

    /** Leanback: confirm before jumping straight into the player from Continue Watching. */
    private fun openContinueWatchingMovie(movie: Movie) {
        val preferredServer = preferredOfflineServerName()
        val play = {
            itemView.findNavController().navigate(
                R.id.action_global_player,
                Bundle().apply {
                    putString("id", movie.id)
                    putString("title", movie.title)
                    putString("subtitle", movie.released?.format("yyyy") ?: "")
                    putSerializable(
                        "videoType",
                        Video.Type.Movie(
                            id = movie.id,
                            title = movie.title,
                            releaseDate = movie.released?.format("yyyy-MM-dd") ?: "",
                            poster = movie.poster ?: movie.banner ?: "",
                            imdbId = movie.imdbId,
                        ),
                    )
                    preferredServer?.let { putString("preferredServerName", it) }
                },
            )
        }
        val details = {
            itemView.findNavController().navigate(
                HomeTvFragmentDirections.actionHomeToMovie(id = movie.id),
            )
        }
        val leanback = DeviceCapabilities.isLeanbackDevice(context) ||
            DeviceCapabilities.isAmazonFireTv(context)
        if (!leanback) {
            play()
            return
        }
        val items = arrayOf(
            context.getString(R.string.home_swiper_watch_now),
            context.getString(R.string.continue_watching_go_to_details),
            context.getString(R.string.option_cancel),
        )
        AlertDialog.Builder(context)
            .setTitle(R.string.continue_watching_confirm_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> play()
                    1 -> details()
                }
            }
            .show()
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun getInstalledSmartTubePackages(): List<String> {
        val installed = mutableListOf<String>()
        if (isPackageInstalled(SMARTTUBE_STABLE_PACKAGE)) installed.add(SMARTTUBE_STABLE_PACKAGE)
        if (isPackageInstalled(SMARTTUBE_BETA_PACKAGE)) installed.add(SMARTTUBE_BETA_PACKAGE)
        return installed
    }

    private fun launchSmartTube(packageName: String, trailerUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, trailerUrl.toUri())
        intent.setPackage(packageName)
        context.startActivity(intent)
    }

    private fun showSmartTubeVersionDialog(packages: List<String>, trailerUrl: String, shouldSavePreference: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = prefs.edit()
        
        val items = packages.map { pkg ->
            if (pkg == SMARTTUBE_STABLE_PACKAGE) context.getString(R.string.smarttube_stable)
            else context.getString(R.string.smarttube_beta)
        }.toTypedArray()

        (if (ExperimentalMobileDesign.enabled()) MaterialAlertDialogBuilder(context) else AlertDialog.Builder(context))
            .setTitle(context.getString(R.string.choose_smarttube_version))
            .setItems(items) { _, which ->
                val selectedPackage = packages[which]
                
                if (shouldSavePreference) {
                    // Salva la scelta dell'utente se la preferenza principale è "smarttube"
                    editor.putString(KEY_SMARTTUBE_PACKAGE, selectedPackage).apply()
                    Log.d(TAG, "SmartTube version saved: $selectedPackage")
                }
                
                launchSmartTube(selectedPackage, trailerUrl)
            }
            .create()
            .also { com.dskja.betterstreamflix.ui.TrailerPlaybackController.polishChooserDialog(it) }
    }

    private fun safeLaunchYoutube(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch YouTube intent", e)
            val message = context.getString(R.string.player_external_player_error_video)
            if (ExperimentalMobileDesign.enabled()) {
                ExpDialogChrome.showInfo(
                    context,
                    R.string.youtube,
                    message,
                ) { ctx ->
                    MaterialAlertDialogBuilder(ctx)
                }
            } else {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleSmartTubeSelection(trailerUrl: String, logPrefix: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val savedPackage = prefs.getString(KEY_SMARTTUBE_PACKAGE, null)
        val stPackages = getInstalledSmartTubePackages()

        Log.d(TAG, "$logPrefix: SmartTube packages found: ${stPackages.size}. Saved package: $savedPackage")
        
        if (stPackages.isEmpty()) {
            // Caso 1: Nessuna SmartTube installata. Fallback su YouTube.
            Log.d(TAG, "$logPrefix: No SmartTube installed, falling back to YouTube")
            safeLaunchYoutube(Intent(Intent.ACTION_VIEW, trailerUrl.toUri()))
            return
        }

        if (stPackages.size == 1) {
            // Caso 2: Una sola SmartTube installata. Avvia direttamente.
            Log.d(TAG, "$logPrefix: Only one SmartTube installed: ${stPackages[0]}. Launching directly.")
            launchSmartTube(stPackages[0], trailerUrl)
            return
        }
        
        // Caso 3: Stable e Beta installate.
        if (savedPackage != null && stPackages.contains(savedPackage)) {
            // Caso 3a: Versione preferita è installata. Avvia direttamente la versione salvata.
            Log.d(TAG, "$logPrefix: Saved SmartTube version found: $savedPackage. Launching directly.")
            launchSmartTube(savedPackage, trailerUrl)
        } else {
            // Caso 3b: Nessuna preferenza salvata O la versione salvata non è più installata. Chiedi all'utente e salva la nuova scelta.
            Log.d(TAG, "$logPrefix: Saved version invalid or missing. Asking user which version to use.")
            showSmartTubeVersionDialog(stPackages, trailerUrl, true)
        }
    }

    private fun handleTrailerClick(trailer: String, logPrefix: String) {
        Log.d(TAG, "$logPrefix: Clicked. Trailer URL: $trailer")

        val youtubeIntent = Intent(Intent.ACTION_VIEW, trailer.toUri())
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val preferredPlayer = prefs.getString(KEY_PREFERRED_PLAYER, PLAYER_ASK)
        Log.d(TAG, "$logPrefix: Preferred player from settings: $preferredPlayer")

        when (preferredPlayer) {
            PLAYER_SMARTTUBE -> {
                handleSmartTubeSelection(trailer, logPrefix)
            }
            PLAYER_SMARTTUBE_STABLE -> {
                Log.d(TAG, "$logPrefix: Launching SmartTube Stable (Preferred)")
                launchSmartTube(SMARTTUBE_STABLE_PACKAGE, trailer)
            }
            PLAYER_SMARTTUBE_BETA -> {
                Log.d(TAG, "$logPrefix: Launching SmartTube Beta (Preferred)")
                launchSmartTube(SMARTTUBE_BETA_PACKAGE, trailer)
            }
            PLAYER_YOUTUBE -> {
                Log.d(TAG, "$logPrefix: Launching YouTube (Preferred)")
                safeLaunchYoutube(youtubeIntent)
            }
            else -> { // PLAYER_ASK or nothing set
                val stPackages = getInstalledSmartTubePackages()
                if (stPackages.isNotEmpty()) {
                    Log.d(TAG, "$logPrefix: Showing choice dialog (Ask)")
                    (if (ExperimentalMobileDesign.enabled()) MaterialAlertDialogBuilder(context) else AlertDialog.Builder(context))
                        .setTitle(context.getString(R.string.watch_trailer_with))
                        .setItems(arrayOf(context.getString(R.string.youtube), context.getString(R.string.smarttube))) { _, which ->
                            if (which == 0) {
                                Log.d(TAG, "$logPrefix: Dialog (Ask): YouTube selected")
                                safeLaunchYoutube(youtubeIntent)
                            } else {
                                Log.d(TAG, "$logPrefix: Dialog (Ask): SmartTube selected")
                                // Qui, non salvare la preferenza per la versione SmartTube,
                                // ma chiedi quale usare se ci sono due installazioni.
                                if (stPackages.size > 1) {
                                    showSmartTubeVersionDialog(stPackages, trailer, false)
                                } else {
                                    launchSmartTube(stPackages[0], trailer)
                                }
                            }
                        }
                        .create()
                        .also { com.dskja.betterstreamflix.ui.TrailerPlaybackController.polishChooserDialog(it) }
                } else {
                    Log.d(TAG, "$logPrefix: SmartTube not found, launching YouTube directly")
                    safeLaunchYoutube(youtubeIntent)
                }
            }
        }
    }

    private fun displayMobileItem(binding: ItemMovieMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                onMovieClick?.let { listener ->
                    listener(movie)
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    when (context.toActivity()?.getCurrentFragment()) {
                        is HomeMobileFragment -> {
                            if (movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM) {
                                findNavController().navigate(
                                    R.id.action_global_player,
                                    Bundle().apply {
                                        putString("id", movie.id)
                                        putString("title", movie.title)
                                        putString("subtitle", movie.released?.format("yyyy") ?: "")
                                        putSerializable(
                                            "videoType",
                                            Video.Type.Movie(
                                                id = movie.id,
                                                title = movie.title,
                                                releaseDate = movie.released?.format("yyyy-MM-dd") ?: "",
                                                poster = movie.poster ?: "",
                                                imdbId = movie.imdbId,
                                            ),
                                        )
                                        preferredOfflineServerName()?.let {
                                            putString("preferredServerName", it)
                                        }
                                    },
                                )
                            } else {
                                findNavController().navigate(
                                    HomeMobileFragmentDirections.actionHomeToMovie(id = movie.id),
                                )
                            }
                        }
                        is MovieMobileFragment -> findNavController().navigate(MovieMobileFragmentDirections.actionMovieToMovie(id = movie.id))
                        is TvShowMobileFragment -> findNavController().navigate(TvShowMobileFragmentDirections.actionTvShowToMovie(id = movie.id))
                        is FavoritesMobileFragment -> findNavController().navigate(FavoritesMobileFragmentDirections.actionFavoritesToMovie(id = movie.id))
                    }
                }
            }
            setOnLongClickListener {
                onMovieLongClick?.let { listener ->
                    listener(movie)
                    return@setOnLongClickListener true
                }
                ShowOptionsMobileDialog(context, movie).show()
                true
            }
        }

        binding.ivMoviePoster.loadMoviePoster(movie) {
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }
        bindRibbons(binding.ivMovieFavoriteRibbon, binding.ivMovieWatchedRibbon, binding.ivMovieDownloadRibbon)

        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            val show = !text.isNullOrEmpty()
            val wasVisible = visibility == View.VISIBLE
            visibility = if (show) View.VISIBLE else View.GONE
            if (ExperimentalMobileDesign.enabled() && show) {
                setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }

        binding.tvMovieReleasedYear.apply {
            val year = movie.released?.format("yyyy")
            text = year ?: context.getString(R.string.movie_item_type)
            if (ExperimentalMobileDesign.enabled() && !year.isNullOrBlank()) {
                setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                val wasVisible = visibility == View.VISIBLE
                visibility = View.VISIBLE
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }

        bindMovieProgress(binding.pbMovieProgress)
        if (ExperimentalMobileDesign.enabled() &&
            movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM
        ) {
            ExpMotion.kenBurns(binding.ivMoviePoster)
            binding.root.findViewById<android.widget.TextView>(R.id.tv_movie_remaining)?.let { remaining ->
                val watchHistory = movie.watchHistory
                if (watchHistory != null && watchHistory.durationMillis > 0) {
                    val pct = (watchHistory.lastPlaybackPositionMillis * 100 /
                        watchHistory.durationMillis.toDouble()).toInt().coerceIn(0, 99)
                    remaining.text = context.getString(R.string.continue_watching_percent, pct)
                    remaining.setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                    val wasVisible = remaining.visibility == View.VISIBLE
                    remaining.visibility = View.VISIBLE
                    if (!wasVisible) ExpMotion.popIn(remaining)
                } else {
                    remaining.visibility = View.GONE
                }
            }
        } else {
            binding.root.findViewById<View>(R.id.tv_movie_remaining)?.visibility = View.GONE
        }

        binding.tvMovieTitle.text = movie.title
        com.dskja.betterstreamflix.logo.TitleLogoSurface.bindCachedOnly(
            imageView = binding.ivMovieLogo,
            titleView = null,
            logoUrl = movie.logo,
            title = movie.title,
            hideUntilReady = true,
        )
        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            if (movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM) {
                binding.root.setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
            }
            ExpMotion.kenBurns(binding.ivMoviePoster)
            ExpMotion.revealHeader(binding.tvMovieTitle)
            ExpMotion.popIn(binding.root)
        }
    }

    private fun displayTvItem(binding: ItemMovieTvBinding) {
        binding.root.apply {
            isFocusable = true
            setOnClickListener {
                ExpMotion.hapticTap(it)
                onMovieClick?.let { listener ->
                    listener(movie)
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    when (context.toActivity()?.getCurrentFragment()) {
                        is HomeTvFragment -> {
                            if (movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM) {
                                openContinueWatchingMovie(movie)
                            } else {
                                findNavController().navigate(HomeTvFragmentDirections.actionHomeToMovie(id = movie.id))
                            }
                        }
                        is MoviesTvFragment -> findNavController().navigate(MoviesTvFragmentDirections.actionMoviesToMovie(id = movie.id))
                        is GenreTvFragment -> findNavController().navigate(GenreTvFragmentDirections.actionGenreToMovie(id = movie.id))
                        is SearchTvFragment -> findNavController().navigate(SearchTvFragmentDirections.actionSearchToMovie(id = movie.id))
                        is MovieTvFragment -> findNavController().navigate(MovieTvFragmentDirections.actionMovieToMovie(id = movie.id))
                        is TvShowTvFragment -> findNavController().navigate(TvShowTvFragmentDirections.actionTvShowToMovie(id = movie.id))
                        is PeopleTvFragment -> findNavController().navigate(PeopleTvFragmentDirections.actionPeopleToMovie(id = movie.id))
                        is FavoritesTvFragment -> findNavController().navigate(FavoritesTvFragmentDirections.actionFavoritesToMovie(id = movie.id))
                    }
                }
            }

            setOnLongClickListener {
                ExpMotion.hapticTap(it)
                onMovieLongClick?.let { listener ->
                    listener(movie)
                    return@setOnLongClickListener true
                }
                ShowOptionsTvDialog(context, movie).show()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                TvFocusZoom.apply(itemView, hasFocus)

                when (val fragment = context.toActivity()?.getCurrentFragment()) {
                    is HomeTvFragment -> {
                        if (hasFocus) {
                            fragment.pinBackground(movie.banner)
                        } else {
                            fragment.releasePinnedBackground()
                        }
                    }
                }
            }
        }

        binding.ivMoviePoster.loadMoviePoster(movie) {
            fallback(R.drawable.glide_fallback_cover)
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }
        bindRibbons(binding.ivMovieFavoriteRibbon, binding.ivMovieWatchedRibbon, binding.ivMovieDownloadRibbon)
        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory
            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }
        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            val show = !text.isNullOrEmpty()
            val wasVisible = visibility == View.VISIBLE
            visibility = if (show) View.VISIBLE else View.GONE
        }
        binding.tvMovieReleasedYear.apply {
            val year = movie.released?.format("yyyy")
            text = year ?: context.getString(R.string.movie_item_type)
        }
        binding.tvMovieTitle.text = movie.title
        binding.root.findViewById<View>(R.id.tv_movie_remaining)?.visibility = View.GONE
    }

    private fun displayGridMobileItem(binding: ItemMovieGridMobileBinding) {
        binding.root.apply {
            alpha = 1f
            isActivated = itemSelected
            applyMobileSelection(this)
            setOnKeyListener { _, _, event -> onMovieKey?.invoke(movie, event) ?: false }
            setOnClickListener {
                ExpMotion.hapticTap(it)
                onMovieClick?.let { listener ->
                    listener(movie)
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    when (context.toActivity()?.getCurrentFragment()) {
                        is GenreMobileFragment -> findNavController().navigate(GenreMobileFragmentDirections.actionGenreToMovie(id = movie.id))
                        is MoviesMobileFragment -> findNavController().navigate(MoviesMobileFragmentDirections.actionMoviesToMovie(id = movie.id))
                        is PeopleMobileFragment -> findNavController().navigate(PeopleMobileFragmentDirections.actionPeopleToMovie(id = movie.id))
                        is SearchMobileFragment -> findNavController().navigate(SearchMobileFragmentDirections.actionSearchToMovie(id = movie.id))
                        is MovieMobileFragment -> findNavController().navigate(MovieMobileFragmentDirections.actionMovieToMovie(id = movie.id))
                        is TvShowMobileFragment -> findNavController().navigate(TvShowMobileFragmentDirections.actionTvShowToMovie(id = movie.id))
                        is FavoritesMobileFragment -> findNavController().navigate(FavoritesMobileFragmentDirections.actionFavoritesToMovie(id = movie.id))
                    }
                }
            }
            setOnLongClickListener {
                onMovieLongClick?.let { listener ->
                    listener(movie)
                    return@setOnLongClickListener true
                }
                ShowOptionsMobileDialog(context, movie).show()
                true
            }
        }

        binding.ivMoviePoster.loadMoviePoster(movie) {
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }
        bindRibbons(binding.ivMovieFavoriteRibbon, binding.ivMovieWatchedRibbon, binding.ivMovieDownloadRibbon)

        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            val show = !text.isNullOrEmpty()
            val wasVisible = visibility == View.VISIBLE
            visibility = if (show) View.VISIBLE else View.GONE
            if (ExperimentalMobileDesign.enabled() && show) {
                setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }

        binding.tvMovieReleasedYear.apply {
            val year = movie.released?.format("yyyy")
            text = year
            if (ExperimentalMobileDesign.enabled() && !year.isNullOrBlank()) {
                setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                setPadding(
                    (6 * context.resources.displayMetrics.density).toInt(),
                    (2 * context.resources.displayMetrics.density).toInt(),
                    (6 * context.resources.displayMetrics.density).toInt(),
                    (2 * context.resources.displayMetrics.density).toInt(),
                )
                val wasVisible = visibility == View.VISIBLE
                visibility = View.VISIBLE
                if (!wasVisible) ExpMotion.popIn(this)
            } else {
                visibility = View.GONE
            }
        }

        bindMovieProgress(binding.pbMovieProgress)

        binding.tvMovieTitle.text = movie.title
        com.dskja.betterstreamflix.logo.TitleLogoSurface.bindCachedOnly(
            imageView = binding.ivMovieLogo,
            titleView = null,
            logoUrl = movie.logo,
            title = movie.title,
            hideUntilReady = true,
        )
        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.kenBurns(binding.ivMoviePoster)
            ExpMotion.revealHeader(binding.tvMovieTitle)
            ExpMotion.popIn(binding.root)
        }
    }

    private fun displayGridTvItem(binding: ItemMovieGridTvBinding) {
        binding.root.apply {
            isFocusable = true
            alpha = 1f
            isActivated = itemSelected
            setOnKeyListener { _, _, event -> onMovieKey?.invoke(movie, event) ?: false }
            setOnClickListener {
                ExpMotion.hapticTap(it)
                onMovieClick?.let { listener ->
                    listener(movie)
                    return@setOnClickListener
                }
                checkProviderAndRun {
                    when (context.toActivity()?.getCurrentFragment()) {
                        is HomeTvFragment -> findNavController().navigate(HomeTvFragmentDirections.actionHomeToMovie(id = movie.id))
                        is MoviesTvFragment -> findNavController().navigate(MoviesTvFragmentDirections.actionMoviesToMovie(id = movie.id))
                        is GenreTvFragment -> findNavController().navigate(GenreTvFragmentDirections.actionGenreToMovie(id = movie.id))
                        is SearchTvFragment -> findNavController().navigate(SearchTvFragmentDirections.actionSearchToMovie(id = movie.id))
                        is MovieTvFragment -> findNavController().navigate(MovieTvFragmentDirections.actionMovieToMovie(id = movie.id))
                        is TvShowTvFragment -> findNavController().navigate(TvShowTvFragmentDirections.actionTvShowToMovie(id = movie.id))
                        is PeopleTvFragment -> findNavController().navigate(PeopleTvFragmentDirections.actionPeopleToMovie(id = movie.id))
                        is FavoritesTvFragment -> findNavController().navigate(FavoritesTvFragmentDirections.actionFavoritesToMovie(id = movie.id))
                    }
                }
            }

            setOnLongClickListener {
                ExpMotion.hapticTap(it)
                onMovieLongClick?.let { listener ->
                    listener(movie)
                    return@setOnLongClickListener true
                }
                ShowOptionsTvDialog(context, movie).show()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                TvFocusZoom.apply(itemView, hasFocus)
            }
        }
        binding.ivMoviePoster.loadMoviePoster(movie) {
            fallback(R.drawable.glide_fallback_cover)
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }
        bindRibbons(binding.ivMovieFavoriteRibbon, binding.ivMovieWatchedRibbon, binding.ivMovieDownloadRibbon)
        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory
            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }
        binding.tvMovieQuality.apply {
            text = movie.quality ?: ""
            val show = !text.isNullOrEmpty()
            val wasVisible = visibility == View.VISIBLE
            visibility = if (show) View.VISIBLE else View.GONE
        }
        binding.tvMovieReleasedYear.apply {
            val year = movie.released?.format("yyyy")
            text = year ?: context.getString(R.string.movie_item_type)
        }
        binding.tvMovieTitle.text = movie.title
    }

    private fun applyMobileSelection(view: View) {
        view.findViewById<View?>(R.id.v_movie_select_ring)?.let { ring ->
            val selectionMode = onMovieClick != null
            if (selectionMode) {
                ring.visibility = View.VISIBLE
                ring.setBackgroundResource(
                    if (itemSelected) R.drawable.bg_mylist_select_checked
                    else R.drawable.bg_mylist_select_ring,
                )
            } else {
                ring.visibility = View.GONE
            }
        }
        if (itemSelected) {
            val width = (3 * context.resources.displayMetrics.density).toInt()
            val stroke = if (ExperimentalMobileDesign.enabled()) {
                com.google.android.material.color.MaterialColors.getColor(
                    view,
                    androidx.appcompat.R.attr.colorPrimary,
                )
            } else {
                ContextCompat.getColor(context, R.color.favorite_selected)
            }
            view.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(width, stroke)
                if (ExperimentalMobileDesign.enabled()) {
                    cornerRadius = 10f * context.resources.displayMetrics.density
                }
            }
            view.setPadding(width, width, width, width)
            if (ExperimentalMobileDesign.enabled() &&
                view.getTag(R.id.exp_enter_animated_tag) != "selected"
            ) {
                view.setTag(R.id.exp_enter_animated_tag, "selected")
                ExpMotion.popIn(view)
            }
        } else {
            view.background = null
            view.setPadding(0, 0, 0, 0)
            view.setTag(R.id.exp_enter_animated_tag, null)
        }
    }
    private fun movieDownloadContentKey(): String? {
        val providerName = movie.providerName
            ?: UserPreferences.currentProvider?.name
            ?: return null
        return DownloadContentKey.movie(providerName, movie.id)
    }

    private fun preferredOfflineServerName(): String? {
        val contentKey = movieDownloadContentKey() ?: return null
        return if (OfflineBadgeStore.isCompleted(context, contentKey)) {
            com.dskja.betterstreamflix.fragments.player.PlayerViewModel.OFFLINE_SERVER_NAME
        } else {
            null
        }
    }

    private fun isIptvProvider(): Boolean {
        val name = movie.providerName
        val provider = if (!name.isNullOrBlank()) {
            com.dskja.betterstreamflix.providers.Provider.findByName(name)
        } else {
            UserPreferences.currentProvider
        }
        return provider is com.dskja.betterstreamflix.providers.IptvProvider
    }

    private fun setRibbonVisible(view: View, visible: Boolean) {
        val wasVisible = view.visibility == View.VISIBLE
        view.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible && ExperimentalMobileDesign.enabled()) {
            view.setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
            val pad = (4 * context.resources.displayMetrics.density).toInt()
            view.setPadding(pad, pad, pad, pad)
            if (!wasVisible || view.getTag(R.id.exp_enter_animated_tag) != true) {
                view.setTag(R.id.exp_enter_animated_tag, true)
                if (!wasVisible) ExpMotion.popIn(view)
            }
        } else if (!visible) {
            view.background = null
            view.setTag(R.id.exp_enter_animated_tag, null)
        }
    }

    private fun bindRibbons(favoriteRibbon: View, watchedRibbon: View, downloadRibbon: View? = null) {
        setRibbonVisible(favoriteRibbon, movie.isFavorite)
        setRibbonVisible(watchedRibbon, movie.isWatched)
        val contentKey = movieDownloadContentKey()
        downloadRibbon?.let {
            setRibbonVisible(
                it,
                contentKey != null && OfflineBadgeStore.isCompleted(context, contentKey),
            )
        }

        ribbonStateJob?.cancel()
        val boundMovieId = movie.id
        val lifecycleOwner = itemView.findViewTreeLifecycleOwner()
            ?: context.toActivity()
            ?: return

        ribbonStateJob = lifecycleOwner.lifecycleScope.launch {
            launch {
                database.movieDao().getByIdAsFlow(boundMovieId).collect { persistedMovie ->
                    if (movie.id != boundMovieId || persistedMovie == null) return@collect
                    setRibbonVisible(favoriteRibbon, persistedMovie.isFavorite)
                    setRibbonVisible(watchedRibbon, persistedMovie.isWatched)
                }
            }
            if (downloadRibbon != null) {
                launch {
                    OfflineBadgeStore.completedKeys(context).collect { keys ->
                        if (movie.id != boundMovieId) return@collect
                        val key = movieDownloadContentKey()
                        setRibbonVisible(
                            downloadRibbon,
                            key != null && keys.contains(key),
                        )
                    }
                }
            }
        }
    }

    private fun displaySwiperMobileItem(binding: ItemCategorySwiperMobileBinding) {
        binding.ivSwiperBackground.loadMovieBanner(movie) {
            override(FeaturedSwiperChrome.ARTWORK_WIDTH, FeaturedSwiperChrome.ARTWORK_HEIGHT)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
        }

        itemView.contentDescription = movie.title
        FeaturedSwiperChrome.resolveAndBindLogo(binding, movie)

        // Retired meta chrome — keep gone so recycled views never flash old pills.
        binding.tvSwiperOverview.visibility = View.GONE
        binding.tvSwiperTvShowLastEpisode.visibility = View.GONE
        binding.tvSwiperQuality.visibility = View.GONE
        binding.tvSwiperReleased.visibility = View.GONE
        binding.tvSwiperRating.visibility = View.GONE
        binding.ivSwiperRatingIcon.visibility = View.GONE
        binding.pbSwiperProgress.visibility = View.GONE

        binding.tvSwiperStatus.apply {
            text = FeaturedHeroController.statusLine(context, movie)
                ?: context.getString(R.string.home_swiper_now_playing)
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        binding.tvSwiperGenres.apply {
            val labels = FeaturedHeroController.genresLine(movie)
            text = labels
            visibility = if (labels.isEmpty()) View.GONE else View.VISIBLE
        }

        val openMovie = View.OnClickListener { view ->
            ExpMotion.hapticTap(view)
            FeaturedProviderSwitch.runWithProvider(movie) {
                view.findNavController().navigate(
                    HomeMobileFragmentDirections.actionHomeToMovie(id = movie.id)
                )
            }
        }

        binding.btnSwiperWatchNow.apply {
            FeaturedSwiperChrome.wireWatchButton(this)
            text = FeaturedHeroController.watchCtaLabel(context, movie)
            applyExpPress()
            setOnClickListener(openMovie)
            setOnLongClickListener { view ->
                fun playTrailer(url: String) {
                    ExpMotion.hapticTap(view)
                    val activity = context.toActivity() as? androidx.fragment.app.FragmentActivity
                    TrailerPlaybackController.play(
                        context = view.context,
                        activity = activity,
                        trailerUrl = url,
                    )
                }
                val existing = movie.trailer
                if (!existing.isNullOrBlank()) {
                    playTrailer(existing)
                    return@setOnLongClickListener true
                }
                val year = movie.released?.format("yyyy")?.toIntOrNull()
                if (!TmdbUtils.hasTrailerLookupKeys(
                        tmdbId = movie.tmdbId,
                        imdbId = movie.imdbId,
                        title = movie.title,
                        year = year,
                    )
                ) {
                    return@setOnLongClickListener false
                }
                ExpMotion.hapticTap(view)
                itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                    val remote = withContext(Dispatchers.IO) {
                        TmdbUtils.listYoutubeTrailers(
                            tmdbId = movie.tmdbId,
                            isTv = false,
                            title = movie.title,
                            year = year,
                            imdbId = movie.imdbId,
                        )
                    }
                    val url = remote.firstOrNull()?.second?.takeIf { it.isNotBlank() } ?: return@launch
                    if (movie.trailer.isNullOrBlank()) movie.trailer = url
                    playTrailer(url)
                }
                true
            }
            androidx.appcompat.widget.TooltipCompat.setTooltipText(
                this,
                if (!movie.trailer.isNullOrBlank() ||
                    TmdbUtils.hasTrailerLookupKeys(
                        tmdbId = movie.tmdbId,
                        imdbId = movie.imdbId,
                        title = movie.title,
                        year = movie.released?.format("yyyy")?.toIntOrNull(),
                    )
                ) {
                    context.getString(R.string.home_swiper_trailer)
                } else {
                    text
                },
            )
        }

        FeaturedSwiperChrome.bindListButton(binding.btnSwiperAddToList, movie.isFavorite)
        binding.btnSwiperAddToList.apply {
            applyExpPress()
            setOnClickListener {
                ExpMotion.hapticTap(it)
                FeaturedSwiperChrome.toggleMovieFavorite(
                    anchor = itemView,
                    button = this,
                    movie = movie,
                )
            }
        }
        ribbonStateJob?.cancel()
        val boundMovieId = movie.id
        ribbonStateJob = FeaturedSwiperChrome.observeListState(
            anchor = itemView,
            button = binding.btnSwiperAddToList,
            movieId = boundMovieId,
        ) { favorite ->
            if (movie.id != boundMovieId) return@observeListState
            movie.isFavorite = favorite
            FeaturedSwiperChrome.bindListButton(binding.btnSwiperAddToList, favorite)
        }

        // Nested clickables confuse TalkBack — keep chrome buttons primary.
        binding.root.isClickable = false
        binding.root.setOnClickListener(null)
        binding.ivSwiperBackground.apply {
            isClickable = true
            contentDescription = movie.title
            setOnClickListener(openMovie)
        }
    }

    private fun displayMovieMobile(binding: ContentMovieMobileBinding) {
        // Poster lives in the fragment banner; keep the layout stub gone.
        binding.ivMoviePoster.visibility = View.GONE
        Glide.with(binding.ivMoviePoster).clear(binding.ivMoviePoster)

        binding.tvMovieTitle.text = movie.title
        // Hero logo bind is owned by DetailHeaderController (after body submit).
        // Keep the fixed slot reserved (INVISIBLE) so layout does not jump.
        binding.ivMovieLogo.visibility = View.INVISIBLE
        binding.tvMovieTitle.visibility = View.VISIBLE

        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.revealHeader(
                binding.tvMovieTitle,
                binding.ivMovieLogo,
                binding.btnMovieWatchNow,
                binding.btnMovieTrailer,
                binding.btnMovieDownload,
            )
        }

        // Legacy meta pills stay gone in the Primate meta row.
        binding.tvMovieRating.visibility = View.GONE
        binding.ivMovieRatingIcon.visibility = View.GONE
        binding.tvMovieQuality.visibility = View.GONE

        binding.tvMovieReleased.apply {
            text = movie.released?.format("yyyy")
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        binding.tvMovieRuntime.apply {
            text = movie.runtime?.let {
                val hours = it / 60
                val minutes = it % 60
                when {
                    hours > 0 -> context.getString(
                        R.string.movie_runtime_hours_minutes_short,
                        hours,
                        minutes,
                    )
                    else -> context.getString(R.string.movie_runtime_minutes_short, minutes)
                }
            }
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        binding.tvMovieGenres.apply {
            if (movie.genres.isEmpty()) {
                text = ""
                visibility = View.GONE
                movementMethod = null
                isClickable = false
                setOnClickListener(null)
            } else {
                text = movie.genres.joinToString(" · ") {
                    it.name.uppercase(Locale.getDefault())
                }
                visibility = View.VISIBLE
                movementMethod = null
                isClickable = true
                contentDescription = context.getString(R.string.genre_section_label)
                setOnClickListener { view ->
                    ExpMotion.hapticTap(view)
                    val genres = movie.genres
                    fun openGenre(genre: com.dskja.betterstreamflix.models.Genre) {
                        checkProviderAndRun {
                            if (context.toActivity()?.getCurrentFragment() is MovieMobileFragment) {
                                findNavController().navigate(
                                    MovieMobileFragmentDirections.actionMovieToGenre(
                                        id = genre.id,
                                        name = genre.name,
                                    ),
                                )
                            }
                        }
                    }
                    if (genres.size == 1) {
                        openGenre(genres.first())
                    } else {
                        val labels = genres.map { it.name }.toTypedArray()
                        val builder = if (ExperimentalMobileDesign.enabled()) {
                            MaterialAlertDialogBuilder(context)
                        } else {
                            AlertDialog.Builder(context)
                        }
                        builder
                            .setTitle(R.string.genre_section_label)
                            .setItems(labels) { _, which ->
                                genres.getOrNull(which)?.let(::openGenre)
                            }
                            .show()
                    }
                }
            }
        }

        binding.tvMovieOverview.apply {
            text = movie.overview
            val more = binding.tvMovieOverviewMore
            val hasText = !movie.overview.isNullOrBlank()
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            var expanded = false
            fun applyExpand(open: Boolean) {
                expanded = open
                maxLines = if (open) Integer.MAX_VALUE else 3
                more.text = context.getString(
                    if (open) R.string.detail_overview_less else R.string.detail_overview_more,
                )
            }
            val toggle = View.OnClickListener {
                ExpMotion.hapticTap(it)
                applyExpand(!expanded)
            }
            if (hasText) {
                setOnClickListener(toggle)
                more.setOnClickListener(toggle)
                post {
                    val overflowing = lineCount > 3 ||
                        (text?.length ?: 0) > 160 ||
                        (layout != null && maxLines == 3 && layout.getEllipsisCount(lineCount.coerceAtLeast(1) - 1) > 0)
                    more.visibility = if (overflowing) View.VISIBLE else View.GONE
                    if (!overflowing) setOnClickListener(null)
                }
            } else {
                more.visibility = View.GONE
                setOnClickListener(null)
            }
        }

        binding.btnMovieWatchNow.apply {
            // Dual CTA: Watch now always streams online; completed downloads use the Download button.
            text = context.getString(R.string.movie_watch_now)
            FeaturedSwiperChrome.wireWatchButton(this)
            applyExpPress()
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun {
                    findNavController().navigate(MovieMobileFragmentDirections.actionMovieToPlayer(
                        id = movie.id,
                        title = movie.title,
                        subtitle = movie.released?.format("yyyy") ?: "",
                        videoType = Video.Type.Movie(id = movie.id, title = movie.title, releaseDate = movie.released?.format("yyyy-MM-dd") ?: "", poster = movie.poster ?: movie.banner ?: "", imdbId = movie.imdbId),
                        preferredServerName = null,
                    ))
                }
            }
        }

        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.btnMovieTrailer.apply {
            val year = movie.released?.format("yyyy")?.toIntOrNull()
            val canLookup = TmdbUtils.hasTrailerLookupKeys(
                tmdbId = movie.tmdbId,
                imdbId = movie.imdbId,
                title = movie.title,
                year = year,
            )
            visibility = if (!movie.trailer.isNullOrBlank() || canLookup) {
                View.VISIBLE
            } else {
                View.GONE
            }
            applyExpPress()
            setOnClickListener {
                ExpMotion.hapticTap(it)
                fun play(url: String) {
                    val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                    if (fragment != null) {
                        TrailerPlaybackController.play(fragment, url)
                    } else {
                        handleTrailerClick(url, "MovieMobile")
                    }
                }
                val existing = movie.trailer
                if (!existing.isNullOrBlank()) {
                    play(existing)
                    return@setOnClickListener
                }
                if (!canLookup) {
                    (bindingAdapter as? AppAdapter)?.onDetailTabSelectedListener?.invoke(
                        DetailTab.TRAILER,
                    )
                    return@setOnClickListener
                }
                itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                    val remote = withContext(Dispatchers.IO) {
                        TmdbUtils.listYoutubeTrailers(
                            tmdbId = movie.tmdbId,
                            isTv = false,
                            title = movie.title,
                            year = year,
                            imdbId = movie.imdbId,
                        )
                    }
                    val first = remote.firstOrNull()?.second?.takeIf { it.isNotBlank() }
                    if (!first.isNullOrBlank()) {
                        if (movie.trailer.isNullOrBlank()) movie.trailer = first
                        play(first)
                    } else {
                        (bindingAdapter as? AppAdapter)?.onDetailTabSelectedListener?.invoke(
                            DetailTab.TRAILER,
                        )
                    }
                }
            }
        }

        binding.btnMovieDownload.apply {
            applyExpPress()
            if (isIptvProvider()) {
                visibility = View.GONE
                setOnClickListener(null)
                setOnLongClickListener(null)
            } else {
                visibility = View.VISIBLE
                val contentKey = movieDownloadContentKey()
                val playOffline = contentKey != null && OfflineBadgeStore.isCompleted(context, contentKey)
                val label = if (playOffline) {
                    context.getString(R.string.downloads_play_offline)
                } else {
                    com.dskja.betterstreamflix.download.DetailDownloadLabels.movieButton(context, movie)
                }
                contentDescription = label
                androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
                setOnClickListener {
                    ExpMotion.hapticTap(it)
                    checkProviderAndRun {
                        if (playOffline) {
                            findNavController().navigate(
                                MovieMobileFragmentDirections.actionMovieToPlayer(
                                    id = movie.id,
                                    title = movie.title,
                                    subtitle = movie.released?.format("yyyy") ?: "",
                                    videoType = Video.Type.Movie(
                                        id = movie.id,
                                        title = movie.title,
                                        releaseDate = movie.released?.format("yyyy-MM-dd") ?: "",
                                        poster = movie.poster ?: movie.banner ?: "",
                                        imdbId = movie.imdbId,
                                    ),
                                    preferredServerName =
                                        com.dskja.betterstreamflix.fragments.player.PlayerViewModel.OFFLINE_SERVER_NAME,
                                ),
                            )
                        } else {
                            val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                                ?: return@checkProviderAndRun
                            DownloadOptionsController.enqueueMovie(fragment, movie)
                        }
                    }
                }
                setOnLongClickListener {
                    if (!playOffline) return@setOnLongClickListener false
                    ExpMotion.hapticTap(it)
                    checkProviderAndRun {
                        val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                            ?: return@checkProviderAndRun
                        DownloadOptionsController.enqueueMovie(fragment, movie)
                    }
                    true
                }
            }
        }

        binding.tvMovieCertification.apply {
            val cert = movie.contentRating?.trim().orEmpty()
            val digits = cert.filter { it.isDigit() }
            val badge = when {
                digits.isNotEmpty() -> digits.take(2)
                cert.isNotEmpty() -> cert.take(3).uppercase(Locale.getDefault())
                else -> ""
            }
            text = badge
            visibility = if (badge.isEmpty()) View.GONE else View.VISIBLE
        }

        binding.root.findViewById<View>(R.id.btn_movie_share)?.let { shareBtn ->
            shareBtn.applyExpPress()
            shareBtn.setOnClickListener {
                ExpMotion.hapticTap(it)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, movie.title)
                    putExtra(
                        Intent.EXTRA_TEXT,
                        buildString {
                            append(movie.title)
                            movie.released?.format("yyyy")?.let { year -> append(" ($year)") }
                            movie.overview?.takeIf { it.isNotBlank() }?.let { overview -> append("\n\n").append(overview.take(280)) }
                            movie.trailer?.let { trailer -> append("\n").append(trailer) }
                        },
                    )
                }
                context.startActivity(Intent.createChooser(share, context.getString(R.string.detail_share)))
            }
        }

        binding.root.findViewById<View>(R.id.btn_movie_watched)?.let { watchedBtn ->
            fun applyWatchedUi(watched: Boolean) {
                val description = context.getString(
                    if (watched) R.string.option_show_unwatched else R.string.option_show_watched,
                )
                watchedBtn.contentDescription = description
                androidx.appcompat.widget.TooltipCompat.setTooltipText(watchedBtn, description)
                watchedBtn.alpha = 1f
                (watchedBtn as? android.widget.ImageView)?.setImageDrawable(
                    ContextCompat.getDrawable(
                        context,
                        if (watched) R.drawable.ic_watched_filled else R.drawable.ic_watched,
                    ),
                )
                (watchedBtn as? TextView)?.text = description
            }

            watchedBtn.applyExpPress()
            applyWatchedUi(movie.isWatched)
            watchedBtn.setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun {
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.movieDao()
                        val current = dao.getById(movie.id)
                        val target = !(current?.isWatched ?: movie.isWatched)
                        val updated = (current ?: movie).copy().apply {
                            isWatched = target
                            watchedDate = if (target) java.util.Calendar.getInstance() else null
                        }
                        dao.save(updated)
                        withContext(Dispatchers.Main) {
                            movie.isWatched = target
                            applyWatchedUi(target)
                            ExpMotion.softScale(watchedBtn)
                        }
                    }
                }
            }
        }

        binding.btnMovieFavorite.apply {
            fun applyState(inList: Boolean) {
                setImageDrawable(
                    ContextCompat.getDrawable(
                        context,
                        if (inList) R.drawable.ic_list_added else R.drawable.ic_list_add,
                    )
                )
                val description = context.getString(
                    if (inList) R.string.detail_remove_from_list else R.string.detail_add_to_list,
                )
                contentDescription = description
                androidx.appcompat.widget.TooltipCompat.setTooltipText(this, description)
            }

            applyExpPress()
            applyState(movie.isFavorite)
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun {
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.movieDao()
                        val target = !(dao.getById(movie.id)?.isFavorite ?: movie.isFavorite)
                        val resolved =
                            ArtworkRepair.resolveMovieForFavorite(context, movie, target)
                        dao.upsertFavorite(resolved, target)
                        com.dskja.betterstreamflix.platform.simkl.SimklSyncHooks.onListToggle(
                            add = target,
                            imdbId = movie.imdbId,
                            tmdbId = movie.tmdbId,
                            isTv = false,
                        )
                        withContext(Dispatchers.Main) {
                            movie.poster = resolved.poster
                            movie.banner = resolved.banner
                            movie.isFavorite = target
                            applyState(target)
                            ExpMotion.softScale(binding.btnMovieFavorite)
                        }
                    }
                }
            }
        }
    }

    private fun displayMovieTv(binding: ContentMovieTvBinding) {
        binding.ivMoviePoster.run {
            loadMoviePoster(movie) {
                transition(DrawableTransitionOptions.withCrossFade())
            }
            visibility = when {
                movie.poster.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieTitle.text = movie.title
        com.dskja.betterstreamflix.logo.TitleLogoSurface.bindAndMaybeResolve(
            anchor = binding.root,
            imageView = binding.ivMovieLogo,
            titleView = binding.tvMovieTitle,
            movie = movie,
            persist = true,
            allowAlternateOnFail = true,
        )

        binding.tvMovieRating.apply {
            text = com.dskja.betterstreamflix.ui.DetailRating.format(movie.rating)
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        binding.ivMovieRatingIcon.visibility = binding.tvMovieRating.visibility

        binding.tvMovieCertification.apply {
            val cert = movie.contentRating?.trim().orEmpty()
            val digits = cert.filter { it.isDigit() }
            val badge = when {
                digits.isNotEmpty() -> digits.take(2)
                cert.isNotEmpty() -> cert.take(3).uppercase(Locale.getDefault())
                else -> ""
            }
            text = badge
            visibility = if (badge.isEmpty()) View.GONE else View.VISIBLE
        }

        binding.tvMovieQuality.apply {
            text = movie.quality
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieReleased.apply {
            text = movie.released?.format("yyyy")
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieRuntime.apply {
            text = movie.runtime?.let {
                val hours = it / 60
                val minutes = it % 60
                when {
                    hours > 0 -> context.getString(
                        R.string.movie_runtime_hours_minutes,
                        hours,
                        minutes
                    )
                    else -> context.getString(R.string.movie_runtime_minutes, minutes)
                }
            }
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieGenres.apply {
            if (movie.genres.isEmpty()) {
                text = ""
                visibility = View.GONE
                isFocusable = false
                setOnClickListener(null)
            } else {
                text = movie.genres.joinToString(", ") { it.name }
                visibility = View.VISIBLE
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                contentDescription = context.getString(R.string.genre_section_label)
                setOnClickListener { view ->
                    ExpMotion.hapticTap(view)
                    val genres = movie.genres
                    fun openGenre(genre: com.dskja.betterstreamflix.models.Genre) {
                        checkProviderAndRun {
                            if (context.toActivity()?.getCurrentFragment() is MovieTvFragment) {
                                findNavController().navigate(
                                    MovieTvFragmentDirections.actionMovieToGenre(
                                        id = genre.id,
                                        name = genre.name,
                                    ),
                                )
                            }
                        }
                    }
                    if (genres.size == 1) {
                        openGenre(genres.first())
                    } else {
                        val labels = genres.map { it.name }.toTypedArray()
                        AlertDialog.Builder(context)
                            .setTitle(R.string.genre_section_label)
                            .setItems(labels) { _, which ->
                                genres.getOrNull(which)?.let(::openGenre)
                            }
                            .show()
                    }
                }
                nextFocusDownId = binding.btnMovieWatchNow.id
                binding.btnMovieWatchNow.nextFocusUpId = id
            }
        }

        binding.tvMovieOverview.text = movie.overview

        binding.btnMovieWatchNow.apply {
            // Dual CTA: Watch now always streams online; completed downloads use the Download button.
            text = context.getString(R.string.movie_watch_now)
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun {
                    findNavController().navigate(MovieTvFragmentDirections.actionMovieToPlayer(
                        id = movie.id,
                        title = movie.title,
                        subtitle = movie.released?.format("yyyy") ?: "",
                        videoType = Video.Type.Movie(id = movie.id, title = movie.title, releaseDate = movie.released?.format("yyyy-MM-dd") ?: "", poster = movie.poster ?: movie.banner ?: "", imdbId = movie.imdbId),
                        preferredServerName = null,
                    ))
                }
            }
        }

        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.btnMovieTrailer.apply {
            val year = movie.released?.format("yyyy")?.toIntOrNull()
            val canLookup = TmdbUtils.hasTrailerLookupKeys(
                tmdbId = movie.tmdbId,
                imdbId = movie.imdbId,
                title = movie.title,
                year = year,
            )
            fun bindTrailer(trailerUrl: String?) {
                setOnClickListener {
                    ExpMotion.hapticTap(it)
                    if (!trailerUrl.isNullOrBlank()) {
                        val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                        if (fragment != null) {
                            TrailerPlaybackController.play(fragment, trailerUrl)
                        } else {
                            handleTrailerClick(trailerUrl, "MovieTv")
                        }
                    }
                }
                visibility = if (!trailerUrl.isNullOrBlank() || canLookup) View.VISIBLE else View.GONE
            }
            bindTrailer(movie.trailer)
            if (movie.trailer.isNullOrBlank() && canLookup) {
                itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                    val remote = withContext(Dispatchers.IO) {
                        TmdbUtils.listYoutubeTrailers(
                            tmdbId = movie.tmdbId,
                            isTv = false,
                            title = movie.title,
                            year = year,
                            imdbId = movie.imdbId,
                        )
                    }
                    val first = remote.firstOrNull()?.second
                    if (!first.isNullOrBlank() && movie.trailer.isNullOrBlank()) {
                        movie.trailer = first
                        bindTrailer(first)
                    }
                }
            }
        }

        binding.btnMovieDownload.apply {
            if (isIptvProvider()) {
                visibility = View.GONE
                setOnClickListener(null)
                setOnLongClickListener(null)
            } else {
                visibility = View.VISIBLE
                val contentKey = movieDownloadContentKey()
                val playOffline = contentKey != null && OfflineBadgeStore.isCompleted(context, contentKey)
                text = if (playOffline) {
                    context.getString(R.string.downloads_play_offline)
                } else {
                    com.dskja.betterstreamflix.download.DetailDownloadLabels.movieButton(context, movie)
                }
                contentDescription = text
                setOnClickListener {
                    ExpMotion.hapticTap(it)
                    checkProviderAndRun {
                        if (playOffline) {
                            findNavController().navigate(
                                MovieTvFragmentDirections.actionMovieToPlayer(
                                    id = movie.id,
                                    title = movie.title,
                                    subtitle = movie.released?.format("yyyy") ?: "",
                                    videoType = Video.Type.Movie(
                                        id = movie.id,
                                        title = movie.title,
                                        releaseDate = movie.released?.format("yyyy-MM-dd") ?: "",
                                        poster = movie.poster ?: movie.banner ?: "",
                                        imdbId = movie.imdbId,
                                    ),
                                    preferredServerName =
                                        com.dskja.betterstreamflix.fragments.player.PlayerViewModel.OFFLINE_SERVER_NAME,
                                ),
                            )
                        } else {
                            val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                                ?: return@checkProviderAndRun
                            DownloadOptionsController.enqueueMovie(fragment, movie)
                        }
                    }
                }
                setOnLongClickListener {
                    if (!playOffline) return@setOnLongClickListener false
                    ExpMotion.hapticTap(it)
                    checkProviderAndRun {
                        val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                            ?: return@checkProviderAndRun
                        DownloadOptionsController.enqueueMovie(fragment, movie)
                    }
                    true
                }
            }
        }

        binding.root.findViewById<TextView>(R.id.btn_movie_watched)?.apply {
            text = if (movie.isWatched) {
                context.getString(R.string.option_show_unwatched)
            } else {
                context.getString(R.string.option_show_watched)
            }
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun {
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.movieDao()
                        val current = dao.getById(movie.id)
                        val target = !(current?.isWatched ?: movie.isWatched)
                        val updated = (current ?: movie).copy().apply {
                            isWatched = target
                            watchedDate = if (target) java.util.Calendar.getInstance() else null
                        }
                        dao.save(updated)
                        withContext(Dispatchers.Main) {
                            movie.isWatched = target
                            text = if (target) {
                                context.getString(R.string.option_show_unwatched)
                            } else {
                                context.getString(R.string.option_show_watched)
                            }
                        }
                    }
                }
            }
        }

        binding.root.findViewById<View>(R.id.btn_movie_share)?.let { shareBtn ->
            shareBtn.setOnClickListener {
                ExpMotion.hapticTap(it)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, movie.title)
                    putExtra(
                        Intent.EXTRA_TEXT,
                        buildString {
                            append(movie.title)
                            movie.released?.format("yyyy")?.let { year -> append(" ($year)") }
                            movie.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                                append("\n\n").append(overview.take(280))
                            }
                            movie.trailer?.let { trailer -> append("\n").append(trailer) }
                        },
                    )
                }
                context.startActivity(
                    Intent.createChooser(share, context.getString(R.string.detail_share)),
                )
            }
        }

        binding.btnMovieFavorite.apply {

            fun Boolean.drawable() = when (this) {
                true -> R.drawable.ic_favorite_enable
                false -> R.drawable.ic_favorite_disable
            }

            fun applyFavoriteState(inList: Boolean) {
                setImageDrawable(ContextCompat.getDrawable(context, inList.drawable()))
                contentDescription = context.getString(
                    if (inList) R.string.detail_remove_from_list else R.string.detail_add_to_list,
                )
            }

            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun {
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                        val dao = database.movieDao()
                        val current = dao.getById(movie.id)?.isFavorite ?: false
                        val newValue = !current
                        val resolvedMovie = ArtworkRepair.resolveMovieForFavorite(context, movie, newValue)

                        dao.upsertFavorite(resolvedMovie, newValue)
                        com.dskja.betterstreamflix.platform.simkl.SimklSyncHooks.onListToggle(
                            add = newValue,
                            imdbId = movie.imdbId,
                            tmdbId = movie.tmdbId,
                            isTv = false,
                        )

                        withContext(Dispatchers.Main) {
                            movie.poster = resolvedMovie.poster
                            movie.banner = resolvedMovie.banner
                            movie.isFavorite = newValue
                            applyFavoriteState(newValue)
                            ExpMotion.popIn(binding.btnMovieFavorite)
                        }
                    }
                }
            }

            applyFavoriteState(movie.isFavorite)
        }

        com.dskja.betterstreamflix.utils.TvFocusChain.linkHorizontal(
            binding.btnMovieWatchNow,
            binding.btnMovieTrailer,
            binding.btnMovieDownload,
            binding.root.findViewById(R.id.btn_movie_watched),
            binding.root.findViewById(R.id.btn_movie_share),
            binding.btnMovieFavorite,
        )
    }

    private fun displayCastMobile(binding: ContentMovieCastMobileBinding) {
        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.revealHeader(
                binding.root.findViewById(R.id.tv_movie_cast_label),
                binding.root.findViewById(R.id.v_movie_cast_rule),
            )
            ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_movie_cast_rule))
            ExpMotion.staggerFirstFill(binding.rvMovieCast)
        }
        binding.rvMovieCast.apply {
            adapter = AppAdapter().apply {
                submitList(movie.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
                })
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(20.dp(context)))
            }
        }
    }

    private fun displayCastTv(binding: ContentMovieCastTvBinding) {
        if (ExperimentalMobileDesign.enabled()) {
            binding.tvMovieCastLabel.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.tvMovieCastLabel,
                    androidx.appcompat.R.attr.colorPrimary,
                ),
            )
            binding.root.findViewById<View>(R.id.v_movie_cast_rule)?.visibility = View.VISIBLE
            if (binding.root.getTag(R.id.exp_enter_animated_tag) != true) {
                binding.root.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.revealHeader(
                    binding.tvMovieCastLabel,
                    binding.root.findViewById(R.id.v_movie_cast_rule),
                )
                ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_movie_cast_rule))
                ExpMotion.staggerFirstFill(binding.hgvMovieCast)
            }
        }
        binding.hgvMovieCast.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(movie.cast.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
                })
            }
            setItemSpacing(80)
        }
    }

    private fun displayDirectorsMobile(binding: ContentMovieDirectorsMobileBinding) {
        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.revealHeader(
                binding.root.findViewById(R.id.tv_movie_directors_label),
                binding.root.findViewById(R.id.v_movie_directors_rule),
            )
            ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_movie_directors_rule))
        }
        binding.rvMovieDirectors.apply {
            adapter = AppAdapter().apply {
                submitList(movie.directors.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
                })
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(20.dp(context)))
            }
        }
    }
    private fun displayDirectorsTv(binding: ContentMovieDirectorsTvBinding) {
        if (ExperimentalMobileDesign.enabled()) {
            binding.tvMovieDirectorsLabel.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.tvMovieDirectorsLabel,
                    androidx.appcompat.R.attr.colorPrimary,
                ),
            )
            binding.root.findViewById<View>(R.id.v_movie_directors_rule)?.visibility = View.VISIBLE
            if (binding.root.getTag(R.id.exp_enter_animated_tag) != true) {
                binding.root.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.revealHeader(
                    binding.tvMovieDirectorsLabel,
                    binding.root.findViewById(R.id.v_movie_directors_rule),
                )
                ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_movie_directors_rule))
                ExpMotion.staggerFirstFill(binding.hgvMovieDirectors)
            }
        }
        binding.hgvMovieDirectors.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(movie.directors.onEach {
                    it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
                })
            }
            setItemSpacing(80)
        }
    }

    private fun displayRecommendationsMobile(binding: ContentMovieRecommendationsMobileBinding) {
        binding.root.tag = DETAIL_SECTION_RECOMMENDATIONS
        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.revealHeader(
                binding.root.findViewById(R.id.tv_movie_recommendations_label),
                binding.root.findViewById(R.id.v_movie_recommendations_rule),
            )
            ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_movie_recommendations_rule))
            ExpMotion.staggerFirstFill(binding.rvMovieRecommendations)
        }
        val empty = movie.recommendations.isEmpty()
        binding.root.findViewById<View>(R.id.tv_movie_recommendations_empty)?.visibility =
            if (empty) View.VISIBLE else View.GONE
        binding.rvMovieRecommendations.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) return
        binding.rvMovieRecommendations.apply {
            val grid = layoutManager as? GridLayoutManager
            if (grid == null) {
                layoutManager = GridLayoutManager(context, 3)
            } else if (grid.spanCount != 3) {
                grid.spanCount = 3
            }
            adapter = AppAdapter().apply {
                submitList(movie.recommendations.onEach {
                    when (it) {
                        is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                        is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
                    }
                })
            }
            if (itemDecorationCount == 0) {
                addItemDecoration(SpacingItemDecoration(10.dp(context)))
            }
        }
    }

    private fun displayRecommendationsTv(binding: ContentMovieRecommendationsTvBinding) {
        if (ExperimentalMobileDesign.enabled()) {
            binding.tvMovieRecommendationsLabel.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.tvMovieRecommendationsLabel,
                    androidx.appcompat.R.attr.colorPrimary,
                ),
            )
            binding.root.findViewById<View>(R.id.v_movie_recommendations_rule)?.visibility = View.VISIBLE
            if (binding.root.getTag(R.id.exp_enter_animated_tag) != true) {
                binding.root.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.revealHeader(
                    binding.tvMovieRecommendationsLabel,
                    binding.root.findViewById(R.id.v_movie_recommendations_rule),
                )
                ExpMotion.pulseAccentRule(
                    binding.root.findViewById(R.id.v_movie_recommendations_rule),
                )
                ExpMotion.staggerFirstFill(binding.hgvMovieRecommendations)
            }
        }
        binding.hgvMovieRecommendations.apply {
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            adapter = AppAdapter().apply {
                submitList(movie.recommendations.onEach {
                    when (it) {
                        is Movie -> it.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                        is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                    }
                })
            }
            setItemSpacing(20)
        }
    }

    private fun bindMovieProgress(bar: android.widget.ProgressBar) {
        val watchHistory = movie.watchHistory
        val target = when {
            watchHistory != null && watchHistory.durationMillis > 0 ->
                (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
            else -> 0
        }
        val show = watchHistory != null && watchHistory.durationMillis > 0
        val wasVisible = bar.visibility == View.VISIBLE
        bar.visibility = if (show) View.VISIBLE else View.GONE
        if (show && ExperimentalMobileDesign.enabled() && (!wasVisible || bar.progress != target)) {
            android.animation.ObjectAnimator.ofInt(bar, "progress", 0, target)
                .setDuration(420L)
                .start()
        } else {
            bar.progress = target
        }
    }

    private fun displayTabsMobile(binding: ContentDetailTabsMobileBinding) {
        binding.tabDetailEpisodes.visibility = View.GONE
        val adapter = bindingAdapter as? AppAdapter
        val selected = adapter?.selectedDetailTab ?: DetailTab.SIMILAR
        fun select(active: android.view.View) {
            listOf(
                binding.tabDetailEpisodes,
                binding.tabDetailSimilar,
                binding.tabDetailTrailer,
                binding.tabDetailAbout,
            ).forEach { tab ->
                val on = tab === active
                tab.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0x8AFFFFFF.toInt())
                tab.setBackgroundResource(if (on) R.drawable.bg_detail_tab_underline else 0)
            }
        }
        val active = when (selected) {
            com.dskja.betterstreamflix.ui.DetailTab.EPISODES -> binding.tabDetailEpisodes
            com.dskja.betterstreamflix.ui.DetailTab.SIMILAR -> binding.tabDetailSimilar
            com.dskja.betterstreamflix.ui.DetailTab.TRAILER -> binding.tabDetailTrailer
            com.dskja.betterstreamflix.ui.DetailTab.ABOUT -> binding.tabDetailAbout
        }
        select(active)

        binding.tabDetailSimilar.setOnClickListener {
            ExpMotion.hapticTap(it)
            select(binding.tabDetailSimilar)
            adapter?.onDetailTabSelectedListener?.invoke(com.dskja.betterstreamflix.ui.DetailTab.SIMILAR)
        }
        binding.tabDetailTrailer.setOnClickListener {
            ExpMotion.hapticTap(it)
            select(binding.tabDetailTrailer)
            adapter?.onDetailTabSelectedListener?.invoke(com.dskja.betterstreamflix.ui.DetailTab.TRAILER)
        }
        binding.tabDetailAbout.setOnClickListener {
            ExpMotion.hapticTap(it)
            select(binding.tabDetailAbout)
            adapter?.onDetailTabSelectedListener?.invoke(com.dskja.betterstreamflix.ui.DetailTab.ABOUT)
        }
    }

    private fun displayTrailerMobile(binding: ContentDetailTrailerMobileBinding) {
        binding.root.tag = DETAIL_SECTION_TRAILER
        binding.llDetailTrailerList.removeAllViews()
        binding.llDetailTrailerRow.visibility = View.GONE
        binding.tvDetailTrailerEmpty.visibility = View.GONE
        binding.flDetailTrailerPlayer.visibility = View.GONE
        binding.tvDetailTrailerNowPlaying.visibility = View.GONE
        binding.tvDetailTrailerMoreLabel.visibility = View.GONE
        binding.llDetailTrailerError.visibility = View.GONE

        val web = binding.wvDetailTrailer
        val loading = binding.pbDetailTrailerLoading
        val errorPanel = binding.llDetailTrailerError
        val metrics = binding.root.resources.displayMetrics
        val playerHeight = ((metrics.widthPixels - (32 * metrics.density)) * 9f / 16f)
            .toInt()
            .coerceIn((180 * metrics.density).toInt(), (metrics.heightPixels * 0.45f).toInt())
        web.layoutParams = web.layoutParams.apply { height = playerHeight }
        binding.flDetailTrailerPlayer.minimumHeight = playerHeight

        if (web.getTag(R.id.detail_trailer_webview_configured_tag) != true) {
            web.setTag(R.id.detail_trailer_webview_configured_tag, true)
            TrailerPlaybackController.configureTrailerWebView(web)
            web.webChromeClient = android.webkit.WebChromeClient()
            web.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    if (errorPanel.visibility != View.VISIBLE) {
                        loading.visibility = View.GONE
                        web.visibility = View.VISIBLE
                    }
                }

                override fun onReceivedError(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?,
                    resourceError: android.webkit.WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        loading.visibility = View.GONE
                        web.visibility = View.INVISIBLE
                        errorPanel.visibility = View.VISIBLE
                    }
                }
            }
            binding.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    web.stopLoading()
                    web.loadUrl("about:blank")
                }
            })
        }

        var activeUrl: String? = null

        fun playInline(title: String, url: String) {
            val ytId = TrailerPlaybackController.youtubeVideoId(url)
            if (ytId.isNullOrBlank()) {
                TrailerPlaybackController.openExternalYoutube(context, url)
                return
            }
            val alreadyPlaying = activeUrl == url &&
                binding.flDetailTrailerPlayer.visibility == View.VISIBLE
            activeUrl = url
            binding.flDetailTrailerPlayer.visibility = View.VISIBLE
            binding.tvDetailTrailerNowPlaying.visibility = View.VISIBLE
            binding.tvDetailTrailerNowPlaying.text = title
            binding.btnDetailTrailerOpenExternal.setOnClickListener {
                ExpMotion.hapticTap(it)
                TrailerPlaybackController.openExternalYoutube(context, url)
            }
            for (i in 0 until binding.llDetailTrailerList.childCount) {
                val child = binding.llDetailTrailerList.getChildAt(i)
                val selected = child.getTag(R.id.detail_trailer_row_url_tag) == url
                child.background = if (selected) {
                    ContextCompat.getDrawable(context, R.drawable.bg_detail_trailer_row_selected)
                } else {
                    null
                }
            }
            if (alreadyPlaying) return
            errorPanel.visibility = View.GONE
            web.visibility = View.VISIBLE
            loading.visibility = View.VISIBLE
            TrailerPlaybackController.loadTrailerEmbed(web, ytId)
        }

        fun bindRows(trailers: List<Triple<String, String, String>>) {
            binding.llDetailTrailerList.removeAllViews()
            if (trailers.isEmpty()) {
                binding.tvDetailTrailerEmpty.visibility = View.VISIBLE
                binding.flDetailTrailerPlayer.visibility = View.GONE
                binding.tvDetailTrailerNowPlaying.visibility = View.GONE
                binding.tvDetailTrailerMoreLabel.visibility = View.GONE
                return
            }
            binding.tvDetailTrailerEmpty.visibility = View.GONE
            binding.tvDetailTrailerMoreLabel.visibility =
                if (trailers.size > 1) View.VISIBLE else View.GONE
            val inflater = LayoutInflater.from(context)
            trailers.take(5).forEach { (title, url, type) ->
                val row = ItemDetailTrailerRowMobileBinding.inflate(
                    inflater,
                    binding.llDetailTrailerList,
                    false,
                )
                row.root.setTag(R.id.detail_trailer_row_url_tag, url)
                row.tvDetailTrailerTitle.text = title
                row.tvDetailTrailerMeta.text = type
                row.tvDetailTrailerDesc.visibility = View.GONE
                val ytId = TrailerPlaybackController.youtubeVideoId(url)
                if (ytId != null) {
                    Glide.with(row.ivDetailTrailerThumb)
                        .load("https://img.youtube.com/vi/$ytId/hqdefault.jpg")
                        .centerCrop()
                        .into(row.ivDetailTrailerThumb)
                } else {
                    row.ivDetailTrailerThumb.setImageDrawable(null)
                }
                val play = View.OnClickListener {
                    ExpMotion.hapticTap(it)
                    playInline(title, url)
                }
                row.root.setOnClickListener(play)
                row.ivDetailTrailerPlay.setOnClickListener(play)
                binding.llDetailTrailerList.addView(row.root)
            }
            val first = trailers.first()
            if (activeUrl == null || trailers.none { it.second == activeUrl }) {
                playInline(first.first, first.second)
            } else {
                playInline(
                    trailers.first { it.second == activeUrl }.first,
                    activeUrl!!,
                )
            }
        }

        val seed = movie.trailer?.takeIf { it.isNotBlank() }?.let { url ->
            listOf(
                Triple(
                    "${movie.title} ${context.getString(R.string.movie_trailer)}",
                    url,
                    context.getString(R.string.movie_trailer),
                ),
            )
        }.orEmpty()
        if (seed.isNotEmpty()) bindRows(seed)

        itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val remote = withContext(Dispatchers.IO) {
                TmdbUtils.listYoutubeTrailers(
                    tmdbId = movie.tmdbId,
                    isTv = false,
                    title = movie.title,
                    year = movie.released?.format("yyyy")?.toIntOrNull(),
                    imdbId = movie.imdbId,
                )
            }
            val trailers = (seed + remote).distinctBy { it.second }
            bindRows(trailers)
        }
    }


    private fun displayTrailerTv(binding: ContentDetailTrailerTvBinding) {
        binding.root.tag = DETAIL_SECTION_TRAILER
        if (ExperimentalMobileDesign.enabled()) {
            binding.tvDetailTrailerLabel.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.tvDetailTrailerLabel,
                    androidx.appcompat.R.attr.colorPrimary,
                ),
            )
            binding.root.findViewById<View>(R.id.v_detail_trailer_rule)?.visibility = View.VISIBLE
            if (binding.root.getTag(R.id.exp_enter_animated_tag) != true) {
                binding.root.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.revealHeader(
                    binding.tvDetailTrailerLabel,
                    binding.root.findViewById(R.id.v_detail_trailer_rule),
                )
                ExpMotion.pulseAccentRule(binding.root.findViewById(R.id.v_detail_trailer_rule))
            }
        }

        fun bindRows(trailers: List<Triple<String, String, String>>) {
            binding.root.visibility = if (trailers.isEmpty()) View.GONE else View.VISIBLE
            binding.hgvDetailTrailers.apply {
                setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
                adapter = AppAdapter().apply {
                    submitList(
                        trailers.take(5).map { (title, url, type) ->
                            Trailer(title = title, url = url, type = type).also {
                                it.itemType = AppAdapter.Type.TRAILER_TV_ITEM
                            }
                        },
                    )
                }
                setItemSpacing(24)
            }
            if (ExperimentalMobileDesign.enabled() && trailers.isNotEmpty()) {
                ExpMotion.staggerFirstFill(binding.hgvDetailTrailers)
            }
        }

        val seed = movie.trailer?.takeIf { it.isNotBlank() }?.let { url ->
            listOf(
                Triple(
                    "${movie.title} ${context.getString(R.string.movie_trailer)}",
                    url,
                    context.getString(R.string.movie_trailer),
                ),
            )
        }.orEmpty()
        if (seed.isNotEmpty()) bindRows(seed) else binding.root.visibility = View.GONE

        itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val remote = withContext(Dispatchers.IO) {
                TmdbUtils.listYoutubeTrailers(
                    tmdbId = movie.tmdbId,
                    isTv = false,
                    title = movie.title,
                    year = movie.released?.format("yyyy")?.toIntOrNull(),
                    imdbId = movie.imdbId,
                )
            }
            val trailers = (seed + remote).distinctBy { it.second }
            if (trailers.isNotEmpty() && movie.trailer.isNullOrBlank()) {
                movie.trailer = trailers.first().second
            }
            bindRows(trailers)
        }
    }

    private fun displayAboutMobile(binding: ContentDetailAboutMobileBinding) {
        binding.root.tag = DETAIL_SECTION_ABOUT
        val overview = movie.overview.orEmpty()
        binding.tvDetailAboutOverview.text = overview
        binding.tvDetailAboutOverview.visibility =
            if (overview.isBlank()) View.GONE else View.VISIBLE
        binding.tvDetailAboutOverviewLabel.visibility =
            if (overview.isBlank()) View.GONE else View.VISIBLE

        // Legacy cast/crew walls stay gone — Actor / Cast / Director systems cover them.
        binding.tvDetailAboutFeaturing.visibility = View.GONE
        binding.tvDetailAboutFeaturingLabel.visibility = View.GONE
        binding.tvDetailAboutDirectors.visibility = View.GONE
        binding.tvDetailAboutDirectorsLabel.visibility = View.GONE
        binding.tvDetailAboutCast.visibility = View.GONE
        binding.tvDetailAboutCastLabel.visibility = View.GONE

        fun bindFact(label: View, value: android.widget.TextView, text: String?) {
            val visible = !text.isNullOrBlank()
            label.visibility = if (visible) View.VISIBLE else View.GONE
            value.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible) value.text = text
        }

        val genres = movie.genres
            .mapNotNull { it.name.takeIf { name -> name.isNotBlank() } }
            .joinToString(" · ")
        bindFact(binding.tvDetailAboutGenresLabel, binding.tvDetailAboutGenres, genres.ifBlank { null })

        val runtime = movie.runtime?.let {
            val hours = it / 60
            val minutes = it % 60
            when {
                hours > 0 -> context.getString(
                    R.string.movie_runtime_hours_minutes_short,
                    hours,
                    minutes,
                )
                else -> context.getString(R.string.movie_runtime_minutes_short, minutes)
            }
        }
        bindFact(binding.tvDetailAboutRuntimeLabel, binding.tvDetailAboutRuntime, runtime)
        bindFact(
            binding.tvDetailAboutYearLabel,
            binding.tvDetailAboutYear,
            movie.released?.format("yyyy"),
        )
        bindFact(
            binding.tvDetailAboutRatingLabel,
            binding.tvDetailAboutRating,
            com.dskja.betterstreamflix.ui.DetailRating.format(movie.rating),
        )
        bindFact(
            binding.tvDetailAboutQualityLabel,
            binding.tvDetailAboutQuality,
            movie.quality?.takeIf { it.isNotBlank() },
        )
        bindFact(
            binding.tvDetailAboutCertLabel,
            binding.tvDetailAboutCert,
            movie.contentRating?.takeIf { it.isNotBlank() },
        )
        bindFact(
            binding.tvDetailAboutProviderLabel,
            binding.tvDetailAboutProvider,
            movie.providerName?.takeIf { it.isNotBlank() },
        )
        binding.tvDetailAboutSeasonsLabel.visibility = View.GONE
        binding.tvDetailAboutSeasons.visibility = View.GONE

        val ids = buildList {
            movie.tmdbId?.takeIf { it.isNotBlank() }?.let {
                add(context.getString(R.string.detail_about_id_tmdb, it))
            }
            movie.imdbId?.takeIf { it.isNotBlank() }?.let {
                add(context.getString(R.string.detail_about_id_imdb, it))
            }
        }.joinToString(" · ")
        bindFact(binding.tvDetailAboutIdsLabel, binding.tvDetailAboutIds, ids.ifBlank { null })

        val anyFact = listOf(
            binding.tvDetailAboutGenres,
            binding.tvDetailAboutRuntime,
            binding.tvDetailAboutYear,
            binding.tvDetailAboutRating,
            binding.tvDetailAboutQuality,
            binding.tvDetailAboutCert,
            binding.tvDetailAboutProvider,
            binding.tvDetailAboutIds,
        ).any { it.visibility == View.VISIBLE }
        binding.tvDetailAboutFactsLabel.visibility =
            if (anyFact) View.VISIBLE else View.GONE
    }

}
