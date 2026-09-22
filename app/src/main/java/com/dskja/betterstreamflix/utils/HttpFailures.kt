package com.dskja.betterstreamflix.utils

import retrofit2.HttpException

/**
 * Detects provider backends failing on their side (500 / 502 / 503). Those are
 * transient and should never blank a detail page that TMDb can still describe.
 */
object HttpFailures {

    private val serverErrorText = Regex(
        "internal server error|bad gateway|service unavailable|gateway timeout|http\\s*5\\d\\d",
        RegexOption.IGNORE_CASE,
    )

    fun isServerError(error: Throwable?): Boolean {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 5) {
            val code = (cause as? HttpException)?.code()
            if (code != null && code in 500..599) return true
            if (cause.message?.let(serverErrorText::containsMatchIn) == true) return true
            cause = cause.cause
            depth += 1
        }
        return false
    }
}
