package com.dskja.betterstreamflix.adapters.viewholders

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.animation.AnimationUtils
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.TvFocusZoom
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.databinding.ItemGenreGridMobileBinding
import com.dskja.betterstreamflix.databinding.ItemGenreGridTvBinding
import com.dskja.betterstreamflix.models.Genre

class GenreViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    init {
        if (ExperimentalMobileDesign.enabled()) {
            itemView.applyExpPress()
        }
    }

    private lateinit var genre: Genre

    fun bind(genre: Genre) {
        this.genre = genre

        when (_binding) {
            is ItemGenreGridMobileBinding -> displayGridMobileItem(_binding)
            is ItemGenreGridTvBinding -> displayGridTvItem(_binding)
        }
    }

    private fun displayGridMobileItem(binding: ItemGenreGridMobileBinding) {
        binding.root.apply {
            val colors = context.resources.getIntArray(R.array.genres)
            val tile = background as? GradientDrawable
            if (ExperimentalMobileDesign.enabled()) {
                val glassFill = if (ExperimentalMobileDesign.reducedGlass()) {
                    com.google.android.material.color.MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorSurfaceContainer,
                    )
                } else {
                    context.getColor(R.color.exp_nav_glass)
                }
                val primary = com.google.android.material.color.MaterialColors.getColor(
                    this,
                    androidx.appcompat.R.attr.colorPrimary,
                )
                val glassStroke = (primary and 0x00FFFFFF) or 0x66000000
                tile?.setColor(glassFill)
                tile?.setStroke(
                    (1.5f * resources.displayMetrics.density).toInt().coerceAtLeast(1),
                    glassStroke,
                )
                if (getTag(R.id.exp_enter_animated_tag) != true) {
                    setTag(R.id.exp_enter_animated_tag, true)
                    postDelayed({
                        ExpMotion.popIn(this)
                        ExpMotion.revealHeader(binding.tvGenreName)
                    }, 24L * bindingAdapterPosition.coerceAtMost(16))
                }
            } else {
                tile?.setColor(colors[genreColorIndex(colors.size)])
            }

            setOnClickListener {
                ExpMotion.hapticTap(it)
                val args = Bundle().apply {
                    putString("id", genre.id)
                    putString("name", genre.name)
                }
                findNavController().navigate(R.id.genre, args)
            }
        }

        binding.tvGenreName.text = genre.name
    }

    private fun displayGridTvItem(binding: ItemGenreGridTvBinding) {
        binding.root.apply {
            val colors = context.resources.getIntArray(R.array.genres)
            val tile = background as? GradientDrawable
            tile?.setColor(colors[genreColorIndex(colors.size)])

            setOnClickListener {
                ExpMotion.hapticTap(it)
                val args = Bundle().apply {
                    putString("id", genre.id)
                    putString("name", genre.name)
                }
                findNavController().navigate(R.id.genre, args)
            }
            setOnFocusChangeListener { _, hasFocus ->
                TvFocusZoom.apply(itemView, hasFocus)
            }
        }

        binding.tvGenreName.text = genre.name
    }

    /** Stable color from genre id/name so recycled grid positions don't reshuffle hues. */
    private fun genreColorIndex(paletteSize: Int): Int {
        if (paletteSize <= 0) return 0
        val key = genre.id.ifBlank { genre.name }
        return (key.hashCode().toLong() and 0x7FFFFFFF).toInt() % paletteSize
    }
}
