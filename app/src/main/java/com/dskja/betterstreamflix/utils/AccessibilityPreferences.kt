package com.dskja.betterstreamflix.utils

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.annotation.StyleRes
import androidx.core.view.ViewCompat
import com.dskja.betterstreamflix.BuildConfig
import com.dskja.betterstreamflix.R
import kotlin.math.max

/**
 * Device-wide accessibility overlays: large text (fontScale) and high contrast theme.
 * Reads SharedPreferences directly so [wrap] works in attachBaseContext before
 * [UserPreferences.setup].
 *
 * Enabling app [HIGH_CONTRAST] also applies
 * [com.dskja.betterstreamflix.player.SubtitleStudio.HIGH_CONTRAST] captions (ST-009)
 * via [syncSubtitleStudioForHighContrast]. Turning HIGH_CONTRAST off leaves captions
 * as-is so user tweaks are not wiped.
 */
object AccessibilityPreferences {
    const val LARGE_TEXT_SCALE = 1.3f

    private const val KEY_LARGE_TEXT = "LARGE_TEXT"
    private const val KEY_HIGH_CONTRAST = "HIGH_CONTRAST"

    fun wrap(context: Context): Context {
        if (!isLargeTextEnabled(context)) return context
        val configuration = Configuration(context.resources.configuration)
        val target = max(configuration.fontScale, LARGE_TEXT_SCALE)
        if (configuration.fontScale >= target) return context
        configuration.fontScale = target
        return context.createConfigurationContext(configuration)
    }

    /** Language first, then large-text fontScale. */
    fun wrapAfterLanguage(context: Context): Context =
        wrap(AppLanguageManager.wrap(context))

    /**
     * LD-027 / LD-034: intentional no-op — do **not** shrink Configuration.densityDpi for
     * Compact Leanback. Global dpi scales nav/player/dialogs and compounds Large Text.
     * Compact sizing is owned by [com.dskja.betterstreamflix.ui.LeanbackDensityPolicy]
     * plus [R.style.AppTheme_Tv_LeanbackCompactOverlay] from MainTvActivity.onCreate.
     */
    fun wrapLeanbackDensity(context: Context): Context = context

    fun isLargeTextEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LARGE_TEXT, false)

    fun isHighContrastEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIGH_CONTRAST, false)

    fun isHighContrastActive(): Boolean =
        runCatching { UserPreferences.highContrast }.getOrDefault(false)

    /**
     * ST-009: when the accessibility High contrast toggle turns **on**, apply the
     * Subtitle Studio high-contrast caption preset so UI chrome and captions align.
     * No-op when [enabled] is false (preserves any caption customizations).
     */
    fun syncSubtitleStudioForHighContrast(enabled: Boolean) {
        if (!enabled) return
        runCatching {
            com.dskja.betterstreamflix.player.SubtitleStudio.applyPreset(
                com.dskja.betterstreamflix.player.SubtitleStudio.HIGH_CONTRAST,
            )
        }
    }

    @StyleRes
    fun highContrastOverlay(mobile: Boolean): Int =
        if (mobile) R.style.AppTheme_Mobile_HighContrastOverlay
        else R.style.AppTheme_Tv_HighContrastOverlay

    /** Apply after [Activity.setTheme] and before [Activity.onCreate] super call. */
    fun applyHighContrastOverlay(activity: Activity, mobile: Boolean) {
        if (!isHighContrastActive() && !isHighContrastEnabled(activity)) return
        activity.theme.applyStyle(highContrastOverlay(mobile), true)
    }

    /**
     * FA-031: shared helper for secondary Activities — leanback → TV overlay,
     * otherwise mobile.
     */
    fun applyHighContrastOverlayAuto(activity: Activity) {
        val leanback = activity.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_LEANBACK,
        )
        applyHighContrastOverlay(activity, mobile = !leanback)
    }

    fun posterLabel(
        context: Context,
        title: String?,
        favorite: Boolean = false,
        watched: Boolean = false,
        downloaded: Boolean = false,
    ): String {
        val base = title?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.a11y_untitled)
        val parts = buildList {
            add(base)
            if (favorite) add(context.getString(R.string.a11y_state_in_my_list))
            if (watched) add(context.getString(R.string.a11y_state_watched))
            if (downloaded) add(context.getString(R.string.a11y_state_downloaded))
        }
        return parts.joinToString(", ")
    }

    fun episodeLabel(
        context: Context,
        number: Int,
        title: String?,
        watched: Boolean = false,
        downloaded: Boolean = false,
    ): String {
        val episodeTitle = title?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.episode_number, number)
        val base = context.getString(R.string.a11y_episode_label, number, episodeTitle)
        val parts = buildList {
            add(base)
            if (watched) add(context.getString(R.string.a11y_state_watched))
            if (downloaded) add(context.getString(R.string.a11y_state_downloaded))
        }
        return parts.joinToString(", ")
    }

    fun announceSelected(view: View, selected: Boolean) {
        // Prefer state description over View.isSelected so TV focus drawables
        // that key off state_selected are not visually altered.
        ViewCompat.setStateDescription(
            view,
            if (selected) view.context.getString(R.string.a11y_state_selected) else null,
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(
            "${BuildConfig.APPLICATION_ID}.preferences",
            Context.MODE_PRIVATE,
        )
}
