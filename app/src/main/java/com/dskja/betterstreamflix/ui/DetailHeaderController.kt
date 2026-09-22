package com.dskja.betterstreamflix.ui

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.ArtworkRepair
import com.dskja.betterstreamflix.utils.ArtworkUrls
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.TmdbUtils
import com.dskja.betterstreamflix.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Overlay chrome for movie / TV detail pages.
 *
 * At the top of the page only Back + Add-to-list float over the hero.
 * After the user scrolls, a soft bar fades in with the TMDb title logo centered.
 */
object DetailHeaderController {

    private const val COLLAPSE_RANGE = 160f

    fun wireBack(root: View) {
        val back = root.findViewById<View>(R.id.iv_detail_back) ?: return
        back.background = null
        TooltipCompat.setTooltipText(back, back.context.getString(R.string.exp_back))
        back.setOnClickListener {
            ExpMotion.hapticTap(it)
            androidx.navigation.Navigation.findNavController(root).navigateUp()
        }
    }

    fun bindMovie(fragment: Fragment, root: View, movie: Movie) {
        val context = root.context
        bindTitleChrome(root, movie.title, movie.logo)
        if (movie.logo.isNullOrBlank()) {
            resolveLogo(
                fragment = fragment,
                root = root,
                title = movie.title,
                year = movie.released?.format("yyyy")?.toIntOrNull(),
                isTv = false,
                tmdbId = movie.tmdbId,
            ) { movie.logo = it }
        }
        onScrolled(root, 0)

        val listIcon = root.findViewById<ImageView>(R.id.btn_detail_list) ?: return
        listIcon.background = null
        applyListState(listIcon, movie.isFavorite, animate = false)
        listIcon.setOnClickListener {
            ExpMotion.hapticTap(it)
            fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val dao = AppDatabase.getInstance(context).movieDao()
                val target = !(dao.getById(movie.id)?.isFavorite ?: movie.isFavorite)
                val resolved = ArtworkRepair.resolveMovieForFavorite(context, movie, target)
                dao.upsertFavorite(resolved, target)
                com.dskja.betterstreamflix.platform.simkl.SimklSyncHooks.onListToggle(
                    add = target,
                    imdbId = movie.imdbId,
                    tmdbId = movie.tmdbId,
                    isTv = false,
                )
                withContext(Dispatchers.Main) {
                    movie.poster = resolved.poster
                    movie.banner = resolved.banner
                    movie.isFavorite = target
                    applyListState(listIcon, target, animate = true)
                }
            }
        }
    }

    fun bindTvShow(fragment: Fragment, root: View, tvShow: TvShow) {
        val context = root.context
        bindTitleChrome(root, tvShow.title, tvShow.logo)
        if (tvShow.logo.isNullOrBlank()) {
            resolveLogo(
                fragment = fragment,
                root = root,
                title = tvShow.title,
                year = tvShow.released?.format("yyyy")?.toIntOrNull(),
                isTv = true,
                tmdbId = tvShow.tmdbId,
            ) { tvShow.logo = it }
        }
        onScrolled(root, 0)

        val listIcon = root.findViewById<ImageView>(R.id.btn_detail_list) ?: return
        listIcon.background = null
        applyListState(listIcon, tvShow.isFavorite, animate = false)
        listIcon.setOnClickListener {
            ExpMotion.hapticTap(it)
            fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val dao = AppDatabase.getInstance(context).tvShowDao()
                val target = !(dao.getById(tvShow.id)?.isFavorite ?: tvShow.isFavorite)
                val resolved = ArtworkRepair.resolveTvShowForFavorite(context, tvShow, target)
                dao.upsertFavorite(resolved, target)
                com.dskja.betterstreamflix.platform.simkl.SimklSyncHooks.onListToggle(
                    add = target,
                    imdbId = tvShow.imdbId,
                    tmdbId = tvShow.tmdbId,
                    isTv = true,
                )
                withContext(Dispatchers.Main) {
                    tvShow.poster = resolved.poster
                    tvShow.banner = resolved.banner
                    tvShow.isFavorite = target
                    applyListState(listIcon, target, animate = true)
                }
            }
        }
    }

    /** Soft bar + centered logo fade in only after the hero scrolls away. */
    fun onScrolled(root: View, scrollY: Int) {
        val progress = (scrollY / COLLAPSE_RANGE).coerceIn(0f, 1f)
        root.findViewById<View>(R.id.v_detail_header_scrim)?.alpha = progress
        val logo = root.findViewById<ImageView>(R.id.iv_detail_header_logo)
        val title = root.findViewById<TextView>(R.id.tv_detail_header_title)
        val showLogo = logo?.tag == true
        if (showLogo) {
            logo?.visibility = View.VISIBLE
            logo?.alpha = progress
            title?.visibility = View.INVISIBLE
            title?.alpha = 0f
        } else {
            logo?.visibility = View.INVISIBLE
            logo?.alpha = 0f
            title?.visibility = View.VISIBLE
            title?.alpha = progress
        }
    }

    fun refreshListState(root: View, inList: Boolean) {
        root.findViewById<ImageView>(R.id.btn_detail_list)
            ?.let { applyListState(it, inList, animate = false) }
    }

    private fun resolveLogo(
        fragment: Fragment,
        root: View,
        title: String,
        year: Int?,
        isTv: Boolean,
        tmdbId: String?,
        onResolved: (String) -> Unit,
    ) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val logo = withContext(Dispatchers.IO) {
                runCatching {
                    TmdbUtils.resolveTitleLogo(
                        title = title,
                        year = year,
                        isTv = isTv,
                        tmdbId = tmdbId,
                    )
                }.getOrNull()
            }
            if (logo.isNullOrBlank()) return@launch
            onResolved(logo)
            val currentTitle = root.findViewById<TextView>(R.id.tv_detail_header_title)?.text?.toString()
            if (currentTitle == title) bindTitleChrome(root, title, logo)
        }
    }

    private fun bindTitleChrome(root: View, title: String, logoUrl: String?) {
        root.findViewById<TextView>(R.id.tv_detail_header_title)?.text = title
        val logo = root.findViewById<ImageView>(R.id.iv_detail_header_logo) ?: return
        val url = ArtworkUrls.preferOriginal(logoUrl) ?: ArtworkUrls.preferHero(logoUrl)
        if (url.isNullOrBlank()) {
            logo.setImageDrawable(null)
            logo.tag = false
            return
        }
        Glide.with(logo)
            .load(url)
            .fitCenter()
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    logo.tag = false
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    logo.tag = true
                    return false
                }
            })
            .into(logo)
        logo.tag = true
    }

    private fun applyListState(icon: ImageView, inList: Boolean, animate: Boolean) {
        icon.setImageDrawable(
            ContextCompat.getDrawable(
                icon.context,
                if (inList) R.drawable.ic_list_added else R.drawable.ic_list_add,
            )
        )
        val description = icon.context.getString(
            if (inList) R.string.detail_remove_from_list else R.string.detail_add_to_list,
        )
        icon.contentDescription = description
        TooltipCompat.setTooltipText(icon, description)
        if (animate) ExpMotion.softScale(icon)
    }
}
