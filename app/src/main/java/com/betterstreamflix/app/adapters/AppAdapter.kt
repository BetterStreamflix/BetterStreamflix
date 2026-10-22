package com.betterstreamflix.app.adapters

import android.os.Parcelable
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.betterstreamflix.app.adapters.viewholders.CategoryViewHolder
import com.betterstreamflix.app.adapters.viewholders.EpisodeViewHolder
import com.betterstreamflix.app.adapters.viewholders.GenreViewHolder
import com.betterstreamflix.app.adapters.viewholders.MovieViewHolder
import com.betterstreamflix.app.adapters.viewholders.PeopleViewHolder
import com.betterstreamflix.app.adapters.viewholders.ProviderViewHolder
import com.betterstreamflix.app.adapters.viewholders.SeasonViewHolder
import com.betterstreamflix.app.adapters.viewholders.TvShowViewHolder
import com.betterstreamflix.app.ui.FeaturedHeroController
import com.betterstreamflix.app.databinding.ContentCategorySwiperMobileBinding
import com.betterstreamflix.app.databinding.ContentCategorySwiperTvBinding
import com.betterstreamflix.app.databinding.ContentDetailAboutMobileBinding
import com.betterstreamflix.app.databinding.ContentDetailTabsMobileBinding
import com.betterstreamflix.app.databinding.ContentDetailTrailerMobileBinding
import com.betterstreamflix.app.databinding.ContentMovieCastMobileBinding
import com.betterstreamflix.app.databinding.ContentMovieCastTvBinding
import com.betterstreamflix.app.databinding.ContentMovieDirectorsMobileBinding
import com.betterstreamflix.app.databinding.ContentMovieDirectorsTvBinding
import com.betterstreamflix.app.databinding.ContentMovieMobileBinding
import com.betterstreamflix.app.databinding.ContentMovieRecommendationsMobileBinding
import com.betterstreamflix.app.databinding.ContentMovieRecommendationsTvBinding
import com.betterstreamflix.app.databinding.ContentMovieTvBinding
import com.betterstreamflix.app.databinding.ContentTvShowCastMobileBinding
import com.betterstreamflix.app.databinding.ContentTvShowCastTvBinding
import com.betterstreamflix.app.databinding.ContentTvShowDirectorsMobileBinding
import com.betterstreamflix.app.databinding.ContentTvShowDirectorsTvBinding
import com.betterstreamflix.app.databinding.ContentTvShowMobileBinding
import com.betterstreamflix.app.databinding.ContentTvShowRecommendationsMobileBinding
import com.betterstreamflix.app.databinding.ContentTvShowRecommendationsTvBinding
import com.betterstreamflix.app.databinding.ContentTvShowSeasonsMobileBinding
import com.betterstreamflix.app.databinding.ContentTvShowSeasonsTvBinding
import com.betterstreamflix.app.databinding.ContentTvShowTvBinding
import com.betterstreamflix.app.databinding.ItemCategoryMobileBinding
import com.betterstreamflix.app.databinding.ItemCategorySwiperMobileBinding
import com.betterstreamflix.app.databinding.ItemCategoryTvBinding
import com.betterstreamflix.app.databinding.ItemEpisodeContinueWatchingMobileBinding
import com.betterstreamflix.app.databinding.ItemEpisodeContinueWatchingTvBinding
import com.betterstreamflix.app.databinding.ItemEpisodeDetailMobileBinding
import com.betterstreamflix.app.databinding.ItemEpisodeMobileBinding
import com.betterstreamflix.app.databinding.ItemEpisodeTvBinding
import com.betterstreamflix.app.databinding.ItemGenreGridMobileBinding
import com.betterstreamflix.app.databinding.ItemGenreGridTvBinding
import com.betterstreamflix.app.databinding.ItemLoadingBinding
import com.betterstreamflix.app.databinding.ItemMovieGridMobileBinding
import com.betterstreamflix.app.databinding.ItemMovieGridTvBinding
import com.betterstreamflix.app.databinding.ItemMovieMobileBinding
import com.betterstreamflix.app.databinding.ItemMovieTvBinding
import com.betterstreamflix.app.databinding.ItemPeopleMobileBinding
import com.betterstreamflix.app.databinding.ItemPeopleTvBinding
import com.betterstreamflix.app.databinding.ItemProviderMobileBinding
import com.betterstreamflix.app.databinding.ItemProviderTvBinding
import com.betterstreamflix.app.databinding.ItemSeasonMobileBinding
import com.betterstreamflix.app.databinding.ItemSeasonTvBinding
import com.betterstreamflix.app.databinding.ItemTvShowGridBinding
import com.betterstreamflix.app.databinding.ItemTvShowGridMobileBinding
import com.betterstreamflix.app.databinding.ItemTvShowMobileBinding
import com.betterstreamflix.app.databinding.ItemTvShowTvBinding
import com.betterstreamflix.app.models.Category
import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.models.Genre
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.People
import com.betterstreamflix.app.models.Provider
import com.betterstreamflix.app.models.Season
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.fragments.favorites.FavoriteSectionHeader
import com.betterstreamflix.app.support.SupportBannerItem
import com.betterstreamflix.app.watchlist.WatchlistDigestBannerItem
import com.betterstreamflix.app.providers.ProviderOutageBannerItem
import com.betterstreamflix.app.R
import com.betterstreamflix.app.databinding.ItemSupportBannerMobileBinding
import com.betterstreamflix.app.databinding.ItemSupportBannerTvBinding
import com.betterstreamflix.app.databinding.ItemWatchlistDigestMobileBinding
import com.betterstreamflix.app.databinding.ItemWatchlistDigestTvBinding
import com.betterstreamflix.app.adapters.viewholders.ProviderOutageBannerViewHolder
import com.betterstreamflix.app.utils.ExpMotion
import com.betterstreamflix.app.utils.UserPreferences
import com.betterstreamflix.app.support.SupportUiBinder
import com.betterstreamflix.app.adapters.viewholders.TrailerViewHolder
import com.betterstreamflix.app.databinding.ContentDetailTrailerTvBinding
import com.betterstreamflix.app.databinding.ItemTrailerTvBinding
import com.betterstreamflix.app.models.Trailer

