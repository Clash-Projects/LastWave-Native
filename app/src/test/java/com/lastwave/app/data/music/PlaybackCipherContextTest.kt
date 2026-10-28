package com.lastwave.app.data.music

import com.lastwave.app.data.ytmusic.YtConnection
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PlaybackCipherContextTest {
    private val account = YtConnection(cookies = mapOf("SAPISID" to "private-cookie"), accountName = "test")
    private val context = PlaybackCipherContext("video-a", "https://www.youtube.com/s/player/player-a/base.js", 20800, account)

    @Test fun sameVideoAndAccountKeepThePairedScriptAndTimestamp() {
        context.requireMatches("video-a", account.copy())
        assertEquals(20800, context.signatureTimestamp)
        assertTrue(context.playerUrl.contains("player-a"))
    }

    @Test fun aDifferentVideoCannotReuseTheContext() {
        assertThrows(CancellationException::class.java) { context.requireMatches("video-b", account) }
    }

    @Test fun changedCookiesOrChannelInvalidateTheContext() {
        for (changed in listOf(account.copy(cookies = mapOf("SAPISID" to "new-cookie")), account.copy(pageId = "other-channel"), YtConnection.DISCONNECTED)) {
            assertThrows(CancellationException::class.java) { context.requireMatches("video-a", changed) }
        }
    }

    @Test fun diagnosticsDoNotExposeTheAccountOrPlayerUrl() {
        assertFalse(context.toString().contains("private-cookie"))
        assertFalse(context.toString().contains("youtube.com"))
        assertFalse(context.toString().contains("test"))
    }
}
