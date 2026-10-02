package com.lastwave.app.presence

import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlayableTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the Discord Rich Presence payload to the format the desktop client
 * publishes (`LastWave-Desktop/lib/features/presence/discord_presence_service.dart`),
 * so a profile can never read differently between the two apps: same activity
 * type, same lines, same asset keys, same two buttons, same clip lengths.
 *
 * Also the regression net for the parts that are easy to get quietly wrong:
 * position ticks must not change the signature, an unknown quality must never
 * become a number, and a stale position past the track end must not push the
 * progress bar negative.
 */
class DiscordPresenceTest {

    private fun track(
        title: String = "Sunglasses at Night",
        artist: String = "Moondrop",
        album: String? = "Afterglow",
        artworkUrl: String? = "https://lh3.googleusercontent.com/cover=abc",
        videoId: String? = "dQw4w9WgXcQ",
        durationMs: Long? = 227_000L,
    ) = PlayableTrack(
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        videoId = videoId,
        durationMs = durationMs,
    )

    private fun state(
        track: PlayableTrack? = track(),
        isPlaying: Boolean = true,
        positionMs: Long = 64_000L,
        durationMs: Long = 227_000L,
        isLossless: Boolean = true,
        bitDepth: Int? = 24,
        samplingRateKHz: Double? = 96.0,
        bitrateKbps: Int? = 4_608,
        audioCodec: String? = "FLAC",
    ) = MusicPlayerState(
        current = track,
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = durationMs,
        isLossless = isLossless,
        bitDepth = bitDepth,
        samplingRateKHz = samplingRateKHz,
        bitrateKbps = bitrateKbps,
        audioCodec = audioCodec,
    )

    private fun str(element: kotlinx.serialization.json.JsonElement?, key: String): String? =
        element?.jsonObject?.get(key)?.jsonPrimitive?.content

    // ---- Card shape -------------------------------------------------------

    @Test
    fun cardIsListeningToLastWaveWithTitleAndArtist() {
        val activity = DiscordPresence.buildActivity(state(), NOW_MS)
        assertEquals(2, activity["type"]?.jsonPrimitive?.int) // 2 = Listening
        assertEquals("LastWave", str(activity, "name"))
        assertEquals("Sunglasses at Night", str(activity, "details"))
        assertEquals("Moondrop\n · Hi-Res Lossless · 24-bit · 96 kHz", str(activity, "state"))
    }

    @Test
    fun qualityLineDescribesOnlyWhatWasMeasured() {
        // CD-quality lossless: 16-bit at 44.1kHz is Lossless, not Hi-Res.
        assertEquals(
            "Lossless · 16-bit · 44.1 kHz",
            DiscordPresence.qualityLine(state(bitDepth = 16, samplingRateKHz = 44.1)),
        )
        // A lossy stream claims nothing about depth it never reported.
        assertEquals(
            "",
            DiscordPresence.qualityLine(
                state(isLossless = false, bitDepth = null, samplingRateKHz = null, audioCodec = null, bitrateKbps = 128),
            ),
        )
        // Spatial is reported only when the codec says so — and the lossless
        // tier still stands on its own.
        assertEquals(
            "Lossless · 48 kHz · ATMOS",
            DiscordPresence.qualityLine(state(audioCodec = "EC-3 ATMOS", bitDepth = null, samplingRateKHz = 48.0)),
        )
    }

    @Test
    fun artworkFallsBackToLogoWhenNotHttp() {
        assertEquals(
            "https://lh3.googleusercontent.com/cover=abc",
            str(DiscordPresence.buildActivity(state(), NOW_MS)["assets"], "large_image"),
        )
        // A local file path cannot be fetched by Discord, and neither can a
        // missing one — both fall back to the uploaded logo key.
        assertEquals(
            "logo",
            str(
                DiscordPresence.buildActivity(
                    state(track = track(artworkUrl = "/data/user/0/com.lastwave.app/files/cover.jpg")),
                    NOW_MS,
                )["assets"],
                "large_image",
            ),
        )
        assertEquals(
            "logo",
            str(DiscordPresence.buildActivity(state(track = track(artworkUrl = null)), NOW_MS)["assets"], "large_image"),
        )
    }

