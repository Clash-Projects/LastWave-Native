package com.lastwave.app.presence

import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.inferSamplingRate
import com.lastwave.app.playback.resolveDepthForDisplay
import com.lastwave.app.playback.spatialIndicatorLabel
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Discord Rich Presence ("Listening to LastWave") payload builder.
 *
 * Ported field for field from the desktop client
 * (`LastWave-Desktop/lib/features/presence/discord_presence_service.dart`) so a
 * user's profile reads the same on both apps: same application id, same `type`,
 * same line layout, same two buttons, same asset slots, same clip lengths. Two
 * Android-specific deviations keep the card readable without a mouse (see
 * [activityName] and [stateLine]). Only the transport differs (see
 * [DiscordIpcTransport]) — on Android the Discord app itself hosts the endpoint,
 * so there is no `discord-ipc-N` socket to hand-roll the way the desktop build
 * does.
 *
 * The card one [buildActivity] call produces:
 * ```
 *   Listening to LastWave · Song title  <- type 2 + name (see [activityName])
 *   ┌────┐  Song title               <- details
 *   │art │  Artist · Hi-Res Lossless · 24-bit · 96 kHz   <- state, one line
 *   │  ○ │  (brand badge)            <- small_image
 *   └────┘  ▁▁▁▁▁▁ 1:04 / 3:47       <- from the timestamps
 *   [ Listen On LastWave ]           <- opens the track
 *   [ Get LastWave ]                 <- opens this project's releases
 * ```
 *
 * `status_display_type` is deliberately left unset so the compact member-list
 * line falls back to the activity name: with [activityName] that reads
 * "Listening to LastWave · <song>". The audio-format line rides in `state` on
 * the same line as the artist: Discord on Android collapses a newline in
 * `state` to an ellipsis and has no hover, so a separate line (or only the
 * `small_text` tooltip) would hide the quality on mobile. The badge tooltip
 * still carries it for desktop viewers.
 *
 * Deliberately free of Android and Discord SDK types: every rule here is pure,
 * so the format is unit-testable on the JVM and can never drift from the
 * desktop client's without a test failing.
 *
 * Quality is only ever what was measured. Where the desktop client could read a
 * real channel count off its output device, [MusicPlayerState] has no such
 * field, so the trailing layout slot carries the spatial badge the stream's
 * codec actually reports ([spatialIndicatorLabel]) and is simply absent
 * otherwise — never a guessed `Stereo`.
 */
object DiscordPresence {

    /**
     * Application ID from the Discord Developer Portal (General Information).
     * Public by design — safe to ship in source (it is not a secret; only the
     * Client Secret / bot token must stay private). Shared with the desktop
     * client so both apps publish under one Discord application.
     */
    const val APPLICATION_ID = "1552563991426105414"

    /**
     * Repo the card's buttons open — this Android project, so "Get LastWave"
     * lands on the native build.
     */
    const val PROJECT_URL = "https://github.com/Clash-Projects/LastWave-Native"
    const val RELEASES_URL = "$PROJECT_URL/releases"
    const val DEFAULT_BUTTON_1 = "Listen On LastWave"
    const val DEFAULT_BUTTON_2 = "Get LastWave"

    /** Discord truncates nothing for us: over-long fields are dropped. */
    const val MAX_FIELD = 120

    /** Cap for the two-line subtitle (artist + quality). */
    const val MAX_STATE = 125

    /**
     * Push throttle. Position ticks arrive several times a second; a presence
     * only needs republishing when something the card shows actually changed,
     * and Discord rate-limits updates anyway. 15s also bounds how long a track
     * can stay invisible if an event-driven push was missed.
     */
    const val PUSH_INTERVAL_MS = 15_000L

    /**
     * Reconnect throttle. While music plays a missing connection is retried at
     * most this often; a user without the Discord app costs one cheap
     * `isDiscordAppInstalled()` per attempt and must never be retried harder.
     */
    const val RETRY_INTERVAL_MS = 15_000L

    /**
     * Brand mark for the card's small badge and the fallback large image —
     * this project's hosted logo URL. Discord resolves https URLs in asset
     * slots exactly like Rich Presence asset keys, so no Developer Portal
     * upload is required.
     */
    const val DISCORD_LOGO_URL =
        "https://raw.githubusercontent.com/Clash-Projects/LastWave-Native/main/assets/lastwave_logo.png"

    /** 2 = "Listening to". */
    const val TYPE_LISTENING = 2

    fun isConfigured(): Boolean =
        APPLICATION_ID.isNotEmpty() && APPLICATION_ID != "YOUR_DISCORD_APPLICATION_ID"

