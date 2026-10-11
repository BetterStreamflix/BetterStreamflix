package com.dskja.betterstreamflix.sync

/**
 * Short codes for Account Link-Code device pairing.
 * Alphabet matches Session Handoff / Cast Room: A–Z and 2–9 (no 0/1/O/I/L).
 */
object DeviceLinkCodes {
    fun newShortCode(random: () -> Int = { kotlin.random.Random.nextInt(0, 32) }): String =
        SessionHandoffCodes.newShortCode(random)

    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val withoutDash = raw.trim().uppercase().replace("-", "")
        return SessionHandoffCodes.normalize(withoutDash)
    }

    fun isValid(code: String?): Boolean = normalize(code) != null

    fun formatDisplay(code: String): String = SessionHandoffCodes.formatDisplay(code)
}
