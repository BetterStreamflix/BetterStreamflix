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
        val motif: Motif,
    )

    enum class Motif { ARC, DIAMOND, BARS, RING, SPARK, CRESCENT }

    // Opaque ARGB — JVM-safe (no android.graphics.Color).
    private val PALETTES = listOf(
        Palette(
            key = "copper",
            titleRes = R.string.profile_avatar_copper_atelier,
            start = 0xFF4A2C14.toInt(),
            end = 0xFFD08A4A.toInt(),
            highlight = 0x66F6DCC0.toInt(),
            onOrb = 0xFFFFF6EC.toInt(),
            motif = Motif.ARC,
        ),
        Palette(
            key = "violet",
            titleRes = R.string.profile_avatar_violet_salon,
            start = 0xFF2A1A40.toInt(),
            end = 0xFF9B7EC8.toInt(),
            highlight = 0x66E6D8F5.toInt(),
            onOrb = 0xFFF8F2FF.toInt(),
            motif = Motif.RING,
        ),
        Palette(
            key = "moss",
            titleRes = R.string.profile_avatar_moss_garden,
            start = 0xFF1C3020.toInt(),
            end = 0xFF7AB08A.toInt(),
            highlight = 0x66D4EDD8.toInt(),
            onOrb = 0xFFF2FFF6.toInt(),
            motif = Motif.DIAMOND,
        ),
        Palette(
            key = "ink",
            titleRes = R.string.profile_avatar_ink_press,
            start = 0xFF1C242C.toInt(),
            end = 0xFFA8B4C0.toInt(),
            highlight = 0x66E4EAF0.toInt(),
            onOrb = 0xFFF5F7FA.toInt(),
            motif = Motif.BARS,
        ),
        Palette(
            key = "ember",
            titleRes = R.string.profile_avatar_ember_forge,
            start = 0xFF4A2818.toInt(),
            end = 0xFFE0A06A.toInt(),
            highlight = 0x66FFE0B8.toInt(),
            onOrb = 0xFFFFF8F0.toInt(),
            motif = Motif.SPARK,
        ),
        Palette(
            key = "aurora",
            titleRes = R.string.profile_avatar_aurora_tide,
            start = 0xFF1A3A28.toInt(),
            end = 0xFF6FA37A.toInt(),
            highlight = 0x66B8FFF4.toInt(),
            onOrb = 0xFFF0FFFC.toInt(),
            motif = Motif.RING,
        ),
        Palette(
            key = "forest",
            titleRes = R.string.profile_avatar_forest_grove,
            start = 0xFF163024.toInt(),
            end = 0xFF4F9A6E.toInt(),
            highlight = 0x66C8F0D4.toInt(),
            onOrb = 0xFFF2FFF6.toInt(),
            motif = Motif.DIAMOND,
        ),
        Palette(
            key = "ocean",
            titleRes = R.string.profile_avatar_ocean_depth,
            start = 0xFF1A2840.toInt(),
            end = 0xFF5A84B8.toInt(),
            highlight = 0x66B8D8FF.toInt(),
            onOrb = 0xFFF0F6FF.toInt(),
            motif = Motif.ARC,
        ),
        Palette(
            key = "amber",
            titleRes = R.string.profile_avatar_amber_lamp,
            start = 0xFF3A2810.toInt(),
            end = 0xFFE0B060.toInt(),
            highlight = 0x66FFE8B8.toInt(),
            onOrb = 0xFFFFF8EC.toInt(),
            motif = Motif.SPARK,
        ),
        Palette(
            key = "plum",
            titleRes = R.string.profile_avatar_plum_velvet,
            start = 0xFF3A1830.toInt(),
            end = 0xFFB06090.toInt(),
            highlight = 0x66FFD0E4.toInt(),
            onOrb = 0xFFFFF5FA.toInt(),
            motif = Motif.CRESCENT,
        ),
        Palette(
            key = "steel",
            titleRes = R.string.profile_avatar_steel_frame,
            start = 0xFF1C242C.toInt(),
            end = 0xFF7A8C9C.toInt(),
            highlight = 0x66D0D8E0.toInt(),
            onOrb = 0xFFF4F6F8.toInt(),
            motif = Motif.BARS,
        ),
        Palette(
            key = "rose",
            titleRes = R.string.profile_avatar_rose_velvet,
            start = 0xFF4A2434.toInt(),
            end = 0xFFC484A0.toInt(),
            highlight = 0x66FFD0E4.toInt(),
            onOrb = 0xFFFFF5FA.toInt(),
            motif = Motif.RING,
        ),
        Palette(
            key = "crimson",
            titleRes = R.string.profile_avatar_crimson_signal,
            start = 0xFF6A2A14.toInt(),
            end = 0xFFD08A4A.toInt(),
            highlight = 0x66FFB0B8.toInt(),
            onOrb = 0xFFFFF5F5.toInt(),
            motif = Motif.ARC,
        ),
        Palette(
            key = "slate",
            titleRes = R.string.profile_avatar_slate_noir,
            start = 0xFF242830.toInt(),
            end = 0xFFA8B4C0.toInt(),
            highlight = 0x66E8ECF0.toInt(),
            onOrb = 0xFFF5F6F8.toInt(),
            motif = Motif.BARS,
        ),
        Palette(
            key = "gold",
            titleRes = R.string.profile_avatar_gold_atelier,
            start = 0xFF3A2A14.toInt(),
            end = 0xFFC4A46A.toInt(),
            highlight = 0x66FFE8B8.toInt(),
            onOrb = 0xFFFFF8EC.toInt(),
            motif = Motif.SPARK,
        ),
    )

    private val byKey = PALETTES.associateBy { it.key }

    fun all(): List<Palette> = PALETTES

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
