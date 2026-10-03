package com.dskja.betterstreamflix.adapters.viewholders

import android.graphics.drawable.Drawable
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.dskja.betterstreamflix.databinding.ItemTrailerTvBinding
import com.dskja.betterstreamflix.models.Trailer
import com.dskja.betterstreamflix.models.TrailerCatalog
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
        val official = trailer.official || TrailerCatalog.isOfficialTitle(trailer.title)
        binding.tvTrailerOfficialBadge.visibility = if (official) View.VISIBLE else View.GONE

        // Hide play chrome until the thumb is ready (parity with mobile hide-until-ready).
        binding.ivTrailerPlay.visibility = View.INVISIBLE
        binding.ivTrailerPlay.alpha = 0f

        val thumbQuality = PreferenceManager.getDefaultSharedPreferences(binding.root.context)
            .getString(TrailerCatalog.KEY_TRAILER_THUMB_QUALITY, TrailerCatalog.THUMB_QUALITY_DEFAULT)
            ?: TrailerCatalog.THUMB_QUALITY_DEFAULT
        val ytId = TrailerPlaybackController.youtubeVideoId(trailer.url)
        if (ytId != null) {
            Glide.with(binding.ivTrailerThumb)
                .load(TrailerCatalog.youtubeThumbUrl(ytId, thumbQuality))
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade(160))
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean,
                    ): Boolean {
                        revealPlay(binding)
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean,
                    ): Boolean {
                        revealPlay(binding)
                        return false
                    }
                })
                .into(binding.ivTrailerThumb)
        } else {
            Glide.with(binding.ivTrailerThumb).clear(binding.ivTrailerThumb)
            binding.ivTrailerThumb.setImageDrawable(null)
            revealPlay(binding)
        }

        val play = View.OnClickListener {
            ExpMotion.hapticTap(it)
            val fragment = resolveHostFragment(binding.root)
            if (fragment != null) {
                TrailerPlaybackController.play(fragment, trailer.url)
            } else {
                val activity = binding.root.context.toActivity()
                TrailerPlaybackController.play(
                    binding.root.context,
                    activity,
                    trailer.url,
                    activity?.supportFragmentManager,
                )
            }
        }
        binding.root.setOnClickListener(play)
        binding.ivTrailerPlay.setOnClickListener(play)
        binding.root.isFocusable = true
        binding.root.isClickable = true
        binding.root.setOnFocusChangeListener { _, hasFocus ->
            TvFocusZoom.apply(binding.flTrailerThumb, hasFocus)
            binding.ivTrailerPlay.animate()
                .scaleX(if (hasFocus) 1.08f else 1f)
                .scaleY(if (hasFocus) 1.08f else 1f)
                .setDuration(140L)
                .start()
        }
    }

    private fun revealPlay(binding: ItemTrailerTvBinding) {
        if (binding.ivTrailerPlay.visibility == View.VISIBLE && binding.ivTrailerPlay.alpha >= 0.99f) {
            return
        }
        binding.ivTrailerPlay.visibility = View.VISIBLE
        binding.ivTrailerPlay.animate().alpha(1f).setDuration(180L).start()
    }

    private fun resolveHostFragment(view: View): Fragment? {
        runCatching { FragmentManager.findFragment<Fragment>(view) }.getOrNull()?.let { return it }
        return view.context.toActivity()?.getCurrentFragment() as? Fragment
    }
}
