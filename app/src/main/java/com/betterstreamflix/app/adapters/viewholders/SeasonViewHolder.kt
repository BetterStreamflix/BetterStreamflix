package com.betterstreamflix.app.adapters.viewholders

import androidx.core.view.isVisible
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.betterstreamflix.app.R
import com.betterstreamflix.app.adapters.AppAdapter
import com.betterstreamflix.app.utils.TvFocusZoom
import com.betterstreamflix.app.utils.ExpMotion
import com.betterstreamflix.app.utils.ExpPressEffects.applyExpPress
import com.betterstreamflix.app.databinding.ItemSeasonMobileBinding
import com.betterstreamflix.app.databinding.ItemSeasonTvBinding
import com.betterstreamflix.app.fragments.tv_show.TvShowTvFragmentDirections
import com.betterstreamflix.app.models.Season
import com.betterstreamflix.app.ui.DetailTab

class SeasonViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    init {
        itemView.applyExpPress()
    }

    private lateinit var season: Season

    fun bind(season: Season) {
        this.season = season

        when (_binding) {
            is ItemSeasonMobileBinding -> displayMobileItem(_binding)
            is ItemSeasonTvBinding -> displayTvItem(_binding)
        }
    }

    private fun displayMobileItem(binding: ItemSeasonMobileBinding) {
        // Mobile detail prefers the in-page Episodes tab. Keep SeasonMobileFragment in the
        // nav graph for deep links / leftovers, but do not navigate there from season chips.
        binding.root.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                (bindingAdapter as? AppAdapter)?.onDetailTabSelectedListener?.invoke(DetailTab.EPISODES)
            }
        }

        binding.ivSeasonPoster.apply {
            clipToOutline = true
            Glide.with(context)
                .load(season.poster)
                .error(R.drawable.glide_fallback_cover)
                .fallback(R.drawable.glide_fallback_cover)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
            if (getTag(R.id.exp_ken_burns_animator) == null
            ) {
                ExpMotion.kenBurns(this)
            }
        }
        val watched = season.isFullyWatched()
        val wasWatched = binding.ivSeasonWatchedRibbon.isVisible
        if (wasWatched && !watched) {
            ExpMotion.fadeOutAndHide(binding.ivSeasonWatchedRibbon)
            binding.ivSeasonWatchedRibbon.background = null
        } else {
            binding.ivSeasonWatchedRibbon.isVisible = watched
            if (watched) {
                binding.ivSeasonWatchedRibbon.setBackgroundResource(
                    R.drawable.bg_detail_icon_chip,
                )
                val pad = (4 * context.resources.displayMetrics.density).toInt()
                binding.ivSeasonWatchedRibbon.setPadding(pad, pad, pad, pad)
                if (!wasWatched) ExpMotion.popIn(binding.ivSeasonWatchedRibbon)
            }
        }

        binding.tvSeasonTitle.text = season.displayTitle()
        if (binding.root.getTag(R.id.exp_enter_animated_tag) != true
        ) {
            binding.root.setTag(R.id.exp_enter_animated_tag, true)
            binding.root.setBackgroundResource(R.drawable.bg_cinema_glass_card)
            ExpMotion.revealHeader(binding.tvSeasonTitle)
        }
    }

    private fun displayTvItem(binding: ItemSeasonTvBinding) {
        binding.root.apply {
            setOnClickListener {
                ExpMotion.hapticTap(it)
                findNavController().navigate(
                    TvShowTvFragmentDirections.actionTvShowToSeason(
                        tvShowId = season.tvShow?.id ?: "",
                        tvShowTitle = season.tvShow?.title ?: "",
                        tvShowPoster = season.tvShow?.poster,
                        tvShowBanner = season.tvShow?.banner,
                        seasonId = season.id,
                        seasonNumber = season.number,
                        seasonTitle = season.displayTitle(),
                    )
                )
            }
            setOnFocusChangeListener { _, hasFocus ->
                TvFocusZoom.apply(binding.ivSeasonPoster, hasFocus)
            }
        }

        binding.ivSeasonPoster.apply {
            clipToOutline = true
            Glide.with(context)
                .load(season.poster)
                .error(R.drawable.glide_fallback_cover)
                .fallback(R.drawable.glide_fallback_cover)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
        }
        val watched = season.isFullyWatched()
        binding.ivSeasonWatchedRibbon.isVisible = watched

        binding.tvSeasonTitle.text = season.displayTitle()
    }

    private fun Season.displayTitle(): String {
        return title ?: context.getString(R.string.season_number, number)
    }

    private fun Season.isFullyWatched(): Boolean {
        return episodes.isNotEmpty() && episodes.all { it.isWatched }
    }
}
