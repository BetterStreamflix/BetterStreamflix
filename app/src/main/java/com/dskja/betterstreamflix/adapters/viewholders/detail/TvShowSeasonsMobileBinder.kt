package com.dskja.betterstreamflix.adapters.viewholders.detail

import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.adapters.viewholders.TvShowViewHolder
import com.dskja.betterstreamflix.databinding.ContentTvShowSeasonsMobileBinding
import com.dskja.betterstreamflix.fragments.tv_show.TvShowMobileFragment
import com.dskja.betterstreamflix.fragments.tv_show.TvShowViewModel
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Season
import com.dskja.betterstreamflix.ui.SpacingItemDecoration
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.dp
import com.dskja.betterstreamflix.utils.getCurrentFragment
import com.dskja.betterstreamflix.utils.toActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun TvShowViewHolder.bindTvShowSeasonsMobile(binding: ContentTvShowSeasonsMobileBinding) {
    binding.root.tag = TvShowViewHolder.DETAIL_SECTION_SEASONS

    val seasons = tvShow.seasons
    if (seasons.isEmpty()) {
        binding.btnTvShowSeasonPicker.visibility = View.GONE
        binding.rvTvShowEpisodes.visibility = View.GONE
        binding.pbTvShowEpisodesLoading.visibility = View.GONE
        binding.llTvShowEpisodesEmpty.visibility = View.VISIBLE
        binding.tvTvShowEpisodesEmpty.setText(R.string.season_empty)
        binding.btnTvShowEpisodesRetry.visibility = View.GONE
        return
    }

    val seasonUi = seasonEpisodeUi()
    val selectedSeason = resolveSelectedSeason(seasons, seasonUi)
    seasonUi?.selectedSeasonId = selectedSeason.id

    binding.btnTvShowSeasonPicker.apply {
        visibility = View.VISIBLE
        text = selectedSeason.title
            ?: context.getString(R.string.season_number, selectedSeason.number)
        setOnClickListener {
            ExpMotion.hapticTap(it)
            showSeasonPicker(binding, seasons, seasonUi)
        }
        if (ExperimentalMobileDesign.enabled()) {
            applyExpPress()
        }
    }

    if (ExperimentalMobileDesign.enabled() &&
        binding.root.getTag(R.id.exp_enter_animated_tag) != true
    ) {
        binding.root.setTag(R.id.exp_enter_animated_tag, true)
        ExpMotion.popIn(binding.btnTvShowSeasonPicker)
        ExpMotion.staggerFirstFill(binding.rvTvShowEpisodes)
    }

    bindSeasonEpisodes(binding, selectedSeason, seasonUi)
}

private fun TvShowViewHolder.seasonEpisodeUi(): TvShowViewModel? {
    return (context.toActivity()?.getCurrentFragment() as? TvShowMobileFragment)?.seasonEpisodeUi()
}

private fun TvShowViewHolder.resolveSelectedSeason(
    seasons: List<Season>,
    seasonUi: TvShowViewModel?,
): Season {
    val rememberedId = seasonUi?.selectedSeasonId
    if (rememberedId != null) {
        seasons.firstOrNull { it.id == rememberedId }?.let { return it }
    }
    // Prefer the season that contains the next episode to watch, else first non-zero.
    val episodeSeasonId = tvShow.episodeToWatch?.season?.id
    if (episodeSeasonId != null) {
        seasons.firstOrNull { it.id == episodeSeasonId }?.let { return it }
    }
    return seasons.firstOrNull { it.number != 0 } ?: seasons.first()
}

