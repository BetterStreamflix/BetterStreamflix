package com.dskja.betterstreamflix.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.adapters.AppAdapter
import com.dskja.betterstreamflix.adapters.submitAppList
import com.dskja.betterstreamflix.databinding.ContentDetailTrailerTvBinding
import com.dskja.betterstreamflix.models.Trailer
import com.dskja.betterstreamflix.models.TrailerCatalog
import com.dskja.betterstreamflix.models.TrailerEntry
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.TmdbUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Leanback detail trailer row: hide-until-ready, stable adapter, fixed row height,
 * Cinema Ink header polish, empty state. Play/focus/SmartTube policy lives in
 * [TrailerPlaybackController] + [com.dskja.betterstreamflix.adapters.viewholders.TrailerViewHolder].
 */
object DetailTrailerTvController {

    /** Thumb 16:9 of 240dp + title/meta ≈ 210dp. Fixed height avoids Leanback WRAP_CONTENT focus bugs. */
    private const val ROW_HEIGHT_DP = 210

    fun bind(
        binding: ContentDetailTrailerTvBinding,
        seedUrl: String?,
        title: String,
        trailerLabel: String,
        tmdbId: String?,
        isTv: Boolean,
        year: Int?,
        imdbId: String?,
        onTrailerSeeded: ((String) -> Unit)? = null,
    ) {
        binding.root.tag = "detail_section_trailer"
        polishHeader(binding)

        val density = binding.root.resources.displayMetrics.density
        val rowHeightPx = (ROW_HEIGHT_DP * density).toInt()
        val emptyView = binding.root.findViewById<TextView>(R.id.tv_detail_trailer_empty)

        fun bindRows(trailers: List<TrailerEntry>, lookupFinished: Boolean) {
            if (trailers.isEmpty()) {
                if (lookupFinished) {
                    // Section was included for lookup — show empty copy instead of silent GONE.
                    binding.root.visibility = View.VISIBLE
                    binding.hgvDetailTrailers.visibility = View.GONE
                    emptyView?.visibility = View.VISIBLE
                } else {
                    binding.root.visibility = View.GONE
                    emptyView?.visibility = View.GONE
                }
                return
            }
            emptyView?.visibility = View.GONE
            binding.root.visibility = View.VISIBLE
            binding.hgvDetailTrailers.visibility = View.VISIBLE
            binding.hgvDetailTrailers.apply {
                setRowHeight(rowHeightPx)
                setItemSpacing((24 * density).toInt())
                clipChildren = false
                clipToPadding = false
                isFocusable = true
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                submitAppList(
                    trailers.take(TrailerCatalog.MAX_LIST_ITEMS).map { entry ->
                        Trailer(
                            title = entry.title,
                            url = entry.url,
                            type = entry.type,
                            official = entry.official,
                        ).also {
                            it.itemType = AppAdapter.Type.TRAILER_TV_ITEM
                        }
                    },
                )
            }
            if (ExperimentalMobileDesign.enabled()) {
                ExpMotion.staggerFirstFill(binding.hgvDetailTrailers)
            }
        }

        val lookupKey = listOf(
            seedUrl.orEmpty(),
            title,
            tmdbId.orEmpty(),
            isTv.toString(),
            year?.toString().orEmpty(),
            imdbId.orEmpty(),
        ).joinToString("\u0001")
        val previousKey = binding.root.getTag(R.id.detail_trailer_lookup_key_tag) as? String
        val existingJob = binding.root.getTag(R.id.detail_trailer_player_tag) as? Job
        val rowsReady = (binding.hgvDetailTrailers.adapter?.itemCount ?: 0) > 0 ||
            emptyView?.visibility == View.VISIBLE
        if (previousKey == lookupKey && (existingJob?.isActive == true || rowsReady)) {
            return
        }
        binding.root.setTag(R.id.detail_trailer_lookup_key_tag, lookupKey)
        existingJob?.cancel()

        val seed = seedUrl?.takeIf { it.isNotBlank() }?.let { url ->
            listOf(TrailerEntry.fromSeed("$title $trailerLabel", url, trailerLabel))
        }.orEmpty()

        // Hide-until-ready: empty seed stays GONE until TMDb returns rows.
        if (seed.isNotEmpty()) bindRows(seed, lookupFinished = false) else {
            binding.root.visibility = View.GONE
            emptyView?.visibility = View.GONE
        }

        val owner = binding.root.findViewTreeLifecycleOwner() ?: return
        val job = owner.lifecycleScope.launch {
            val remote = withContext(Dispatchers.IO) {
                TmdbUtils.listYoutubeTrailers(
                    tmdbId = tmdbId,
                    isTv = isTv,
                    title = title,
                    year = year,
                    imdbId = imdbId,
                )
            }
            val trailers = TrailerCatalog.mergeTrailers(seed, remote)
            if (trailers.isNotEmpty() && seedUrl.isNullOrBlank()) {
                TrailerCatalog.preferredPlayableUrl(trailers)?.let { onTrailerSeeded?.invoke(it) }
            }
            bindRows(trailers, lookupFinished = true)
        }
        binding.root.setTag(R.id.detail_trailer_player_tag, job)
    }

    private fun polishHeader(binding: ContentDetailTrailerTvBinding) {
        if (!ExperimentalMobileDesign.enabled()) return
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
}
