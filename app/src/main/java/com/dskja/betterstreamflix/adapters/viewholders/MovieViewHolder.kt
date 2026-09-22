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
import android.graphics.drawable.Drawable
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import androidx.fragment.app.Fragment
import com.dskja.betterstreamflix.R
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
import com.dskja.betterstreamflix.ui.FeaturedSwiperChrome
import com.dskja.betterstreamflix.ui.ShowOptionsMobileDialog
import com.dskja.betterstreamflix.ui.ShowOptionsTvDialog
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.ui.TrailerPlaybackController
import com.dskja.betterstreamflix.ui.DetailTab
import androidx.recyclerview.widget.GridLayoutManager
import com.dskja.betterstreamflix.utils.ExpAmbientGlow
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
import com.dskja.betterstreamflix.utils.ArtworkUrls
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
            is ContentMovieRecommendationsMobileBinding -> _binding.rvMovieRecommendations
            is ContentMovieRecommendationsTvBinding -> _binding.hgvMovieRecommendations
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
                UserPreferences.currentProvider = it
            }
        }
        action()
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
                                                poster = movie.poster ?: movie.banner ?: "",
                                                imdbId = movie.imdbId,
                                            )
                                        )
                                    }
                                )
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
                val animation = when {
                    hasFocus -> AnimationUtils.loadAnimation(context, R.anim.zoom_in)
                    else -> AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                }
                binding.root.startAnimation(animation)
                animation.fillAfter = true

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
            if (ExperimentalMobileDesign.enabled() && watchHistory != null) {
                val primary = com.google.android.material.color.MaterialColors.getColor(
                    this, androidx.appcompat.R.attr.colorPrimary,
                )
                progressTintList = android.content.res.ColorStateList.valueOf(primary)
            }
        }
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
            }
        }
        binding.tvMovieTitle.text = movie.title
        if (ExperimentalMobileDesign.enabled() &&
            movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM
        ) {
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
        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            if (movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM) {
                binding.root.setBackgroundResource(ExperimentalMobileDesign.glassCardBackground())
            }
            ExpMotion.kenBurns(binding.ivMoviePoster)
            ExpMotion.revealHeader(binding.tvMovieTitle)
            ExpMotion.popIn(binding.root)
        }
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
                val animation = when {
                    hasFocus -> AnimationUtils.loadAnimation(context, R.anim.zoom_in)
                    else -> AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                }
                binding.root.startAnimation(animation)
                animation.fillAfter = true
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
            if (ExperimentalMobileDesign.enabled() && watchHistory != null) {
                val primary = com.google.android.material.color.MaterialColors.getColor(
                    this, androidx.appcompat.R.attr.colorPrimary,
                )
                progressTintList = android.content.res.ColorStateList.valueOf(primary)
            }
        }
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
            }
        }
        binding.tvMovieTitle.text = movie.title
        if (ExperimentalMobileDesign.enabled() &&
            binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            ExpMotion.kenBurns(binding.ivMoviePoster)
            ExpMotion.revealHeader(binding.tvMovieTitle)
            ExpMotion.popIn(binding.root)
        }
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
            text = context.getString(R.string.home_swiper_now_playing)
            visibility = View.VISIBLE
        }
        binding.tvSwiperGenres.apply {
            val labels = movie.genres.map { it.name }.filter { it.isNotBlank() }
            text = labels.joinToString(", ")
            visibility = if (labels.isEmpty()) View.GONE else View.VISIBLE
        }

        val openMovie = View.OnClickListener { view ->
            ExpMotion.hapticTap(view)
            view.findNavController().navigate(
                HomeMobileFragmentDirections.actionHomeToMovie(id = movie.id)
            )
        }

        binding.btnSwiperWatchNow.apply {
            FeaturedSwiperChrome.wireWatchButton(this)
            applyExpPress()
            setOnClickListener(openMovie)
        }

        FeaturedSwiperChrome.bindListButton(binding.btnSwiperAddToList, movie.isFavorite)
        binding.btnSwiperAddToList.apply {
            applyExpPress()
            setOnClickListener {
                ExpMotion.hapticTap(it)
                itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
                    val dao = database.movieDao()
                    val target = !(dao.getById(movie.id)?.isFavorite ?: movie.isFavorite)
                    val resolved = ArtworkRepair.resolveMovieForFavorite(context, movie, target)
                    dao.upsertFavorite(resolved, target)
                    com.dskja.betterstreamflix.platform.simkl.SimklSyncHooks.onListToggle(
                        add = target,
                        imdbId = movie.imdbId,
                        tmdbId = movie.tmdbId,
                        isTv = false,
                    )
                    withContext(Dispatchers.Main) {
                        movie.isFavorite = target
                        movie.poster = resolved.poster
                        movie.banner = resolved.banner
                        FeaturedSwiperChrome.bindListButton(this@apply, target, animate = true)
                    }
                }
            }
        }

        binding.root.setOnClickListener(openMovie)
        binding.ivSwiperBackground.apply {
            isClickable = true
            setOnClickListener(openMovie)
        }
    }


    private fun displayMovieMobile(binding: ContentMovieMobileBinding) {
        // Poster lives in the fragment banner; keep the layout stub gone.
        binding.ivMoviePoster.visibility = View.GONE
        Glide.with(binding.ivMoviePoster).clear(binding.ivMoviePoster)

        binding.tvMovieTitle.text = movie.title
        val logoUrl = ArtworkUrls.preferOriginal(movie.logo) ?: ArtworkUrls.preferHero(movie.logo)
        val logoView = binding.ivMovieLogo
        if (!logoUrl.isNullOrBlank()) {
            logoView.visibility = View.VISIBLE
            binding.tvMovieTitle.visibility = View.GONE
            Glide.with(logoView)
                .load(logoUrl)
                .fitCenter()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean,
                    ): Boolean {
                        logoView.visibility = View.GONE
                        binding.tvMovieTitle.visibility = View.VISIBLE
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean,
                    ): Boolean {
                        logoView.visibility = View.VISIBLE
                        binding.tvMovieTitle.visibility = View.GONE
                        return false
                    }
                })
                .into(logoView)
        } else {
            Glide.with(logoView).clear(logoView)
            logoView.setImageDrawable(null)
            logoView.visibility = View.GONE
            binding.tvMovieTitle.visibility = View.VISIBLE
        }

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
                setOnClickListener(null)
            } else {
                val genre = movie.genres.first()
                val label = genre.name.uppercase(Locale.getDefault())
                visibility = View.VISIBLE
                val spanned = android.text.SpannableString(label)
                spanned.setSpan(
                    object : android.text.style.ClickableSpan() {
                        override fun onClick(widget: View) {
                            ExpMotion.hapticTap(widget)
                            checkProviderAndRun {
                                if (context.toActivity()?.getCurrentFragment() is MovieMobileFragment) {
                                    findNavController().navigate(
                                        MovieMobileFragmentDirections.actionMovieToGenre(
                                            id = genre.id,
                                            name = genre.name,
                                        )
                                    )
                                }
                            }
                        }

                        override fun updateDrawState(ds: android.text.TextPaint) {
                            ds.isUnderlineText = false
                            ds.color = currentTextColor
                        }
                    },
                    0,
                    label.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                text = spanned
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                highlightColor = android.graphics.Color.TRANSPARENT
                setOnClickListener(null)
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
            val contentKey = movieDownloadContentKey()
            val playOffline = contentKey != null && OfflineBadgeStore.isCompleted(context, contentKey)
            text = if (playOffline) {
                context.getString(R.string.downloads_play_offline)
            } else {
                context.getString(R.string.movie_watch_now)
            }
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
                        preferredServerName = if (playOffline) {
                            com.dskja.betterstreamflix.fragments.player.PlayerViewModel.OFFLINE_SERVER_NAME
                        } else {
                            null
                        },
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
            val trailer = movie.trailer
            text = ""
            val playIcon = ContextCompat.getDrawable(context, R.drawable.ic_trailer_play)
            TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(
                this,
                null,
                playIcon,
                null,
                null,
            )
            visibility = if (trailer != null || !movie.tmdbId.isNullOrBlank()) {
                View.VISIBLE
            } else {
                View.GONE
            }
            applyExpPress()
            setOnClickListener {
                ExpMotion.hapticTap(it)
                if (trailer != null) {
                    val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                    if (fragment != null) {
                        com.dskja.betterstreamflix.ui.TrailerPlaybackController.play(fragment, trailer)
                    } else {
                        handleTrailerClick(trailer, "MovieMobile")
                    }
                } else {
                    (bindingAdapter as? AppAdapter)?.onDetailTabSelectedListener?.invoke(
                        com.dskja.betterstreamflix.ui.DetailTab.TRAILER,
                    )
                }
            }
        }

        binding.btnMovieDownload.apply {
            applyExpPress()
            contentDescription =
                com.dskja.betterstreamflix.download.DetailDownloadLabels.movieButton(context, movie)
            androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun {
                    val fragment = context.toActivity()?.getCurrentFragment() as? Fragment ?: return@checkProviderAndRun
                    DownloadOptionsController.enqueueMovie(fragment, movie)
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

        binding.tvMovieRating.text = movie.rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"

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
            text = movie.genres.joinToString(", ") { it.name }
            visibility = when {
                movie.genres.isEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.tvMovieOverview.text = movie.overview

        if (ExperimentalMobileDesign.enabled()) {
            val onSurface = com.google.android.material.color.MaterialColors.getColor(
                binding.tvMovieTitle, com.google.android.material.R.attr.colorOnSurface,
            )
            val onVariant = com.google.android.material.color.MaterialColors.getColor(
                binding.tvMovieOverview, com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
            binding.tvMovieTitle.setTextColor(onSurface)
            binding.tvMovieOverview.setTextColor(onVariant)
            listOf(
                binding.tvMovieRating,
                binding.tvMovieQuality,
                binding.tvMovieReleased,
                binding.tvMovieRuntime,
            ).forEach { meta ->
                if (meta.visibility == View.VISIBLE) {
                    meta.setBackgroundResource(ExperimentalMobileDesign.metaPillBackground())
                    meta.setTextColor(onSurface)
                }
            }
            if (binding.root.getTag(R.id.exp_enter_animated_tag) != true) {
                binding.root.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.revealHeader(binding.tvMovieTitle, binding.tvMovieOverview)
            }
        }

        binding.btnMovieWatchNow.apply {
            val contentKey = movieDownloadContentKey()
            val playOffline = contentKey != null && OfflineBadgeStore.isCompleted(context, contentKey)
            text = if (playOffline) {
                context.getString(R.string.downloads_play_offline)
            } else {
                context.getString(R.string.movie_watch_now)
            }
            if (ExperimentalMobileDesign.enabled()) {
                setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
                applyExpPress()
            }
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun {
                    findNavController().navigate(MovieTvFragmentDirections.actionMovieToPlayer(
                        id = movie.id,
                        title = movie.title,
                        subtitle = movie.released?.format("yyyy") ?: "",
                        videoType = Video.Type.Movie(id = movie.id, title = movie.title, releaseDate = movie.released?.format("yyyy-MM-dd") ?: "", poster = movie.poster ?: movie.banner ?: "", imdbId = movie.imdbId),
                        preferredServerName = if (playOffline) {
                            com.dskja.betterstreamflix.fragments.player.PlayerViewModel.OFFLINE_SERVER_NAME
                        } else {
                            null
                        },
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
            val trailer = movie.trailer
            if (ExperimentalMobileDesign.enabled() && trailer != null) {
                setBackgroundResource(ExperimentalMobileDesign.chipBackground())
                applyExpPress()
            }
            setOnClickListener {
                ExpMotion.hapticTap(it)
                if (trailer != null) {
                    val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                    if (fragment != null) {
                        com.dskja.betterstreamflix.ui.TrailerPlaybackController.play(fragment, trailer)
                    } else {
                        handleTrailerClick(trailer, "MovieTv")
                    }
                }
            }
            visibility = if (trailer != null) View.VISIBLE else View.GONE
        }

        binding.btnMovieDownload.apply {
            if (ExperimentalMobileDesign.enabled()) {
                setBackgroundResource(ExperimentalMobileDesign.chipBackground())
                applyExpPress()
            }
            text = com.dskja.betterstreamflix.download.DetailDownloadLabels.movieButton(context, movie)
            setOnClickListener {
                ExpMotion.hapticTap(it)
                checkProviderAndRun {
                    val fragment = context.toActivity()?.getCurrentFragment() as? Fragment ?: return@checkProviderAndRun
                    DownloadOptionsController.enqueueMovie(fragment, movie)
                }
            }
        }

        binding.btnMovieFavorite.apply {

            fun Boolean.drawable() = when (this) {
                true -> R.drawable.ic_favorite_enable
                false -> R.drawable.ic_favorite_disable
            }

            if (ExperimentalMobileDesign.enabled()) {
                setBackgroundResource(ExperimentalMobileDesign.iconChipBackground())
                applyExpPress()
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

                        withContext(Dispatchers.Main) {
                            movie.poster = resolvedMovie.poster
                            movie.banner = resolvedMovie.banner
                            movie.isFavorite = newValue
                            setImageDrawable(
                                ContextCompat.getDrawable(context, newValue.drawable())
                            )
                            ExpMotion.popIn(binding.btnMovieFavorite)
                        }
                    }
                }
            }

            setImageDrawable(
                ContextCompat.getDrawable(context, movie.isFavorite.drawable())
            )
        }
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
            binding.rvMovieDirectors.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    binding.rvMovieDirectors,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
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
            }
        }
        binding.rvMovieDirectors.text = movie.directors.joinToString(separator = ", ") { it.name }
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

        fun bindRows(trailers: List<Triple<String, String, String>>) {
            binding.llDetailTrailerList.removeAllViews()
            if (trailers.isEmpty()) {
                binding.tvDetailTrailerEmpty.visibility = View.VISIBLE
                return
            }
            binding.tvDetailTrailerEmpty.visibility = View.GONE
            val inflater = LayoutInflater.from(context)
            trailers.take(5).forEach { (title, url, type) ->
                val row = ItemDetailTrailerRowMobileBinding.inflate(
                    inflater,
                    binding.llDetailTrailerList,
                    false,
                )
                row.tvDetailTrailerTitle.text = title
                row.tvDetailTrailerMeta.text = type
                row.tvDetailTrailerDesc.text = type
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
                    val fragment = context.toActivity()?.getCurrentFragment() as? Fragment
                    if (fragment != null) {
                        TrailerPlaybackController.play(fragment, url)
                    } else {
                        handleTrailerClick(url, "MovieTrailerSection")
                    }
                }
                row.root.setOnClickListener(play)
                row.ivDetailTrailerPlay.setOnClickListener(play)
                binding.llDetailTrailerList.addView(row.root)
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
                TmdbUtils.listYoutubeTrailers(movie.tmdbId, isTv = false)
            }
            val trailers = when {
                remote.isNotEmpty() -> remote
                seed.isNotEmpty() -> seed
                else -> emptyList()
            }
            bindRows(trailers)
        }
    }

    private fun displayAboutMobile(binding: ContentDetailAboutMobileBinding) {
        binding.root.tag = DETAIL_SECTION_ABOUT
        binding.tvDetailAboutOverview.text = movie.overview.orEmpty()
        val castNames = movie.cast.map { it.name }.filter { it.isNotBlank() }
        val directorNames = movie.directors.map { it.name }.filter { it.isNotBlank() }
        binding.tvDetailAboutFeaturing.apply {
            if (castNames.isEmpty()) visibility = View.GONE
            else {
                visibility = View.VISIBLE
                text = context.getString(R.string.detail_featuring_fmt, castNames.take(4).joinToString(", "))
            }
        }
        binding.tvDetailAboutDirectors.apply {
            if (directorNames.isEmpty()) visibility = View.GONE
            else {
                visibility = View.VISIBLE
                text = context.getString(R.string.detail_directors_fmt, directorNames.joinToString(", "))
            }
        }
        binding.tvDetailAboutCast.apply {
            if (castNames.isEmpty()) visibility = View.GONE
            else {
                visibility = View.VISIBLE
                text = context.getString(R.string.detail_actors_fmt, castNames.joinToString(", "))
            }
        }
    }

}
