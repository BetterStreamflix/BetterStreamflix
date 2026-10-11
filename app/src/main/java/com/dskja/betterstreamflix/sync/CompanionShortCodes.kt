package com.dskja.betterstreamflix.sync

import java.security.SecureRandom

/**
 * Shared 6-char companion codes (Cast Room, Watch Party, Session, Remote Find, …).
 * Generator alphabet: A–Z / 2–9 without 0/1/O/I/L.
 *
 * Normalize: strip dashes/spaces/non-alphanumerics, uppercase, map ambiguous glyphs
 * into the generator alphabet (0/O→Q, 1/I/L→J), then require length 6.
 */
object CompanionShortCodes {
    const val ALPHABET: String = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val SHORT_CODE_PATTERN = Regex("^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$")
    private val secureRandom = SecureRandom()

    fun newShortCode(
        random: () -> Int = { secureRandom.nextInt(ALPHABET.length) },
    ): String =
        buildString(6) {
            repeat(6) { append(ALPHABET[random() % ALPHABET.length]) }
        }

    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = buildString(raw.length) {
            for (ch in raw) {
                when {
                    ch == '-' || ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' -> Unit
                    !ch.isLetterOrDigit() -> Unit
                    else -> {
                        val u = ch.uppercaseChar()
                        append(
                            when (u) {
                                '0', 'O' -> 'Q'
                                '1', 'I', 'L' -> 'J'
                                else -> u
                            },
                        )
                    }
                }
            }
        }
        return cleaned.takeIf { SHORT_CODE_PATTERN.matches(it) }
    }

    fun isValid(code: String?): Boolean = normalize(code) != null

    fun formatDisplay(code: String): String {
        val n = normalize(code) ?: return code
        return "${n.substring(0, 3)}-${n.substring(3)}"
    }

    /**
     * Mint a short code, retrying [block] when the caller reports a unique-key collision.
     * [block] should return null when the insert failed due to short_code uniqueness.
     */
    inline fun <T> mintUnique(maxAttempts: Int = 8, block: (code: String) -> T?): T {
        var lastError: Throwable? = null
        repeat(maxAttempts.coerceAtLeast(1)) {
            val code = newShortCode()
            try {
                val result = block(code)
                if (result != null) return result
            } catch (t: Throwable) {
                lastError = t
                val msg = t.message.orEmpty()
                val uniqueClash = msg.contains("short_code", ignoreCase = true) ||
                    msg.contains("unique", ignoreCase = true) ||
                    msg.contains("23505")
                if (!uniqueClash) throw t
            }
        }
        throw lastError ?: IllegalStateException("Could not mint a unique companion short code")
    }
}
