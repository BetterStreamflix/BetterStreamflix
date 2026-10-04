package com.dskja.betterstreamflix.sync

import android.content.Context
import android.net.Uri
import com.dskja.betterstreamflix.BetterStreamflixApp
import com.dskja.betterstreamflix.profiles.ProfileManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SupabaseProvider {
    private const val PREFS = "supabase_connection"
    private const val URL_KEY = "url"
    private const val PUBLIC_KEY = "public_key"
    private const val SESSION_KEY = "streamflix_supabase_session"
    private val clientsMutex = Mutex()

    private data class ProfileClient(
        val fingerprint: String,
        val client: SupabaseClient,
    )

    private val clients = mutableMapOf<String, ProfileClient>()

    @Volatile
    private var activeProfileId: String = ProfileManager.DEFAULT_PROFILE_ID

    val isConfigured: Boolean
        get() = readConfig(BetterStreamflixApp.instance)?.let { it.first.isNotEmpty() } == true

    /** Active profile's client — prefer [clientFor] when the profile is known. */
    val client: SupabaseClient
        get() = clientOrNull(activeProfileId)
            ?: error("Supabase has not been initialized for profile $activeProfileId")

    fun activeClientOrNull(): SupabaseClient? = clientOrNull(activeProfileId)

    fun clientOrNull(profileId: String): SupabaseClient? =
        clients[profileId]?.client

    fun configured(context: Context): Boolean = readConfig(context) != null

    fun getUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(URL_KEY, "").orEmpty()

    fun getPublicKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PUBLIC_KEY, "").orEmpty()

    fun saveConfig(context: Context, url: String, publicKey: String) {
        val normalizedUrl = normalizeUrl(url)
            ?: throw IllegalArgumentException("Enter a valid HTTPS Supabase URL")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(URL_KEY, normalizedUrl)
            .putString(PUBLIC_KEY, publicKey.trim())
            .apply()
        // Drop cached clients so the next initialize rebuilds against the new project.
        // close() is suspend — clearConfig handles orderly shutdown.
        clients.clear()
    }

    suspend fun initialize(context: Context) {
        initializeForProfile(context, ProfileManager.activeProfileId)
    }

    suspend fun initializeForProfile(context: Context, profileId: String): SupabaseClient? {
        val config = readConfig(context) ?: return null
        val fingerprint = config.first + "\u0000" + config.second
        clients[profileId]?.takeIf { it.fingerprint == fingerprint }?.let {
            activeProfileId = profileId
            return it.client
        }
        return clientsMutex.withLock {
            clients[profileId]?.takeIf { it.fingerprint == fingerprint }?.let {
                activeProfileId = profileId
                return@withLock it.client
            }
            // Drop a stale client for this profile (config fingerprint changed).
            clients.remove(profileId)?.client?.let { client -> try { client.close() } catch (_: Throwable) {} }
            createSupabaseClient(
                supabaseUrl = config.first,
                supabaseKey = config.second,
            ) {
                install(Auth) {
                    sessionManager = SettingsSessionManager(
                        key = sessionKey(profileId, fingerprint),
                    )
                }
                install(Postgrest)
                install(Realtime)
            }.also { created ->
                clients[profileId] = ProfileClient(fingerprint, created)
                activeProfileId = profileId
            }
        }
    }

    suspend fun clientFor(context: Context, profileId: String): SupabaseClient {
        return initializeForProfile(context, profileId)
            ?: error("Supabase is not configured")
    }

    suspend fun removeProfile(profileId: String) {
        clientsMutex.withLock {
            clients.remove(profileId)?.client?.let { client -> try { client.close() } catch (_: Throwable) {} }
            if (activeProfileId == profileId) {
                activeProfileId = ProfileManager.DEFAULT_PROFILE_ID
            }
        }
    }

    suspend fun clearConfig(context: Context) {
        clientsMutex.withLock {
            clients.values.forEach { entry -> try { entry.client.close() } catch (_: Throwable) {} }
            clients.clear()
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    /**
     * Default keeps the legacy unsuffixed session key so existing installs stay signed in.
     * Other profiles use `$legacy-$profileId`.
     */
    internal fun sessionKey(profileId: String, fingerprint: String): String {
        val legacy = "$SESSION_KEY-${fingerprint.hashCode()}"
        return if (profileId == ProfileManager.DEFAULT_PROFILE_ID) legacy else "$legacy-$profileId"
    }

    private fun readConfig(context: Context): Pair<String, String>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = normalizeUrl(prefs.getString(URL_KEY, null)) ?: return null
        val key = prefs.getString(PUBLIC_KEY, null)?.trim().orEmpty()
        if (key.isEmpty()) return null
        return url to key
    }

    private fun normalizeUrl(raw: String?): String? {
        val parsed = raw?.trim()?.let(Uri::parse) ?: return null
        if (parsed.scheme != "https" || parsed.host.isNullOrBlank()) return null
        return raw.trim().trimEnd('/')
    }
}
