package com.lastwave.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState as PlatformPlaybackState
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.media.MediaBrowserServiceCompat
import androidx.media.VolumeProviderCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.lastwave.app.MainShellActivity
import com.lastwave.app.R
import com.lastwave.app.data.local.ScrobblerPreferences
import com.lastwave.app.data.local.ScrobblerSettings
import com.lastwave.app.data.playlist.LIKED_SONGS_MODE
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.data.playlist.TrackLikeController
import com.lastwave.app.data.repository.ScrobbleRepository
import com.lastwave.app.data.repository.ThemeRepository
import com.lastwave.app.playback.resolve.MetadataLog

import com.lastwave.app.widget.ActiveMediaSessionHolder
import com.lastwave.app.widget.WidgetUpdater
import com.lastwave.app.widget.wave.WavePlaylistShortcut
import com.lastwave.app.widget.wave.WaveWidgetActions
import com.lastwave.app.widget.wave.WaveWidgetActionReceiver
import com.lastwave.app.widget.wave.WaveWidgetUpdater
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs



internal fun MusicPlaybackService.handleWaveWidgetMediaButton(intent: Intent) {
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            -> musicPlayer.togglePlayPause()
            KeyEvent.KEYCODE_MEDIA_NEXT -> musicPlayer.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> musicPlayer.previous()
        }
    }

internal fun MusicPlaybackService.cleanTrackMetadata(title: String, artist: String): CleanTrackMetadata {
        if (title == cleanedSourceTitle && artist == cleanedSourceArtist) return cleanedMetadata
        var cleanArtist = artist.trim()
            .replace(TOPIC_SUFFIX_REGEX, "")
            .replace(VEVO_SUFFIX_REGEX, "")
            .trim()
        if (com.lastwave.app.util.ArtistHelper.isPlayCountOrStat(cleanArtist)) {
            cleanArtist = ""
        }
        val cleanTitle = title.trim()
            .replace(TITLE_SUFFIX_REGEX, "")
            .trim()
        cleanedSourceTitle = title
        cleanedSourceArtist = artist
        return CleanTrackMetadata(
            cleanTitle.ifBlank { title },
            cleanArtist.ifBlank { artist },
        ).also { cleanedMetadata = it }
    }

internal fun MusicPlaybackService.detectTransition(state: MusicPlayerState) {
        val track = state.current ?: return
        val isEpisode = track.mediaType != com.lastwave.app.playback.MediaType.SONG ||
            !track.showId.isNullOrBlank() ||
            track.artist.equals("Podcast", ignoreCase = true)
        val (cleanTitle, cleanArtist) = cleanTrackMetadata(track.title, track.artist)
        val key = if (isEpisode) "podcast:${track.videoId.orEmpty()}" else "${cleanArtist.lowercase()}|${cleanTitle.lowercase()}"
        if (key == detectedKey) {
            if (state.isPlaying && key != nowPlayingAnnouncedKey) {
                announceNowPlaying(state)
            }
            return
        }

        // If previous track reached threshold before transition but wasn't submitted yet, submit now
        val prev = pendingPreviousTrack
        if (prev != null && !submissionAttempted && accumulatedMs >= 30_000L) {
            val (prevTitle, prevArtist) = cleanTrackMetadata(prev.title, prev.artist)
            val prevStartedAt = pendingPreviousStartedAt
            scope.launch(Dispatchers.IO) {
                runCatching {
                    scrobbleRepository.scrobble(
                        artist = prevArtist,
                        track = prevTitle,
                        album = prev.album,
                        timestampSec = prevStartedAt,
                    )
                }.onFailure { error ->
                    android.util.Log.e("MusicPlaybackService", "Deferred scrobble failed", error)
                }
            }
        }

        detectedKey = key
        pendingPreviousTrack = if (isEpisode) null else track
        pendingPreviousStartedAt = System.currentTimeMillis() / 1000
        nowPlayingAnnouncedKey = ""
        accumulatedMs = 0
        lastPositionMs = 0
        retryCount = 0
        startedAtEpochSec = System.currentTimeMillis() / 1000
        submissionAttempted = false

        if (state.isPlaying) {
            announceNowPlaying(state)
        }
    }

