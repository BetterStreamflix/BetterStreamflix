package com.dskja.betterstreamflix.profiles

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.dskja.betterstreamflix.R

/** Per-profile lounge wash. Keys persist in UserProfile.atmosphereKey. */
object ProfileAtmosphere {

    const val DEFAULT = "atelier"

    data class Spec(
        val key: String,
        @StringRes val titleRes: Int,
        @DrawableRes val backgroundRes: Int,
    )

    private val SPECS = listOf(
        Spec("atelier", R.string.profile_atmosphere_atelier, R.drawable.bg_profile_atmosphere),
        Spec("cinema", R.string.profile_atmosphere_cinema, R.drawable.bg_profile_atmosphere_cinema),
        Spec("lounge", R.string.profile_atmosphere_lounge, R.drawable.bg_profile_atmosphere_lounge),
        Spec("night", R.string.profile_atmosphere_night, R.drawable.bg_profile_atmosphere_night),
        Spec("kids", R.string.profile_atmosphere_kids, R.drawable.bg_profile_atmosphere_kids),
    )

    private val byKey = SPECS.associateBy { it.key }

    val KEYS: Set<String> = byKey.keys

    fun all(): List<Spec> = SPECS

    fun normalize(raw: String?): String =
        if (raw != null && raw in KEYS) raw else DEFAULT

    fun specFor(raw: String?): Spec = byKey[normalize(raw)] ?: SPECS.first()
}
