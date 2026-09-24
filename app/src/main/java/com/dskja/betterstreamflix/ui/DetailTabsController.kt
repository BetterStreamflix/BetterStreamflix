package com.dskja.betterstreamflix.ui

import android.view.View
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.databinding.ContentDetailTabsMobileBinding
import com.dskja.betterstreamflix.utils.ExpMotion

/**
 * Shared mobile detail tab chrome for Movie + TV heroes.
 */
object DetailTabsController {

    fun bind(
        binding: ContentDetailTabsMobileBinding,
        selected: DetailTab,
        showEpisodes: Boolean,
        showSimilar: Boolean = true,
        onSelect: (DetailTab) -> Unit,
    ) {
        binding.tabDetailEpisodes.visibility = if (showEpisodes) View.VISIBLE else View.GONE
        binding.tabDetailSimilar.visibility = if (showSimilar) View.VISIBLE else View.GONE

        fun paint(active: View) {
            listOf(
                binding.tabDetailEpisodes,
                binding.tabDetailSimilar,
                binding.tabDetailTrailer,
                binding.tabDetailAbout,
            ).forEach { tab ->
                if (tab.visibility != View.VISIBLE) return@forEach
                val on = tab === active
                tab.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0x8AFFFFFF.toInt())
                tab.setBackgroundResource(if (on) R.drawable.bg_detail_tab_underline else 0)
            }
        }

        val active = when (selected) {
            DetailTab.EPISODES -> binding.tabDetailEpisodes
            DetailTab.SIMILAR -> binding.tabDetailSimilar
            DetailTab.TRAILER -> binding.tabDetailTrailer
            DetailTab.ABOUT -> binding.tabDetailAbout
        }.let { candidate ->
            when {
                candidate.visibility == View.VISIBLE -> candidate
                binding.tabDetailSimilar.visibility == View.VISIBLE -> binding.tabDetailSimilar
                else -> binding.tabDetailAbout
            }
        }
        paint(active)

        fun wire(tab: View, value: DetailTab) {
            tab.setOnClickListener {
                ExpMotion.hapticTap(it)
                paint(tab)
                onSelect(value)
            }
        }
        wire(binding.tabDetailEpisodes, DetailTab.EPISODES)
        wire(binding.tabDetailSimilar, DetailTab.SIMILAR)
        wire(binding.tabDetailTrailer, DetailTab.TRAILER)
        wire(binding.tabDetailAbout, DetailTab.ABOUT)
    }
}
