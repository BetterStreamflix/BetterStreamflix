package com.dskja.betterstreamflix.profiles

import android.content.Context
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.ui.UserDataNotifier
import com.dskja.betterstreamflix.utils.ProviderChangeNotifier
import com.dskja.betterstreamflix.utils.UserDataCache
import com.dskja.betterstreamflix.utils.UserPreferences
import java.security.MessageDigest
import java.util.UUID

object ProfileManager {

    const val DEFAULT_PROFILE_ID = ProfileStore.DEFAULT_PROFILE_ID
    const val KIDS_DEFAULT_MAX_AGE = 12
    const val PIN_MIN_LENGTH = 4
    const val PIN_MAX_LENGTH = 8
    const val PIN_MAX_ATTEMPTS = 5
    const val PIN_LOCKOUT_MS = 30_000L

    val avatarKeys = listOf(
        "copper",
        "violet",
        "moss",
        "ink",
        "ember",
        "aurora",
        "forest",
        "ocean",
        "amber",
        "plum",
        "steel",
        "rose",
        "crimson",
        "slate",
        "gold",
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
        ProfileStore.ensureDefaultExists(appContext)
        applyKidsParentalDefaults(activeProfile())
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

    fun profiles(): List<UserProfile> {
        if (!::appContext.isInitialized) return emptyList()
        return ProfileStore.loadAll(appContext)
    }

    fun profilesForPicker(): List<UserProfile> {
        val active = activeProfileId
        return profiles().sortedWith(
            compareBy<UserProfile> { it.id != active }
                .thenByDescending { it.lastUsedAtMillis }
                .thenBy { it.displayName.lowercase() },
        )
    }

    fun create(
        name: String,
        isKids: Boolean = false,
        avatarKey: String = avatarKeys.first(),
        atmosphereKey: String = ProfileAtmosphere.DEFAULT,
        greetingName: String? = null,
    ): UserProfile {
        require(::appContext.isInitialized) { "ProfileManager.init() must be called first" }
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "Profile name cannot be empty" }
        val resolvedAvatar = if (avatarKey in avatarKeys) avatarKey else avatarKeys.first()

        val now = System.currentTimeMillis()
        val profile = UserProfile(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            displayName = trimmedName,
            avatarKey = resolvedAvatar,
            isKids = isKids,
            maxAgeRating = if (isKids) KIDS_DEFAULT_MAX_AGE else null,
            createdAtMillis = now,
            updatedAtMillis = now,
            atmosphereKey = ProfileAtmosphere.normalize(atmosphereKey),
            greetingName = greetingName?.trim()?.takeIf { it.isNotEmpty() },
        )
        val updated = profiles() + profile
        ProfileStore.saveAll(appContext, updated)
        return profile
    }

    fun duplicate(id: String): UserProfile? {
        require(::appContext.isInitialized) { "ProfileManager.init() must be called first" }
        val source = profiles().find { it.id == id } ?: return null
        val copy = create(
            name = uniqueCopyName(source.displayName),
            isKids = source.isKids,
            avatarKey = nextUnusedAvatar(source.avatarKey),
            atmosphereKey = source.safeAtmosphere(),
            greetingName = source.greetingName,
        )
        updateProfile(copy.id) {
            it.copy(
                accentColorArgb = source.accentColorArgb,
                maxAgeRating = source.maxAgeRating,
                notes = source.notes,
                enabledIntegrations = source.safeIntegrations(),
                autoLockMinutes = source.autoLockMinutes,
            )
        }
        return profiles().find { it.id == copy.id } ?: copy
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

        if (activeProfileId == id) {
            val fallback = updated.firstOrNull { it.id == DEFAULT_PROFILE_ID } ?: updated.first()
            switchTo(appContext, fallback.id)
        }
        return true
    }

    fun switchTo(context: Context, id: String): Boolean {
        require(::appContext.isInitialized) { "ProfileManager.init() must be called first" }
        val profile = profiles().find { it.id == id } ?: return false
        if (activeProfileId == id) {
            applyKidsParentalDefaults(profile)
            touchLastUsed(profile.id)
            return true
        }

        ProfileStore.setActiveId(context.applicationContext, profile.id)
        touchLastUsed(profile.id)
        AppDatabase.resetInstance()
        UserDataCache.clearMemory()
        applyKidsParentalDefaults(profile)
        UserDataNotifier.notifyChanged()
        ProviderChangeNotifier.notifyProviderChanged()
        return true
    }

    fun setPin(profileId: String, pin: String): Boolean {
        require(::appContext.isInitialized) { "ProfileManager.init() must be called first" }
        if (pin.isEmpty()) return false
        if (pin.length !in PIN_MIN_LENGTH..PIN_MAX_LENGTH || pin.any { !it.isDigit() }) return false
        return updateProfile(profileId) {
            it.copy(
                pinHash = hashPin(pin, profileId),
                pinFailedAttempts = 0,
                pinLockedUntilMillis = 0L,
            )
        }
    }