internal fun MusicPlaybackService.announceNowPlaying(state: MusicPlayerState) {
        val track = state.current ?: return
        if (track.mediaType != com.lastwave.app.playback.MediaType.SONG ||
            !track.showId.isNullOrBlank() ||
            track.artist.equals("Podcast", ignoreCase = true)
        ) return
        if (!state.isPlaying || !settings.submitNowPlaying) return
        // One announcement in flight at a time: during a rate-limit cooldown
        // each write can wait seconds on the shared write mutex — without this
        // guard the 1s detector loop would stack duplicate announcements that
        // all fire once the cooldown clears.
        if (nowPlayingInFlight) return
        val (cleanTitle, cleanArtist) = cleanTrackMetadata(track.title, track.artist)
        val key = "${cleanArtist.lowercase()}|${cleanTitle.lowercase()}"
        nowPlayingAnnouncedKey = key
        nowPlayingInFlight = true
        scope.launch(Dispatchers.IO) {
            try {
                val result = scrobbleRepository.updateNowPlaying(cleanArtist, cleanTitle, track.album)
                when {
                    result is ScrobbleRepository.Result.Success -> Unit
                    result is ScrobbleRepository.Result.Failed && result.retryable -> {
                        // Transient (rate limit / network): clear the announced key so
                        // the 1s detector loop re-attempts once the rate shield
                        // clears, instead of leaving Now Playing silently dead for
                        // the rest of the track.
                        if (nowPlayingAnnouncedKey == key) nowPlayingAnnouncedKey = ""
                    }
                    else -> Unit
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A throw here used to escape via try/finally with no catch
                // and (before the scope handler existed) kill the process.

                android.util.Log.e("MusicPlaybackService", "updateNowPlaying failed", e)
            } finally {
                nowPlayingInFlight = false
            }
        }
    }

internal fun MusicPlaybackService.startDetector() {
        detectorJob?.cancel()
        detectorJob = scope.launch {
            var wasPlaying = false
            while (true) {
                delay(1_000)
                val state = musicPlayer.state.value
                val track = state.current

                if (track == null) {
                    wasPlaying = false
                    continue
                }

                if (track.mediaType != com.lastwave.app.playback.MediaType.SONG ||
                    !track.showId.isNullOrBlank() ||
                    track.artist.equals("Podcast", ignoreCase = true)
                ) {
                    // Podcast episodes are never scrobbled. The detector still
                    // has to keep its position bookkeeping in sync so the
                    // timer below does not count stale seconds. The scrobble
                    // latch stays untouched: the next song must be able to
                    // scrobble normally.
                    wasPlaying = state.isPlaying
                    lastPositionMs = state.positionMs
                    accumulatedMs = 0L
                    continue
                }

                val (cleanTitle, cleanArtist) = cleanTrackMetadata(track.title, track.artist)
                val key = "${cleanArtist.lowercase()}|${cleanTitle.lowercase()}"

                // Announce Now Playing when transitioning to playing or if not yet announced
                if (state.isPlaying && (!wasPlaying || key != nowPlayingAnnouncedKey)) {
                    announceNowPlaying(state)
                }
                wasPlaying = state.isPlaying

                if (!state.isPlaying) continue

                // Check for track replay / restart
                if (state.positionMs in 1..4_000 && lastPositionMs > 20_000L && accumulatedMs > 15_000L) {
                    accumulatedMs = 0L
                    startedAtEpochSec = System.currentTimeMillis() / 1000
                    submissionAttempted = false
                    retryCount = 0
                    announceNowPlaying(state)
                }
                lastPositionMs = state.positionMs

                accumulatedMs += 1_000

                // Heartbeat to keep listener alive on global map (backend expires in 60s)
                if (accumulatedMs > 0 && accumulatedMs % 45_000L == 0L) {
                }

                if (submissionAttempted) continue

                // Calculate threshold with fallback for streaming tracks where duration is not yet reported
                val effectiveDuration = if (state.durationMs > 0) state.durationMs else 180_000L
                val percent = if (settings.scrobblePercent in 25..90) settings.scrobblePercent else 50
                val threshold = minOf(effectiveDuration * percent / 100, 4 * 60_000L).coerceAtLeast(30_000L)

                if (accumulatedMs < threshold) continue

                submissionAttempted = true
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        when (val result = scrobbleRepository.scrobble(
                            artist = cleanArtist,
                            track = cleanTitle,
                            album = track.album,
                            timestampSec = startedAtEpochSec,
                        )) {
                            ScrobbleRepository.Result.Success -> {

                                // Re-announce now playing so Last.fm keeps active status during ongoing playback
                                if (musicPlayer.state.value.isPlaying) {
                                    scrobbleRepository.updateNowPlaying(cleanArtist, cleanTitle, track.album)
                                }
                            }
                            ScrobbleRepository.Result.NoSessionKey -> {

                            }
                            is ScrobbleRepository.Result.Failed -> {

                                // Allow up to 3 retries on network failure
                                if (retryCount < 3) {
                                    retryCount++
                                    submissionAttempted = false
                                }
                            }
                        }
                    }.onFailure { error ->
                        android.util.Log.e("MusicPlaybackService", "Scrobble submission crashed", error)
                    }
                }
            }
        }
    }

