package com.dskja.betterstreamflix.ui

import android.content.Intent
import android.view.View
import android.widget.ImageView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.download.ui.DownloadOptionsController
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.utils.ArtworkRepair
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Overlay header shared by the movie and TV show detail pages: back, download,
 * list toggle and share. The list toggle is backed by the favorite column.
 */
object DetailHeaderController {

    fun wireBack(root: View) {
        val back = root.findViewById<View>(R.id.iv_detail_back) ?: return
        TooltipCompat.setTooltipText(back, back.context.getString(R.string.exp_back))
        back.setOnClickListener {
            ExpMotion.hapticTap(it)
            androidx.navigation.Navigation.findNavController(root).navigateUp()
        }
    }

    fun bindMovie(fragment: Fragment, root: View, movie: Movie) {
        val context = root.context
        bindShare(root, movie.title, movie.released?.format("yyyy"), movie.overview, movie.trailer)

        root.findViewById<ImageView>(R.id.btn_detail_download)?.apply {
            TooltipCompat.setTooltipText(this, context.getString(R.string.detail_download))
            setOnClickListener {
                ExpMotion.hapticTap(it)
                DownloadOptionsController.enqueueMovie(fragment, movie)
            }
        }

        val listIcon = root.findViewById<ImageView>(R.id.btn_detail_list) ?: return
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
        bindShare(root, tvShow.title, tvShow.released?.format("yyyy"), tvShow.overview, tvShow.trailer)

        root.findViewById<ImageView>(R.id.btn_detail_download)?.apply {
            TooltipCompat.setTooltipText(this, context.getString(R.string.detail_download))
            setOnClickListener {
                ExpMotion.hapticTap(it)
                DownloadOptionsController.offerTvShowDownload(fragment, tvShow, tvShow.episodeToWatch)
            }
        }

        val listIcon = root.findViewById<ImageView>(R.id.btn_detail_list) ?: return
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

    /** Refreshes only the list icon so a favorite change never re-submits the page list. */
    fun refreshListState(root: View, inList: Boolean) {
        root.findViewById<ImageView>(R.id.btn_detail_list)
            ?.let { applyListState(it, inList, animate = false) }
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

    private fun bindShare(
        root: View,
        title: String,
        year: String?,
        overview: String?,
        trailer: String?,
    ) {
        val share = root.findViewById<ImageView>(R.id.btn_detail_share) ?: return
        TooltipCompat.setTooltipText(share, share.context.getString(R.string.detail_share))
        share.setOnClickListener {
            ExpMotion.hapticTap(it)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(
                    Intent.EXTRA_TEXT,
                    buildString {
                        append(title)
                        year?.let { append(" ($it)") }
                        overview?.takeIf { text -> text.isNotBlank() }
                            ?.let { text -> append("\n\n").append(text.take(280)) }
                        trailer?.let { url -> append("\n").append(url) }
                    },
                )
            }
            share.context.startActivity(
                Intent.createChooser(intent, share.context.getString(R.string.detail_share))
            )
        }
    }
}
