package com.lastwave.app.data.ytmusic

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class CookieNormalizerTest {

    @Test
    fun `normalizes raw cookie header with SAPISID`() {
        val raw = "SID=abc123xyz; __Secure-3PAPISID=secret_sapisid; HSID=some_hsid"
        val normalized = CookieNormalizer.normalize(raw)
        assertThat(normalized).contains("__Secure-3PAPISID=secret_sapisid")
        assertThat(normalized).contains("HSID=some_hsid")
        assertThat(normalized).contains("SID=abc123xyz")
    }

    @Test
    fun `strips cookie prefix and surrounding quotes`() {
        val raw = "Cookie: SID=\"abc123xyz\"; SAPISID='my_sapisid'"
        val normalized = CookieNormalizer.normalize(raw)
        assertThat(normalized).contains("SAPISID=my_sapisid")
        assertThat(normalized).contains("SID=abc123xyz")
        assertThat(normalized).doesNotContain("\"")
        assertThat(normalized).doesNotContain("'")
    }

    @Test
    fun `parses JSON array from browser extension`() {
        val json = """
            [
                {"name": "SID", "value": "12345"},
                {"name": "__Secure-3PAPISID", "value": "secure_token_123"},
                {"name": "SSID", "value": "67890"}
            ]
        """.trimIndent()
        val normalized = CookieNormalizer.normalize(json)
        assertThat(normalized).contains("__Secure-3PAPISID=secure_token_123")
        assertThat(normalized).contains("SID=12345")
        assertThat(normalized).contains("SSID=67890")
    }

    @Test
    fun `parses Netscape cookies text format with HttpOnly`() {
        val netscape = """
            # Netscape HTTP Cookie File
            # http://curl.haxx.se/rfc/cookie_spec.html
            # This is a generated file!  Do not edit.

            .youtube.com	TRUE	/	TRUE	1799999999	SID	netscape_sid
            #HttpOnly_.youtube.com	TRUE	/	TRUE	1799999999	SAPISID	netscape_sapisid
        """.trimIndent()
        val normalized = CookieNormalizer.normalize(netscape)
        assertThat(normalized).contains("SAPISID=netscape_sapisid")
        assertThat(normalized).contains("SID=netscape_sid")
    }

    @Test
    fun `throws when empty or blank`() {
        assertThrows(IllegalArgumentException::class.java) {
            CookieNormalizer.normalize("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CookieNormalizer.normalize("   ")
        }
    }

    @Test
    fun `throws when missing SAPISID family`() {
        val err = assertThrows(IllegalArgumentException::class.java) {
            CookieNormalizer.normalize("SID=12345; HSID=67890")
        }
        assertThat(err.message).contains("Missing SAPISID")
    }
}
