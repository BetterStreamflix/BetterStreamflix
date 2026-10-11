package com.dskja.betterstreamflix.kids

/**
 * Pure Kids Fortress policy — PIN/bedtime/age/CW-DL isolation gates.
 * JVM-safe for unit tests (no Android Context).
 *
 * Builds on Kids Bedtime, Spoiler Shield, Guest (2h), and Familien-Profile ceilings.
 */
object KidsFortressPolicy {

    const val DEFAULT_MAX_AGE = 12
    const val MIN_MAX_AGE = 0
    const val MAX_MAX_AGE = 18

    enum class SessionKind {
        ADULT,
        KIDS,
        GUEST,
    }

    data class KidsProfileCard(
        val profileId: String,
        val displayName: String,
        val maxAgeRating: Int,
        val hasPin: Boolean,
        val bedtimeEnabled: Boolean,
        val bedtimeLocked: Boolean,
        val bedtimeMinuteOfDay: Int,
        val continueCount: Int,
        val downloadCount: Int,
        val downloadBytes: Long,
        val spoilerForced: Boolean = true,
    )

    fun sessionKind(isKids: Boolean, isGuest: Boolean): SessionKind = when {
        isGuest -> SessionKind.GUEST
        isKids -> SessionKind.KIDS
        else -> SessionKind.ADULT
    }

    /** Guest shells can never become kids profiles. */
    fun canEnableKids(isGuest: Boolean): Boolean = !isGuest

    /**
     * Parent Dashboard is phone-only and only when the active session is a
     * non-kids, non-guest adult (so parents aren't configuring fortress from inside it).
     */
    fun parentDashboardEligible(
        isKidsActive: Boolean,
        isGuestActive: Boolean,
    ): Boolean = !isKidsActive && !isGuestActive

    /**
     * Kids get their own Downloads tab (profile-isolated DB + folder).
     * Always visible under Fortress — never hide the tab for kids sessions.
     */
    @Suppress("UNUSED_PARAMETER")
    fun downloadsNavVisible(isKids: Boolean): Boolean = true

    fun liveHubAllowed(isKids: Boolean): Boolean = !isKids

    fun spoilerForced(isKids: Boolean): Boolean = isKids

    fun ageFilteringActive(isKids: Boolean, parentalControlActive: Boolean): Boolean =
        isKids || parentalControlActive

    /**
     * Effective age ceiling for a session.
     * Kids → profile ceiling (default 12); adult parental → [parentalMaxAge]; else null.
     */
    fun effectiveCeiling(
        isKids: Boolean,
        profileMaxAge: Int?,
        parentalMaxAge: Int?,
        defaultKidsAge: Int = DEFAULT_MAX_AGE,
    ): Int? {
        if (isKids) {
            return (profileMaxAge ?: defaultKidsAge).coerceIn(MIN_MAX_AGE, MAX_MAX_AGE)
        }
        return parentalMaxAge?.coerceIn(MIN_MAX_AGE, MAX_MAX_AGE)
    }

    fun clampMaxAge(age: Int): Int = age.coerceIn(MIN_MAX_AGE, MAX_MAX_AGE)

    fun bedtimeApplies(enabled: Boolean, isKids: Boolean): Boolean = enabled && isKids

    /**
     * Leaving a kids (or ceiling) session requires a PIN when household or
     * profile PIN exists.
     */
    fun exitChallengeRequired(
        leavingRestricted: Boolean,
        hasHouseholdPin: Boolean,
        hasProfilePin: Boolean,
    ): Boolean = leavingRestricted && (hasHouseholdPin || hasProfilePin)

    /** Kids without any exit PIN must not silently unlock. */
    fun kidsUnlockAllowed(hasHouseholdPin: Boolean, hasProfilePin: Boolean): Boolean =
        hasHouseholdPin || hasProfilePin

    fun hideUnratedForKids(isKids: Boolean): Boolean = isKids

    /**
     * Summary line for a kids card on the parent dashboard (pure formatting).
     */
    fun formatCardSummary(
        maxAge: Int,
        hasPin: Boolean,
        bedtimeEnabled: Boolean,
        bedtimeLocked: Boolean,
        continueCount: Int,
        downloadCount: Int,
        pinLabel: String,
        noPinLabel: String,
        bedtimeOnLabel: String,
        bedtimeOffLabel: String,
        bedtimeLockedLabel: String,
        continueLabel: String,
        downloadsLabel: String,
    ): String {
        val pin = if (hasPin) pinLabel else noPinLabel
        val bed = when {
            bedtimeLocked -> bedtimeLockedLabel
            bedtimeEnabled -> bedtimeOnLabel
            else -> bedtimeOffLabel
        }
        return "≤$maxAge · $pin · $bed · $continueLabel $continueCount · $downloadsLabel $downloadCount"
    }

    fun fortressHealthy(
        isKids: Boolean,
        hasExitPin: Boolean,
        ceiling: Int?,
    ): Boolean {
        if (!isKids) return true
        if (!hasExitPin) return false
        if (ceiling == null) return false
        return ceiling in MIN_MAX_AGE..MAX_MAX_AGE
    }
}
