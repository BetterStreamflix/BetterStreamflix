package com.dskja.betterstreamflix.profiles

import android.content.Context
import androidx.core.content.edit
import com.dskja.betterstreamflix.BuildConfig
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.sync.CloudSyncManager
import com.dskja.betterstreamflix.ui.UserDataNotifier
import com.dskja.betterstreamflix.utils.ProviderChangeNotifier
import com.dskja.betterstreamflix.utils.UserDataCache
import com.dskja.betterstreamflix.utils.UserPreferences
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object ProfileManager {

    const val DEFAULT_PROFILE_ID = ProfileStore.DEFAULT_PROFILE_ID
    const val KIDS_DEFAULT_MAX_AGE = 12

    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val SCOPED_PREF_BASES = listOf(
        "SHOW_CONTINUE_WATCHING",
        "SHOW_RECENTLY_WATCHED",
        "SHOW_NEXT_EPISODES",
        "SHOW_SMART_COLLECTIONS",
        "HOME_SHELF_BLOCK_ORDER",
        "SERIES_FAST_FORWARD_ENABLED",
        "HOME_LAST_VISIT_AT_MS",
        "HOME_VISIT_NEXT_EPISODE_IDS",
        "HOME_VISIT_FAVORITE_TV_IDS",
        "KIDS_BEDTIME_ENABLED",
        "KIDS_BEDTIME_MINUTE_OF_DAY",
        "KIDS_BEDTIME_LOCKED",
        "KIDS_BEDTIME_BYPASS_UNTIL_MS",
        "LIBRARY_SCOPE",
        "PARENTAL_CONTROL_PIN",
        "PARENTAL_CONTROL_ADMIN_PIN",
        "PARENTAL_CONTROL_MAX_AGE",
        "PARENTAL_CONTROL_FAILED_ATTEMPTS",
        "PARENTAL_CONTROL_LOCKED_UNTIL",
        "PARENTAL_CONTROL_HARD_LOCKED",
        "SELECTED_THEME",
    )

    val avatarKeys = listOf(
        "crimson",
        "ember",
        "aurora",
        "slate",
        "forest",
        "ocean",
        "gold",
        "rose",
        "violet",
        "mint",
        "indigo",
        "copper",
        "arctic",
        "sunset",
        "neon",
        "cobalt",
        "magenta",
        "charcoal",
        "teal",
        "peach",
    )

    private lateinit var appContext: Context

    /**
     * True when [init] could not ensure a usable profile store. Callers should
     * force Who's Watching / repair UI rather than skipping the PIN/kids gate.
     */
    @Volatile
    var initFailed: Boolean = false
        private set

    val activeProfileId: String
        get() = if (::appContext.isInitialized) {
            ProfileStore.getActiveId(appContext) ?: DEFAULT_PROFILE_ID
        } else {
            DEFAULT_PROFILE_ID
        }

    fun init(context: Context) {
        appContext = context.applicationContext
        // Never throw out of init — corrupted prefs / Gson failures must not brick startup.
        val ensured =         runCatching { ProfileStore.ensureDefaultExists(appContext) }
        initFailed = ensured.isFailure
        if (initFailed) {
            android.util.Log.e("ProfileManager", "ProfileStore.ensureDefaultExists failed", ensured.exceptionOrNull())
        }
        runCatching { applyKidsParentalDefaults(activeProfile(), previous = null) }
        // Drop expired guest shells early so Home never mounts their CW/history.
        runCatching { sweepExpiredGuests(appContext) }
    }

    fun isGuestActive(): Boolean = activeProfile()?.isGuest == true

    fun findGuest(): UserProfile? = profiles().firstOrNull { it.isGuest }

    /**
     * Creates or reuses the temporary Guest profile (one at a time).
     * Fresh shell when none exists or the previous guest already expired.
     */
    fun createGuest(displayName: String = GuestProfilePolicy.DEFAULT_DISPLAY_NAME): UserProfile {
        require(::appContext.isInitialized) { "ProfileManager.init() must be called first" }
        sweepExpiredGuests(appContext)
        findGuest()?.let { return it }

        val now = System.currentTimeMillis()
        val name = displayName.trim().ifEmpty { GuestProfilePolicy.DEFAULT_DISPLAY_NAME }
        val avatar = if (GuestProfilePolicy.AVATAR_KEY in avatarKeys) {
            GuestProfilePolicy.AVATAR_KEY
        } else {
            avatarKeys.first()
        }
        val profile = UserProfile(
            id = "guest_" + UUID.randomUUID().toString().replace("-", "").take(8),
            displayName = name,
            avatarKey = avatar,
            isKids = false,
            isGuest = true,
            expiresAtMillis = GuestProfilePolicy.expiresAtMillis(now),
            createdAtMillis = now,
            updatedAtMillis = now,
            enabledIntegrations = emptySet(),
        )
        ProfileStore.saveAll(appContext, profiles() + profile)
        applyDefaultThemeForProfile(profile)
        return profile
    }

    /**
     * Ends the guest early. Profile-local wipe inside [delete] removes Continue/History
     * and switches back to Default when Guest was active.
     */
    fun endGuest(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
        require(::appContext.isInitialized) { "ProfileManager.init() must be called first" }
        val guest = findGuest() ?: return false
        return delete(guest.id)
    }

    /**
     * Removes every expired guest. Profile-local Room/cache wipe inside [delete]
     * auto-deletes Continue/History; an active guest switches back to Default.
     * @return number of guests removed
     */
    fun sweepExpiredGuests(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int {
        if (!::appContext.isInitialized) return 0
        val expired = profiles().filter { GuestProfilePolicy.isExpired(it, nowMillis) }
        if (expired.isEmpty()) return 0
        var removed = 0
        expired.forEach { guest ->
            if (guest.isGuest && delete(guest.id)) removed++
        }
        return removed
    }

    fun activeProfile(): UserProfile? =
        profiles().find { it.id == activeProfileId }

    /** Effective content age ceiling: kids profile rating or parental-control setting. */
    fun effectiveMaxAgeRating(): Int? {
        val profile = activeProfile()
        if (profile?.isKids == true) {
            return profile.maxAgeRating ?: KIDS_DEFAULT_MAX_AGE
        }
        return UserPreferences.parentalControlMaxAge
    }

    fun isKidsActive(): Boolean = activeProfile()?.isKids == true

    /** Profile enforces a content age ceiling (kids and/or explicit max age). */
    fun hasParentalCeiling(profile: UserProfile?): Boolean {
        if (profile == null) return false
        return profile.isKids || profile.maxAgeRating != null
    }

    /**
     * Switching away from a parental-locked profile (kids / parental max-age) to a
     * profile without a ceiling requires verifying the parental PIN (or the active
     * profile PIN as fallback).
     */
    fun requiresParentalExitChallenge(target: UserProfile): Boolean {
        if (hasParentalCeiling(target)) return false
        val active = activeProfile()
        val leavingRestricted = hasParentalCeiling(active) || UserPreferences.isParentalControlActive
        if (!leavingRestricted) return false
        return UserPreferences.parentalControlPin.isNotBlank() || active?.pinHash != null
    }

    fun profiles(): List<UserProfile> {
        if (!::appContext.isInitialized) return emptyList()
        return ProfileStore.loadAll(appContext)
    }

    fun create(
        name: String,
        isKids: Boolean = false,
        avatarKey: String = avatarKeys.first(),
    ): UserProfile {
        require(::appContext.isInitialized) { "ProfileManager.init() must be called first" }
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "Profile name cannot be empty" }
        require(avatarKey in avatarKeys) { "Unknown avatar key: $avatarKey" }

        val now = System.currentTimeMillis()
        val profile = UserProfile(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            displayName = trimmedName,
            avatarKey = avatarKey,
            isKids = isKids,
            maxAgeRating = if (isKids) KIDS_DEFAULT_MAX_AGE else null,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        val updated = profiles() + profile
        ProfileStore.saveAll(appContext, updated)
        applyDefaultThemeForProfile(profile)
        return profile
    }

    fun rename(id: String, newName: String): Boolean {
        require(::appContext.isInitialized) { "ProfileManager.init() must be called first" }
        val trimmedName = newName.trim()
        if (trimmedName.isEmpty()) return false

        val current = profiles()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return false

        val now = System.currentTimeMillis()
        val updated = current.toMutableList()
        updated[index] = updated[index].copy(
            displayName = trimmedName,
            updatedAtMillis = now,
        )
        ProfileStore.saveAll(appContext, updated)
        return true
    }

    fun delete(id: String): Boolean {
        require(::appContext.isInitialized) { "ProfileManager.init() must be called first" }
        val current = profiles()
        if (current.size <= 1) return false
        if (current.none { it.id == id }) return false

        val updated = current.filterNot { it.id == id }
        if (updated.isEmpty()) return false
        ProfileStore.saveAll(appContext, updated)
        wipeProfileLocalData(id)
        lifecycleScope.launch {
            runCatching { CloudSyncManager.onProfileDeleted(appContext, id) }
        }

        if (activeProfileId == id) {
            val fallback = updated.firstOrNull { it.id == DEFAULT_PROFILE_ID } ?: updated.first()
            val fallbackNeedsGate = fallback.pinHash != null || fallback.isKids
            switchTo(appContext, fallback.id, markUnlocked = !fallbackNeedsGate)
        }
        return true
    }

    fun switchTo(context: Context, id: String, markUnlocked: Boolean = true): Boolean {
        require(::appContext.isInitialized) { "ProfileManager.init() must be called first" }
        val profile = profiles().find { it.id == id } ?: return false
        val previous = activeProfile()
        if (activeProfileId == id) {
            applyKidsParentalDefaults(profile, previous)
            if (markUnlocked) ProfileUnlockGate.markUnlocked(profile.id)
            return true
        }

        ProfileStore.setActiveId(context.applicationContext, profile.id)
        touchLastUsed(profile.id)
        AppDatabase.resetInstance()
        UserDataCache.clearMemory()
        applyKidsParentalDefaults(profile, previous)
        if (markUnlocked) ProfileUnlockGate.markUnlocked(profile.id)
        UserDataNotifier.notifyChanged()
        ProviderChangeNotifier.notifyProviderChanged()
        lifecycleScope.launch {
            runCatching { CloudSyncManager.onProfileChanged(appContext, profile.id) }
        }
        return true
    }

    fun setPin(profileId: String, pin: String): Boolean {
        require(::appContext.isInitialized) { "ProfileManager.init() must be called first" }
        if (pin.isEmpty()) return false
        if (pin.length !in 4..8 || pin.any { !it.isDigit() }) return false
        return updateProfile(profileId) { it.copy(pinHash = hashPin(pin, profileId)) }
    }

    fun clearPin(profileId: String): Boolean =
        updateProfile(profileId) { it.copy(pinHash = null) }

    fun updateAvatar(profileId: String, avatarKey: String): Boolean {
        require(avatarKey in avatarKeys) { "Unknown avatar key: $avatarKey" }
        return updateProfile(profileId) { it.copy(avatarKey = avatarKey) }
    }

    fun updateKids(profileId: String, isKids: Boolean): Boolean {
        val target = profiles().find { it.id == profileId }
        // Guest shells stay adult-temporary — no kids ceiling.
        if (target?.isGuest == true && isKids) return false
        val previous = target
        val ok = updateProfile(profileId) {
            it.copy(
                isKids = isKids,
                maxAgeRating = if (isKids) (it.maxAgeRating ?: KIDS_DEFAULT_MAX_AGE) else null,
            )
        }
        if (ok) {
            val updated = profiles().find { it.id == profileId }
            if (updated != null && previous?.isKids != updated.isKids) {
                applyDefaultThemeForProfile(updated, force = true)
            }
            if (profileId == activeProfileId) {
                applyKidsParentalDefaults(updated, previous)
            }
        }
        return ok
    }

    fun updateMaxAgeRating(profileId: String, maxAge: Int?): Boolean {
        val previous = profiles().find { it.id == profileId }
        val clamped = maxAge?.coerceIn(0, 18)
        val ok = updateProfile(profileId) { it.copy(maxAgeRating = clamped) }
        if (ok && profileId == activeProfileId) {
            val updated = profiles().find { it.id == profileId }
            if (updated?.isKids == true) {
                // Keep the scoped parental pref aligned so Settings lists match the filter.
                UserPreferences.parentalControlMaxAge =
                    updated.maxAgeRating ?: KIDS_DEFAULT_MAX_AGE
            } else {
                applyKidsParentalDefaults(updated, previous)
            }
            UserDataNotifier.notifyChanged()
            ProviderChangeNotifier.notifyProviderChanged()
        }
        return ok
    }

    /**
     * Empty [UserProfile.enabledIntegrations] means all integrations are enabled (backward compatible).
     * A non-empty set lists only the integrations enabled for that profile.
     */
    fun isIntegrationEnabled(profile: UserProfile, integration: String): Boolean =
        profile.enabledIntegrations.isEmpty() || profile.enabledIntegrations.contains(integration)

    /** Gate for runtime Trakt/Simkl/Debrid/TMDb/etc. against the active profile. */
    fun isActiveIntegrationEnabled(integration: String): Boolean {
        val profile = activeProfile() ?: return true
        return isIntegrationEnabled(profile, integration)
    }

    fun setIntegrationEnabled(profileId: String, integration: String, enabled: Boolean): Boolean {
        val profile = profiles().find { it.id == profileId } ?: return false
        val current = if (profile.enabledIntegrations.isEmpty()) {
            UserProfile.Integration.ALL
        } else {
            profile.enabledIntegrations
        }
        val updated = if (enabled) current + integration else current - integration
        return setEnabledIntegrations(profileId, updated)
    }

    fun setEnabledIntegrations(profileId: String, enabled: Set<String>): Boolean {
        val stored = if (enabled.containsAll(UserProfile.Integration.ALL)) {
            emptySet()
        } else {
            enabled
        }
        return updateProfile(profileId) { it.copy(enabledIntegrations = stored) }
    }

    fun verifyPin(profileId: String, pin: String): Boolean {
        val profile = profiles().find { it.id == profileId } ?: return false
        val stored = profile.pinHash ?: return false
        // Prefer v2 PBKDF2; fall back to legacy SHA-256 for existing hashes.
        return PinHasher.verify(pin, stored, profileId)
    }

    fun scopedPrefKey(base: String): String = scopedPrefKeyFor(base, activeProfileId)

    internal fun scopedPrefKeyFor(base: String, profileId: String): String =
        if (profileId == DEFAULT_PROFILE_ID) base else "${base}_p_$profileId"

    /** Always writes a v2 PBKDF2 hash. Salt scope is [profileId] bytes. */
    internal fun hashPin(pin: String, profileId: String): String =
        PinHasher.hash(pin, profileId)

    /** Salt scopes for parental / admin PIN storage (profile-scoped prefs). */
    internal fun parentalPinSalt(profileId: String = activeProfileId): String =
        "parental_$profileId"

    internal fun adminPinSalt(profileId: String = activeProfileId): String =
        "admin_$profileId"

    private fun touchLastUsed(profileId: String) {
        updateProfile(profileId) { it.copy(updatedAtMillis = System.currentTimeMillis()) }
    }

    /**
     * Kids profiles enforce a content ceiling without requiring the parental PIN flow.
     * Leaving a kids profile lifts a ceiling that only matched that kids profile so adult
     * prefs are not stuck tightened forever.
     */
    private fun applyKidsParentalDefaults(profile: UserProfile?, previous: UserProfile?) {
        if (profile == null) return
        val ceiling = if (profile.isKids) {
            profile.maxAgeRating ?: KIDS_DEFAULT_MAX_AGE
        } else {
            previous?.maxAgeRating ?: KIDS_DEFAULT_MAX_AGE
        }
        val next = kidsCeilingToApply(
            becomingKids = profile.isKids,
            previousWasKids = previous?.isKids == true,
            currentMaxAge = UserPreferences.parentalControlMaxAge,
            kidsCeiling = ceiling,
        )
        if (next != UserPreferences.parentalControlMaxAge) {
            UserPreferences.parentalControlMaxAge = next
        }
        // Seed kids/adult shell themes when this profile has never picked one.
        applyDefaultThemeForProfile(profile, force = false)
    }

    /**
     * Kids → Cinema-Ink Kids, adult → Cinema-Ink Dark. With [force], overwrites when
     * the kids flag flips; otherwise only writes when the scoped theme key is missing.
     */
    private fun applyDefaultThemeForProfile(profile: UserProfile, force: Boolean = false) {
        if (!::appContext.isInitialized) return
        val prefs = appContext.getSharedPreferences(
            "${BuildConfig.APPLICATION_ID}.preferences",
            Context.MODE_PRIVATE,
        )
        val key = scopedPrefKeyFor("SELECTED_THEME", profile.id)
        if (!force && prefs.contains(key)) return
        // Active-profile writes go through UserPreferences so getters stay consistent.
        if (profile.id == activeProfileId) {
            UserPreferences.selectedTheme =
                com.dskja.betterstreamflix.utils.ThemeManager.defaultThemeForKids(profile.isKids)
        } else {
            prefs.edit {
                putString(
                    key,
                    com.dskja.betterstreamflix.utils.ThemeManager.defaultThemeForKids(profile.isKids),
                )
            }
        }
    }

    /**
     * Pure ceiling decision used by [applyKidsParentalDefaults].
     * Returns the age that should be stored, or null when the kids tighten should be lifted.
     */
    internal fun kidsCeilingToApply(
        becomingKids: Boolean,
        previousWasKids: Boolean,
        currentMaxAge: Int?,
        kidsCeiling: Int,
    ): Int? {
        if (becomingKids) {
            if (currentMaxAge == null || currentMaxAge > kidsCeiling) return kidsCeiling
            return currentMaxAge
        }
        if (previousWasKids && currentMaxAge == kidsCeiling) return null
        return currentMaxAge
    }

    /**
     * Delete Room DB files, scoped prefs, and cached userdata for a removed profile.
     * The default profile is also wiped (provider `*.db` files + userdata cache);
     * only identity prefs for default stay (no scoped `_p_` suffix to clear).
     */
    private fun wipeProfileLocalData(profileId: String) {
        runCatching {
            // Only drop the live connection when this profile is the one on screen.
            // Wiping a background profile must not close the active database.
            if (profileId == activeProfileId) {
                AppDatabase.resetInstance()
                UserDataCache.clearMemory()
            }
            val dir = appContext.getDatabasePath("placeholder").parentFile ?: return@runCatching
            dir.listFiles()
                ?.filter { file ->
                    when {
                        profileId == DEFAULT_PROFILE_ID ->
                            file.name.endsWith(".db") &&
                                !file.name.contains("__") &&
                                !file.name.startsWith("downloads")
                        else ->
                            file.name.startsWith("${profileId}__") ||
                                file.name.contains("${profileId}__") ||
                                file.name.startsWith("downloads__${profileId}")
                    }
                }
                ?.forEach { file ->
                    runCatching { file.delete() }
                    runCatching { appContext.deleteDatabase(file.name) }
                }
            UserDataCache.clearForProfile(appContext, profileId)
            if (profileId != DEFAULT_PROFILE_ID) {
                wipeScopedPreferences(profileId)
            }
            runCatching {
                appContext.deleteSharedPreferences(
                    scopedPrefKeyFor("shared_watchlist_pending", profileId),
                )
            }
            runCatching {
                appContext.deleteSharedPreferences(
                    scopedPrefKeyFor("watch_party_pending", profileId),
                )
            }
            runCatching {
                com.dskja.betterstreamflix.utils.EpisodeNotesStore.wipeProfile(appContext, profileId)
            }
            runCatching {
                val folder = com.dskja.betterstreamflix.download.DownloadStorage
                    .profileDownloadsDir(appContext, profileId)
                com.dskja.betterstreamflix.download.DownloadStorage.deleteQuietly(folder)
            }
        }
    }

    private fun wipeScopedPreferences(profileId: String) {
        val prefs = appContext.getSharedPreferences(
            "${BuildConfig.APPLICATION_ID}.preferences",
            Context.MODE_PRIVATE,
        )
        prefs.edit {
            SCOPED_PREF_BASES.forEach { base ->
                remove(scopedPrefKeyFor(base, profileId))
            }
            // Also drop any leftover `_p_<id>` keys for this profile.
            prefs.all.keys
                .filter { it.endsWith("_p_$profileId") }
                .forEach { remove(it) }
        }
    }

    private fun updateProfile(profileId: String, transform: (UserProfile) -> UserProfile): Boolean {
        require(::appContext.isInitialized) { "ProfileManager.init() must be called first" }
        val current = profiles()
        val index = current.indexOfFirst { it.id == profileId }
        if (index < 0) return false

        val now = System.currentTimeMillis()
        val updated = current.toMutableList()
        updated[index] = transform(updated[index]).copy(updatedAtMillis = now)
        ProfileStore.saveAll(appContext, updated)
        return true
    }
}
