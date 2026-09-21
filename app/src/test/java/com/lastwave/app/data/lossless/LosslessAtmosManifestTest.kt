package com.lastwave.app.data.lossless

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * True-Dolby guards: Dolby ON must only ever badge genuine E-AC-3 spatial
 * bytes, and stereo requests must never accept an Atmos mix (devices
 * without an EC-3 decoder fail on it outright).
 *
 * Fixtures mirror live backend shapes: a real Atmos MPD carries
 * codecs="ec-3" with a 6-channel AudioChannelConfiguration, while a
 * stereo-only track answers the atmos endpoint with a plain
 * FLAC/AAC rendition (atmos_available=false).
 */
class LosslessAtmosManifestTest {

    private val atmosMpd = """
        <MPD>
          <Period>
            <AdaptationSet mimeType="audio/mp4">
              <Representation codecs="ec-3" bandwidth="768000">
                <AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="6"/>
              </Representation>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    private val stereoMpd = """
        <MPD>
          <Period>
            <AdaptationSet mimeType="audio/mp4">
              <Representation codecs="flac" bandwidth="3500000"/>
            </AdaptationSet>
            <AdaptationSet mimeType="audio/mp4">
              <Representation codecs="mp4a.40.2" bandwidth="320000"/>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    @Test
    fun trueAtmosManifestAccepted() {
        assertTrue(LosslessMusicApi.isAtmosManifest(atmosMpd))
    }

    @Test
    fun stereoRenditionRejected() {
        // Exactly what a stereo-only track returns from the atmos endpoint.
        assertFalse(LosslessMusicApi.isAtmosManifest(stereoMpd))
    }

    @Test
    fun ec3WithoutChannelTagIsStillAtmos() {
        // Dolby JOC often uses a hex channel mask, not value="6".
        assertTrue(LosslessMusicApi.isAtmosManifest("<MPD><Representation codecs=\"ec-3\"/></MPD>"))
    }

    @Test
    fun blankManifestRejected() {
        assertFalse(LosslessMusicApi.isAtmosManifest(""))
    }

    @Test
    fun atmosCodecLabels() {
        assertTrue(LosslessMusicApi.isAtmosCodec("ec-3"))
        assertTrue(LosslessMusicApi.isAtmosCodec("EC-3"))
        assertTrue(LosslessMusicApi.isAtmosCodec("eac3"))
        assertTrue(LosslessMusicApi.isAtmosCodec("ac-3"))
        assertFalse(LosslessMusicApi.isAtmosCodec("flac"))
        assertFalse(LosslessMusicApi.isAtmosCodec("mp4a.40.2"))
        assertFalse(LosslessMusicApi.isAtmosCodec(null))
        assertFalse(LosslessMusicApi.isAtmosCodec(""))
    }

    @Test
    fun nonDashUrlsNeverAtmos() {
        assertFalse(LosslessMusicApi.isAtmosStreamUrl("https://cdn.example/track.flac"))
        assertFalse(LosslessMusicApi.isAtmosStreamUrl(""))
    }
}
