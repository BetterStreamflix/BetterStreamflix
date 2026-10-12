package com.dskja.betterstreamflix.ui

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.fragment.app.Fragment
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.activities.main.MainMobileActivity
import com.dskja.betterstreamflix.activities.main.MainTvActivity
import com.dskja.betterstreamflix.fragments.settings.ProfilesSettingsController
import com.dskja.betterstreamflix.profiles.ProfileAvatarView
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign

/**
 * The profile switcher chip that floats in the top-right corner of browse tabs.
 * Pass [showName]=true on mobile Home (and TV) so household profiles are readable.
 */
object HomeProfileChip {

    fun refresh(fragment: Fragment, root: View?, showName: Boolean = false) {
        val chip = root?.findViewById<View>(R.id.tv_home_profile_chip) ?: return
        val profile = ProfileManager.activeProfile()
        val name = profile?.displayName?.takeIf { it.isNotBlank() }
            ?: chip.context.getString(R.string.profile_default)
        val nameView = chip.findViewById<TextView>(R.id.tv_home_profile_name)
        if (showName) {
            nameView?.visibility = View.VISIBLE
            nameView?.text = name
        } else {
            nameView?.visibility = View.GONE
        }
        chip.findViewById<ProfileAvatarView>(R.id.pav_home_profile)?.bind(
            avatarKey = profile?.avatarKey ?: ProfileManager.avatarKeys.first(),
            displayName = name,
            textSizeSp = 12f,
        )
        chip.contentDescription = name
        TooltipCompat.setTooltipText(chip, name)
        chip.visibility = View.VISIBLE
        chip.isFocusable = true
        chip.isClickable = true
        // TV browse: DPAD down from the chip must land on the first poster, not the
        // VerticalGridView container (Leanback focus trap on Movies / TV Shows).
        val grid = root.findViewById<View>(R.id.vgv_home)
            ?: root.findViewById<View>(R.id.vgv_movies)
            ?: root.findViewById<View>(R.id.vgv_tv_shows)
            ?: root.findViewById<View>(R.id.vgv_live_hub)
            ?: root.findViewById<View>(R.id.rv_favorites)
        if (grid != null) {
            chip.isFocusableInTouchMode = true
            // FA-026: avoid nextFocusDown → grid container trap; key handler lands on poster.
            chip.nextFocusDownId = View.NO_ID
            chip.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
                    return@setOnKeyListener false
                }
                focusFirstPoster(grid)
                true
            }
        }

        val chipKey = "${profile?.id.orEmpty()}|$name|${profile?.avatarKey.orEmpty()}|$showName"
        if (ExperimentalMobileDesign.enabled()) {
            chip.applyExpPress()
            chip.setBackgroundResource(ExperimentalMobileDesign.chipBackground())
            if (chip.getTag(R.id.profile_chip_key_tag) != chipKey) ExpMotion.popIn(chip)
        }
        chip.setTag(R.id.profile_chip_key_tag, chipKey)

        chip.setOnClickListener {
            ExpMotion.hapticTap(it)
            ProfilesSettingsController.showSwitchDialog(fragment) {
                val activity = fragment.requireActivity()
                val isTv = activity is MainTvActivity ||
                    DeviceCapabilities.isLeanbackDevice(activity) ||
                    DeviceCapabilities.isAmazonFireTv(activity)
                val launch = Intent(
                    activity,
                    if (isTv) MainTvActivity::class.java else MainMobileActivity::class.java,
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.finish()
                activity.startActivity(launch)
            }
        }
    }

    /**
     * Focus the first visible poster / shelf tile under [grid], never the grid shell.
     */
    fun focusFirstPoster(grid: View): Boolean {
        if (!grid.isAttachedToWindow) return false
        // Featured Watch on Home (id may exist only when Featured row is bound).
        val featuredWatch = (grid as? ViewGroup)?.findViewById<View>(R.id.btn_swiper_watch_now)
        if (featuredWatch != null && featuredWatch.isShown && featuredWatch.isFocusable) {
            return featuredWatch.requestFocus()
        }
        val firstChild = when (grid) {
            is VerticalGridView -> grid.getChildAt(0)
            is HorizontalGridView -> grid.getChildAt(0)
            is RecyclerView -> grid.getChildAt(0)
            is ViewGroup -> grid.getChildAt(0)
            else -> null
        }
        val target = firstFocusableDescendant(firstChild) ?: firstChild
        return if (target != null && target.isShown && target.isFocusable) {
            target.requestFocus()
        } else {
            false
        }
    }

    private fun firstFocusableDescendant(root: View?): View? {
        if (root == null) return null
        if (root.isShown && root.isFocusable && root.id != R.id.vgv_home &&
            root.id != R.id.vgv_movies && root.id != R.id.vgv_tv_shows &&
            root !is VerticalGridView
        ) {
            // Prefer a poster tile over a nested empty grid container.
            if (root is ViewGroup && root.childCount > 0) {
                val nestedGrid = root.findViewById<View>(R.id.hgv_category)
                if (nestedGrid is ViewGroup && nestedGrid.childCount > 0) {
                    val poster = nestedGrid.getChildAt(0)
                    if (poster.isShown && poster.isFocusable) return poster
                }
            }
            if (root !is ViewGroup || root.childCount == 0) return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                firstFocusableDescendant(root.getChildAt(i))?.let { return it }
            }
        }
        return root.takeIf { it.isShown && it.isFocusable }
    }
}
