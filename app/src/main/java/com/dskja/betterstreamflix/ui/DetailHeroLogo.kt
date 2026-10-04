package com.dskja.betterstreamflix.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.logo.TmdbLogoGlide

/**
 * Mobile detail hero title slot.
 *
 * The header controller owns the Glide load. This only decides what is on screen
 * when the row rebinds before that load runs again, so a recycled wordmark from
 * another title never stays up and the slot is never left blank.
 */
object DetailHeroLogo {

    fun tagMatchesLogo(tag: String?, logoUrl: String?): Boolean {
        if (tag.isNullOrBlank() || logoUrl.isNullOrBlank()) return false
        if (tag == logoUrl) return true
        val file = logoUrl.substringAfterLast('/').substringBefore('?').substringBefore('#')
        return file.isNotBlank() && tag.contains(file)
    }

    /**
     * @return true when this title's wordmark is already decoded and shown.
     */
    fun restore(
        logoView: ImageView,
        titleView: TextView,
        logoUrl: String?,
        title: String,
    ): Boolean {
        titleView.text = title
        val tag = logoView.getTag(R.id.tmdb_logo_expected_url_tag) as? String
        val decoded = logoView.drawable != null && tagMatchesLogo(tag, logoUrl)
        if (decoded) {
            logoView.visibility = View.VISIBLE
            titleView.visibility = View.INVISIBLE
            titleView.alpha = 1f
            return true
        }
        if (logoView.drawable != null || !tag.isNullOrBlank()) {
            TmdbLogoGlide.clear(logoView)
        }
        logoView.visibility = View.INVISIBLE
        titleView.visibility = View.VISIBLE
        titleView.alpha = 1f
        return false
    }
}
