package com.dskja.betterstreamflix.adapters.viewholders

import com.dskja.betterstreamflix.models.TrailerCatalog
import com.dskja.betterstreamflix.adapters.viewholders.detail.bindMovieMobileDetail
import com.dskja.betterstreamflix.adapters.viewholders.detail.bindMovieTvDetail

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
import com.dskja.betterstreamflix.adapters.submitAppList
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
import com.dskja.betterstreamflix.ui.DetailRating
import com.dskja.betterstreamflix.ui.DetailTabsController
import com.dskja.betterstreamflix.ui.DetailTrailerMobilePlayer
import com.dskja.betterstreamflix.ui.DetailTrailerTvController
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
import com.dskja.betterstreamflix.databinding.ContentDetailTrailerTvBinding
import com.dskja.betterstreamflix.models.Trailer

class MovieViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    internal val context = itemView.context
    internal val database: AppDatabase
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
    internal lateinit var movie: Movie
    private var onMovieClick: ((Movie) -> Unit)? = null
    private var onMovieLongClick: ((Movie) -> Unit)? = null
    private var onMovieKey: ((Movie, KeyEvent) -> Boolean)? = null
    private var itemSelected: Boolean = false
    private var ribbonStateJob: Job? = null
    private val TAG = "TrailerChoiceDebug" // Logging Tag

    companion object {
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

    internal fun checkProviderAndRun(action: () -> Unit) {
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

    internal fun handleTrailerClick(trailer: String, logPrefix: String = "Movie") {
        Log.d(TAG, "$logPrefix: Clicked. Trailer URL: $trailer")
        val activity = context.toActivity()
        val fragment = activity?.getCurrentFragment() as? Fragment
        if (fragment != null) {
            TrailerPlaybackController.play(fragment, trailer)
        } else {
            TrailerPlaybackController.play(
                context,
                activity,
                trailer,
                activity?.supportFragmentManager,
            )
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
            val dens = binding.ivMoviePoster.resources.displayMetrics.density
            val h = (200f * dens).toInt().coerceAtLeast(400)
            val w = (h * 2 / 3f).toInt().coerceAtLeast(280)
            val reduce = DeviceCapabilities.shouldReduceHomeEffects(binding.ivMoviePoster.context)
            override(w, h)
                .centerCrop()
                .error(R.drawable.glide_fallback_cover)
                .fallback(R.drawable.glide_fallback_cover)
                .let { request ->
                    if (reduce) request.dontAnimate()
                    else request.transition(DrawableTransitionOptions.withCrossFade(120))
                }
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
                            fragment.pinBackground(
                                ArtworkUrls.featuredBannerOrPoster(movie.banner, movie.poster),
                            )
                        } else {
                            fragment.releasePinnedBackground()
                        }
                    }
                }
            }
        }

        binding.ivMoviePoster.loadMoviePoster(movie) {
            val dens = binding.ivMoviePoster.resources.displayMetrics.density
            val h = (200f * dens).toInt().coerceAtLeast(400)
            val w = (h * 2 / 3f).toInt().coerceAtLeast(280)
            val reduce = DeviceCapabilities.shouldReduceHomeEffects(binding.ivMoviePoster.context)
            fallback(R.drawable.glide_fallback_cover)
                .error(R.drawable.glide_fallback_cover)
                .override(w, h)
                .centerCrop()
                .let { request ->
                    if (reduce) request.dontAnimate()
                    else request.transition(DrawableTransitionOptions.withCrossFade(120))
                }
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
            val dens = binding.ivMoviePoster.resources.displayMetrics.density
            val h = (200f * dens).toInt().coerceAtLeast(300)
            val w = (h * 2 / 3f).toInt().coerceAtLeast(200)
            override(w, h)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
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
            val dens = binding.ivMoviePoster.resources.displayMetrics.density
            val h = (200f * dens).toInt().coerceAtLeast(300)
            val w = (h * 2 / 3f).toInt().coerceAtLeast(200)
            fallback(R.drawable.glide_fallback_cover)
                .override(w, h)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
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
    internal fun movieDownloadContentKey(): String? {
        val providerName = movie.providerName
            ?: UserPreferences.currentProvider?.name
            ?: return null
        return DownloadContentKey.movie(providerName, movie.id)
    }

    internal fun preferredOfflineServerName(): String? {
        val contentKey = movieDownloadContentKey() ?: return null
        return if (OfflineBadgeStore.isCompleted(context, contentKey)) {
            com.dskja.betterstreamflix.fragments.player.PlayerViewModel.OFFLINE_SERVER_NAME
        } else {
            null
        }
    }

    internal fun isIptvProvider(): Boolean {
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
        val (artW, artH) = FeaturedSwiperChrome.artworkOverride(binding.ivSwiperBackground)
        val reduceArt = DeviceCapabilities.shouldReduceHomeEffects(binding.ivSwiperBackground.context)
        binding.ivSwiperBackground.loadMovieBanner(movie, hero = false) {
            override(artW, artH)
                .centerCrop()
                .error(R.drawable.glide_fallback_cover)
                .let { request ->
                    if (reduceArt) request.dontAnimate()
                    else request.transition(DrawableTransitionOptions.withCrossFade(160))
                }
        }

        itemView.contentDescription = movie.title
        FeaturedSwiperChrome.resolveAndBindLogo(binding, movie)
        binding.tvSwiperTitle.setTextColor(0xFFF7F7F8.toInt())
        binding.tvSwiperGenres.setTextColor(0xFFE8E8EC.toInt())

        // Retired meta chrome — keep gone so recycled views never flash old pills.
        binding.tvSwiperStatus.visibility = View.GONE
        binding.tvSwiperOverview.visibility = View.GONE
        binding.tvSwiperTvShowLastEpisode.visibility = View.GONE
        binding.tvSwiperQuality.visibility = View.GONE
        binding.tvSwiperReleased.visibility = View.GONE
        binding.tvSwiperRating.visibility = View.GONE
        binding.ivSwiperRatingIcon.visibility = View.GONE
        binding.pbSwiperProgress.visibility = View.GONE

        binding.tvSwiperGenres.apply {
            val genres = FeaturedHeroController.genresLine(movie)
            val status = FeaturedHeroController.statusLine(context, movie)
            text = when {
                genres.isNotEmpty() -> genres
                !status.isNullOrBlank() -> status
                else -> context.getString(R.string.home_swiper_now_playing)
            }
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
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
                    val url = TrailerCatalog.preferredPlayableUrl(remote) ?: return@launch
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

    private fun displayMovieMobile(binding: ContentMovieMobileBinding) =
        bindMovieMobileDetail(binding)

    private fun displayMovieTv(binding: ContentMovieTvBinding) =
        bindMovieTvDetail(binding)

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
            submitAppList(movie.cast.onEach {
                it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
            })
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
            submitAppList(movie.cast.onEach {
                it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
            })
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
            submitAppList(movie.directors.onEach {
                it.itemType = AppAdapter.Type.PEOPLE_MOBILE_ITEM
            })
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
            submitAppList(movie.directors.onEach {
                it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
            })
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
            submitAppList(movie.recommendations.onEach {
                when (it) {
                    is Movie -> it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                    is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
                }
            })
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
            submitAppList(movie.recommendations.onEach {
                when (it) {
                    is Movie -> it.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                    is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                }
            })
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
        val adapter = bindingAdapter as? AppAdapter
        DetailTabsController.bind(
            binding = binding,
            selected = adapter?.selectedDetailTab ?: DetailTab.SIMILAR,
            showEpisodes = false,
            showSimilar = movie.recommendations.isNotEmpty(),
            onSelect = { tab -> adapter?.onDetailTabSelectedListener?.invoke(tab) },
        )
    }

    private fun displayTrailerMobile(binding: ContentDetailTrailerMobileBinding) {
        val player = binding.root.getTag(R.id.detail_trailer_player_tag) as? DetailTrailerMobilePlayer
            ?: DetailTrailerMobilePlayer(binding).also {
                binding.root.setTag(R.id.detail_trailer_player_tag, it)
            }
        player.bind(
            seedUrl = movie.trailer,
            title = movie.title,
            trailerLabel = context.getString(R.string.movie_trailer),
            tmdbId = movie.tmdbId,
            isTv = false,
            year = movie.released?.format("yyyy")?.toIntOrNull(),
            imdbId = movie.imdbId,
            sectionTag = DETAIL_SECTION_TRAILER,
        )
    }

    private fun displayTrailerTv(binding: ContentDetailTrailerTvBinding) {
        DetailTrailerTvController.bind(
            binding = binding,
            seedUrl = movie.trailer,
            title = movie.title,
            trailerLabel = context.getString(R.string.movie_trailer),
            tmdbId = movie.tmdbId,
            isTv = false,
            year = movie.released?.format("yyyy")?.toIntOrNull(),
            imdbId = movie.imdbId,
            onTrailerSeeded = { url -> movie.trailer = url },
        )
    }

    private fun displayAboutMobile(binding: ContentDetailAboutMobileBinding) {
        binding.root.tag = DETAIL_SECTION_ABOUT
        // Hero already shows synopsis — About keeps extras (quality / provider / IDs).
        binding.tvDetailAboutOverview.visibility = View.GONE
        binding.tvDetailAboutOverviewLabel.visibility = View.GONE

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

        // Hero already shows genres / runtime / year / rating / cert — keep About for extras only.
        binding.tvDetailAboutGenresLabel.visibility = View.GONE
        binding.tvDetailAboutGenres.visibility = View.GONE
        binding.tvDetailAboutRuntimeLabel.visibility = View.GONE
        binding.tvDetailAboutRuntime.visibility = View.GONE
        binding.tvDetailAboutYearLabel.visibility = View.GONE
        binding.tvDetailAboutYear.visibility = View.GONE
        binding.tvDetailAboutRatingLabel.visibility = View.GONE
        binding.tvDetailAboutRating.visibility = View.GONE
        binding.tvDetailAboutCertLabel.visibility = View.GONE
        binding.tvDetailAboutCert.visibility = View.GONE
        binding.tvDetailAboutSeasonsLabel.visibility = View.GONE
        binding.tvDetailAboutSeasons.visibility = View.GONE

        bindFact(
            binding.tvDetailAboutQualityLabel,
            binding.tvDetailAboutQuality,
            movie.quality?.takeIf { it.isNotBlank() },
        )
        bindFact(
            binding.tvDetailAboutProviderLabel,
            binding.tvDetailAboutProvider,
            movie.providerName?.takeIf { it.isNotBlank() },
        )

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
            binding.tvDetailAboutQuality,
            binding.tvDetailAboutProvider,
            binding.tvDetailAboutIds,
        ).any { it.visibility == View.VISIBLE }
        binding.tvDetailAboutFactsLabel.visibility =
            if (anyFact) View.VISIBLE else View.GONE
        binding.llDetailAboutFacts.visibility =
            if (anyFact) View.VISIBLE else View.GONE
        binding.vDetailAboutDivider.visibility =
            if (anyFact) View.VISIBLE else View.GONE
        if (!anyFact) {
            val overview = movie.overview?.trim().orEmpty()
            binding.tvDetailAboutOverview.visibility = View.VISIBLE
            binding.tvDetailAboutOverview.text = overview.ifEmpty {
                context.getString(R.string.detail_about_empty)
            }
            binding.tvDetailAboutOverviewLabel.visibility =
                if (overview.isEmpty()) View.GONE else View.VISIBLE
        }
    }

}
