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
        /** Small geometric motif drawn behind the initial (arc / diamond / bars). */
        val motif: Motif,
    )

    enum class Motif { ARC, DIAMOND, BARS, RING, SPARK }

    // Opaque ARGB — JVM-safe (no android.graphics.Color).
    private val PALETTES = listOf(
        Palette(
            key = "crimson",
            titleRes = R.string.profile_avatar_crimson_signal,
            start = 0xFF8B1020.toInt(),
            end = 0xFFE23A45.toInt(),
            highlight = 0x66FFB0B8.toInt(),
            onOrb = 0xFFFFF5F5.toInt(),
            motif = Motif.ARC,
        ),
        Palette(
            key = "ember",
            titleRes = R.string.profile_avatar_ember_forge,
            start = 0xFF7A3318.toInt(),
            end = 0xFFE8904A.toInt(),
            highlight = 0x66FFE0B8.toInt(),
            onOrb = 0xFFFFF8F0.toInt(),
            motif = Motif.SPARK,
        ),
        Palette(
            key = "aurora",
            titleRes = R.string.profile_avatar_aurora_tide,
            start = 0xFF0E4A44.toInt(),
            end = 0xFF4ECFBE.toInt(),
            highlight = 0x66B8FFF4.toInt(),
            onOrb = 0xFFF0FFFC.toInt(),
            motif = Motif.RING,
        ),
        Palette(
            key = "slate",
            titleRes = R.string.profile_avatar_slate_noir,
            start = 0xFF2A3038.toInt(),
            end = 0xFF8A93A0.toInt(),
            highlight = 0x66E8ECF0.toInt(),
            onOrb = 0xFFF5F6F8.toInt(),
            motif = Motif.BARS,
        ),
        Palette(
            key = "forest",
            titleRes = R.string.profile_avatar_forest_grove,
            start = 0xFF1A3D2C.toInt(),
            end = 0xFF4F9A6E.toInt(),
            highlight = 0x66C8F0D4.toInt(),
            onOrb = 0xFFF2FFF6.toInt(),
            motif = Motif.DIAMOND,
        ),
        Palette(
            key = "ocean",
            titleRes = R.string.profile_avatar_ocean_depth,
            start = 0xFF12304F.toInt(),
            end = 0xFF3D7CC9.toInt(),
            highlight = 0x66B8D8FF.toInt(),
            onOrb = 0xFFF0F6FF.toInt(),
            motif = Motif.ARC,
        ),
        Palette(
            key = "gold",
            titleRes = R.string.profile_avatar_gold_atelier,
            start = 0xFF0F2A3F.toInt(),
            end = 0xFF2AABEE.toInt(),
            highlight = 0x665BC4F5.toInt(),
            onOrb = 0xFFF5F8FC.toInt(),
            motif = Motif.SPARK,
        ),
        Palette(
            key = "rose",
            titleRes = R.string.profile_avatar_rose_velvet,
            start = 0xFF5A2A3C.toInt(),
            end = 0xFFD484A8.toInt(),
            highlight = 0x66FFD0E4.toInt(),
            onOrb = 0xFFFFF5FA.toInt(),
            motif = Motif.RING,
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
