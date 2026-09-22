package com.dskja.betterstreamflix.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.ArtworkRepair
import com.dskja.betterstreamflix.utils.ArtworkUrls
import com.dskja.betterstreamflix.utils.ExpMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Overlay header shared by the movie and TV show detail pages: solid black bar
 * with back (left), title logo (center), and list toggle (right).
 */
object DetailHeaderController {

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
        ensureSolidBar(root)

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
        ensureSolidBar(root)

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
                withContext(Dispatchers.Main) {
                    tvShow.poster = resolved.poster
                    tvShow.banner = resolved.banner
                    tvShow.isFavorite = target
                    applyListState(listIcon, target, animate = true)
                }
            }
        }
    }

    /**
     * Kept for scroll listeners; the bar stays solid black and the logo stays
     * visible so scrolling never darkens page content behind a tall scrim.
     */
    fun onScrolled(root: View, @Suppress("UNUSED_PARAMETER") scrollY: Int) {
        ensureSolidBar(root)
        applyTitleVisibility(root)
    }

    fun refreshListState(root: View, inList: Boolean) {
        root.findViewById<ImageView>(R.id.btn_detail_list)
            ?.let { applyListState(it, inList, animate = false) }
    }

    private fun ensureSolidBar(root: View) {
        root.findViewById<View>(R.id.v_detail_header_scrim)?.alpha = 1f
    }

    private fun bindTitleChrome(root: View, title: String, logoUrl: String?) {
        root.findViewById<TextView>(R.id.tv_detail_header_title)?.text = title
        val logo = root.findViewById<ImageView>(R.id.iv_detail_header_logo) ?: return
        val url = ArtworkUrls.preferOriginal(logoUrl) ?: ArtworkUrls.preferHero(logoUrl)
        if (url.isNullOrBlank()) {
            logo.setImageDrawable(null)
            logo.tag = false
            applyTitleVisibility(root)
            return
        }
        Glide.with(logo)
            .load(url)
            .fitCenter()
            .into(logo)
        logo.tag = true
        applyTitleVisibility(root)
    }

    private fun applyTitleVisibility(root: View) {
        val logo = root.findViewById<ImageView>(R.id.iv_detail_header_logo)
        val title = root.findViewById<TextView>(R.id.tv_detail_header_title)
        val showLogo = logo?.tag == true
        if (showLogo) {
            logo?.visibility = View.VISIBLE
            logo?.alpha = 1f
            title?.visibility = View.INVISIBLE
            title?.alpha = 0f
        } else {
            logo?.visibility = View.INVISIBLE
            logo?.alpha = 0f
            title?.visibility = View.VISIBLE
            title?.alpha = 1f
        }
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
