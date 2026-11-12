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
        isBuffering: Boolean = false,
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
        isBuffering = isBuffering,
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
        // The compact member-list line renders "Listening to " + name, so the
        // name carries the track to read "Listening to LastWave · <song>".
        assertEquals("LastWave \u00B7 Sunglasses at Night", str(activity, "name"))
        assertEquals("Sunglasses at Night", str(activity, "details"))
        assertEquals("Moondrop \u00B7 Hi-Res Lossless \u00B7 24-bit \u00B7 96 kHz", str(activity, "state"))
        // No status_display_type: the compact member-list line falls back to
        // the activity name, so others see "Listening to LastWave".
        assertNull(activity["status_display_type"])
        // Discord's mobile clients collapse a newline in `state` to an
        // ellipsis, so the quality must never move to a second line.
        assertFalse(str(activity, "state")!!.contains('\n'))
    }

    @Test
    fun qualityLineDescribesOnlyWhatWasMeasured() {
        // CD-quality lossless: 16-bit at 44.1kHz is Lossless, not Hi-Res.
        assertEquals(
            "Lossless \u00B7 16-bit \u00B7 44.1 kHz",
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
            "Lossless \u00B7 48 kHz \u00B7 ATMOS",
            DiscordPresence.qualityLine(state(audioCodec = "EC-3 ATMOS", bitDepth = null, samplingRateKHz = 48.0)),
        )
    }

    @Test
    fun artworkFallsBackToTheHostedLogoLikeDesktop() {
        // No artwork URL: Discord gets the hosted logo, exactly as on desktop
        // (the desktop client never substitutes a video thumbnail).
        assertEquals(
            DiscordPresence.DISCORD_LOGO_URL,
            str(DiscordPresence.buildActivity(state(track = track(artworkUrl = null)), NOW_MS)["assets"], "large_image"),
        )
    }

    @Test
    fun artworkFallsBackToLogoWhenNotHttp() {
        assertEquals(
            "https://lh3.googleusercontent.com/cover=abc",
            str(DiscordPresence.buildActivity(state(), NOW_MS)["assets"], "large_image"),
        )
        // A local file path cannot be fetched by Discord, and neither can a
        // missing one — both fall back to the same hosted logo as desktop.
        assertEquals(
            DiscordPresence.DISCORD_LOGO_URL,
            str(
                DiscordPresence.buildActivity(
                    state(track = track(artworkUrl = "/data/user/0/com.lastwave.app/files/cover.jpg", videoId = null)),
                    NOW_MS,
                )["assets"],
                "large_image",
            ),
        )
        assertEquals(
            DiscordPresence.DISCORD_LOGO_URL,
            str(DiscordPresence.buildActivity(state(track = track(artworkUrl = null, videoId = null)), NOW_MS)["assets"], "large_image"),
        )
    }

    @Test
    fun assetsCarryTheCoverAlbumTextAndBrandBadge() {
        val playing = DiscordPresence.buildActivity(state(isPlaying = true), NOW_MS)["assets"]!!.jsonObject
        assertEquals("Afterglow", playing["large_text"]?.jsonPrimitive?.content)
        assertEquals("https://lh3.googleusercontent.com/cover=abc", playing["large_image"]?.jsonPrimitive?.content)
        // The brand badge is the same hosted logo on every track, and its
        // tooltip carries the measured audio-format line.
        assertEquals(DiscordPresence.DISCORD_LOGO_URL, playing["small_image"]?.jsonPrimitive?.content)
        assertEquals(
            "LastWave \u00B7 Hi-Res Lossless \u00B7 24-bit \u00B7 96 kHz",
            playing["small_text"]?.jsonPrimitive?.content,
        )

        // Unknown quality: the badge still carries the brand, alone.
        val lossy = DiscordPresence.buildActivity(
            state(isLossless = false, bitDepth = null, samplingRateKHz = null, audioCodec = null, bitrateKbps = 128),
            NOW_MS,
        )["assets"]!!.jsonObject
        assertEquals("LastWave", lossy["small_text"]?.jsonPrimitive?.content)

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
        // Native build, not the desktop one.
        assertEquals(
            "https://github.com/Clash-Projects/LastWave-Native",
            buttons[1].jsonObject["url"]?.jsonPrimitive?.content,
        )

        // No video id (a local/downloaded file): the first button has nowhere
        // to deep-link, so it falls back to the releases page.
        val local = DiscordPresence.buildActivity(state(track = track(videoId = null)), NOW_MS)["buttons"]!!.jsonArray
        assertEquals(
            "https://github.com/Clash-Projects/LastWave-Native/releases",
            local[0].jsonObject["url"]?.jsonPrimitive?.content,
        )
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
    fun signatureChangesWhenProviderArtworkArrives() {
        // Provider art (Apple/Tidal/Deezer/Spotify/YouTube) resolves after
        // playback starts. Its arrival changes what the card shows, so the
        // throttle must repush instead of deduping it away as "unchanged".
        assertFalse(
            DiscordPresence.signature(state(track = track(artworkUrl = null, videoId = null))) ==
                DiscordPresence.signature(
                    state(track = track(artworkUrl = "https://example.com/cover.jpg", videoId = null)),
                ),
        )
    }

    @Test
    fun largeImagePrefersArtworkThenTheHostedLogo() {
        assertEquals(
            "https://example.com/cover.jpg",
            DiscordPresence.largeImage("https://example.com/cover.jpg"),
        )
        assertEquals(DiscordPresence.DISCORD_LOGO_URL, DiscordPresence.largeImage(null))
        assertEquals(
            DiscordPresence.DISCORD_LOGO_URL,
            DiscordPresence.largeImage("/data/user/0/com.lastwave.app/files/cover.jpg"),
        )
        assertEquals(DiscordPresence.DISCORD_LOGO_URL, DiscordPresence.largeImage(""))
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
            "Afterglow \u00B7 Lossless \u00B7 16-bit \u00B7 44.1 kHz",
            DiscordPresence.buildActivity(
                state(track = track(artist = "  ", album = "Afterglow"), bitDepth = 16, samplingRateKHz = 44.1),
                NOW_MS,
            )["state"]?.jsonPrimitive?.content,
        )
        assertEquals("LastWave", DiscordPresence.stateLine("", null, ""))
    }

    // ---- A buffer stall is not a pause ------------------------------------
    //
    // ExoPlayer's isPlaying is false on every stall (first buffer of a fresh
    // track, a mid-track hiccup, the re-prepare behind a quality upgrade)
    // while playWhenReady stays true, so the state reports isBuffering for
    // the whole stall. The user never paused; the card must not say they did.

    @Test
    fun bufferingWhileTheUserWantsPlaybackStillReadsAsPlaying() {
        val stalled = state(isPlaying = false, isBuffering = true)
        val activity = DiscordPresence.buildActivity(stalled, NOW_MS)
        val assets = activity["assets"]!!.jsonObject
        // The brand badge is constant — only the play state and bar must not
        // read as "paused" during a stall.
        assertEquals(DiscordPresence.DISCORD_LOGO_URL, assets["small_image"]?.jsonPrimitive?.content)
        // The countdown bar stays up while the buffer refills, so the card
        // never reads as a frozen "paused" one in the middle of a stall.
        assertTrue(activity.containsKey("timestamps"))
    }

    @Test
    fun aStallSharesThePlayingSignatureSoItCannotChurnOrBeSwallowed() {
        // Visible output is identical, so the throttle must treat a stall and
        // rendering as one signature: a resume repushes nothing (nothing the
        // card shows changed), and a stall cannot become a frame either.
        assertEquals(
            DiscordPresence.signature(state(isPlaying = true, isBuffering = false)),
            DiscordPresence.signature(state(isPlaying = false, isBuffering = true)),
        )
    }

    @Test
    fun aRealPauseStillReadsAsPausedAndRepushes() {
        // pause() clears both flags, so only the intent-aware read can tell
        // "waiting for data while playing" apart from "the user paused".
        val paused = state(isPlaying = false, isBuffering = false)
        val activity = DiscordPresence.buildActivity(paused, NOW_MS)
        assertNull(activity["timestamps"])
        assertFalse(
            DiscordPresence.signature(paused) == DiscordPresence.signature(state()),
        )
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