    @Test
    fun assetsCarryAlbumAndPlayState() {
        val playing = DiscordPresence.buildActivity(state(isPlaying = true), NOW_MS)["assets"]!!.jsonObject
        assertEquals("Afterglow", playing["large_text"]?.jsonPrimitive?.content)
        assertEquals("play", playing["small_image"]?.jsonPrimitive?.content)
        assertEquals("Playing", playing["small_text"]?.jsonPrimitive?.content)

        val paused = DiscordPresence.buildActivity(state(isPlaying = false), NOW_MS)["assets"]!!.jsonObject
        assertEquals("pause", paused["small_image"]?.jsonPrimitive?.content)
        assertEquals("Paused", paused["small_text"]?.jsonPrimitive?.content)

        // No album: the title stands in, exactly as on desktop.
        val noAlbum = DiscordPresence.buildActivity(state(track = track(album = null)), NOW_MS)["assets"]!!.jsonObject
        assertEquals("Sunglasses at Night", noAlbum["large_text"]?.jsonPrimitive?.content)
    }

    @Test
    fun buttonsOpenTheTrackAndTheProject() {
        val buttons = DiscordPresence.buildActivity(state(), NOW_MS)["buttons"]!!.jsonArray
        assertEquals(2, buttons.size)
        assertEquals("Listen On LastWave", buttons[0].jsonObject["label"]?.jsonPrimitive?.content)
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            buttons[0].jsonObject["url"]?.jsonPrimitive?.content,
        )
        assertEquals("Get LastWave", buttons[1].jsonObject["label"]?.jsonPrimitive?.content)
        assertEquals(DiscordPresence.PROJECT_URL, buttons[1].jsonObject["url"]?.jsonPrimitive?.content)

