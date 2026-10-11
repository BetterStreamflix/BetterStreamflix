package com.dskja.betterstreamflix.remotefind

/**
 * Short pairing codes for Remote Find Phone — A–Z / 2–9 (no 0/1/O/I/L confusion).
 */
object RemoteFindCode {
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val SHORT_CODE_PATTERN = Regex("^[A-Z2-9]{6}$")

    fun newShortCode(random: () -> Int = { kotlin.random.Random.nextInt(0, ALPHABET.length) }): String {
        return buildString(6) {
            repeat(6) { append(ALPHABET[random() % ALPHABET.length]) }
        }
    }

    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().uppercase()
            .replace('0', 'O')
            .replace('1', 'I')
            .replace('L', 'I')
        return cleaned.takeIf { SHORT_CODE_PATTERN.matches(it) }
    }

    fun isValid(code: String?): Boolean = normalize(code) != null
}
