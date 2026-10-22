package com.betterstreamflix.app.support

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.betterstreamflix.app.models.Video
import com.betterstreamflix.app.utils.EpisodeManager
import com.betterstreamflix.app.utils.ExpMotion

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
        ExpMotion.popIn(panel.root)

        panel.cta.setOnClickListener {
            ExpMotion.hapticTap(it)
            ThanksAfterBingePolicy.openSupport()
            ThanksAfterBingePolicy.recordShown()
            panel.root.isVisible = false
            onOpenSupport()
        }
        panel.dismiss.setOnClickListener {
            ExpMotion.hapticTap(it)
            ThanksAfterBingePolicy.dismiss()
            ThanksAfterBingePolicy.recordShown()
            panel.root.isVisible = false
        }
        // Long-press dismiss → permanent opt-out for this profile.
        panel.dismiss.setOnLongClickListener {
            ExpMotion.hapticTap(it)
            ThanksAfterBingePolicy.neverShowAgain()
            ThanksAfterBingePolicy.recordShown()
            panel.root.isVisible = false
            true
        }
    }

    fun hide(panel: Panel?) {
        panel?.root?.isVisible = false
    }
}