    /**
     * Discord's own clip: keep the whole field readable and mark the cut
     * rather than letting a long title silently vanish from the card.
     *
     * The result is `limit - 1` characters plus the three-dot marker, i.e.
     * `limit + 2`. That off-by-two is the desktop client's behaviour, kept
     * deliberately so both apps produce byte-identical fields.
     */
    fun clip(value: String, limit: Int = MAX_FIELD): String {
        val trimmed = value.trim()
        return if (trimmed.length > limit) trimmed.substring(0, limit - 1) + "..." else trimmed
    }

    /**
     * The activity name. Discord prefixes it with "Listening to" in the member
     * list, so appending the track makes the compact line read
     * `Listening to LastWave · <song>`. The plain app name is used when there
     * is no track text.
     */
    fun activityName(title: String): String {
        val trimmed = title.trim()
        return if (trimmed.isEmpty()) "LastWave" else "LastWave \u00B7 " + clip(trimmed)
    }

    /**
     * The card's subtitle: the track's artist, then the measured quality.
     *
     * They must share one line. Discord's mobile clients do not render a
     * newline in `state` — they show the first line and an ellipsis, which is
     * exactly how the quality disappeared from the Android card (mobile has no
     * hover for the badge tooltip either). A single `artist · quality` line is
     * visible on both platforms, so the audio format never depends on hovering.
     */
    fun stateLine(artist: String, album: String?, quality: String): String {
        val head = artist.trim().ifEmpty { album?.trim()?.takeIf { it.isNotEmpty() } ?: "LastWave" }
        return clip(if (quality.isEmpty()) head else "$head \u00B7 $quality", MAX_STATE)
    }

    /**
     * "Hi-Res Lossless · 24-bit · 96 kHz · ATMOS" — every part comes from a
     * value the stream reported, joined exactly like the desktop client's line.
     * Unknown depth or rate is omitted, never inferred into a plausible-looking
     * number.
     */
    fun qualityLine(state: MusicPlayerState): String {
        val parts = mutableListOf<String>()
        val rateKhz = state.samplingRateKHz?.takeIf { it > 0.0 } ?: inferSamplingRate(state) ?: 0.0
        val depth = resolveDepthForDisplay(state.bitDepth, rateKhz.takeIf { it > 0.0 }) ?: 0
        if (state.isLossless) {
            parts += if (rateKhz > 48.0 || depth > 16) "Hi-Res Lossless" else "Lossless"
        }
        if (depth > 0) parts += "$depth-bit"
        if (rateKhz > 0.0) parts += rateLabel(rateKhz)
        spatialIndicatorLabel(state.audioCodec)?.let { parts += it }
        return parts.joinToString(" \u00B7 ")
    }

    /** `96 kHz`, `44.1 kHz` — whole rates drop the decimal, as on desktop. */
    fun rateLabel(rateKhz: Double): String {
        val rounded = Math.round(rateKhz).toInt()
        return if (kotlin.math.abs(rateKhz - rounded) < 0.05) "$rounded kHz"
        else String.format(java.util.Locale.US, "%.1f kHz", rateKhz)
    }

    /**
     * Cover Discord can actually fetch: the track's own http(s) artwork, else
     * the hosted logo — exactly what the desktop client sends. A local file
     * path cannot be fetched by Discord, and the desktop never substitutes a
     * video thumbnail. Pure so the format stays unit-testable.
     */
    fun largeImage(artworkUrl: String?): String {
        val artwork = artworkUrl?.trim().orEmpty()
        return if (artwork.startsWith("http://") || artwork.startsWith("https://")) artwork else DISCORD_LOGO_URL
    }

    /**
     * Whether the card must read "Playing".
     *
     * [MusicPlayerState.isPlaying] is the raw ExoPlayer flag, and ExoPlayer
     * reports it false on every stall — the first buffer of a fresh track, a
     * mid-track network hiccup, the re-prepare behind a quality upgrade —
     * while `playWhenReady` stays true. The player publishes exactly that as
     * [MusicPlayerState.isBuffering], which is `playWhenReady`-gated, so the
     * user never paused and audio resumes on its own. Mapping the raw flag
     * onto the card told Discord "Paused" for every stall on a streaming
     * connection, which on a hi-res stream is most of the listen.
     *
     * `isPlaying || isBuffering` is therefore the user's intent at the state
     * boundary: isBuffering only ever rides with playWhenReady, and both are
     * cleared together by pause(), a fatal error, and cast teardown. The
     * desktop client keys off the same intent — a paused player — rather than
     * the rendered frames.
     */
    fun isPlayingForPresence(state: MusicPlayerState): Boolean =
        state.isPlaying || state.isBuffering