    fun clearPin(profileId: String): Boolean =
        updateProfile(profileId) {
            it.copy(pinHash = null, pinFailedAttempts = 0, pinLockedUntilMillis = 0L)
        }

    fun updateAvatar(profileId: String, avatarKey: String): Boolean {
        val resolved = if (avatarKey in avatarKeys) avatarKey else return false
        return updateProfile(profileId) { it.copy(avatarKey = resolved) }
    }

    fun updateAtmosphere(profileId: String, atmosphereKey: String): Boolean =
        updateProfile(profileId) { it.copy(atmosphereKey = ProfileAtmosphere.normalize(atmosphereKey)) }

    fun updateGreetingName(profileId: String, greetingName: String?): Boolean =
        updateProfile(profileId) {
            it.copy(greetingName = greetingName?.trim()?.takeIf { name -> name.isNotEmpty() })
        }

    fun updateAccentColor(profileId: String, argb: Int?): Boolean =
        updateProfile(profileId) { it.copy(accentColorArgb = argb) }

    fun updateKids(profileId: String, isKids: Boolean): Boolean {
        val ok = updateProfile(profileId) {
            it.copy(
                isKids = isKids,
                maxAgeRating = if (isKids) (it.maxAgeRating ?: KIDS_DEFAULT_MAX_AGE) else null,
                atmosphereKey = if (isKids && it.atmosphereKey == ProfileAtmosphere.DEFAULT) {
                    "kids"
                } else {
                    it.atmosphereKey
                },
            )
        }
        if (ok && profileId == activeProfileId) {
            applyKidsParentalDefaults(profiles().find { it.id == profileId })
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
    fun isIntegrationEnabled(profile: UserProfile, integration: String): Boolean {
        val enabled = profile.safeIntegrations()
        return enabled.isEmpty() || enabled.contains(integration)
    }

    fun setIntegrationEnabled(profileId: String, integration: String, enabled: Boolean): Boolean {
        val profile = profiles().find { it.id == profileId } ?: return false
        val current = if (profile.safeIntegrations().isEmpty()) {
            UserProfile.Integration.ALL
        } else {
            profile.safeIntegrations()
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

    fun isPinLocked(profileId: String): Boolean {
        val profile = profiles().find { it.id == profileId } ?: return false
        return profile.pinLockedUntilMillis > System.currentTimeMillis()
    }

    fun pinLockRemainingMillis(profileId: String): Long {
        val profile = profiles().find { it.id == profileId } ?: return 0L
        return (profile.pinLockedUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun verifyPin(profileId: String, pin: String): Boolean {
        val profile = profiles().find { it.id == profileId } ?: return false
        val stored = profile.pinHash ?: return false
        if (isPinLocked(profileId)) return false
        if (stored == hashPin(pin, profileId)) {
            updateProfile(profileId) { it.copy(pinFailedAttempts = 0, pinLockedUntilMillis = 0L) }
            return true
        }
        val attempts = profile.pinFailedAttempts + 1
        val lockUntil = if (attempts >= PIN_MAX_ATTEMPTS) {
            System.currentTimeMillis() + PIN_LOCKOUT_MS
        } else {
            0L
        }
        updateProfile(profileId) {
            it.copy(pinFailedAttempts = attempts, pinLockedUntilMillis = lockUntil)
        }
        return false
    }

    fun scopedPrefKey(base: String): String = scopedPrefKeyFor(base, activeProfileId)

    internal fun scopedPrefKeyFor(base: String, profileId: String): String =
        if (profileId == DEFAULT_PROFILE_ID) base else "${base}_p_$profileId"

    internal fun hashPin(pin: String, profileId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$pin$profileId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun uniqueCopyName(base: String): String {
        val names = profiles().map { it.displayName.lowercase() }.toSet()
        val stem = base.trim().ifEmpty { "Profile" }
        var n = 2
        var candidate = "$stem ($n)"
        while (candidate.lowercase() in names) {
            n++
            candidate = "$stem ($n)"
        }
        return candidate
    }

    private fun nextUnusedAvatar(preferred: String): String {
        val used = profiles().map { it.avatarKey }.toSet()
        return avatarKeys.firstOrNull { it !in used } ?: preferred
    }

    private fun touchLastUsed(profileId: String) {
        val now = System.currentTimeMillis()
        updateProfile(profileId) { it.copy(lastUsedAtMillis = now) }
    }

    /**
     * Kids profiles enforce a content ceiling without requiring the parental PIN flow.
     * Adult profiles leave parental prefs untouched.
     */
    private fun applyKidsParentalDefaults(profile: UserProfile?) {
        if (profile == null) return
        if (profile.isKids) {
            val ceiling = profile.maxAgeRating ?: KIDS_DEFAULT_MAX_AGE
            if (UserPreferences.parentalControlMaxAge == null ||
                (UserPreferences.parentalControlMaxAge ?: 99) > ceiling
            ) {
                UserPreferences.parentalControlMaxAge = ceiling
            }
        }
    }

    /** Delete Room DB files and cached userdata for a removed profile. */
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
