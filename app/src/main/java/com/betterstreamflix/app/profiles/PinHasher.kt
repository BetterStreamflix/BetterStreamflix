package com.betterstreamflix.app.profiles

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Shared PIN hashing for profile PINs and parental/admin PINs.
 *
 * New hashes use PBKDF2-HMAC-SHA256 with a version prefix (`v2:`).
 * [verify] still accepts legacy SHA-256 (`pin + saltScope` hex) for migration.
 */
object PinHasher {

    const val V2_PREFIX = "v2:"
    private const val PBKDF2_ITERATIONS = 10_000
    private const val PBKDF2_KEY_BITS = 256

    fun hash(pin: String, saltScope: String): String {
        val salt = saltScope.toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(spec).encoded
        return V2_PREFIX + bytes.toHex()
    }

    fun verify(pin: String, stored: String, saltScope: String): Boolean {
        if (stored.isEmpty()) return false
        if (stored.startsWith(V2_PREFIX)) {
            return stored == hash(pin, saltScope)
        }
        return stored == legacySha256(pin, saltScope)
    }

    /** Old plaintext parental PINs were stored as 4–8 digit strings. */
    fun looksLikePlaintextPin(value: String): Boolean =
        value.length in 4..8 && value.all { it.isDigit() }

    fun legacySha256(pin: String, saltScope: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$pin$saltScope".toByteArray(Charsets.UTF_8))
            .toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
