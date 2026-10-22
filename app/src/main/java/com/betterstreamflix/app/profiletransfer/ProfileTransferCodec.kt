package com.betterstreamflix.app.profiletransfer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure file JSON codec for [ProfileTransferPayload] (JVM-safe unit tests).
 */
object ProfileTransferCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
        prettyPrint = true
    }

    private val compactJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
        prettyPrint = false
    }

    /** Device-independent salt — deters casual forged imports; not a secret bound to hardware. */
    private const val INTEGRITY_SALT = "bsf.profile.transfer.v2"

    fun encodeFileJson(payload: ProfileTransferPayload): String {
        require(payload.v == ProfileTransferPayload.VERSION)
        require(payload.kind == ProfileTransferPayload.KIND)
        val signed = payload.copy(integrity = computeIntegrity(payload.copy(integrity = null)))
        return json.encodeToString(signed)
    }

    fun encodeCompactJson(payload: ProfileTransferPayload): String {
        require(payload.v == ProfileTransferPayload.VERSION)
        require(payload.kind == ProfileTransferPayload.KIND)
        val signed = payload.copy(integrity = computeIntegrity(payload.copy(integrity = null)))
        return compactJson.encodeToString(signed)
    }

    fun decodeFileJson(text: String?): ProfileTransferPayload? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        // Allow QR URI pasted into file import
        ProfileTransferLinks.parse(trimmed)?.let { return it }
        val payload = runCatching {
            compactJson.decodeFromString<ProfileTransferPayload>(trimmed)
        }.getOrNull() ?: return null
        if (payload.kind != ProfileTransferPayload.KIND) return null
        if (payload.v != ProfileTransferPayload.VERSION &&
            payload.v != ProfileTransferPayload.VERSION_LEGACY
        ) {
            return null
        }
        if (payload.v == ProfileTransferPayload.VERSION) {
            val expected = payload.integrity?.takeIf { it.isNotBlank() } ?: return null
            val actual = computeIntegrity(payload.copy(integrity = null))
            if (!expected.equals(actual, ignoreCase = true)) return null
        }
        return payload
    }

    internal fun computeIntegrity(payload: ProfileTransferPayload): String {
        val body = compactJson.encodeToString(payload.copy(integrity = null))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(INTEGRITY_SALT.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
