package com.dskja.betterstreamflix.sync

/**
 * Short codes for Phone→TV Session Handoff (pure / unit-tested).
 * Alphabet matches Cast Room: A–Z and 2–9 (no 0/1/O/I/L confusion).
 */
object SessionHandoffCodes {
    private val SHORT_CODE_PATTERN = Regex("^[A-Z2-9]{6}$")
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    fun newShortCode(random: () -> Int = { kotlin.random.Random.nextInt(0, ALPHABET.length) }): String =
        buildString(6) {
            repeat(6) { append(ALPHABET[random() % ALPHABET.length]) }
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

    fun formatDisplay(code: String): String {
        val n = normalize(code) ?: return code
        return "${n.substring(0, 3)}-${n.substring(3)}"
    }
}
