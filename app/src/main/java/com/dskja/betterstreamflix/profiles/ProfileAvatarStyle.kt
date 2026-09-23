package com.dskja.betterstreamflix.profiles

import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import com.dskja.betterstreamflix.R

/** Avatar palettes: two-stop gradient, highlight tint, and on-orb glyph color. */
object ProfileAvatarStyle {

    data class Palette(
        val key: String,
        @StringRes val titleRes: Int,
        @ColorInt val start: Int,
        @ColorInt val end: Int,
        @ColorInt val highlight: Int,
        @ColorInt val onOrb: Int,
        /** Illustrated motif drawn behind the monogram. */
        val motif: Motif,
        /** Optional mid-stop for richer multi-stop fills. */
        @ColorInt val mid: Int = 0,
    )

    enum class Motif { ARC, DIAMOND, BARS, RING, SPARK, CHEVRON, WAVE, ORBIT, FACE, PORTRAIT }

    // Opaque ARGB — JVM-safe (no android.graphics.Color).
    private val PALETTES = listOf(
        Palette(
            key = "crimson",
            titleRes = R.string.profile_avatar_crimson_signal,
            start = 0xFF6B0C18.toInt(),
            end = 0xFFE23A45.toInt(),
            mid = 0xFFB02030.toInt(),
            highlight = 0x88FFB0B8.toInt(),
            onOrb = 0xFFFFF8F8.toInt(),
            motif = Motif.FACE,
        ),
        Palette(
            key = "ember",
            titleRes = R.string.profile_avatar_ember_forge,
            start = 0xFF5C2410.toInt(),
            end = 0xFFE8904A.toInt(),
            mid = 0xFFC06028.toInt(),
            highlight = 0x88FFE0B8.toInt(),
            onOrb = 0xFFFFF8F0.toInt(),
            motif = Motif.PORTRAIT,
        ),
        Palette(
            key = "aurora",
            titleRes = R.string.profile_avatar_aurora_tide,
            start = 0xFF0A3834.toInt(),
            end = 0xFF4ECFBE.toInt(),
            mid = 0xFF1A8A7A.toInt(),
            highlight = 0x88B8FFF4.toInt(),
            onOrb = 0xFFF0FFFC.toInt(),
            motif = Motif.FACE,
        ),
        Palette(
            key = "slate",
            titleRes = R.string.profile_avatar_slate_noir,
            start = 0xFF1C2228.toInt(),
            end = 0xFF8A93A0.toInt(),
            mid = 0xFF4A5560.toInt(),
            highlight = 0x88E8ECF0.toInt(),
            onOrb = 0xFFF8F9FA.toInt(),
            motif = Motif.PORTRAIT,
        ),
        Palette(
            key = "forest",
            titleRes = R.string.profile_avatar_forest_grove,
            start = 0xFF123024.toInt(),
            end = 0xFF4F9A6E.toInt(),
            mid = 0xFF2A6A48.toInt(),
            highlight = 0x88C8F0D4.toInt(),
            onOrb = 0xFFF2FFF6.toInt(),
            motif = Motif.FACE,
        ),
        Palette(
            key = "ocean",
            titleRes = R.string.profile_avatar_ocean_depth,
            start = 0xFF0C2440.toInt(),
            end = 0xFF3D7CC9.toInt(),
            mid = 0xFF1A5088.toInt(),
            highlight = 0x88B8D8FF.toInt(),
            onOrb = 0xFFF0F6FF.toInt(),
            motif = Motif.PORTRAIT,
        ),
        Palette(
            key = "gold",
            titleRes = R.string.profile_avatar_gold_atelier,
            start = 0xFF4A0A0E.toInt(),
            end = 0xFFE50914.toInt(),
            mid = 0xFFB20710.toInt(),
            highlight = 0x88FFB0B8.toInt(),
            onOrb = 0xFFFFF8F8.toInt(),
            motif = Motif.FACE,
        ),
        Palette(
            key = "rose",
            titleRes = R.string.profile_avatar_rose_velvet,
            start = 0xFF4A2030.toInt(),
            end = 0xFFD484A8.toInt(),
            mid = 0xFFA05070.toInt(),
            highlight = 0x88FFD0E4.toInt(),
            onOrb = 0xFFFFF5FA.toInt(),
            motif = Motif.PORTRAIT,
        ),
        Palette(
            key = "violet",
            titleRes = R.string.profile_avatar_violet_flare,
            start = 0xFF2E1458.toInt(),
            end = 0xFF9B6BFF.toInt(),
            mid = 0xFF5A38A8.toInt(),
            highlight = 0x88E0C8FF.toInt(),
            onOrb = 0xFFF8F4FF.toInt(),
            motif = Motif.FACE,
        ),
        Palette(
            key = "mint",
            titleRes = R.string.profile_avatar_mint_breeze,
            start = 0xFF0A3830.toInt(),
            end = 0xFF5EE0B0.toInt(),
            mid = 0xFF28A080.toInt(),
            highlight = 0x88C8FFE8.toInt(),
            onOrb = 0xFFF0FFF8.toInt(),
            motif = Motif.WAVE,
        ),
        Palette(
            key = "indigo",
            titleRes = R.string.profile_avatar_indigo_night,
            start = 0xFF141C48.toInt(),
            end = 0xFF5B6FE8.toInt(),
            mid = 0xFF3040A0.toInt(),
            highlight = 0x88C0CAFF.toInt(),
            onOrb = 0xFFF2F4FF.toInt(),
            motif = Motif.PORTRAIT,
        ),
        Palette(
            key = "copper",
            titleRes = R.string.profile_avatar_copper_glow,
            start = 0xFF4A2010.toInt(),
            end = 0xFFD4844A.toInt(),
            mid = 0xFFA05028.toInt(),
            highlight = 0x88FFD0A8.toInt(),
            onOrb = 0xFFFFF6EE.toInt(),
            motif = Motif.FACE,
        ),
        Palette(
            key = "arctic",
            titleRes = R.string.profile_avatar_arctic_ice,
            start = 0xFF142E3A.toInt(),
            end = 0xFF7EC8E0.toInt(),
            mid = 0xFF3A88A0.toInt(),
            highlight = 0x88D0F0FF.toInt(),
            onOrb = 0xFFF5FCFF.toInt(),
            motif = Motif.RING,
        ),
        Palette(
            key = "sunset",
            titleRes = R.string.profile_avatar_sunset_flare,
            start = 0xFF581828.toInt(),
            end = 0xFFF07850.toInt(),
            mid = 0xFFC04838.toInt(),
            highlight = 0x88FFC8A0.toInt(),
            onOrb = 0xFFFFF8F4.toInt(),
            motif = Motif.PORTRAIT,
        ),
        Palette(
            key = "neon",
            titleRes = R.string.profile_avatar_neon_lime,
            start = 0xFF143812.toInt(),
            end = 0xFF8BE04A.toInt(),
            mid = 0xFF4A9828.toInt(),
            highlight = 0x88D8FFA8.toInt(),
            onOrb = 0xFFF6FFF0.toInt(),
            motif = Motif.SPARK,
        ),
        Palette(
            key = "cobalt",
            titleRes = R.string.profile_avatar_cobalt_rush,
            start = 0xFF081E48.toInt(),
            end = 0xFF2A7BFF.toInt(),
            mid = 0xFF1450B0.toInt(),
            highlight = 0x88A8C8FF.toInt(),
            onOrb = 0xFFF0F6FF.toInt(),
            motif = Motif.FACE,
        ),
        Palette(
            key = "magenta",
            titleRes = R.string.profile_avatar_magenta_pulse,
            start = 0xFF481038.toInt(),
            end = 0xFFE048A8.toInt(),
            mid = 0xFFA02870.toInt(),
            highlight = 0x88FFB8E0.toInt(),
            onOrb = 0xFFFFF5FA.toInt(),
            motif = Motif.PORTRAIT,
        ),
        Palette(
            key = "charcoal",
            titleRes = R.string.profile_avatar_charcoal_ink,
            start = 0xFF12141A.toInt(),
            end = 0xFF5A6270.toInt(),
            mid = 0xFF343840.toInt(),
            highlight = 0x88C8D0DC.toInt(),
            onOrb = 0xFFF4F6FA.toInt(),
            motif = Motif.FACE,
        ),
        Palette(
            key = "teal",
            titleRes = R.string.profile_avatar_teal_current,
            start = 0xFF082E34.toInt(),
            end = 0xFF2AB8C8.toInt(),
            mid = 0xFF148088.toInt(),
            highlight = 0x88A8F0F8.toInt(),
            onOrb = 0xFFF0FFFE.toInt(),
            motif = Motif.WAVE,
        ),
        Palette(
            key = "peach",
            titleRes = R.string.profile_avatar_peach_bloom,
            start = 0xFF583020.toInt(),
            end = 0xFFF0A878.toInt(),
            mid = 0xFFC07048.toInt(),
            highlight = 0x88FFE0C8.toInt(),
            onOrb = 0xFFFFF8F4.toInt(),
            motif = Motif.PORTRAIT,
        ),
    )