        // No video id (a local/downloaded file): the first button has nowhere
        // to deep-link, so it falls back to the releases page.
        val local = DiscordPresence.buildActivity(state(track = track(videoId = null)), NOW_MS)["buttons"]!!.jsonArray
        assertEquals(DiscordPresence.RELEASES_URL, local[0].jsonObject["url"]?.jsonPrimitive?.content)
    }

    // ---- Timestamps -------------------------------------------------------

    @Test
    fun timestampsBracketTheWholeTrackSoTheBarCountsDown() {
        val timestamps = DiscordPresence.buildActivity(
            state(positionMs = 64_000L, durationMs = 227_000L),
            NOW_MS,
        )["timestamps"]!!.jsonObject
        val end = timestamps["end"]!!.jsonPrimitive.long
        val start = timestamps["start"]!!.jsonPrimitive.long
        assertEquals(NOW_MS / 1000L + 163, end) // 227s track, 64s already played
        assertEquals(227L, end - start) // the whole track, so it stays right without repushing
    }

    @Test
    fun noTimestampsWhilePausedOrWithoutDuration() {
        assertNull(DiscordPresence.buildActivity(state(isPlaying = false), NOW_MS)["timestamps"])

        val unknownDuration = state(durationMs = 0L, positionMs = 0L)
            .copy(current = track(durationMs = null))
        assertNull(DiscordPresence.buildActivity(unknownDuration, NOW_MS)["timestamps"])
    }

    @Test
    fun stalePositionPastTrackEndCannotPushTheBarNegative() {
        // A ticker that has not yet rolled over to the next track can report a
        // position past the duration; the countdown must clamp, not invert.
        val timestamps = DiscordPresence.buildActivity(
            state(positionMs = 400_000L, durationMs = 227_000L),
            NOW_MS,
        )["timestamps"]!!.jsonObject
        assertEquals(NOW_MS / 1000L, timestamps["end"]!!.jsonPrimitive.long)
    }

    // ---- Throttling -------------------------------------------------------

    @Test
    fun positionTicksAloneDoNotChangeTheSignature() {
        assertEquals(
            DiscordPresence.signature(state(positionMs = 60_000L)),
            DiscordPresence.signature(state(positionMs = 61_000L)),
        )
    }

    @Test
    fun signatureChangesWithEverythingTheCardShows() {
        val base = DiscordPresence.signature(state())
        assertFalse(base == DiscordPresence.signature(state(isPlaying = false)))
        assertFalse(base == DiscordPresence.signature(state(bitrateKbps = 320)))
        assertFalse(base == DiscordPresence.signature(state(durationMs = 300_000L)))
        assertFalse(base == DiscordPresence.signature(state(track = track(title = "Different"))))
        assertFalse(base == DiscordPresence.signature(state(isLossless = false, audioCodec = "MP3 320k")))
        assertEquals("none", DiscordPresence.signature(state(track = null)))
    }

    @Test
    fun signatureTracksTheTextTheCardShowsNotJustTheVideoId() {
        // Two entries can share a video id and still differ in what the card
        // renders — a re-added track under an edited title, a local file that
        // inherited one. The throttle must not swallow that change.
        assertFalse(
            DiscordPresence.signature(state(track = track(title = "Different"))) ==
                DiscordPresence.signature(state(track = track(title = "Also Different"))),
        )
        assertFalse(
            DiscordPresence.signature(state(track = track(artist = "Different Artist"))) ==
                DiscordPresence.signature(state()),
        )
    }

    // ---- Clipping ---------------------------------------------------------

    @Test
    fun longFieldsAreClippedNotDropped() {
        // Clipping is the desktop client's: limit-1 characters plus the marker,
        // so both apps emit byte-identical fields.
        val details = DiscordPresence.buildActivity(
            state(track = track(title = "A".repeat(400))),
            NOW_MS,
        )["details"]!!.jsonPrimitive.content
        assertEquals(DiscordPresence.MAX_FIELD + 2, details.length)
        assertTrue(details.endsWith("..."))

        val status = DiscordPresence.buildActivity(
            state(track = track(artist = "B".repeat(300))),
            NOW_MS,
        )["state"]!!.jsonPrimitive.content
        assertEquals(DiscordPresence.MAX_STATE + 2, status.length)
        assertTrue(status.endsWith("..."))
    }

    @Test
    fun unknownArtistFallsBackToAlbumThenAppName() {
        assertEquals(
            "Afterglow\n · Lossless · 16-bit · 44.1 kHz",
            DiscordPresence.buildActivity(
                state(track = track(artist = "  ", album = "Afterglow"), bitDepth = 16, samplingRateKHz = 44.1),
                NOW_MS,
            )["state"]?.jsonPrimitive?.content,
        )
        assertEquals("LastWave", DiscordPresence.stateLine("", null, "Title", ""))
    }

    // ---- Frames -----------------------------------------------------------

    @Test
    fun framesMatchTheDesktopWireFormat() {
        val activity = DiscordPresence.buildActivity(state(), NOW_MS)
        val frame = Json.parseToJsonElement(
            DiscordPresence.setActivityFrame(activity, pid = 4321, nonce = "1700000000000000"),
        ).jsonObject
        assertEquals("SET_ACTIVITY", frame["cmd"]?.jsonPrimitive?.content)
        assertEquals("1700000000000000", frame["nonce"]?.jsonPrimitive?.content)
        val args = frame["args"]!!.jsonObject
        assertEquals(4321, args["pid"]?.jsonPrimitive?.int)
        assertEquals("Sunglasses at Night", args["activity"]?.jsonObject?.get("details")?.jsonPrimitive?.content)
    }

    @Test
    fun clearFrameSendsANullActivity() {
        val frame = Json.parseToJsonElement(
            DiscordPresence.clearActivityFrame(pid = 4321, nonce = "1"),
        ).jsonObject
        assertEquals("SET_ACTIVITY", frame["cmd"]?.jsonPrimitive?.content)
        assertTrue(frame["args"]!!.jsonObject["activity"] is JsonNull)
    }

    @Test
    fun shipsTheSameApplicationIdAsTheDesktopClient() {
        assertEquals("1552563991426105414", DiscordPresence.APPLICATION_ID)
        assertTrue(DiscordPresence.isConfigured())
    }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
    }
}