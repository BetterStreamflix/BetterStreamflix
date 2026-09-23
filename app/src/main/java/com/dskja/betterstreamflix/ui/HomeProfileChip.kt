package com.dskja.betterstreamflix.ui

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.fragment.app.Fragment
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
 * The avatar-only profile switcher that floats in the top-right corner of the
 * mobile browse tabs (home, movies, TV shows) and the TV home chrome.
 */
object HomeProfileChip {

    fun refresh(fragment: Fragment, root: View?) {
        val chip = root?.findViewById<View>(R.id.tv_home_profile_chip) ?: return
        val profile = ProfileManager.activeProfile()
        val name = profile?.displayName?.takeIf { it.isNotBlank() }
            ?: chip.context.getString(R.string.profile_default)
        // The chip is avatar-only; the name lives in the tooltip and content description.
        chip.findViewById<TextView>(R.id.tv_home_profile_name)?.visibility = View.GONE
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
        // TV home: DPAD down from the chip lands in the catalog grid.
        root.findViewById<View>(R.id.vgv_home)?.let { grid ->
            chip.isFocusableInTouchMode = true
            chip.nextFocusDownId = grid.id
        }

        val chipKey = "${profile?.id.orEmpty()}|$name|${profile?.avatarKey.orEmpty()}"
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
}