    private val byKey = PALETTES.associateBy { it.key }

    /** Curated set for Create/Edit — avoids crowding the card with every signal. */
    private val FEATURED_KEYS = listOf(
        "crimson",
        "ember",
        "aurora",
        "slate",
        "forest",
        "ocean",
        "gold",
        "rose",
    )

    fun all(): List<Palette> = PALETTES

    /** Eight hero styles for create/edit; always includes [selectedKey] if set. */
    fun featured(selectedKey: String? = null): List<Palette> {
        val base = FEATURED_KEYS.mapNotNull { byKey[it] }
        if (selectedKey.isNullOrBlank()) return base
        val selected = byKey[selectedKey] ?: return base
        if (base.any { it.key == selected.key }) return base
        return listOf(selected) + base
    }

    fun paletteFor(avatarKey: String): Palette =
        byKey[avatarKey] ?: PALETTES.first()

    @ColorInt
    fun colorFor(avatarKey: String): Int = paletteFor(avatarKey).end

    @ColorInt
    fun startFor(avatarKey: String): Int = paletteFor(avatarKey).start

    fun initialFor(displayName: String): String {
        val trimmed = displayName.trim()
        if (trimmed.isEmpty()) return "?"
        val parts = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return if (parts.size >= 2) {
            "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
        } else {
            trimmed.first().uppercaseChar().toString()
        }
    }

    fun labelFor(avatarKey: String): String =
        avatarKey.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
