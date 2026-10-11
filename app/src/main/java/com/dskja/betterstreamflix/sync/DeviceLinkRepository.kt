package com.dskja.betterstreamflix.sync

import android.content.Context
import android.util.Log
import com.dskja.betterstreamflix.profiles.ProfileManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import java.time.Instant
import java.time.temporal.ChronoUnit

object DeviceLinkRepository {
    private const val TAG = "DeviceLink"
    private const val TABLE = "device_link_codes"
    private const val RPC_GET = "get_device_link_by_code"
    private const val RPC_CLAIM = "claim_device_link_code"
    private const val TOKEN_TTL_MINUTES = 15L

    data class OfferResult(
        val shortCode: String,
        val email: String,
        val expiresAt: String?,
    )

    data class LookupResult(
        val shortCode: String,
        val email: String,
        val expiresAt: String?,
    )

    suspend fun createOffer(context: Context): OfferResult {
        requireConfigured()
        rejectKids()
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.clientFor(appContext, profileId)
        client.auth.awaitInitialization()
        val session = client.auth.currentSessionOrNull()
            ?: error("Sign in to show a link code")
        val userId = session.user?.id ?: error("Sign in to show a link code")
        val email = session.user?.email?.trim().orEmpty()
        if (email.isEmpty() || !email.contains('@')) {
            error("Account email is required for Link-Code pairing")
        }

        expireOwnedActiveOffers(appContext, profileId, userId)

        val shortCode = DeviceLinkCodes.newShortCode()
        val row = RemoteDeviceLinkCode(
            shortCode = shortCode,
            hostUserId = userId,
            hostEmail = email,
            expiresAt = Instant.now().plus(TOKEN_TTL_MINUTES, ChronoUnit.MINUTES).toString(),
        )
        val inserted = client.from(TABLE)
            .insert(row) { select(Columns.ALL) }
            .decodeSingle<RemoteDeviceLinkCode>()

        return OfferResult(
            shortCode = inserted.shortCode,
            email = inserted.hostEmail,
            expiresAt = inserted.expiresAt,
        )
    }

    suspend fun lookupByCode(context: Context, code: String): LookupResult {
        requireConfigured()
        rejectKids()
        val cleaned = DeviceLinkCodes.normalize(code) ?: error("Invalid link code")
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId)
            ?: error("Configure Supabase before using Link-Code")
        client.auth.awaitInitialization()

        val payload = runCatching {
            client.postgrest.rpc(
                RPC_GET,
                DeviceLinkCodeParam(cleaned),
            ).decodeAs<DeviceLinkLookupPayload>()
        }.getOrNull()
        val email = payload?.email?.trim().orEmpty()
        if (email.isEmpty() || !email.contains('@')) {
            error("Link code not found or expired")
        }
        return LookupResult(
            shortCode = payload?.shortCode ?: cleaned,
            email = email,
            expiresAt = payload?.expiresAt,
        )
    }

    /**
     * Optional single-use mark after the claimer successfully signs in.
     * Best-effort — sign-in already succeeded if this fails.
     */
    suspend fun markClaimed(context: Context, code: String) {
        val cleaned = DeviceLinkCodes.normalize(code) ?: return
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val client = SupabaseProvider.initializeForProfile(appContext, profileId) ?: return
        client.auth.awaitInitialization()
        runCatching {
            client.postgrest.rpc(
                RPC_CLAIM,
                DeviceLinkCodeParam(cleaned),
            )
        }.onFailure { Log.w(TAG, "Could not mark device link claimed", it) }
    }

    private suspend fun expireOwnedActiveOffers(
        context: Context,
        profileId: String,
        userId: String,
    ) {
        val client = SupabaseProvider.clientFor(context, profileId)
        runCatching {
            client.from(TABLE)
                .update(
                    RemoteDeviceLinkClaimUpdate(claimedAt = Instant.now().toString()),
                ) {
                    filter { eq("host_user_id", userId) }
                }
        }.onFailure { Log.w(TAG, "Could not expire previous link codes", it) }
    }

    private fun requireConfigured() {
        if (!SupabaseProvider.isConfigured) {
            error("Configure Supabase before using Link-Code")
        }
    }

    private fun rejectKids() {
        if (ProfileManager.isKidsActive()) {
            throw KidsCloudAuthException()
        }
    }
}