internal fun MusicPlaybackService.publishSystemState(state: MusicPlayerState) {
        val track = state.current
        // Transport-control target is cheap and must stay fresh every emission.
        val active = ActiveMediaSessionHolder.controller
        val otherAppIsPlaying = active?.packageName != packageName &&
            active?.playbackState?.state == PlatformPlaybackState.STATE_PLAYING
        if (track != null && (state.isPlaying || active == null || !otherAppIsPlaying)) {
            ActiveMediaSessionHolder.controller = ownController
        }

        // setMetadata/setPlaybackState are binder IPC into system_server. The
        // player state emits ~4x/second while playing; republishing on every
        // emission meant ~240 binder transactions/minute for identical data —
        // a constant main-thread tax that compounds into visible jank and
        // "app stopped responding" spells. PlaybackState carries position +
        // speed, so the system extrapolates between publishes; only actual
        // changes (track, duration, play/buffer state, artwork, or a seek
        // while paused) need to cross the binder.
        val playbackState = when {
            state.isBuffering -> PlaybackStateCompat.STATE_BUFFERING
            state.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            track != null -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_NONE
        }
        // Legacy stock-Android broadcasts KWGT/Tasker/Zooper-style widgets
        // still listen for. Sent even when the OEM media stack is broken
        // (session == null) so the fallback path keeps working.
        broadcastLegacyState(state)
        val session = mediaSession ?: return
        updateMediaSessionVolumeRouting(state.isCasting, state.appVolume)
        publishSessionQueue(session, state)
        val isLiked = track != null && trackLikeController.isLiked(track)
        val signature = buildString {
            append(track?.title).append('|')
            append(track?.artist).append('|')
            append(track?.album).append('|')
            append(state.durationMs).append('|')
            append(playbackState).append('|')
            append(state.shuffleEnabled).append('|')
            append(state.repeatMode).append('|')
            append(state.isCasting).append('|')
            append(state.appVolume).append('|')
            append(artworkUrl).append('|')
            append(artworkBitmap != null).append('|')
            append(isLiked)
            if (!state.isPlaying) append("|pos=").append(state.positionMs)
        }
        if (signature == systemStateSignature) return
        systemStateSignature = signature

        val artUri = artworkUrl ?: track?.artworkUrl.orEmpty()
        runCatching {
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    // Unique per track: the OriginOS music card ignores a new
                    // track whose media id is the same as the one before.
                    .putString(
                        MediaMetadataCompat.METADATA_KEY_MEDIA_ID,
                        track?.let { OriginOsMusicCard.mediaIdOf(state.currentIndex, it) } ?: state.currentIndex.toString(),
                    )
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track?.title.orEmpty())
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track?.artist.orEmpty())
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track?.album.orEmpty())
                    .apply {
                        // Same artwork keys Media3 (and SimpMusic) publish: the
                        // cover URL in the three URI keys, only when there is one.
                        if (artUri.isNotBlank()) {
                            putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artUri)
                            putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artUri)
                            putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artUri)
                        }
                    }
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs)
                    .putRating(MediaMetadataCompat.METADATA_KEY_RATING, RatingCompat.newHeartRating(isLiked))
                    .putLong(OriginOsMusicCard.KEY_SUPPORT_EVENT, OriginOsMusicCard.SUPPORTED_EVENTS)
                    .putLong(
                        OriginOsMusicCard.KEY_LOOP_MODE,
                        OriginOsMusicCard.loopModeOf(state.shuffleEnabled, state.repeatMode).toLong(),
                    )
                    .apply {
                        // Media3 / SimpMusic style: one bitmap in DISPLAY_ICON and
                        // ALBUM_ART, capped at the platform's own metadata artwork
                        // limit so MediaSession never rescales (or recycles) it.
                        val rawArt = artworkBitmap?.takeIf { !it.isRecycled }
                        val limit = sessionArtworkLimitPx
                        val art = if (rawArt != null && (rawArt.width > limit || rawArt.height > limit)) {
                            val scale = limit.toFloat() / maxOf(rawArt.width, rawArt.height)
                            Bitmap.createScaledBitmap(
                                rawArt,
                                (rawArt.width * scale).toInt().coerceAtLeast(1),
                                (rawArt.height * scale).toInt().coerceAtLeast(1),
                                true,
                            )
                        } else rawArt
                        if (art != null) {
                            putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
                            putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                        }
                    }
                    .build(),
            )
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or PlaybackStateCompat.ACTION_STOP or
                            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
                            PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or PlaybackStateCompat.ACTION_SET_REPEAT_MODE or
                            PlaybackStateCompat.ACTION_SET_RATING,
                    )
                    .setActiveQueueItemId(state.currentIndex.toLong())
                    .setBufferedPosition(state.bufferedPositionMs)
                    .setState(playbackState, state.positionMs, if (state.isPlaying) state.speed else 0f)
                    .build(),
            )
            session.setShuffleMode(
                if (state.shuffleEnabled) PlaybackStateCompat.SHUFFLE_MODE_ALL
                else PlaybackStateCompat.SHUFFLE_MODE_NONE,
            )
            session.setRepeatMode(
                when (state.repeatMode) {
                    androidx.media3.common.Player.REPEAT_MODE_ONE -> PlaybackStateCompat.REPEAT_MODE_ONE
                    androidx.media3.common.Player.REPEAT_MODE_ALL -> PlaybackStateCompat.REPEAT_MODE_ALL
                    else -> PlaybackStateCompat.REPEAT_MODE_NONE
                },
            )
        }.onFailure { error ->
            // Binder IPC into a broken OEM media stack must not kill the
            // state collector that also drives notifications and widget.
            android.util.Log.w("MusicPlaybackService", "Media session publish failed", error)
        }
    }


