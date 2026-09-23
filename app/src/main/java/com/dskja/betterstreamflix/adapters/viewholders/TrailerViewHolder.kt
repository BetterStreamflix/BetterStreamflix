package com.dskja.betterstreamflix.adapters.viewholders

import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.dskja.betterstreamflix.databinding.ItemTrailerTvBinding
import com.dskja.betterstreamflix.models.Trailer
import com.dskja.betterstreamflix.ui.TrailerPlaybackController
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.TvFocusZoom
import com.dskja.betterstreamflix.utils.getCurrentFragment
import com.dskja.betterstreamflix.utils.toActivity

class TrailerViewHolder(
    private val _binding: ViewBinding,
) : RecyclerView.ViewHolder(_binding.root) {

    private lateinit var trailer: Trailer

    fun bind(trailer: Trailer) {
        this.trailer = trailer
        when (_binding) {
            is ItemTrailerTvBinding -> displayTvItem(_binding)
        }
    }

    private fun displayTvItem(binding: ItemTrailerTvBinding) {
        binding.tvTrailerTitle.text = trailer.title
        binding.tvTrailerMeta.text = trailer.type

        val ytId = TrailerPlaybackController.youtubeVideoId(trailer.url)
        if (ytId != null) {
            Glide.with(binding.ivTrailerThumb)
                .load("https://img.youtube.com/vi/$ytId/hqdefault.jpg")
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(binding.ivTrailerThumb)
        } else {
            Glide.with(binding.ivTrailerThumb).clear(binding.ivTrailerThumb)
            binding.ivTrailerThumb.setImageDrawable(null)
        }

        val play = View.OnClickListener {
            ExpMotion.hapticTap(it)
            val fragment = binding.root.context.toActivity()?.getCurrentFragment() as? Fragment
            if (fragment != null) {
                TrailerPlaybackController.play(fragment, trailer.url)
            }
        }
        binding.root.setOnClickListener(play)
        binding.ivTrailerPlay.setOnClickListener(play)
        binding.root.setOnFocusChangeListener { _, hasFocus ->
            TvFocusZoom.apply(binding.flTrailerThumb, hasFocus)
        }
    }
}
