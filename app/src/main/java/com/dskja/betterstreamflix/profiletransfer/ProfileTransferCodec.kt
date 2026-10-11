package com.dskja.betterstreamflix.profiletransfer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    fun encodeFileJson(payload: ProfileTransferPayload): String {
        require(payload.v == ProfileTransferPayload.VERSION)
        require(payload.kind == ProfileTransferPayload.KIND)
        return json.encodeToString(payload)
    }

    fun encodeCompactJson(payload: ProfileTransferPayload): String {
        require(payload.v == ProfileTransferPayload.VERSION)
        require(payload.kind == ProfileTransferPayload.KIND)
        return compactJson.encodeToString(payload)
    }

    fun decodeFileJson(text: String?): ProfileTransferPayload? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        // Allow QR URI pasted into file import
        ProfileTransferLinks.parse(trimmed)?.let { return it }
        val payload = runCatching {
            compactJson.decodeFromString<ProfileTransferPayload>(trimmed)
        }.getOrNull() ?: return null
        if (payload.v != ProfileTransferPayload.VERSION) return null
        if (payload.kind != ProfileTransferPayload.KIND) return null
        return payload
    }
}