internal fun MusicPlaybackService.updateMediaSessionVolumeRouting(isCasting: Boolean, appVolume: Float) {
        val session = mediaSession ?: return
        if (isCasting) {
            val maxVolume = 100
            val current = (appVolume * maxVolume).toInt().coerceIn(0, maxVolume)
            val existing = castVolumeProvider
            if (existing != null) {
                existing.currentVolume = current
            } else {
                val newProvider = object : VolumeProviderCompat(
                    VOLUME_CONTROL_ABSOLUTE,
                    maxVolume,
                    current,
                ) {
                    override fun onSetVolumeTo(volume: Int) {
                        val target = volume.toFloat() / maxVolume
                        musicPlayer.setAppVolume(target)
                    }

                    override fun onAdjustVolume(direction: Int) {
                        val step = when (direction) {
                            android.media.AudioManager.ADJUST_RAISE -> 0.05f
                            android.media.AudioManager.ADJUST_LOWER -> -0.05f
                            else -> 0f
                        }
                        if (step != 0f) {
                            val currentVol = musicPlayer.state.value.appVolume
                            musicPlayer.setAppVolume((currentVol + step).coerceIn(0f, 1f))
                        }
                    }
                }
                castVolumeProvider = newProvider
                session.setPlaybackToRemote(newProvider)
            }
        } else {
            if (castVolumeProvider != null) {
                castVolumeProvider = null
                session.setPlaybackToLocal(android.media.AudioManager.STREAM_MUSIC)
            }
        }
    }

