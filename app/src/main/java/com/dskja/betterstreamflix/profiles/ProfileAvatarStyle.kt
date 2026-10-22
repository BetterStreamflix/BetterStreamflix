package com.dskja.betterstreamflix.profiles

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.dskja.betterstreamflix.R

/**
 * Curated flat illustrated avatars (vector art, no generated imagery).
 * Each entry ships a full-bleed 108dp vector and an accent color used for
 * rings, summary icons and tints.
 */
object ProfileAvatarStyle {

    data class Avatar(
        val key: String,
        @StringRes val titleRes: Int,
        @DrawableRes val drawableRes: Int,
        @ColorInt val accent: Int,
    )

    private val AVATARS = listOf(
        Avatar("popcorn", R.string.profile_avatar_popcorn, R.drawable.avatar_popcorn, 0xFFA01B22.toInt()),
        Avatar("reel", R.string.profile_avatar_reel, R.drawable.avatar_reel, 0xFF26313D.toInt()),
        Avatar("clapper", R.string.profile_avatar_clapper, R.drawable.avatar_clapper, 0xFF152238.toInt()),
        Avatar("ticket", R.string.profile_avatar_ticket, R.drawable.avatar_ticket, 0xFFC58A1A.toInt()),
        Avatar("crown", R.string.profile_avatar_crown, R.drawable.avatar_crown, 0xFF4A148C.toInt()),
        Avatar("astro", R.string.profile_avatar_astro, R.drawable.avatar_astro, 0xFF142850.toInt()),
        Avatar("rocket", R.string.profile_avatar_rocket, R.drawable.avatar_rocket, 0xFFB71C1C.toInt()),
        Avatar("bolt", R.string.profile_avatar_bolt, R.drawable.avatar_bolt, 0xFF00838F.toInt()),
        Avatar("heart", R.string.profile_avatar_heart, R.drawable.avatar_heart, 0xFFC2185B.toInt()),
        Avatar("ninja", R.string.profile_avatar_ninja, R.drawable.avatar_ninja, 0xFF37474F.toInt()),
        Avatar("ghost", R.string.profile_avatar_ghost, R.drawable.avatar_ghost, 0xFF5E35B1.toInt()),
        Avatar("alien", R.string.profile_avatar_alien, R.drawable.avatar_alien, 0xFF1B5E20.toInt()),
        Avatar("monster", R.string.profile_avatar_monster, R.drawable.avatar_monster, 0xFF7CB342.toInt()),
        Avatar("robot", R.string.profile_avatar_robot, R.drawable.avatar_robot, 0xFF455A64.toInt()),
        Avatar("cat", R.string.profile_avatar_cat, R.drawable.avatar_cat, 0xFF0E6E6B.toInt()),
        Avatar("fox", R.string.profile_avatar_fox, R.drawable.avatar_fox, 0xFFD9622B.toInt()),
        Avatar("owl", R.string.profile_avatar_owl, R.drawable.avatar_owl, 0xFF3D2E66.toInt()),
        Avatar("panda", R.string.profile_avatar_panda, R.drawable.avatar_panda, 0xFF57B894.toInt()),
        Avatar("bear", R.string.profile_avatar_bear, R.drawable.avatar_bear, 0xFF5D4037.toInt()),
        Avatar("penguin", R.string.profile_avatar_penguin, R.drawable.avatar_penguin, 0xFF1B4F72.toInt()),
    )

    private val byKey = AVATARS.associateBy { it.key }

    /** Profiles stored before the illustrated set shipped used palette keys. */
    private val LEGACY_KEYS = mapOf(
        "crimson" to "popcorn",
        "ember" to "fox",
        "aurora" to "cat",
        "slate" to "robot",
        "forest" to "bear",
        "ocean" to "penguin",
        "gold" to "ticket",
        "rose" to "heart",
        "violet" to "ghost",
        "mint" to "panda",
        "indigo" to "owl",
        "copper" to "reel",
        "arctic" to "astro",
        "sunset" to "crown",
        "neon" to "monster",
        "cobalt" to "clapper",
        "magenta" to "alien",
        "charcoal" to "ninja",
        "teal" to "rocket",
        "peach" to "bolt",
    )

    private val FEATURED_KEYS = listOf(
        "popcorn",
        "cat",
        "fox",
        "panda",
        "ghost",
        "robot",
        "rocket",
        "astro",
    )

    /** Maps any stored/legacy key to a valid avatar key. */
    fun resolveKey(avatarKey: String?): String = when {
        avatarKey.isNullOrBlank() -> AVATARS.first().key
        byKey.containsKey(avatarKey) -> avatarKey
        else -> LEGACY_KEYS[avatarKey] ?: AVATARS.first().key
    }

    fun all(): List<Avatar> = AVATARS

    /** Eight hero avatars for create/edit; always includes [selectedKey] if set. */
    fun featured(selectedKey: String? = null): List<Avatar> {
        val base = FEATURED_KEYS.mapNotNull { byKey[it] }
        if (selectedKey.isNullOrBlank()) return base
        val selected = byKey[resolveKey(selectedKey)] ?: return base
        if (base.any { it.key == selected.key }) return base
        return listOf(selected) + base
    }

    fun avatarFor(avatarKey: String): Avatar = byKey.getValue(resolveKey(avatarKey))

    @DrawableRes
    fun drawableFor(avatarKey: String): Int = avatarFor(avatarKey).drawableRes

    @ColorInt
    fun colorFor(avatarKey: String): Int = avatarFor(avatarKey).accent

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
