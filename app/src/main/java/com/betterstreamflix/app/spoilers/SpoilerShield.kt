package com.betterstreamflix.app.spoilers

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.betterstreamflix.app.R
import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.utils.UserPreferences
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Spoiler Shield — blur episode titles/thumbs until unlock.
 * Always on for Kids profiles; opt-in for adult profiles.
 *
 * Unlock state is **session-only** (in-memory): process death, profile switch, or
 * [clearSession] re-shields. Intentional — spoilers should not persist across restarts
 * on a shared device (SS-007).
 */
object SpoilerShield {

    private val unlockedEpisodes: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val unlockedShows: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())

    /** Kids always; adults only when the profile toggle is on. */
    fun isActive(
        isKids: () -> Boolean = { ProfileManager.isKidsActive() },
        adultOptIn: () -> Boolean = { UserPreferences.spoilerShieldEnabled },
    ): Boolean = isKids() || adultOptIn()

    fun isShielded(
        episode: Episode,
        isKids: () -> Boolean = { ProfileManager.isKidsActive() },
        adultOptIn: () -> Boolean = { UserPreferences.spoilerShieldEnabled },
    ): Boolean {
        if (!isActive(isKids, adultOptIn)) return false
        // Already watched or in-progress CW — not treated as unwatched spoilers.
        if (episode.isWatched) return false
        if (hasMeaningfulWatchProgress(episode)) return false
        val episodeId = episode.id.trim()
        if (episodeId.isNotEmpty() && episodeId in unlockedEpisodes) return false
        val showId = episode.tvShow?.id?.trim().orEmpty()
        if (showId.isNotEmpty() && showId in unlockedShows) return false
        return true
    }

    fun unlockEpisode(episodeId: String) {
        val id = episodeId.trim()
        if (id.isNotEmpty()) unlockedEpisodes += id
    }

    fun unlockShow(showId: String) {
        val id = showId.trim()
        if (id.isNotEmpty()) unlockedShows += id
    }

    fun clearSession() {
        unlockedEpisodes.clear()
        unlockedShows.clear()
    }

    fun displayTitle(
        episode: Episode,
        episodeNumberFallback: (Int) -> String,
        isKids: () -> Boolean = { ProfileManager.isKidsActive() },
        adultOptIn: () -> Boolean = { UserPreferences.spoilerShieldEnabled },
    ): String {
        if (!isShielded(episode, isKids, adultOptIn)) {
            return episode.title?.trim()?.takeIf { it.isNotEmpty() }
                ?: episodeNumberFallback(episode.number)
        }
        return episodeNumberFallback(episode.number)
    }

    fun displayOverview(
        episode: Episode,
        shieldedPlaceholder: String,
        isKids: () -> Boolean = { ProfileManager.isKidsActive() },
        adultOptIn: () -> Boolean = { UserPreferences.spoilerShieldEnabled },
    ): String {
        if (!isShielded(episode, isKids, adultOptIn)) {
            return episode.overview.orEmpty()
        }
        return shieldedPlaceholder
    }

    fun applyPosterShield(imageView: ImageView, shielded: Boolean) {
        if (shielded) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                imageView.setRenderEffect(
                    RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP),
                )
                imageView.clearColorFilter()
                imageView.alpha = 0.92f
            } else {
                val matrix = ColorMatrix().apply {
                    setSaturation(0f)
                    // Darken so the still-frame is hard to read.
                    val scale = floatArrayOf(
                        0.35f, 0f, 0f, 0f, 0f,
                        0f, 0.35f, 0f, 0f, 0f,
                        0f, 0f, 0.35f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    )
                    postConcat(ColorMatrix(scale))
                }
                imageView.colorFilter = ColorMatrixColorFilter(matrix)
                imageView.alpha = 0.85f
            }
            imageView.setTag(R.id.spoiler_shield_tag, true)
        } else {
            if (imageView.getTag(R.id.spoiler_shield_tag) == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    imageView.setRenderEffect(null)
                }
                imageView.clearColorFilter()
                imageView.alpha = 1f
            }
            imageView.setTag(R.id.spoiler_shield_tag, false)
        }
    }

    private fun hasMeaningfulWatchProgress(episode: Episode): Boolean {
        val history = episode.watchHistory ?: return false
        return history.lastPlaybackPositionMillis > 0L && history.durationMillis > 0L
    }

    fun applyTextShield(textView: TextView?, shielded: Boolean) {
        textView ?: return
        if (shielded) {
            if (textView.getTag(R.id.spoiler_shield_original_text_tag) == null) {
                textView.setTag(R.id.spoiler_shield_original_text_tag, textView.text)
            }
            // Replace text so TalkBack / pre-blur APIs never speak the spoiler string.
            textView.text = "•••"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                textView.setRenderEffect(
                    RenderEffect.createBlurEffect(12f, 12f, Shader.TileMode.CLAMP),
                )
            }
            textView.alpha = 1f
            textView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            textView.contentDescription = null
            textView.setTag(R.id.spoiler_shield_tag, true)
        } else {
            if (textView.getTag(R.id.spoiler_shield_tag) == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    textView.setRenderEffect(null)
                }
                (textView.getTag(R.id.spoiler_shield_original_text_tag) as? CharSequence)?.let {
                    textView.text = it
                }
                textView.setTag(R.id.spoiler_shield_original_text_tag, null)
                textView.alpha = 1f
            }
            textView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            textView.setTag(R.id.spoiler_shield_tag, false)
        }
    }

    fun applyOverviewShield(textView: TextView?, shielded: Boolean, placeholder: String, real: String?) {
        textView ?: return
        if (shielded) {
            textView.text = placeholder
            textView.visibility = View.VISIBLE
            applyTextShield(textView, true)
        } else {
            applyTextShield(textView, false)
            textView.text = real.orEmpty()
            textView.visibility = if (real.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }
}