internal fun MusicPlaybackService.publishSessionQueue(session: MediaSessionCompat, state: MusicPlayerState) {
        val signature = state.queue.joinToString(separator = "|") {
            "${it.videoId.orEmpty()}:${it.title}:${it.artist}"
        }
        if (signature == sessionQueueSignature) return
        sessionQueueSignature = signature
        val start = (state.currentIndex - MAX_CAR_QUEUE_BEFORE_CURRENT).coerceAtLeast(0)
        val end = (start + MAX_CAR_QUEUE_ITEMS).coerceAtMost(state.queue.size)
        val queue = (start until end).map { index ->
            val track = state.queue[index]
            MediaSessionCompat.QueueItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId("queue:$index")
                    .setTitle(track.title)
                    .setSubtitle(track.artist)
                    .setDescription(track.album)
                    .build(),
                index.toLong(),
            )
        }
        runCatching {
            session.setQueue(queue)
            session.setQueueTitle(state.sourceLabel)
        }.onFailure { error ->
            android.util.Log.w("MusicPlaybackService", "Media session queue publish failed", error)
        }
    }

internal suspend fun MusicPlaybackService.loadWavePlaylistShortcuts(): List<WavePlaylistShortcut> =
        withContext(Dispatchers.IO) {
            val playlists = try {
                playlistRepository.getAll()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("MusicPlaybackService", "Wave playlist shortcuts unavailable", error)
                emptyList()
            }
            playlists
                .asSequence()
                .filter { playlist ->
                    playlist.mode != LIKED_SONGS_MODE &&
                        playlist.title.isNotBlank() &&
                        (playlist.tracks.isNotEmpty() || (playlist.remoteTrackCount ?: 0) > 0)
                }
                .sortedWith(
                    compareByDescending<SavedPlaylist> { it.isPinned }
                        .thenByDescending { it.createdAtMillis },
                )
                .take(MAX_WAVE_USER_PLAYLISTS)
                .map { playlist ->
                    WavePlaylistShortcut(
                        id = WaveWidgetActions.PLAYLIST_USER_PREFIX + playlist.id,
                        title = playlist.title.trim(),
                    )
                }
                .toList()
        }

    /**
     * The service's main collector deliberately drops ordinary playing-position
     * ticks. Watch the unfiltered stream only for a real discontinuity so a
     * seek can reset the Wave widget's extrapolation anchor without restoring
     * the high-frequency widget work that collector avoids.
     */
internal fun MusicPlaybackService.detectWaveSeek(state: MusicPlayerState) {
        val nowMs = SystemClock.elapsedRealtime()
        val previous = lastWaveObservedState
        if (
            previous != null &&
            previous.current == state.current &&
            previous.current != null &&
            previous.isPlaying &&
            state.isPlaying
        ) {
            val elapsedMs = (nowMs - lastWaveObservedAtMs).coerceAtLeast(0L)
            val expectedAdvanceMs = (elapsedMs * previous.speed.coerceAtLeast(0f)).toLong()
            val actualAdvanceMs = state.positionMs - previous.positionMs
            if (abs(actualAdvanceMs - expectedAdvanceMs) >= WAVE_SEEK_DISCONTINUITY_MS) {
                publishWaveWidget(state, force = true)
            }
        }
        lastWaveObservedState = state
        lastWaveObservedAtMs = nowMs
    }

internal fun MusicPlaybackService.publishWaveWidget(state: MusicPlayerState, force: Boolean = false) {
        val track = state.current
        val isLiked = track?.let(trackLikeController::isLiked) == true
        val shortcuts = wavePlaylistShortcuts
        val resolvedArtworkUri = artworkUrl ?: track?.artworkUrl
        val bitmap = artworkBitmap
        val signature = buildString {
            append(track).append('|')
            append(state.queue.hashCode()).append('|')
            append(state.currentIndex).append('|')
            append(state.isPlaying).append('|')
            append(state.positionMs).append('|')
            append(state.durationMs).append('|')
            append(state.shuffleEnabled).append('|')
            append(state.repeatMode).append('|')
            append(isLiked).append('|')
            append(resolvedArtworkUri).append('|')
            append(bitmap?.let { System.identityHashCode(it) } ?: 0).append('|')
            append(shortcuts.hashCode())
        }
        if (!force && signature == waveWidgetSignature) return
        waveWidgetSignature = signature
        scope.launch {
            WaveWidgetUpdater.publish(
                context = this@publishWaveWidget,
                playerState = state,
                isLiked = isLiked,
                artworkUri = resolvedArtworkUri,
                bitmap = bitmap,
                playlists = shortcuts,
            )
        }
    }
