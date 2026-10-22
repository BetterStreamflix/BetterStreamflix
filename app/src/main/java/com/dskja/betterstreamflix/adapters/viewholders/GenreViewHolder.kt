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
import com.dskja.betterstreamflix.databinding.ItemGenreGridMobileBinding
import com.dskja.betterstreamflix.databinding.ItemGenreGridTvBinding
import com.dskja.betterstreamflix.models.Genre

class GenreViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context

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
            tile?.setColor(colors[genreColorIndex(colors.size)])

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

        binding.tvGenreName.textSize =
            com.dskja.betterstreamflix.ui.LeanbackDensityPolicy.genreLabelTextSp()
        binding.tvGenreName.text = genre.name
    }

    /** Stable color from genre id/name so recycled grid positions don't reshuffle hues. */
    private fun genreColorIndex(paletteSize: Int): Int {
        if (paletteSize <= 0) return 0
        val key = genre.id.ifBlank { genre.name }
        return (key.hashCode().toLong() and 0x7FFFFFFF).toInt() % paletteSize
    }
}
