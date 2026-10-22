package com.betterstreamflix.app.adapters.viewholders.detail

import android.view.View
import android.widget.Toast
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import com.betterstreamflix.app.R
import com.betterstreamflix.app.models.Movie
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.utils.ParentalControlUtils
import com.betterstreamflix.app.utils.toActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Re-checks age at Detail bind time so deep links / stale rows cannot expose Play (PS-003).
 */
internal object DetailParentalGate {
    fun guardMovie(itemView: View, movie: Movie, vararg playViews: View) {
        if (!ParentalControlUtils.isFilteringActive()) return
        setPlayEnabled(playViews, enabled = false)
        val owner = itemView.findViewTreeLifecycleOwner() ?: run {
            leaveDetail(itemView)
            return
        }
        owner.lifecycleScope.launch {
            val allowed = withContext(Dispatchers.IO) {
                ParentalControlUtils.isAllowed(movie)
            }
            if (!itemView.isAttachedToWindow) return@launch
            if (!allowed) {
                Toast.makeText(
                    itemView.context,
                    R.string.parental_content_blocked,
                    Toast.LENGTH_SHORT,
                ).show()
                leaveDetail(itemView)
                return@launch
            }
            setPlayEnabled(playViews, enabled = true)
        }
    }

    fun guardTvShow(itemView: View, tvShow: TvShow, vararg playViews: View) {
        if (!ParentalControlUtils.isFilteringActive()) return
        setPlayEnabled(playViews, enabled = false)
        val owner = itemView.findViewTreeLifecycleOwner() ?: run {
            leaveDetail(itemView)
            return
        }
        owner.lifecycleScope.launch {
            val allowed = withContext(Dispatchers.IO) {
                ParentalControlUtils.isAllowed(tvShow)
            }
            if (!itemView.isAttachedToWindow) return@launch
            if (!allowed) {
                Toast.makeText(
                    itemView.context,
                    R.string.parental_content_blocked,
                    Toast.LENGTH_SHORT,
                ).show()
                leaveDetail(itemView)
                return@launch
            }
            setPlayEnabled(playViews, enabled = true)
        }
    }

    private fun setPlayEnabled(views: Array<out View>, enabled: Boolean) {
        views.forEach { view ->
            view.isEnabled = enabled
            view.isClickable = enabled
            view.alpha = if (enabled) 1f else 0.4f
        }
    }

    private fun leaveDetail(itemView: View) {
        runCatching { itemView.findNavController().navigateUp() }
            .recoverCatching {
                itemView.context.toActivity()?.onBackPressedDispatcher?.onBackPressed()
            }
    }
}
