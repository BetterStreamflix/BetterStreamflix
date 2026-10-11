package com.dskja.betterstreamflix.support

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.dskja.betterstreamflix.models.Video
import com.dskja.betterstreamflix.utils.EpisodeManager
import com.dskja.betterstreamflix.utils.ExpMotion

/**
 * Binds the soft Thanks After Binge panel on the post-credits end card.
 * Visible only for season finales when [ThanksAfterBingePolicy.shouldShow].
 */
object ThanksAfterBingeBinder {

    data class Panel(
        val root: View,
        val title: TextView?,
        val body: TextView?,
        val cta: View,
        val dismiss: View,
    )

    fun bind(
        panel: Panel,
        videoType: Video.Type?,
        onOpenSupport: () -> Unit,
    ) {
        val episode = videoType as? Video.Type.Episode
        if (episode == null) {
            panel.root.isVisible = false
            return
        }
        val next = EpisodeManager.peekNextEpisode()
        val seasonFinale = ThanksAfterBingePolicy.isSeasonFinale(
            currentSeasonNumber = episode.season.number,
            nextSeasonNumber = next?.season?.number,
        )
        if (!seasonFinale || !ThanksAfterBingePolicy.shouldShow()) {
            panel.root.isVisible = false
            return
        }

        panel.root.isVisible = true
        ThanksAfterBingePolicy.recordShown()
        ExpMotion.popIn(panel.root)

        panel.cta.setOnClickListener {
            ExpMotion.hapticTap(it)
            ThanksAfterBingePolicy.openSupport()
            panel.root.isVisible = false
            onOpenSupport()
        }
        panel.dismiss.setOnClickListener {
            ExpMotion.hapticTap(it)
            ThanksAfterBingePolicy.dismiss()
            panel.root.isVisible = false
        }
    }

    fun hide(panel: Panel?) {
        panel?.root?.isVisible = false
    }
}