    /**
     * Everything the card renders that is not a position tick. Two states with
     * the same signature are visually identical, so a repush would be pure
     * cost — this is the dedupe that keeps a 16 Hz position stream off the
     * wire.
     *
     * The play state is [isPlayingForPresence], so a stall never looks like a
     * change: a resume repushes nothing, and neither can a stall become a
     * frame on its own.
     *
     * Identity is title + artist rather than the video id alone: two entries
     * can share a video id and still differ in the text the card shows (same
     * stream re-added under an edited title, a local file that inherited one),
     * and a stale card is worse than one extra frame.
     */
    fun signature(state: MusicPlayerState): String {
        val track = state.current ?: return "none"
        return buildString {
            append(track.videoId.orEmpty()).append('|')
            append(track.title).append('|')
            append(track.artist).append('|')
            // Album feeds the card's large-image tooltip; a change there is a
            // visible change and must repush.
            append(track.album.orEmpty()).append('|')
            // Artwork is part of identity: provider art typically resolves
            // after playback starts, and its arrival must repush the card
            // instead of being deduped away as "unchanged".
            append(track.artworkUrl.orEmpty()).append('|')
            append("playing=").append(isPlayingForPresence(state))
            append("|dur=").append(durationMs(state) / 1000L)
            append("|br=").append(state.bitrateKbps)
            append("|q=").append(qualityLine(state))
        }
    }

    /** Stream duration when known, else the track's own, else 0. */
    fun durationMs(state: MusicPlayerState): Long =
        state.durationMs.takeIf { it > 0L } ?: state.current?.durationMs ?: 0L

    /**
     * The `SET_ACTIVITY` activity object. [nowMs] is wall clock and only feeds
     * the timestamps.
     *
     * Timestamps are a start/end pair rather than a position value so Discord
     * counts the bar down on its own clock: a presence pushed once stays
     * correct for the rest of the track, and the push throttle can skip
     * position ticks entirely.
     */
    fun buildActivity(state: MusicPlayerState, nowMs: Long): JsonObject {
        val track = requireNotNull(state.current) { "no current track" }
        val title = clip(track.title)
        val duration = durationMs(state)
        val playing = isPlayingForPresence(state)

        val timestamps = if (playing && duration > 0L) {
            // A position past the end (a stale ticker on the last tick before
            // the next track) must not push the bar negative.
            val position = state.positionMs.coerceIn(0L, duration)
            val endMs = nowMs + (duration - position)
            val startMs = endMs - duration
            buildJsonObject {
                put("start", startMs / 1000L)
                put("end", endMs / 1000L)
            }
        } else {
            null
        }

        val album = track.album?.trim().orEmpty()
        val videoId = track.videoId?.trim().orEmpty()
        val quality = qualityLine(state)
        // The desktop card keeps the subtitle for the artist and parks the
        // audio-format line in the brand badge's tooltip.
        val smallText = clip(if (quality.isEmpty()) "LastWave" else "LastWave \u00B7 $quality")

        return buildJsonObject {
            put("type", TYPE_LISTENING)
            put("name", activityName(title))
            put("details", title)
            put("state", stateLine(track.artist, track.album, quality))
            timestamps?.let { put("timestamps", it) }
            put("assets", buildJsonObject {
                put("large_image", largeImage(track.artworkUrl))
                put("large_text", if (album.isEmpty()) title else clip(album))
                // Brand badge, drawn as the little corner overlay; its tooltip
                // carries the audio-format line, exactly as on desktop.
                put("small_image", DISCORD_LOGO_URL)
                put("small_text", smallText)
            })
            putJsonArray("buttons") {
                // Buttons are plain URLs and cannot detect the app, so the
                // first one opens the track itself rather than a search page.
                add(buildJsonObject {
                    put("label", DEFAULT_BUTTON_1)
                    put("url", if (videoId.isEmpty()) RELEASES_URL else "https://www.youtube.com/watch?v=$videoId")
                })
                add(buildJsonObject {
                    put("label", DEFAULT_BUTTON_2)
                    put("url", PROJECT_URL)
                })
            }
            put("instance", JsonPrimitive(true))
        }
    }

    /** `{"cmd":"SET_ACTIVITY", ...}` — the frame the transport hands to Discord. */
    fun setActivityFrame(activity: JsonObject, pid: Int, nonce: String): String =
        frame(
            buildJsonObject {
                put("cmd", "SET_ACTIVITY")
                put("args", buildJsonObject {
                    put("pid", pid)
                    put("activity", activity)
                })
                put("nonce", nonce)
            },
        )

    /**
     * The clear frame. Discord treats a null activity as "hide the card", so
     * the same command does both jobs — there is no separate teardown.
     */
    fun clearActivityFrame(pid: Int, nonce: String): String =
        frame(
            buildJsonObject {
                put("cmd", "SET_ACTIVITY")
                put("args", buildJsonObject {
                    put("pid", pid)
                    put("activity", JsonNull)
                })
                put("nonce", nonce)
            },
        )

    private fun frame(payload: JsonObject): String = payload.toString()
}