private fun TvShowViewHolder.showSeasonPicker(
    binding: ContentTvShowSeasonsMobileBinding,
    seasons: List<Season>,
    seasonUi: TvShowViewModel?,
) {
    val labels = seasons.map { season ->
        season.title ?: context.getString(R.string.season_number, season.number)
    }.toTypedArray()
    val selectedIndex = seasons.indexOfFirst { it.id == seasonUi?.selectedSeasonId }
        .coerceAtLeast(0)
    val builder = if (ExperimentalMobileDesign.enabled()) {
        MaterialAlertDialogBuilder(context)
    } else {
        AlertDialog.Builder(context)
    }
    builder
        .setTitle(R.string.tv_show_seasons)
        .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
            val season = seasons.getOrNull(which) ?: return@setSingleChoiceItems
            seasonUi?.selectedSeasonId = season.id
            binding.btnTvShowSeasonPicker.text = labels[which]
            bindSeasonEpisodes(binding, season, seasonUi)
            dialog.dismiss()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun TvShowViewHolder.bindSeasonEpisodes(
    binding: ContentTvShowSeasonsMobileBinding,
    season: Season,
    seasonUi: TvShowViewModel?,
) {
    val episodes = season.episodes.sortedBy { it.number }
    val adapter = (binding.rvTvShowEpisodes.adapter as? AppAdapter) ?: AppAdapter().also {
        binding.rvTvShowEpisodes.adapter = it
        if (binding.rvTvShowEpisodes.itemDecorationCount == 0) {
            binding.rvTvShowEpisodes.addItemDecoration(SpacingItemDecoration(8.dp(context)))
        }
    }

    when {
        episodes.isNotEmpty() -> {
            seasonUi?.clearSeasonEpisodeFailure(season.id)
            binding.pbTvShowEpisodesLoading.visibility = View.GONE
            binding.llTvShowEpisodesEmpty.visibility = View.GONE
            binding.btnTvShowEpisodesRetry.visibility = View.GONE
            binding.rvTvShowEpisodes.visibility = View.VISIBLE
            adapter.submitList(
                episodes.onEach { episode ->
                    val show = episode.tvShow ?: tvShow
                    if (show.contentRating.isNullOrBlank()) {
                        show.contentRating = tvShow.contentRating
                    }
                    episode.tvShow = show
                    episode.season = episode.season ?: season
                    episode.itemType = AppAdapter.Type.EPISODE_DETAIL_MOBILE_ITEM
                },
            )
            binding.rvTvShowEpisodes.post {
                binding.rvTvShowEpisodes.requestLayout()
                (binding.rvTvShowEpisodes.parent as? View)?.requestLayout()
            }
        }
        seasonUi?.isSeasonEpisodeFailed(season.id) == true -> {
            seasonUi.endSeasonEpisodeLoad(season.id)
            binding.rvTvShowEpisodes.visibility = View.GONE
            binding.pbTvShowEpisodesLoading.visibility = View.GONE
            binding.llTvShowEpisodesEmpty.visibility = View.VISIBLE
            binding.tvTvShowEpisodesEmpty.setText(R.string.season_empty)
            binding.btnTvShowEpisodesRetry.visibility = View.VISIBLE
            binding.btnTvShowEpisodesRetry.setOnClickListener {
                ExpMotion.hapticTap(it)
                seasonUi.clearSeasonEpisodeFailure(season.id)
                binding.btnTvShowEpisodesRetry.visibility = View.GONE
                binding.llTvShowEpisodesEmpty.visibility = View.GONE
                binding.pbTvShowEpisodesLoading.visibility = View.VISIBLE
                adapter.submitList(emptyList())
                requestSeasonEpisodes(binding, season, seasonUi)
            }
            adapter.submitList(emptyList())
        }
        seasonUi?.isSeasonEpisodeLoading(season.id) == true -> {
            binding.rvTvShowEpisodes.visibility = View.GONE
            binding.llTvShowEpisodesEmpty.visibility = View.GONE
            binding.btnTvShowEpisodesRetry.visibility = View.GONE
            binding.pbTvShowEpisodesLoading.visibility = View.VISIBLE
            adapter.submitList(emptyList())
        }
        else -> {
            binding.rvTvShowEpisodes.visibility = View.GONE
            binding.llTvShowEpisodesEmpty.visibility = View.GONE
            binding.btnTvShowEpisodesRetry.visibility = View.GONE
            binding.pbTvShowEpisodesLoading.visibility = View.VISIBLE
            adapter.submitList(emptyList())
            requestSeasonEpisodes(binding, season, seasonUi)
        }
    }
}

private fun TvShowViewHolder.requestSeasonEpisodes(
    binding: ContentTvShowSeasonsMobileBinding,
    season: Season,
    seasonUi: TvShowViewModel?,
) {
    if (seasonUi != null) {
        if (!seasonUi.beginSeasonEpisodeLoad(season.id)) return
        seasonUi.clearSeasonEpisodeFailure(season.id)
        val fragment = context.toActivity()?.getCurrentFragment() as? TvShowMobileFragment
        if (fragment != null) {
            fragment.loadSeasonEpisodes(season)
            // Failure is handled via SeasonState.FailedLoading in the fragment.
            return
        }
        seasonUi.endSeasonEpisodeLoad(season.id)
    }

    // Fallback: load directly if fragment/ViewModel isn't available.
    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val provider = UserPreferences.currentProvider
                    ?: return@runCatching emptyList<Episode>()
                val episodes = provider.getEpisodesBySeason(season.id)
                val episodeMap = episodes.associateBy { it.id }
                episodes.map { it.id }.chunked(400).forEach { chunk ->
                    database.episodeDao().getByIds(chunk).forEach { db ->
                        episodeMap[db.id]?.merge(db)
                    }
                }
                episodes.forEach {
                    it.tvShow = tvShow
                    it.season = season
                }
                database.episodeDao().insertAll(episodes)
                episodes
            }
        }
        result.onSuccess { loaded ->
            season.episodes = loaded
            tvShow.seasons.firstOrNull { it.id == season.id }?.episodes = loaded
            if (seasonUi?.selectedSeasonId == season.id || seasonUi == null) {
                bindSeasonEpisodes(binding, season, seasonUi)
            }
        }.onFailure {
            seasonUi?.markSeasonEpisodeFailure(season.id)
            if (seasonUi?.selectedSeasonId == season.id || seasonUi == null) {
                bindSeasonEpisodes(binding, season, seasonUi)
            }
        }
    }
}
