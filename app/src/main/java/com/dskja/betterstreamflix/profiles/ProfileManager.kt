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
import java.security.MessageDigest
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
        "LIBRARY_SCOPE",
        "PARENTAL_CONTROL_PIN",
        "PARENTAL_CONTROL_ADMIN_PIN",
        "PARENTAL_CONTROL_MAX_AGE",
        "PARENTAL_CONTROL_FAILED_ATTEMPTS",
        "PARENTAL_CONTROL_LOCKED_UNTIL",
        "PARENTAL_CONTROL_HARD_LOCKED",
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

    val activeProfileId: String
        get() = if (::appContext.isInitialized) {
            ProfileStore.getActiveId(appContext) ?: DEFAULT_PROFILE_ID
        } else {
            DEFAULT_PROFILE_ID
        }

    fun init(context: Context) {
        appContext = context.applicationContext
        // Never throw out of init — corrupted prefs / Gson failures must not brick startup.
        runCatching { ProfileStore.ensureDefaultExists(appContext) }
        runCatching { applyKidsParentalDefaults(activeProfile(), previous = null) }
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
            switchTo(appContext, fallback.id)
        }
        return true
    }

    fun switchTo(context: Context, id: String): Boolean {
        require(::appContext.isInitialized) { "ProfileManager.init() must be called first" }
        val profile = profiles().find { it.id == id } ?: return false
        val previous = activeProfile()
        if (activeProfileId == id) {
            applyKidsParentalDefaults(profile, previous)
            ProfileUnlockGate.markUnlocked(profile.id)
            return true
        }

        ProfileStore.setActiveId(context.applicationContext, profile.id)
        touchLastUsed(profile.id)
        AppDatabase.resetInstance()
        UserDataCache.clearMemory()
        applyKidsParentalDefaults(profile, previous)
        ProfileUnlockGate.markUnlocked(profile.id)
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
        val ok = updateProfile(profileId) {
            it.copy(
                isKids = isKids,
                maxAgeRating = if (isKids) (it.maxAgeRating ?: KIDS_DEFAULT_MAX_AGE) else null,
            )
        }
        if (ok && profileId == activeProfileId) {
            applyKidsParentalDefaults(profiles().find { it.id == profileId }, previous = null)
        }
        return ok
    }

    fun updateMaxAgeRating(profileId: String, maxAge: Int?): Boolean {
        val clamped = maxAge?.coerceIn(0, 18)
        return updateProfile(profileId) { it.copy(maxAgeRating = clamped) }
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
        return stored == hashPin(pin, profileId)
    }

    fun scopedPrefKey(base: String): String = scopedPrefKeyFor(base, activeProfileId)

    internal fun scopedPrefKeyFor(base: String, profileId: String): String =
        if (profileId == DEFAULT_PROFILE_ID) base else "${base}_p_$profileId"

    internal fun hashPin(pin: String, profileId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$pin$profileId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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
        if (profile.isKids) {
            val ceiling = profile.maxAgeRating ?: KIDS_DEFAULT_MAX_AGE
            if (UserPreferences.parentalControlMaxAge == null ||
                (UserPreferences.parentalControlMaxAge ?: 99) > ceiling
            ) {
                UserPreferences.parentalControlMaxAge = ceiling
            }
        } else if (previous?.isKids == true) {
            val kidsCeiling = previous.maxAgeRating ?: KIDS_DEFAULT_MAX_AGE
            if (UserPreferences.parentalControlMaxAge == kidsCeiling) {
                UserPreferences.parentalControlMaxAge = null
            }
        }
    }

    /** Delete Room DB files, scoped prefs, and cached userdata for a removed profile. */
    private fun wipeProfileLocalData(profileId: String) {
        if (profileId == DEFAULT_PROFILE_ID) return
        runCatching {
            AppDatabase.resetInstance()
            val dir = appContext.getDatabasePath("placeholder").parentFile ?: return@runCatching
            dir.listFiles()
                ?.filter { it.name.startsWith("${profileId}__") || it.name.contains("${profileId}__") }
                ?.forEach { file ->
                    runCatching { file.delete() }
                    runCatching { appContext.deleteDatabase(file.name) }
                }
            UserDataCache.clearMemory()
            UserDataCache.clearForProfile(appContext, profileId)
            wipeScopedPreferences(profileId)
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
