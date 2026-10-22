package com.betterstreamflix.app.extractors

import com.betterstreamflix.app.models.Video
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the extractor dispatch in [Extractor.findExtractorForLink]: host matching must
 * respect domain boundaries so lookalike hosts cannot hijack another hoster's extractor,
 * while legit rotating-TLD domains keep working.
 */
class ExtractorHostMatchingTest {

    private fun select(link: String, server: Video.Server? = null) =
        Extractor.findExtractorForLink(link, server)

    @Test
    fun `exact host selects owning extractor`() {
        assertTrue(select("https://voe.sx/e/abc123") is VoeExtractor)
        assertTrue(select("https://mixdrop.co/f/xyz") is MixDropExtractor)
        assertTrue(select("https://v.vidxgo.co/xyz") is VidxGoExtractor)
    }

    @Test
    fun `alias host selects owning extractor`() {
        assertTrue(select("https://voe.bar/e/x") is VoeExtractor)
        assertTrue(select("https://streamta.site/f/x") is StreamtapeExtractor)
    }

    @Test
    fun `subdomain of known host still matches`() {
        assertTrue(select("https://cdn.voe.sx/e/x") is VoeExtractor)
        assertTrue(select("https://cdn.filemoon.site/e/x") is FilemoonExtractor)
    }

    @Test
    fun `rotating tld matches via registrable name`() {
        assertTrue(select("https://filemoon.to/e/x") is FilemoonExtractor)
        assertTrue(select("https://voe.li/e/x") is VoeExtractor)
    }

    @Test
    fun `rotating domain regex matches registrable part`() {
        assertTrue(select("https://md3b7.xyz/f/x") is MixDropExtractor)
        assertTrue(select("https://voe-unblock.net/e/x") is VoeExtractor)
    }

    @Test
    fun `lookalike hosts are rejected`() {
        // Subdomain of an attacker domain must not match the real host.
        assertNull(select("https://voe.sx.evil.com/e/x"))
        assertNull(select("https://v.vidxgo.co.evil.com/x"))
        // Name as a subdomain label must not match via rotating-domain regexes.
        assertNull(select("https://filemoon.evil.com/x"))
        // Prefix lookalikes must not match via registrable-name prefix.
        assertNull(select("https://filemoonevil.com/x"))
        assertNull(select("https://vidxgo.com.evil.org/x"))
    }

    @Test
    fun `case and port are ignored`() {
        assertTrue(select("HTTPS://VOE.SX:443/e/x") is VoeExtractor)
    }

    @Test
    fun `server name fallback still works`() {
        val sel = select("https://unknown.invalid/x", Video.Server(id = "1", name = "Streamtape"))
        assertTrue(sel is StreamtapeExtractor)
    }
}
