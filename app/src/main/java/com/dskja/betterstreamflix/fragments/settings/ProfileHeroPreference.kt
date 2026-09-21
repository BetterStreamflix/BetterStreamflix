package com.dskja.betterstreamflix.fragments.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.profiles.ProfileAvatarStyle
import com.dskja.betterstreamflix.profiles.ProfileAvatarView
import com.dskja.betterstreamflix.profiles.ProfileManager

/**
 * Hero row at the top of Settings → Profiles.
 */
class ProfileHeroPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.header_profiles_settings
        isSelectable = false
        key = KEY
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val profile = ProfileManager.activeProfile()
        val name = profile?.displayName?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.profile_default)
        holder.itemView.findViewById<ProfileAvatarView>(R.id.pav_profiles_settings_hero)
            ?.bind(
                avatarKey = profile?.avatarKey ?: ProfileManager.avatarKeys.first(),
                displayName = name,
                textSizeSp = 28f,
            )
        holder.itemView.findViewById<TextView>(R.id.tv_profiles_settings_hero_name)?.text = name
        val meta = buildList {
            profile?.let {
                add(context.getString(ProfileAvatarStyle.paletteFor(it.avatarKey).titleRes))
            }
            add(context.getString(R.string.profile_picker_active))
            if (profile?.isKids == true) add(context.getString(R.string.profile_kids_badge_short))
            if (profile?.pinHash != null) add(context.getString(R.string.profile_picker_locked))
        }.joinToString(" · ")
        holder.itemView.findViewById<TextView>(R.id.tv_profiles_settings_hero_meta)?.text = meta
    }

    fun refresh() {
        notifyChanged()
    }

    companion object {
        const val KEY = "PROFILE_HERO"
    }
}
