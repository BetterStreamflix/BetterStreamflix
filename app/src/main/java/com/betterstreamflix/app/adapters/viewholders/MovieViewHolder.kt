package com.betterstreamflix.app.adapters.viewholders

import com.betterstreamflix.app.models.TrailerCatalog
import com.betterstreamflix.app.adapters.viewholders.detail.bindMovieMobileDetail
import com.betterstreamflix.app.adapters.viewholders.detail.bindMovieTvDetail

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AlertDialog as AppCompatAlertDialog
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
import com.betterstreamflix.app.R
import com.betterstreamflix.app.utils.AccessibilityPreferences
import com.betterstreamflix.app.utils.TvFocusZoom
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.adapters.submitAppList
import com.betterstreamflix.app.database.AppDatabase
import com.betterstreamflix.app.download.DownloadContentKey
import com.betterstreamflix.app.download.OfflineBadgeStore
import com.betterstreamflix.app.download.ui.DownloadOptionsController
import com.betterstreamflix.app.databinding.ContentMovieCastMobileBinding
import com.betterstreamflix.app.databinding.ContentMovieCastTvBinding
import com.betterstreamflix.app.databinding.ContentMovieMobileBinding
import com.betterstreamflix.app.databinding.ContentDetailAboutMobileBinding
import com.betterstreamflix.app.databinding.ContentDetailTrailerMobileBinding
import com.betterstreamflix.app.databinding.ContentDetailTabsMobileBinding
import com.betterstreamflix.app.utils.TmdbUtils
import com.betterstreamflix.app.databinding.ContentMovieRecommendationsMobileBinding
import com.betterstreamflix.app.databinding.ContentMovieRecommendationsTvBinding
import com.betterstreamflix.app.databinding.ContentMovieTvBinding
import com.betterstreamflix.app.databinding.ItemCategorySwiperMobileBinding
import com.betterstreamflix.app.databinding.ItemMovieGridMobileBinding
import com.betterstreamflix.app.databinding.ItemMovieGridTvBinding
import com.betterstreamflix.app.databinding.ItemMovieMobileBinding
import com.betterstreamflix.app.databinding.ItemMovieTvBinding
import com.betterstreamflix.app.fragments.favorites.FavoritesMobileFragment
import com.betterstreamflix.app.fragments.favorites.FavoritesMobileFragmentDirections
import com.betterstreamflix.app.fragments.favorites.FavoritesTvFragment
import com.betterstreamflix.app.fragments.favorites.FavoritesTvFragmentDirections
import com.betterstreamflix.app.fragments.genre.GenreMobileFragment
import com.betterstreamflix.app.fragments.genre.GenreMobileFragmentDirections
import com.betterstreamflix.app.fragments.genre.GenreTvFragment
import com.betterstreamflix.app.fragments.genre.GenreTvFragmentDirections
import com.betterstreamflix.app.fragments.home.HomeMobileFragment
import com.betterstreamflix.app.fragments.home.HomeMobileFragmentDirections
import com.betterstreamflix.app.fragments.home.HomeTvFragment
import com.betterstreamflix.app.fragments.home.HomeTvFragmentDirections
import com.betterstreamflix.app.fragments.movie.MovieMobileFragment
import com.betterstreamflix.app.fragments.movie.MovieMobileFragmentDirections
import com.betterstreamflix.app.fragments.movie.MovieTvFragment
import com.betterstreamflix.app.fragments.movie.MovieTvFragmentDirections
import com.betterstreamflix.app.fragments.movies.MoviesMobileFragment
import com.betterstreamflix.app.fragments.movies.MoviesMobileFragmentDirections
import com.betterstreamflix.app.fragments.movies.MoviesTvFragment
import com.betterstreamflix.app.fragments.movies.MoviesTvFragmentDirections
import com.betterstreamflix.app.fragments.people.PeopleMobileFragment
import com.betterstreamflix.app.fragments.people.PeopleMobileFragmentDirections
import com.betterstreamflix.app.fragments.people.PeopleTvFragment
import com.betterstreamflix.app.fragments.people.PeopleTvFragmentDirections
import com.betterstreamflix.app.fragments.search.SearchMobileFragment
import com.betterstreamflix.app.fragments.search.SearchMobileFragmentDirections
import com.betterstreamflix.app.fragments.search.SearchTvFragment
import com.betterstreamflix.app.fragments.search.SearchTvFragmentDirections
import com.betterstreamflix.app.fragments.tv_show.TvShowMobileFragment
import com.betterstreamflix.app.fragments.tv_show.TvShowMobileFragmentDirections
import com.betterstreamflix.app.fragments.tv_show.TvShowTvFragment
import com.betterstreamflix.app.fragments.tv_show.TvShowTvFragmentDirections
import com.betterstreamflix.app.fragments.tv_shows.TvShowsTvFragment
import com.betterstreamflix.app.fragments.tv_shows.TvShowsTvFragmentDirections
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.models.Video
import com.betterstreamflix.app.ui.FeaturedHeroController
import com.betterstreamflix.app.ui.FeaturedProviderSwitch
import com.betterstreamflix.app.logo.FeaturedLogoEnrich
import com.betterstreamflix.app.ui.FeaturedSwiperChrome
import com.betterstreamflix.app.ui.ShowOptionsMobileDialog
import com.betterstreamflix.app.ui.ShowOptionsTvDialog
import com.betterstreamflix.app.ui.SpacingItemDecoration
import com.betterstreamflix.app.ui.DetailRating
import com.betterstreamflix.app.ui.DetailTabsController
import com.betterstreamflix.app.ui.DetailTrailerMobilePlayer
import com.betterstreamflix.app.ui.DetailTrailerPlayHooks
import com.betterstreamflix.app.ui.DetailTrailerTvController
import com.betterstreamflix.app.ui.TrailerPlayPipeline
import com.betterstreamflix.app.ui.TrailerPlaybackController
import com.betterstreamflix.app.ui.DetailTab
import com.betterstreamflix.app.ui.TmdbLogoGlide
import androidx.recyclerview.widget.GridLayoutManager
import com.betterstreamflix.app.utils.ExpAmbientGlow
import com.betterstreamflix.app.utils.DeviceCapabilities
import com.betterstreamflix.app.utils.ExpDialogChrome
import com.betterstreamflix.app.utils.ExpMotion
import com.betterstreamflix.app.utils.ExpPressEffects.applyExpPress
import com.betterstreamflix.app.utils.dp
import androidx.preference.Preference
import com.betterstreamflix.app.utils.format
import com.betterstreamflix.app.utils.getCurrentFragment
import com.betterstreamflix.app.utils.loadMovieBanner
import com.betterstreamflix.app.utils.loadMoviePoster
import com.betterstreamflix.app.utils.ArtworkRepair
import com.betterstreamflix.app.utils.ArtworkUrls
import com.betterstreamflix.app.utils.toActivity
import java.util.Locale
import com.betterstreamflix.app.utils.UserPreferences
import com.betterstreamflix.app.providers.Provider
import android.view.KeyEvent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.betterstreamflix.app.databinding.ContentMovieDirectorsMobileBinding
import com.betterstreamflix.app.databinding.ContentMovieDirectorsTvBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.betterstreamflix.app.databinding.ContentDetailTrailerTvBinding
import com.betterstreamflix.app.models.Trailer

class MovieViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    internal val context = itemView.context
    internal val database: AppDatabase?
        get() = AppDatabase.getInstanceOrNull(context)

    init {
        if ((_binding is ItemMovieMobileBinding ||
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
        FeaturedProviderSwitch.switchSticky(movie.providerName, context)
        action()
    }

    /** Leanback: confirm before jumping straight into the player from Continue Watching. */
    private fun openContinueWatchingMovie(movie: Movie) {
        val preferredServer = preferredOfflineServerName()
        val play = {
            // Clean Exit: remember this tile so Back restores exact Home focus.
            com.betterstreamflix.app.player.PlayerCleanExit.tagHomeFocusView(itemView, movie.id)
            com.betterstreamflix.app.player.PlayerCleanExit.rememberHomeFocus(
                movie.id,
                rowHint = "continue_watching",
            )
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
        // OK / Details / Cancel buttons — setItems is fragile for Fire OS DPAD focus.
        AlertDialog.Builder(context)
            .setTitle(R.string.continue_watching_confirm_title)
            .setPositiveButton(R.string.home_swiper_watch_now) { _, _ -> play() }
            .setNeutralButton(R.string.continue_watching_go_to_details) { _, _ -> details() }
            .setNegativeButton(R.string.option_cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener { ExpDialogChrome.polishButtons(dialog) }
                dialog.show()
            }
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
                                com.betterstreamflix.app.player.PlayerCleanExit.tagHomeFocusView(itemView, movie.id)
                                com.betterstreamflix.app.player.PlayerCleanExit.rememberHomeFocus(
                                    movie.id,
                                    rowHint = "continue_watching",
                                )
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
            if (show) {
                setBackgroundResource(R.drawable.bg_meta_pill)
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }

        binding.tvMovieReleasedYear.apply {
            val year = movie.released?.format("yyyy")
            text = year ?: context.getString(R.string.movie_item_type)
            if (!year.isNullOrBlank()) {
                setBackgroundResource(R.drawable.bg_meta_pill)
                val wasVisible = visibility == View.VISIBLE
                visibility = View.VISIBLE
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }

        bindMovieProgress(binding.pbMovieProgress)
        bindMovieRemainingPill(binding.root, continueWatching = movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM)
        if (movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM
        ) {
            ExpMotion.kenBurns(binding.ivMoviePoster)
        }

        binding.tvMovieTitle.text = movie.title
        com.betterstreamflix.app.logo.TitleLogoSurface.bindAndMaybeResolve(
            anchor = binding.root,
            imageView = binding.ivMovieLogo,
            titleView = null,
            movie = movie,
            hideUntilReady = true,
        )
        if (binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            if (movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM) {
                binding.root.setBackgroundResource(R.drawable.bg_cinema_glass_card)
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

        com.betterstreamflix.app.ui.LeanbackDensityPolicy.applyShelfPosterHeight(binding.ivMoviePoster)
        binding.ivMoviePoster.loadMoviePoster(movie) {
            val dens = binding.ivMoviePoster.resources.displayMetrics.density
            val (w, h) = com.betterstreamflix.app.ui.LeanbackDensityPolicy.shelfPosterOverridePx(dens)
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
            val show = watchHistory != null && watchHistory.durationMillis > 0
            progress = if (show) {
                (watchHistory!!.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble())
                    .toInt()
                    .coerceIn(0, 99)
            } else {
                0
            }
            visibility = if (show) View.VISIBLE else View.GONE
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
        bindMovieRemainingPill(
            binding.root,
            continueWatching = movie.itemType == AppAdapter.Type.MOVIE_CONTINUE_WATCHING_TV_ITEM,
        )
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
            if (show) {
                setBackgroundResource(R.drawable.bg_meta_pill)
                if (!wasVisible) ExpMotion.popIn(this)
            }
        }

        binding.tvMovieReleasedYear.apply {
            val year = movie.released?.format("yyyy")
            text = year
            if (!year.isNullOrBlank()) {
                setBackgroundResource(R.drawable.bg_meta_pill)
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
        com.betterstreamflix.app.logo.TitleLogoSurface.bindAndMaybeResolve(
            anchor = binding.root,
            imageView = binding.ivMovieLogo,
            titleView = null,
            movie = movie,
            hideUntilReady = true,
        )
        if (binding.root.getTag(R.id.exp_enter_animated_tag) != true
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
            val (w, h) = com.betterstreamflix.app.ui.LeanbackDensityPolicy.gridPosterOverridePx(dens)
            fallback(R.drawable.glide_fallback_cover)
                .override(w, h)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
        }
        bindRibbons(binding.ivMovieFavoriteRibbon, binding.ivMovieWatchedRibbon, binding.ivMovieDownloadRibbon)
        binding.pbMovieProgress.apply {
            val watchHistory = movie.watchHistory
            val show = watchHistory != null && watchHistory.durationMillis > 0
            progress = if (show) {
                (watchHistory!!.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble())
                    .toInt()
                    .coerceIn(0, 99)
            } else {
                0
            }
            visibility = if (show) View.VISIBLE else View.GONE
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
            val stroke = com.google.android.material.color.MaterialColors.getColor(
                    view,
                    androidx.appcompat.R.attr.colorPrimary,
                )
            view.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(width, stroke)
                cornerRadius = 10f * context.resources.displayMetrics.density
            }
            view.setPadding(width, width, width, width)
            if (view.getTag(R.id.exp_enter_animated_tag) != "selected"
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
            com.betterstreamflix.app.fragments.player.PlayerViewModel.OFFLINE_SERVER_NAME
        } else {
            null
        }
    }

    internal fun isIptvProvider(): Boolean {
        val name = movie.providerName
        val provider = if (!name.isNullOrBlank()) {
            com.betterstreamflix.app.providers.Provider.findByName(name)
        } else {
            UserPreferences.currentProvider
        }
        return provider is com.betterstreamflix.app.providers.IptvProvider
    }

    private fun setRibbonVisible(view: View, visible: Boolean) {
        val wasVisible = view.visibility == View.VISIBLE
        view.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            view.setBackgroundResource(R.drawable.bg_detail_icon_chip)
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
        favoriteRibbon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        watchedRibbon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        downloadRibbon?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setRibbonVisible(favoriteRibbon, movie.isFavorite)
        setRibbonVisible(watchedRibbon, movie.isWatched)
        val contentKey = movieDownloadContentKey()
        val downloaded = contentKey != null && OfflineBadgeStore.isCompleted(context, contentKey)
        downloadRibbon?.let { setRibbonVisible(it, downloaded) }
        applyPosterA11y(movie.isFavorite, movie.isWatched, downloaded)

        ribbonStateJob?.cancel()
        val boundMovieId = movie.id
        val lifecycleOwner = itemView.findViewTreeLifecycleOwner()
            ?: context.toActivity()
            ?: return

        ribbonStateJob = lifecycleOwner.lifecycleScope.launch {
            launch {
                val db = database ?: return@launch
                db.movieDao().getByIdAsFlow(boundMovieId).collect { persistedMovie ->
                    if (movie.id != boundMovieId || persistedMovie == null) return@collect
                    movie.isFavorite = persistedMovie.isFavorite
                    movie.isWatched = persistedMovie.isWatched
                    setRibbonVisible(favoriteRibbon, persistedMovie.isFavorite)
                    setRibbonVisible(watchedRibbon, persistedMovie.isWatched)
                    val key = movieDownloadContentKey()
                    applyPosterA11y(
                        persistedMovie.isFavorite,
                        persistedMovie.isWatched,
                        key != null && OfflineBadgeStore.isCompleted(context, key),
                    )
                }
            }
            if (downloadRibbon != null) {
                launch {
                    OfflineBadgeStore.completedKeys(context).collect { keys ->
                        if (movie.id != boundMovieId) return@collect
                        val key = movieDownloadContentKey()
                        val isDownloaded = key != null && keys.contains(key)
                        setRibbonVisible(downloadRibbon, isDownloaded)
                        applyPosterA11y(movie.isFavorite, movie.isWatched, isDownloaded)
                    }
                }
            }
        }
    }

    private fun applyPosterA11y(
        favorite: Boolean = movie.isFavorite,
        watched: Boolean = movie.isWatched,
        downloaded: Boolean = false,
    ) {
        itemView.contentDescription = AccessibilityPreferences.posterLabel(
            context,
            movie.title,
            favorite = favorite,
            watched = watched,
            downloaded = downloaded,
        )
        AccessibilityPreferences.announceSelected(itemView, itemSelected || favorite)
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

        applyPosterA11y()
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
            FeaturedProviderSwitch.runWithProvider(movie, context) {
                view.findNavController().navigate(
                    HomeMobileFragmentDirections.actionHomeToMovie(id = movie.id)
                )
            }
        }

        binding.btnSwiperWatchNow.apply {
            FeaturedSwiperChrome.wireWatchButton(this)
            com.betterstreamflix.app.player.PlayerCleanExit.tagFeaturedWatch(this)
            text = FeaturedHeroController.watchCtaLabel(context, movie)
            contentDescription = context.getString(R.string.a11y_featured_watch, movie.title)
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

        FeaturedSwiperChrome.bindMobileListButton(
            binding.btnSwiperAddToList,
            movie.isFavorite,
            title = movie.title,
        )
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
            FeaturedSwiperChrome.bindMobileListButton(
                binding.btnSwiperAddToList,
                favorite,
                title = movie.title,
            )
        }

        // Featured cover upgrade — providers that only ship a portrait poster
        // (e.g. Anikoto) get a real TMDb backdrop instead of a blurry crop.
        FeaturedLogoEnrich.enrichBackdropIfNeeded(
            anchor = itemView,
            imageView = binding.ivSwiperBackground,
            movie = movie,
        )

        // Nested clickables confuse TalkBack — keep chrome buttons primary.
        binding.root.isClickable = false
        binding.root.setOnClickListener(null)
        binding.ivSwiperBackground.apply {
            isClickable = true
            contentDescription = AccessibilityPreferences.posterLabel(
                context,
                movie.title,
                favorite = movie.isFavorite,
                watched = movie.isWatched,
            )
            setOnClickListener(openMovie)
        }
    }

    private fun displayMovieMobile(binding: ContentMovieMobileBinding) =
        bindMovieMobileDetail(binding)

    private fun displayMovieTv(binding: ContentMovieTvBinding) =
        bindMovieTvDetail(binding)

    private fun displayCastMobile(binding: ContentMovieCastMobileBinding) {
        if (binding.root.getTag(R.id.exp_enter_animated_tag) != true
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
        binding.hgvMovieCast.apply {
            // Always pin after bind — Leanback WRAP_CONTENT HGV focus bugs on Fire OS.
            setRowHeight((210 * resources.displayMetrics.density).toInt())
            setTag(R.id.tv_shelf_row_height_tag, true)
            submitAppList(movie.cast.onEach {
                it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
            })
            setItemSpacing(80)
        }
    }

    private fun displayDirectorsMobile(binding: ContentMovieDirectorsMobileBinding) {
        if (binding.root.getTag(R.id.exp_enter_animated_tag) != true
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
        binding.hgvMovieDirectors.apply {
            // Always pin after bind — Leanback WRAP_CONTENT HGV focus bugs on Fire OS.
            setRowHeight((210 * resources.displayMetrics.density).toInt())
            setTag(R.id.tv_shelf_row_height_tag, true)
            submitAppList(movie.directors.onEach {
                it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM
            })
            setItemSpacing(80)
        }
    }

    private fun displayRecommendationsMobile(binding: ContentMovieRecommendationsMobileBinding) {
        binding.root.tag = DETAIL_SECTION_RECOMMENDATIONS
        if (binding.root.getTag(R.id.exp_enter_animated_tag) != true
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
        binding.hgvMovieRecommendations.apply {
            // Always pin after bind — Leanback WRAP_CONTENT HGV focus bugs on Fire OS.
            setRowHeight((210 * resources.displayMetrics.density).toInt())
            setTag(R.id.tv_shelf_row_height_tag, true)
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
        if (show && (!wasVisible || bar.progress != target)) {
            android.animation.ObjectAnimator.ofInt(bar, "progress", 0, target)
                .setDuration(420L)
                .start()
        } else {
            bar.progress = target
        }
    }

    private fun bindMovieRemainingPill(root: View, continueWatching: Boolean) {
        val remaining = root.findViewById<android.widget.TextView>(R.id.tv_movie_remaining) ?: return
        val watchHistory = movie.watchHistory
        if (continueWatching && watchHistory != null && watchHistory.durationMillis > 0) {
            remaining.text = com.betterstreamflix.app.ui.DetailWatchLabels
                .remainingMinutesLabel(context, watchHistory)
                ?: context.getString(R.string.continue_watching_percent, 0)
            remaining.setBackgroundResource(R.drawable.bg_meta_pill)
            val wasVisible = remaining.visibility == View.VISIBLE
            remaining.visibility = View.VISIBLE
            if (!wasVisible) ExpMotion.popIn(remaining)
        } else {
            remaining.visibility = View.GONE
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
        val base = DetailTrailerPlayHooks.movieSession(
            movie = movie,
            host = binding.root,
            leanback = false,
        )
        val session = TrailerPlayPipeline.Session(
            target = base.target,
            onStreamNow = {
                checkProviderAndRun { base.onStreamNow?.invoke() }
            },
        )
        player.bind(
            seedUrl = movie.trailer,
            title = movie.title,
            trailerLabel = context.getString(R.string.movie_trailer),
            tmdbId = movie.tmdbId,
            isTv = false,
            year = movie.released?.format("yyyy")?.toIntOrNull(),
            imdbId = movie.imdbId,
            sectionTag = DETAIL_SECTION_TRAILER,
            pipelineSession = session,
        )
    }

    private fun displayTrailerTv(binding: ContentDetailTrailerTvBinding) {
        val session = TrailerPlayPipeline.Session(
            target = DetailTrailerPlayHooks.movieSession(
                movie = movie,
                host = binding.root,
                leanback = true,
            ).target,
            onStreamNow = {
                checkProviderAndRun {
                    DetailTrailerPlayHooks.movieSession(
                        movie = movie,
                        host = binding.root,
                        leanback = true,
                    ).onStreamNow?.invoke()
                }
            },
        )
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
            pipelineSession = session,
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

        val tmdbPart = movie.tmdbId?.takeIf { it.isNotBlank() }?.let {
            context.getString(R.string.detail_about_id_tmdb, it)
        }
        val imdbPart = movie.imdbId?.takeIf { it.isNotBlank() }?.let {
            context.getString(R.string.detail_about_id_imdb, it)
        }
        val ids = listOfNotNull(tmdbPart, imdbPart).joinToString(" · ")
        bindFact(binding.tvDetailAboutIdsLabel, binding.tvDetailAboutIds, ids.ifBlank { null })
        if (!tmdbPart.isNullOrBlank() && binding.tvDetailAboutIds.visibility == View.VISIBLE) {
            val spanned = android.text.SpannableString(ids)
            val tmdbColor = ContextCompat.getColor(context, R.color.cinema_tmdb)
            spanned.setSpan(
                android.text.style.ForegroundColorSpan(tmdbColor),
                0,
                tmdbPart.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            binding.tvDetailAboutIds.text = spanned
        }

        val stripNetworks = binding.root.findViewById<View>(R.id.strip_detail_about_networks)
        val stripWatch = binding.root.findViewById<View>(R.id.strip_detail_about_watch)
        com.betterstreamflix.app.logo.LogoChipStrip.bind(
            stripNetworks,
            context.getString(R.string.detail_about_studios_label),
            com.betterstreamflix.app.logo.LogoChips.parse(movie.companyJson),
        )
        com.betterstreamflix.app.logo.LogoChipStrip.bind(
            stripWatch,
            context.getString(R.string.detail_about_watch_label),
            com.betterstreamflix.app.logo.LogoChips.parse(movie.watchProviderJson),
        )

        val anyFact = listOf(
            binding.tvDetailAboutQuality,
            binding.tvDetailAboutProvider,
            binding.tvDetailAboutIds,
            stripNetworks,
            stripWatch,
        ).any { it != null && it.visibility == View.VISIBLE }
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
