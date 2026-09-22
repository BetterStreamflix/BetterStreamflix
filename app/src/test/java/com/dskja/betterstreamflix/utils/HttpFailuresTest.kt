package com.dskja.betterstreamflix.utils

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class HttpFailuresTest {

    private fun httpException(code: Int) = HttpException(
        Response.error<Any>(code, "".toResponseBody("text/plain".toMediaType())),
    )

    @Test
    fun serverResponseCodesAreServerErrors() {
        assertTrue(HttpFailures.isServerError(httpException(500)))
        assertTrue(HttpFailures.isServerError(httpException(502)))
        assertTrue(HttpFailures.isServerError(httpException(503)))
    }

    @Test
    fun clientResponseCodesAreNot() {
        assertFalse(HttpFailures.isServerError(httpException(404)))
        assertFalse(HttpFailures.isServerError(httpException(409)))
        assertFalse(HttpFailures.isServerError(null))
    }

    @Test
    fun wordedFailuresAreRecognizedThroughTheCauseChain() {
        assertTrue(HttpFailures.isServerError(IllegalStateException("Internal Server Error")))
        assertTrue(HttpFailures.isServerError(IOException("wrapped", httpException(500))))
        assertFalse(HttpFailures.isServerError(IOException("timed out")))
    }
}