class AppAdapter(
    val items: MutableList<Item> = mutableListOf()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val PAYLOAD_SELECTION = "selection"
    }

    init {
        setHasStableIds(true)
    }

    // --- LISTENERS AÑADIDOS AQUÍ ---
    var onMovieClickListener: ((Movie) -> Unit)? = null
    var onTvShowClickListener: ((TvShow) -> Unit)? = null
    var onMovieLongClickListener: ((Movie) -> Unit)? = null
    var onTvShowLongClickListener: ((TvShow) -> Unit)? = null
    var onMovieKeyListener: ((Movie, KeyEvent) -> Boolean)? = null
    var onTvShowKeyListener: ((TvShow, KeyEvent) -> Boolean)? = null
    var isItemSelectedListener: ((Item) -> Boolean)? = null
    var onGenreClickListener: ((Genre) -> Unit)? = null
    var onPeopleClickListener: ((People) -> Unit)? = null
    var onEpisodeClickListener: ((Episode) -> Unit)? = null
    var onSeasonClickListener: ((Season) -> Unit)? = null
    var onProviderClickListener: ((Provider) -> Unit)? = null
    var onSupportBannerClickListener: (() -> Unit)? = null
    var onSupportBannerDismissListener: (() -> Unit)? = null
    var onWatchlistDigestClickListener: ((WatchlistDigestBannerItem) -> Unit)? = null
    var onWatchlistDigestDismissListener: ((WatchlistDigestBannerItem) -> Unit)? = null
    var onProviderOutageBannerClickListener: ((ProviderOutageBannerItem) -> Unit)? = null
    var onProviderOutageBannerDismissListener: ((ProviderOutageBannerItem) -> Unit)? = null
    var onDetailTabSelectedListener: ((com.betterstreamflix.app.ui.DetailTab) -> Unit)? = null
    var selectedDetailTab: com.betterstreamflix.app.ui.DetailTab =
        com.betterstreamflix.app.ui.DetailTab.SIMILAR
    // ---------------------------------
    interface Item {
        var itemType: Type

        /** Safe read — Featured/Room clones can leave lateinit unset (BETTERSTREAMFLIX-1P). */
        fun itemTypeOrNull(): Type? = runCatching { itemType }.getOrNull()
    }

    enum class Type {
        CATEGORY_MOBILE_ITEM,
        CATEGORY_TV_ITEM,

        CATEGORY_MOBILE_SWIPER,
        CATEGORY_TV_SWIPER,

        EPISODE_MOBILE_ITEM,
        EPISODE_DETAIL_MOBILE_ITEM,
        EPISODE_TV_ITEM,
        EPISODE_CONTINUE_WATCHING_MOBILE_ITEM,
        EPISODE_CONTINUE_WATCHING_TV_ITEM,

        FOOTER,

        FAVORITE_SECTION_HEADER,

        SUPPORT_BANNER_MOBILE_ITEM,
        SUPPORT_BANNER_TV_ITEM,

        WATCHLIST_DIGEST_MOBILE_ITEM,
        WATCHLIST_DIGEST_TV_ITEM,

        PROVIDER_OUTAGE_BANNER_MOBILE_ITEM,
        PROVIDER_OUTAGE_BANNER_TV_ITEM,

        GENRE_GRID_MOBILE_ITEM,
        GENRE_GRID_TV_ITEM,

        HEADER,

        LOADING_ITEM,

        MOVIE_MOBILE_ITEM,
        MOVIE_TV_ITEM,
        MOVIE_CONTINUE_WATCHING_MOBILE_ITEM,
        MOVIE_CONTINUE_WATCHING_TV_ITEM,
        MOVIE_GRID_MOBILE_ITEM,
        MOVIE_GRID_TV_ITEM,
        MOVIE_SWIPER_MOBILE_ITEM,

        MOVIE_MOBILE,
        MOVIE_TV,
        MOVIE_DIRECTORS_MOBILE,
        MOVIE_DIRECTORS_TV,
        MOVIE_CAST_MOBILE,
        MOVIE_CAST_TV,
        MOVIE_RECOMMENDATIONS_MOBILE,
        MOVIE_RECOMMENDATIONS_TV,

        MOVIE_TABS_MOBILE,
        MOVIE_TRAILER_MOBILE,
        MOVIE_TRAILER_TV,
        MOVIE_ABOUT_MOBILE,

        PEOPLE_MOBILE_ITEM,
        PEOPLE_TV_ITEM,

        PROVIDER_MOBILE_ITEM,
        PROVIDER_TV_ITEM,

        SEASON_MOBILE_ITEM,
        SEASON_TV_ITEM,

        TRAILER_TV_ITEM,

        TV_SHOW_MOBILE_ITEM,
        TV_SHOW_TV_ITEM,
        TV_SHOW_GRID_MOBILE_ITEM,
        TV_SHOW_GRID_TV_ITEM,
        TV_SHOW_SWIPER_MOBILE_ITEM,

        TV_SHOW_MOBILE,
        TV_SHOW_TV,
        TV_SHOW_SEASONS_MOBILE,
        TV_SHOW_SEASONS_TV,
        TV_SHOW_DIRECTORS_MOBILE,
        TV_SHOW_DIRECTORS_TV,
        TV_SHOW_CAST_MOBILE,
        TV_SHOW_CAST_TV,
        TV_SHOW_RECOMMENDATIONS_MOBILE,
        TV_SHOW_RECOMMENDATIONS_TV,

        TV_SHOW_TABS_MOBILE,
        TV_SHOW_TRAILER_MOBILE,
        TV_SHOW_TRAILER_TV,
        TV_SHOW_ABOUT_MOBILE,
    }

    private val states = mutableMapOf<Int, Parcelable?>()
    private var itemIdentities: List<String> = emptyList()
    private var itemIdentityCounts: MutableMap<String, Int> = mutableMapOf()
    private var itemStableIds: LongArray = longArrayOf()

    var isLoading = false
    private var header: Header<ViewBinding>? = null
    private var onLoadMoreListener: (() -> Unit)? = null
    private var footer: Footer<ViewBinding>? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (Type.entries[viewType]) {
            Type.CATEGORY_MOBILE_ITEM -> CategoryViewHolder(
                ItemCategoryMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.item_category_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.CATEGORY_TV_ITEM -> CategoryViewHolder(
                ItemCategoryTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.CATEGORY_MOBILE_SWIPER -> CategoryViewHolder(
                ContentCategorySwiperMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.content_category_swiper_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.CATEGORY_TV_SWIPER -> CategoryViewHolder(
                ContentCategorySwiperTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.EPISODE_MOBILE_ITEM -> EpisodeViewHolder(
                ItemEpisodeMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.item_episode_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.EPISODE_DETAIL_MOBILE_ITEM -> EpisodeViewHolder(
                ItemEpisodeDetailMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.item_episode_detail_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.EPISODE_TV_ITEM -> EpisodeViewHolder(
                ItemEpisodeTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.EPISODE_CONTINUE_WATCHING_MOBILE_ITEM -> EpisodeViewHolder(
                ItemEpisodeContinueWatchingMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.item_episode_continue_watching_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.EPISODE_CONTINUE_WATCHING_TV_ITEM -> EpisodeViewHolder(
                ItemEpisodeContinueWatchingTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.FOOTER -> FooterViewHolder(
                footer!!.binding(parent)
            )
            Type.FAVORITE_SECTION_HEADER -> FavoriteSectionHeaderViewHolder(
                LayoutInflater.from(parent.context).inflate(
                    R.layout.item_favorite_section_header,
                    parent,
                    false,
                )
            )

            Type.SUPPORT_BANNER_MOBILE_ITEM -> SupportBannerViewHolder(
                ItemSupportBannerMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.SUPPORT_BANNER_TV_ITEM -> SupportBannerViewHolder(
                ItemSupportBannerTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.WATCHLIST_DIGEST_MOBILE_ITEM -> WatchlistDigestViewHolder(
                ItemWatchlistDigestMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.WATCHLIST_DIGEST_TV_ITEM -> WatchlistDigestViewHolder(
                ItemWatchlistDigestTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.PROVIDER_OUTAGE_BANNER_MOBILE_ITEM -> ProviderOutageBannerViewHolder(
                LayoutInflater.from(parent.context).inflate(
                    R.layout.item_provider_outage_banner_mobile,
                    parent,
                    false,
                )
            )
            Type.PROVIDER_OUTAGE_BANNER_TV_ITEM -> ProviderOutageBannerViewHolder(
                LayoutInflater.from(parent.context).inflate(
                    R.layout.item_provider_outage_banner_tv,
                    parent,
                    false,
                )
            )

            Type.GENRE_GRID_MOBILE_ITEM -> GenreViewHolder(
                ItemGenreGridMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.item_genre_grid_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.GENRE_GRID_TV_ITEM -> GenreViewHolder(
                ItemGenreGridTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.HEADER -> HeaderViewHolder(
                header!!.binding(parent)
            )

            Type.LOADING_ITEM -> {
                val layoutRes = R.layout.item_loading
                val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
                // Bind defensively: exp/classic layouts share required IDs; never crash load-more.
                val binding = runCatching { ItemLoadingBinding.bind(view) }.getOrNull()
                LoadingViewHolder(binding ?: object : ViewBinding {
                    override fun getRoot(): View = view
                })
            }

            Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM,
            Type.MOVIE_MOBILE_ITEM -> MovieViewHolder(
                ItemMovieMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.item_movie_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.MOVIE_CONTINUE_WATCHING_TV_ITEM,
            Type.MOVIE_TV_ITEM -> MovieViewHolder(
                ItemMovieTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_GRID_MOBILE_ITEM -> MovieViewHolder(
                ItemMovieGridMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.item_movie_grid_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.MOVIE_GRID_TV_ITEM -> MovieViewHolder(
                ItemMovieGridTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_SWIPER_MOBILE_ITEM -> MovieViewHolder(
                ItemCategorySwiperMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.item_category_swiper_mobile,
                        parent,
                        false,
                    )
                )
            )

            Type.MOVIE_MOBILE -> MovieViewHolder(
                ContentMovieMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.content_movie_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.MOVIE_TV -> MovieViewHolder(
                ContentMovieTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_DIRECTORS_MOBILE -> MovieViewHolder(
                ContentMovieDirectorsMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.content_movie_directors_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.MOVIE_DIRECTORS_TV -> MovieViewHolder(
                ContentMovieDirectorsTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_CAST_MOBILE -> MovieViewHolder(
                ContentMovieCastMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.content_movie_cast_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.MOVIE_CAST_TV -> MovieViewHolder(
                ContentMovieCastTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_RECOMMENDATIONS_MOBILE -> MovieViewHolder(
                ContentMovieRecommendationsMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.content_movie_recommendations_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.MOVIE_RECOMMENDATIONS_TV -> MovieViewHolder(
                ContentMovieRecommendationsTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.MOVIE_TABS_MOBILE -> MovieViewHolder(
                ContentDetailTabsMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.content_detail_tabs_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.MOVIE_TRAILER_MOBILE -> MovieViewHolder(
                ContentDetailTrailerMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.content_detail_trailer_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.MOVIE_TRAILER_TV -> MovieViewHolder(
                ContentDetailTrailerTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.MOVIE_ABOUT_MOBILE -> MovieViewHolder(
                ContentDetailAboutMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.content_detail_about_mobile,
                        parent,
                        false,
                    )
                )
            )

            Type.PEOPLE_MOBILE_ITEM -> PeopleViewHolder(
                ItemPeopleMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.item_people_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.PEOPLE_TV_ITEM -> PeopleViewHolder(
                ItemPeopleTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.TRAILER_TV_ITEM -> TrailerViewHolder(
                ItemTrailerTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.PROVIDER_MOBILE_ITEM -> ProviderViewHolder(
                ItemProviderMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.item_provider_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.PROVIDER_TV_ITEM -> ProviderViewHolder(
                ItemProviderTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.SEASON_MOBILE_ITEM -> SeasonViewHolder(
                ItemSeasonMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.item_season_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.SEASON_TV_ITEM -> SeasonViewHolder(
                ItemSeasonTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

            Type.TV_SHOW_MOBILE_ITEM -> TvShowViewHolder(
                ItemTvShowMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.item_tv_show_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.TV_SHOW_TV_ITEM -> TvShowViewHolder(
                ItemTvShowTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
            Type.TV_SHOW_GRID_MOBILE_ITEM -> TvShowViewHolder(
                ItemTvShowGridMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.item_tv_show_grid_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.TV_SHOW_GRID_TV_ITEM -> TvShowViewHolder(
                ItemTvShowGridBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
            Type.TV_SHOW_SWIPER_MOBILE_ITEM -> TvShowViewHolder(
                ItemCategorySwiperMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.item_category_swiper_mobile,
                        parent,
                        false,
                    )
                )
            )

            Type.TV_SHOW_MOBILE -> TvShowViewHolder(
                ContentTvShowMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.content_tv_show_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.TV_SHOW_TV -> TvShowViewHolder(
                ContentTvShowTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_SEASONS_MOBILE -> TvShowViewHolder(
                ContentTvShowSeasonsMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.content_tv_show_seasons_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.TV_SHOW_SEASONS_TV -> TvShowViewHolder(
                ContentTvShowSeasonsTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_DIRECTORS_MOBILE -> TvShowViewHolder(
                ContentTvShowDirectorsMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.content_tv_show_directors_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.TV_SHOW_DIRECTORS_TV -> TvShowViewHolder(
                ContentTvShowDirectorsTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_CAST_MOBILE -> TvShowViewHolder(
                ContentTvShowCastMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.content_tv_show_cast_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.TV_SHOW_CAST_TV -> TvShowViewHolder(
                ContentTvShowCastTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_RECOMMENDATIONS_MOBILE -> TvShowViewHolder(
                ContentTvShowRecommendationsMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.content_tv_show_recommendations_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.TV_SHOW_RECOMMENDATIONS_TV -> TvShowViewHolder(
                ContentTvShowRecommendationsTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_TABS_MOBILE -> TvShowViewHolder(
                ContentDetailTabsMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.content_detail_tabs_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.TV_SHOW_TRAILER_MOBILE -> TvShowViewHolder(
                ContentDetailTrailerMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.content_detail_trailer_mobile,
                        parent,
                        false,
                    )
                )
            )
            Type.TV_SHOW_TRAILER_TV -> TvShowViewHolder(
                ContentDetailTrailerTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )
            Type.TV_SHOW_ABOUT_MOBILE -> TvShowViewHolder(
                ContentDetailAboutMobileBinding.bind(
                    LayoutInflater.from(parent.context).inflate(
                        R.layout.content_detail_about_mobile,
                        parent,
                        false,
                    )
                )
            )
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position >= itemCount - 5 && !isLoading) {
            onLoadMoreListener?.invoke()
            isLoading = true
        }

        val adjustedPosition = header?.let { position - 1 } ?: position
        val item = items.getOrNull(adjustedPosition)
        when (holder) {
            is CategoryViewHolder -> {
                val category = item as? Category ?: return
                holder.bind(
                    category,
                    onMovieClickListener,
                    onTvShowClickListener,
                    onMovieLongClickListener,
                    onTvShowLongClickListener,
                )
            }
            is EpisodeViewHolder -> {
                val episode = item as? Episode ?: return
                holder.bind(episode)
            }
            is FooterViewHolder -> footer?.bind?.invoke(holder.binding)
            is FavoriteSectionHeaderViewHolder -> {
                val headerItem = item as? FavoriteSectionHeader ?: return
                holder.bind(headerItem)
            }
            is LoadingViewHolder -> holder.bind()
            is SupportBannerViewHolder -> holder.bind(
                onSupportBannerClickListener,
                onSupportBannerDismissListener,
            )
            is WatchlistDigestViewHolder -> {
                val banner = item as? WatchlistDigestBannerItem ?: return
                holder.bind(
                    banner,
                    onWatchlistDigestClickListener,
                    onWatchlistDigestDismissListener,
                )
            }
            is ProviderOutageBannerViewHolder -> {
                val banner = item as? ProviderOutageBannerItem ?: return
                holder.bind(
                    banner,
                    onProviderOutageBannerClickListener,
                    onProviderOutageBannerDismissListener,
                )
            }
            is GenreViewHolder -> {
                val genre = item as? Genre ?: return
                holder.bind(genre)
            }
            is HeaderViewHolder -> header?.bind?.invoke(holder.binding)
            is MovieViewHolder -> {
                val movie = item as? Movie ?: return
                holder.bind(
                    movie,
                    onMovieClickListener,
                    onMovieLongClickListener,
                    onMovieKeyListener,
                    isItemSelectedListener?.invoke(movie) == true,
                )
            }
            is PeopleViewHolder -> {
                val people = item as? People ?: return
                holder.bind(people)
            }
            is ProviderViewHolder -> {
                val provider = item as? Provider ?: return
                holder.bind(provider)
            }
            is SeasonViewHolder -> {
                val season = item as? Season ?: return
                holder.bind(season)
            }
            is TrailerViewHolder -> {
                val trailer = item as? Trailer ?: return
                holder.bind(trailer)
            }
            is TvShowViewHolder -> {
                val tvShow = item as? TvShow ?: return
                holder.bind(
                    tvShow,
                    onTvShowClickListener,
                    onTvShowLongClickListener,
                    onTvShowKeyListener,
                    isItemSelectedListener?.invoke(tvShow) == true,
                )
            }
        }

        val state = states[holder.layoutPosition]
        if (state != null) {
            when (holder) {
                is CategoryViewHolder -> holder.childRecyclerView?.layoutManager?.onRestoreInstanceState(state)
                is MovieViewHolder -> holder.childRecyclerView?.layoutManager?.onRestoreInstanceState(state)
                is TvShowViewHolder -> holder.childRecyclerView?.layoutManager?.onRestoreInstanceState(state)
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (PAYLOAD_SELECTION in payloads) {
            val adjustedPosition = header?.let { position - 1 } ?: position
            val selected = items.getOrNull(adjustedPosition)
                ?.let { isItemSelectedListener?.invoke(it) }
                ?: false
            when (holder) {
                is MovieViewHolder -> holder.setItemSelected(selected)
                is TvShowViewHolder -> holder.setItemSelected(selected)
                else -> super.onBindViewHolder(holder, position, payloads)
            }
            return
        }
        if (FeaturedHeroController.PAYLOAD_ROTATE in payloads) {
            (holder as? CategoryViewHolder)?.bindFeaturedRotatePayload()
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    fun notifyItemSelectionChanged(position: Int) {
        if (position in items.indices) notifyItemChanged(position, PAYLOAD_SELECTION)
    }

    override fun getItemCount(): Int = items.size +
            (header?.let { 1 } ?: 0) +
            (onLoadMoreListener?.let { 1 } ?: 0) +
            (footer?.let { 1 } ?: 0)

    override fun getItemId(position: Int): Long {
        if (header != null && position == 0) return Long.MIN_VALUE

        val adjustedPosition = header?.let { position - 1 } ?: position
        if (adjustedPosition in itemStableIds.indices) {
            return itemStableIds[adjustedPosition]
        }

        val loadMorePosition = itemCount - 1 - (if (footer != null) 1 else 0)
        if (onLoadMoreListener != null && position == loadMorePosition) {
            return Long.MIN_VALUE + 1
        }

        if (footer != null && position == itemCount - 1) {
            return Long.MIN_VALUE + 2
        }

        return RecyclerView.NO_ID
    }

    override fun getItemViewType(position: Int): Int {
        if (header != null && position == 0) {
            return Type.HEADER.ordinal
        }

        val adjustedPosition = header?.let { position - 1 } ?: position
        if (adjustedPosition in items.indices) {
            return runCatching { items[adjustedPosition].itemType.ordinal }
                .getOrDefault(Type.LOADING_ITEM.ordinal)
        }

        val loadMorePosition = itemCount - 1 - (if (footer != null) 1 else 0)
        if (onLoadMoreListener != null && position == loadMorePosition) {
            return Type.LOADING_ITEM.ordinal
        }

        if (footer != null && position == itemCount - 1) {
            return Type.FOOTER.ordinal
        }

        return Type.LOADING_ITEM.ordinal
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is CategoryViewHolder) {
            holder.clearSwiper()
        }
        // Trailer tab item removed / scrolled off — stop YouTube WebView audio.
        (holder.itemView.getTag(R.id.detail_trailer_player_tag)
            as? com.betterstreamflix.app.ui.DetailTrailerMobilePlayer)
            ?.release()
        super.onViewRecycled(holder)

        val state = when (holder) {
            is CategoryViewHolder -> holder.childRecyclerView?.layoutManager?.onSaveInstanceState()
            is MovieViewHolder -> holder.childRecyclerView?.layoutManager?.onSaveInstanceState()
            is TvShowViewHolder -> holder.childRecyclerView?.layoutManager?.onSaveInstanceState()
            else -> null
        }

        if (state != null) {
            states[holder.layoutPosition] = state
        } else {
            states.remove(holder.layoutPosition)
        }
    }

    /** Pause featured auto-advance without tearing down the adapter (Home under detail stack). */
    fun pauseCategorySwipers(recyclerView: RecyclerView) {
        for (i in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i))
            if (holder is CategoryViewHolder) holder.pauseSwiper()
        }
    }

    /** Resume featured auto-advance after returning to Home. */
    fun resumeCategorySwipers(recyclerView: RecyclerView) {
        for (i in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i))
            if (holder is CategoryViewHolder) holder.resumeSwiper()
        }
    }

    fun onSaveInstanceState(recyclerView: RecyclerView) {
        for (position in items.indices) {
            val holder = recyclerView.findViewHolderForAdapterPosition(position) ?: continue

            val state = when (holder) {
                is CategoryViewHolder -> holder.childRecyclerView?.layoutManager?.onSaveInstanceState()
                is MovieViewHolder -> holder.childRecyclerView?.layoutManager?.onSaveInstanceState()
                is TvShowViewHolder -> holder.childRecyclerView?.layoutManager?.onSaveInstanceState()
                else -> null
            }

            if (state != null) {
                states[position] = state
            } else {
                states.remove(position)
            }
        }
    }


    fun submitList(list: List<Item>) {
        val oldItems = items.toList()
        val newItemCount = list.size

        if (oldItems.isNotEmpty() &&
            oldItems.size <= newItemCount &&
            oldItems == list.subList(0, oldItems.size)
        ) {
            val appendedItems = list.subList(oldItems.size, newItemCount)
            if (appendedItems.isEmpty()) {
                return
            }

            val appendedIdentityState = appendedItems.buildIdentityState(itemIdentityCounts)

            items.addAll(appendedItems)
            itemIdentities = itemIdentities + appendedIdentityState.identities
            itemIdentityCounts = appendedIdentityState.counts
            itemStableIds = itemStableIds + appendedIdentityState.stableIds

            notifyItemRangeInserted(
                oldItems.size + (header?.let { 1 } ?: 0),
                appendedItems.size
            )
            return
        }

        val oldIdentities = itemIdentities
        val newIdentityState = list.buildIdentityState()
        val newIdentities = newIdentityState.identities

        val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size

            override fun getNewListSize() = list.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldItems[oldItemPosition]
                val newItem = list[newItemPosition]
                return oldIdentities.getOrNull(oldItemPosition) == newIdentities.getOrNull(newItemPosition) &&
                        oldItem::class == newItem::class
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldItems[oldItemPosition]
                val newItem = list[newItemPosition]
                return oldItem == newItem
            }
        })

        val newStates = mutableMapOf<Int, Parcelable?>()
        if (items.size < list.size) {
            for (newItemPosition in list.indices.reversed()) {
                val oldItemPosition = result.convertNewPositionToOld(newItemPosition)
                    .takeIf { it != -1 } ?: continue

                states[oldItemPosition]?.let { newStates[newItemPosition] = it }
            }
        } else if (items.size > list.size) {
            for (oldItemPosition in items.indices) {
                val newItemPosition = result.convertOldPositionToNew(oldItemPosition)
                    .takeIf { it != -1 } ?: continue

                states[oldItemPosition]?.let { newStates[newItemPosition] = it }
            }
        } else {
            for (index in list.indices) {
                states[index]?.let { newStates[index] = it }
            }
        }

        states.clear()
        states.putAll(newStates)

        items.clear()
        items.addAll(list)
        itemIdentities = newIdentities
        itemIdentityCounts = newIdentityState.counts
        itemStableIds = newIdentityState.stableIds
        result.dispatchUpdatesTo(this)
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition !in items.indices || toPosition !in items.indices || fromPosition == toPosition) return

        java.util.Collections.swap(items, fromPosition, toPosition)

        itemIdentities = itemIdentities.toMutableList().also {
            java.util.Collections.swap(it, fromPosition, toPosition)
        }
        val stableId = itemStableIds[fromPosition]
        itemStableIds[fromPosition] = itemStableIds[toPosition]
        itemStableIds[toPosition] = stableId

        val fromState = states.remove(fromPosition)
        val toState = states.remove(toPosition)
        if (fromState != null) states[toPosition] = fromState
        if (toState != null) states[fromPosition] = toState

        notifyItemMoved(fromPosition, toPosition)
    }

    fun replaceItemOrder(newItems: List<Item>) {
        if (newItems.size != items.size) return
        newItems.forEachIndexed { targetIndex, desiredItem ->
            var currentIndex = items.indexOfFirst { it === desiredItem }
            if (currentIndex < 0) return
            while (currentIndex > targetIndex) {
                moveItem(currentIndex, currentIndex - 1)
                currentIndex--
            }
            while (currentIndex < targetIndex) {
                moveItem(currentIndex, currentIndex + 1)
                currentIndex++
            }
        }
    }


    fun <T : ViewBinding> setHeader(
        binding: (parent: ViewGroup) -> T,
        bind: ((binding: T) -> Unit)? = null,
    ) {
        @Suppress("UNCHECKED_CAST")
        this.header = Header(
            binding = binding,
            bind = bind as ((ViewBinding) -> Unit)?,
        )
    }

    fun setOnLoadMoreListener(onLoadMoreListener: (() -> Unit)?) {
        if (this.onLoadMoreListener != null && onLoadMoreListener == null) {
            this.onLoadMoreListener = null
            notifyItemRemoved(items.size)
        } else {
            this.onLoadMoreListener = onLoadMoreListener
        }
    }

    fun <T : ViewBinding> setFooter(
        binding: (parent: ViewGroup) -> T,
        bind: ((binding: T) -> Unit)? = null,
    ) {
        @Suppress("UNCHECKED_CAST")
        this.footer = Footer(
            binding = binding,
            bind = bind as ((ViewBinding) -> Unit)?,
        )
    }


    private class HeaderViewHolder(
        val binding: ViewBinding
    ) : RecyclerView.ViewHolder(
        binding.root
    )

    private class FavoriteSectionHeaderViewHolder(
        itemView: View,
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(header: FavoriteSectionHeader) {
            itemView.findViewById<TextView>(R.id.tv_favorite_section_title)?.text = header.title
            val rule = itemView.findViewById<View>(R.id.v_favorite_section_rule)
            itemView.setBackgroundResource(R.drawable.bg_cinema_glass_card)
            if (itemView.getTag(R.id.exp_enter_animated_tag) != true) {
                itemView.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.revealHeader(
                    itemView.findViewById(R.id.tv_favorite_section_title),
                    rule,
                )
                ExpMotion.pulseAccentRule(rule)
            }
        }
    }

    private class SupportBannerViewHolder(
        private val root: android.view.View,
        private val cta: android.view.View,
        private val dismiss: android.view.View,
    ) : RecyclerView.ViewHolder(root) {
        constructor(binding: ItemSupportBannerMobileBinding) : this(
            binding.root,
            binding.btnSupportBannerCta,
            binding.btnSupportBannerDismiss,
        )
        constructor(binding: ItemSupportBannerTvBinding) : this(
            binding.root,
            binding.btnSupportBannerCta,
            binding.btnSupportBannerDismiss,
        )

        fun bind(onClick: (() -> Unit)?, onDismiss: (() -> Unit)?) {
            val title = root.context.getString(R.string.support_banner_title)
            val subtitle = root.context.getString(R.string.support_banner_subtitle)
            root.contentDescription = "$title. $subtitle"
            cta.contentDescription = root.context.getString(R.string.support_banner_cta)
            val open = android.view.View.OnClickListener {
                ExpMotion.hapticTap(it)
                onClick?.invoke()
            }
            root.setOnClickListener(open)
            cta.setOnClickListener(open)
            dismiss.setOnClickListener {
                ExpMotion.hapticTap(it)
                onDismiss?.invoke()
            }
            root.findViewById<View>(R.id.btn_support_banner_never)?.let { never ->
                root.isFocusable = false
                root.isFocusableInTouchMode = false
                (root as? android.view.ViewGroup)?.descendantFocusability =
                    android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
                never.setOnClickListener {
                    ExpMotion.hapticTap(it)
                    com.betterstreamflix.app.support.SupportPromptPolicy.neverShowHomeCard()
                    onDismiss?.invoke()
                }
                SupportUiBinder.applyFocusScale(never)
            }
            SupportUiBinder.applyFocusScale(cta)
            SupportUiBinder.applyFocusScale(dismiss)
            SupportUiBinder.applyFocusScale(root)
            root.setBackgroundResource(R.drawable.bg_cinema_glass_card)
            with(com.betterstreamflix.app.utils.ExpPressEffects) {
                root.applyExpPress()
                cta.applyExpPress()
                dismiss.applyExpPress()
            }
            cta.setBackgroundResource(R.drawable.bg_button_mobile)
            dismiss.setBackgroundResource(R.drawable.bg_detail_icon_chip)
            root.findViewById<android.widget.ImageView>(R.id.iv_support_banner_icon)?.let { icon ->
                val primary = com.google.android.material.color.MaterialColors.getColor(
                    icon,
                    androidx.appcompat.R.attr.colorPrimary,
                    icon.context.getColor(R.color.m3_primary),
                )
                icon.imageTintList = android.content.res.ColorStateList.valueOf(primary)
            }
            if (root.getTag(R.id.exp_enter_animated_tag) != true) {
                root.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.revealHeader(
                    root.findViewById(R.id.tv_support_banner_title),
                    root.findViewById(R.id.tv_support_banner_subtitle),
                )
                root.findViewById<View>(R.id.iv_support_banner_icon)?.let { ExpMotion.popIn(it) }
                ExpMotion.popIn(cta)
                ExpMotion.popIn(dismiss)
            }
        }
    }

    private class WatchlistDigestViewHolder(
        private val root: android.view.View,
        private val cta: android.view.View,
        private val dismiss: android.view.View,
        private val titleView: android.widget.TextView?,
        private val subtitleView: android.widget.TextView?,
    ) : RecyclerView.ViewHolder(root) {
        constructor(binding: ItemWatchlistDigestMobileBinding) : this(
            binding.root,
            binding.btnWatchlistDigestCta,
            binding.btnWatchlistDigestDismiss,
            binding.tvWatchlistDigestTitle,
            binding.tvWatchlistDigestSubtitle,
        )
        constructor(binding: ItemWatchlistDigestTvBinding) : this(
            binding.root,
            binding.btnWatchlistDigestCta,
            binding.btnWatchlistDigestDismiss,
            binding.tvWatchlistDigestTitle,
            binding.tvWatchlistDigestSubtitle,
        )

        fun bind(
            item: WatchlistDigestBannerItem,
            onClick: ((WatchlistDigestBannerItem) -> Unit)?,
            onDismiss: ((WatchlistDigestBannerItem) -> Unit)?,
        ) {
            val bannerH = com.betterstreamflix.app.ui.LeanbackDensityPolicy.dpToPx(
                root,
                com.betterstreamflix.app.ui.LeanbackDensityPolicy.supportBannerHeightDp(),
            )
            root.minimumHeight = bannerH
            val count = item.episodeCount.coerceAtLeast(1)
            val title = root.context.getString(R.string.watchlist_digest_banner_title)
            val subtitle = root.context.getString(R.string.watchlist_digest_banner_subtitle, count)
            titleView?.text = title
            subtitleView?.text = subtitle
            root.contentDescription = "$title. $subtitle"
            cta.contentDescription = root.context.getString(R.string.watchlist_digest_banner_cta)
            val open = android.view.View.OnClickListener {
                ExpMotion.hapticTap(it)
                onClick?.invoke(item)
            }
            root.setOnClickListener(open)
            cta.setOnClickListener(open)
            dismiss.setOnClickListener {
                ExpMotion.hapticTap(it)
                onDismiss?.invoke(item)
            }
            root.findViewById<View>(R.id.btn_watchlist_digest_never)?.let { never ->
                root.isFocusable = false
                root.isFocusableInTouchMode = false
                (root as? android.view.ViewGroup)?.descendantFocusability =
                    android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
                never.setOnClickListener {
                    ExpMotion.hapticTap(it)
                    com.betterstreamflix.app.watchlist.WatchlistDigestPolicy.neverShow()
                    onDismiss?.invoke(item)
                }
                SupportUiBinder.applyFocusScale(never)
            }
            SupportUiBinder.applyFocusScale(cta)
            SupportUiBinder.applyFocusScale(dismiss)
            SupportUiBinder.applyFocusScale(root)
            root.setBackgroundResource(R.drawable.bg_cinema_glass_card)
            with(com.betterstreamflix.app.utils.ExpPressEffects) {
                root.applyExpPress()
                cta.applyExpPress()
                dismiss.applyExpPress()
            }
            cta.setBackgroundResource(R.drawable.bg_button_mobile)
            dismiss.setBackgroundResource(R.drawable.bg_detail_icon_chip)
            root.findViewById<android.widget.ImageView>(R.id.iv_watchlist_digest_icon)?.let { icon ->
                val primary = com.google.android.material.color.MaterialColors.getColor(
                    icon,
                    androidx.appcompat.R.attr.colorPrimary,
                    icon.context.getColor(R.color.m3_primary),
                )
                icon.imageTintList = android.content.res.ColorStateList.valueOf(primary)
            }
            if (root.getTag(R.id.exp_enter_animated_tag) != true) {
                root.setTag(R.id.exp_enter_animated_tag, true)
                ExpMotion.revealHeader(titleView, subtitleView)
                root.findViewById<View>(R.id.iv_watchlist_digest_icon)?.let { ExpMotion.popIn(it) }
                ExpMotion.popIn(cta)
                ExpMotion.popIn(dismiss)
            }
        }
    }

    private data class Header<T : ViewBinding>(
        val binding: (parent: ViewGroup) -> T,
        val bind: ((binding: T) -> Unit)? = null,
    )

    private class LoadingViewHolder(
        binding: ViewBinding
    ) : RecyclerView.ViewHolder(
        binding.root
    ) {
        fun bind() {
            val shimmer = itemView.findViewById<View>(R.id.sh_load_more)
            val spinner = itemView.findViewById<View>(R.id.pb_load_more_is_loading)
                ?: itemView.findViewById(R.id.pb_is_loading)
            if (shimmer != null) {
                shimmer.visibility = View.VISIBLE
                spinner?.visibility = View.GONE
                (shimmer as? com.facebook.shimmer.ShimmerFrameLayout)?.startShimmer()
                if (itemView.getTag(R.id.exp_enter_animated_tag) != true) {
                    itemView.setTag(R.id.exp_enter_animated_tag, true)
                    ExpMotion.fadeInAndShow(shimmer)
                }
            } else if (itemView.getTag(R.id.exp_enter_animated_tag) != true) {
                itemView.setTag(R.id.exp_enter_animated_tag, true)
                spinner?.let { ExpMotion.popIn(it) } ?: ExpMotion.popIn(itemView)
            }
        }
    }

    private class FooterViewHolder(
        val binding: ViewBinding
    ) : RecyclerView.ViewHolder(
        binding.root
    )

    private data class Footer<T : ViewBinding>(
        val binding: (parent: ViewGroup) -> T,
        val bind: ((binding: T) -> Unit)? = null,
    )

    private data class IdentityState(
        val identities: List<String>,
        val counts: MutableMap<String, Int>,
        val stableIds: LongArray,
    )

    private fun List<Item>.buildIdentityState(
        startingCounts: Map<String, Int> = emptyMap()
    ): IdentityState {
        val occurrenceCounts = startingCounts.toMutableMap()
        val identities = ArrayList<String>(size)
        val stableIds = LongArray(size)

        forEachIndexed { index, item ->
            val baseKey = item.baseIdentityKey()
            // Never touch uninitialized lateinit itemType (Featured loop clones, Room merges).
            val typeOrdinal = runCatching { item.itemType.ordinal }.getOrDefault(-1)
            val key = "$typeOrdinal:$baseKey"
            val occurrenceIndex = occurrenceCounts.getOrDefault(key, 0)
            occurrenceCounts[key] = occurrenceIndex + 1

            val identity = "$key:$occurrenceIndex"
            identities.add(identity)
            stableIds[index] = identity.fold(1125899906842597L) { acc, char ->
                31L * acc + char.code
            }
        }

        return IdentityState(
            identities = identities,
            counts = occurrenceCounts,
            stableIds = stableIds,
        )
    }

    private fun Item.baseIdentityKey(): String = when (this) {
        is Category -> "category:${identityKey ?: name}"
        is Episode -> "episode:${id}"
        is FavoriteSectionHeader -> "favorite-header:${section.key}"
        is SupportBannerItem -> "support-banner:${id}"
        is WatchlistDigestBannerItem -> "watchlist-digest:${id}:${episodeCount}"
        is ProviderOutageBannerItem -> "provider-outage:${id}:${level}:${providerNames.joinToString()}"
        is Genre -> "genre:${id}"
        is Movie -> "movie:${id}"
        is People -> "people:${id}"
        is Provider -> "provider:${name}"
        is Season -> "season:${id}"
        is Trailer -> "trailer:${url}"
        is TvShow -> "tvshow:${id}"
        else -> "item:${runCatching { itemType.name }.getOrDefault(javaClass.simpleName)}"
    }
}

/**
 * Reuse an existing [AppAdapter] on a child RecyclerView / HorizontalGridView so
 * parent rebinds (detail DB merges, trailer remote fill) do not restart Glide loads
 * or drop horizontal scroll/focus position.
 */
fun RecyclerView.submitAppList(items: List<AppAdapter.Item>) {
    val child = (adapter as? AppAdapter) ?: AppAdapter().also { adapter = it }
    child.submitList(items)
}
