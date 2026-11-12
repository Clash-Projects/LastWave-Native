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


import android.app.Service

internal fun MusicPlaybackService.publishWidget(state: MusicPlayerState) {
        val track = state.current ?: return
        val active = ActiveMediaSessionHolder.controller
        val otherAppIsPlaying = active?.packageName != packageName &&
            active?.playbackState?.state == PlatformPlaybackState.STATE_PLAYING
        if (!state.isPlaying && otherAppIsPlaying) return
        val signature = "${track.title}|${track.artist}|${state.isPlaying}|$artworkUrl|${artworkBitmap != null}"
        if (signature == widgetSignature) return
        widgetSignature = signature
        scope.launch(Dispatchers.IO) {
            WidgetUpdater.publish(
                context = this@publishWidget,
                title = track.title,
                artist = track.artist,
                album = track.album,
                sourceApp = getString(R.string.app_name),
                sourcePackage = packageName,
                art = artworkBitmap,
                isPlaying = state.isPlaying,
            )
        }
    }

internal fun MusicPlaybackService.publishNotification(state: MusicPlayerState, force: Boolean = false) {
        val track = state.current
        if (track == null) {
            notificationSignature = ""
            if (isPlaybackForeground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(Service.STOP_FOREGROUND_REMOVE)
                else @Suppress("DEPRECATION") stopForeground(true)
                isPlaybackForeground = false
            }
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            return
        }
        // A loaded track keeps the media-playback foreground service alive even
        // while paused. Demoting to an ordinary background service on pause is
        // what made the whole session vanish on aggressive OEMs (HyperOS/MIUI,
        // ColorOS, One UI) the moment another app came to the front: the frozen
        // process takes the media notification and the MediaSession with it, so
        // there is nothing left to resume from. The FGS costs nothing while
        // paused — updateWakeLocks() has already released the wake/wifi locks —
        // and the system keeps the notification that carries the transport
        // controls. Only stopAndClear() (track == null, handled above) leaves
        // the foreground state.
        if (!isPlaybackForeground) {
            if (!promoteForPlayback()) return
        }
        val signature = "${track.title}|${track.artist}|${track.album}|${state.isPlaying}|${state.isBuffering}|${state.shuffleEnabled}|${state.repeatMode}|$artworkUrl|${artworkBitmap != null}"
        if (!force && signature == notificationSignature) return
        val success = runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(state, artworkBitmap))
            true
        }.onFailure { error ->
            // Some OEMs throw from notify() for transient binder failures;
            // clearing signature allows subsequent state emissions to retry.
            android.util.Log.w("MusicPlaybackService", "Notification publish failed", error)
        }.getOrDefault(false)

        if (success) {
            notificationSignature = signature
        } else {
            notificationSignature = ""
        }
    }

internal fun MusicPlaybackService.requestArtwork(track: PlayableTrack?) {
        if (track == null) {
            artworkRequestKey = ""
            artworkUrl = null
            artworkBitmap = null
            artworkJob?.cancel()
            return
        }

        // The player state emits ~4x/second; without this guard every emission
        // re-launched an artwork resolution job for the same track.
        val requestKey = "${track.title}|${track.artist}|${track.artworkUrl.orEmpty()}"
        if (requestKey == artworkRequestKey) return
        artworkRequestKey = requestKey

        // Track has changed: immediately clear previous track's artwork
        // so the notification bar never displays the old song's cover art
        artworkBitmap = null
        artworkUrl = null
        artworkJob?.cancel()

        val key = com.lastwave.app.data.artwork.ArtworkNormalizer.cacheKey(track.title, track.artist)
        val cached = artworkRepository.resolved.value[key]?.takeIf(String::isNotBlank)
        if (cached != null) {
            fetchArtwork(cached)
            return
        }

        // Show immediate upscaled placeholder while high-res artwork resolves
        val placeholder = com.lastwave.app.data.artwork.ArtworkNormalizer.upscaleYoutubeArtwork(track.artworkUrl)?.takeIf(String::isNotBlank)
        if (placeholder != null) {
            fetchArtwork(placeholder)
        }

        // Resolve high-resolution master artwork after this track is audible.
        // Starting Tidal/Deezer/iTunes on the tap shares connections with the
        // stream extract and stretches the gap before the next song.
        scope.launch(Dispatchers.IO) {
            withTimeoutOrNull(8_000L) {
                musicPlayer.state.drop(1).first { state ->
                    val same = track.videoId.isNullOrBlank() || state.current?.videoId == track.videoId
                    same && state.isPlaying && !state.isBuffering && state.error == null
                }
            }
            if (artworkRequestKey != requestKey) {
                MetadataLog.skipped("artwork", track.videoId)
                return@launch
            }
            MetadataLog.delayed("artwork", track.videoId)
            delay(MetadataLog.SETTLE_MS)
            if (artworkRequestKey != requestKey) {
                MetadataLog.skipped("artwork", track.videoId)
                return@launch
            }
            MetadataLog.started("artwork", track.videoId)
            artworkRepository.resolve(track.title, track.artist)
            val resolved = artworkRepository.resolved.value[key]?.takeIf(String::isNotBlank)
            if (resolved != null && resolved != artworkUrl) {
                withContext(Dispatchers.Main) {
                    if (artworkRequestKey == requestKey) {
                        fetchArtwork(resolved)
                    }
                }
            }
        }
    }

internal fun MusicPlaybackService.fetchArtwork(url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        if (cleanUrl == artworkUrl && artworkBitmap != null) return
        artworkUrl = cleanUrl
        artworkJob?.cancel()

        // Immediate memory cache lookup for zero-latency widget update
        runCatching {
            val cached = imageLoader.memoryCache?.get(coil.memory.MemoryCache.Key(cleanUrl))?.bitmap
            if (cached != null && !cached.isRecycled) {
                artworkBitmap = cached
                widgetSignature = ""
                publishWidget(musicPlayer.state.value)
                publishWaveWidget(musicPlayer.state.value, force = true)
            }
        }

        artworkJob = scope.launch {
            val expectedUrl = cleanUrl
            val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                imageLoader.execute(
                    ImageRequest.Builder(this@fetchArtwork)
                        .data(expectedUrl)
                        .size(512)
                        .allowHardware(false)
                        .build(),
                )
            }
            if (expectedUrl != artworkUrl) return@launch
            artworkBitmap = (result as? SuccessResult)?.drawable?.toBitmap()
            val state = musicPlayer.state.value
            publishSystemState(state)
            publishNotification(state, force = true)
            widgetSignature = ""
            publishWidget(state)
            publishWaveWidget(state, force = true)
        }
    }


internal fun MusicPlaybackService.openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        // ColorOS opens the activity from the MediaSession's sessionActivity
        // when the user taps the artwork in its expanded control-center
        // player. Target the declared activity directly instead of the
        // launcher alias, and explicitly bring the app task to the foreground.
        Intent(this, MainShellActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

internal fun MusicPlaybackService.useSystemMediaStyle(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || isChineseOemSkin()

    /** Heavily-skinned ROMs whose lockscreen/panel only honor standard MediaStyle. */
internal fun MusicPlaybackService.isChineseOemSkin(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("vivo") || m.contains("iqoo") ||
            m.contains("oppo") || m.contains("oneplus") || m.contains("realme") ||
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ||
            m.contains("huawei") || m.contains("honor") ||
            m.contains("nubia") || m.contains("zte") ||
            m.contains("tecno") || m.contains("infinix") || m.contains("itel") ||
            m.contains("meizu")
    }

internal fun MusicPlaybackService.buildNotification(state: MusicPlayerState, art: Bitmap?): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }

        // System-rendered MediaStyle path. Used on Android 13+ (SystemUI /
        // Samsung One UI host the player natively and reject custom
        // RemoteViews) and on Chinese-OEM skins (Vivo/OriginOS, OPPO/ColorOS,
        // OnePlus, Realme, Xiaomi/HyperOS, Huawei/EMUI, Honor, …): their
        // lockscreen + control-center media cards never render third-party
        // custom RemoteViews — they draw their own card from the MediaSession,
        // so the material custom views would mean no controls at all there.
        // Everywhere else (Pixels, Motorola, …) the custom views survive.
        if (useSystemMediaStyle()) {
            val mediaStyle = Notification.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
            platformSessionToken?.let { mediaStyle.setMediaSession(it) }

            val repeatIcon = when (state.repeatMode) {
                androidx.media3.common.Player.REPEAT_MODE_ONE -> R.drawable.ic_widget_repeat_one
                else -> R.drawable.ic_widget_repeat
            }
            val repeatLabel = when (state.repeatMode) {
                androidx.media3.common.Player.REPEAT_MODE_ONE -> "Repeat one"
                androidx.media3.common.Player.REPEAT_MODE_ALL -> "Repeat all"
                else -> "Repeat off"
            }

            // Hosts that can't render custom views (cars, watches, DeX text
            // rows) show these titles verbatim — "Play or pause" looked broken,
            // so the play action always reads the actual current state.
            val playPauseLabel = if (state.isPlaying) "Pause" else "Play"
            return builder
                .setSmallIcon(R.drawable.ic_launcher_logo)
                .setContentTitle(state.current?.title ?: "LastWave")
                .setContentText(state.current?.artist ?: "Music player")
                .setSubText(state.current?.album)
                .setContentIntent(openAppPendingIntent())
                .setLargeIcon(scaledBitmap(art, 384))
                .setOnlyAlertOnce(true)
                .setOngoing(state.isPlaying)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setColor(notificationPalette.primary)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(mediaStyle)
                .addAction(Notification.Action.Builder(R.drawable.ic_widget_skip_previous, "Previous", serviceAction(ACTION_PREVIOUS, 1)).build())
                .addAction(Notification.Action.Builder(if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play, playPauseLabel, serviceAction(ACTION_TOGGLE, 2)).build())
                .addAction(Notification.Action.Builder(R.drawable.ic_widget_skip_next, "Next", serviceAction(ACTION_NEXT, 3)).build())
                .addAction(Notification.Action.Builder(R.drawable.ic_widget_shuffle, if (state.shuffleEnabled) "Shuffle on" else "Shuffle off", serviceAction(ACTION_SHUFFLE, 5)).build())
                .addAction(Notification.Action.Builder(repeatIcon, repeatLabel, serviceAction(ACTION_REPEAT, 6)).build())
                .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", serviceAction(ACTION_STOP, 4)).build())
                .apply {
                    // setColorized is API 26+; calling it on Android 7.x throws
                    // NoSuchMethodError the moment playback posts its notification.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setColorized(true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                    }
                }
                .build()
        }

        val compact = notificationRemoteViews(
            layout = R.layout.notification_player_compact,
            widthDp = 520,
            heightDp = 72,
            state = state,
            art = art,
            expanded = false,
        )
        val expanded = notificationRemoteViews(
            layout = R.layout.notification_player_expanded,
            widthDp = 520,
            heightDp = EXPANDED_HEIGHT_DP,
            state = state,
            art = art,
            expanded = true,
        )
        // On Android 10 and below (One UI 2.x especially), attaching a
        // MediaSession to DecoratedMediaCustomViewStyle causes SystemUI's
        // older media-notification renderer to draw its own full media
        // chrome as a second layer on top of our custom RemoteViews,
        // instead of just framing it — producing two overlapping players.
        // Our compact/expanded RemoteViews already draw everything (art,
        // title, artist, transport buttons), so the system's own media
        // decoration is redundant; drop just the session tag on affected
        // versions. Lock screen, Bluetooth, Android Auto, and the in-app
        // widget are unaffected — they all read from `mediaSession`
        // directly, never from this notification Style object.
        val style = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Notification.DecoratedMediaCustomViewStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .also { s -> platformSessionToken?.let(s::setMediaSession) }
        } else {
            Notification.DecoratedCustomViewStyle()
        }

        return builder
            .setSmallIcon(R.drawable.ic_launcher_logo)
            .setContentTitle(state.current?.title ?: "LastWave")
            .setContentText(state.current?.artist ?: "Music player")
            .setSubText(state.current?.album)
            .setContentIntent(openAppPendingIntent())
            .setLargeIcon(scaledBitmap(art, 384))
            .setOnlyAlertOnce(true)
            .setOngoing(state.isPlaying)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setColor(notificationPalette.primary)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(style)
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)
            .setCustomHeadsUpContentView(compact)
            .addAction(Notification.Action.Builder(R.drawable.ic_widget_skip_previous, "Previous", serviceAction(ACTION_PREVIOUS, 1)).build())
            .addAction(Notification.Action.Builder(if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play, if (state.isPlaying) "Pause" else "Play", serviceAction(ACTION_TOGGLE, 2)).build())
            .addAction(Notification.Action.Builder(R.drawable.ic_widget_skip_next, "Next", serviceAction(ACTION_NEXT, 3)).build())
            .addAction(Notification.Action.Builder(R.drawable.ic_widget_shuffle, if (state.shuffleEnabled) "Shuffle on" else "Shuffle off", serviceAction(ACTION_SHUFFLE, 5)).build())
            .addAction(
                Notification.Action.Builder(
                    when (state.repeatMode) {
                        androidx.media3.common.Player.REPEAT_MODE_ONE -> R.drawable.ic_widget_repeat_one
                        else -> R.drawable.ic_widget_repeat
                    },
                    when (state.repeatMode) {
                        androidx.media3.common.Player.REPEAT_MODE_ONE -> "Repeat one"
                        androidx.media3.common.Player.REPEAT_MODE_ALL -> "Repeat all"
                        else -> "Repeat off"
                    },
                    serviceAction(ACTION_REPEAT, 6),
                ).build(),
            )
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", serviceAction(ACTION_STOP, 4)).build())
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setColorized(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

internal fun MusicPlaybackService.notificationRemoteViews(
        layout: Int,
        widthDp: Int,
        heightDp: Int,
        state: MusicPlayerState,
        art: Bitmap?,
        expanded: Boolean,
    ): RemoteViews = RemoteViews(packageName, layout).apply {
        val palette = notificationPalette
        setImageViewBitmap(R.id.notification_background, glassBackground(widthDp, heightDp, palette))
        // Expanded art is shown at 96dp (288px @3x); compact at 54dp where
        // 192px is already sharp. Two sizes keep the Binder parcel small.
        if (art != null) setImageViewBitmap(R.id.notification_artwork, scaledBitmap(art, if (expanded) 288 else 192))
        else setImageViewResource(R.id.notification_artwork, R.mipmap.ic_launcher)
        setTextViewText(R.id.notification_title, state.current?.title ?: "LastWave")
        setTextViewText(R.id.notification_artist, state.current?.artist ?: "Music player")
        setTextColor(R.id.notification_title, palette.onSurface)
        setTextColor(R.id.notification_artist, palette.onSurfaceVariant)
        setImageViewResource(
            R.id.notification_play_pause,
            if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )
        setImageViewResource(
            R.id.notification_previous,
            R.drawable.ic_widget_skip_previous,
        )
        setImageViewResource(
            R.id.notification_next,
            R.drawable.ic_widget_skip_next,
        )
        // Shuffle/repeat only exist in the expanded layout; the compact row
        // is the prev/play/next transport cluster.
        if (expanded) {
            setImageViewResource(
                R.id.notification_shuffle,
                R.drawable.ic_widget_shuffle,
            )
            setImageViewResource(
                R.id.notification_repeat,
                if (state.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) R.drawable.ic_widget_repeat_one else R.drawable.ic_widget_repeat,
            )
            setInt(
                R.id.notification_shuffle,
                "setColorFilter",
                if (state.shuffleEnabled) palette.primary else palette.onSurface,
            )
            setInt(
                R.id.notification_repeat,
                "setColorFilter",
                if (state.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) palette.primary else palette.onSurface,
            )
            setOnClickPendingIntent(R.id.notification_shuffle, serviceAction(ACTION_SHUFFLE, 5))
            setOnClickPendingIntent(R.id.notification_repeat, serviceAction(ACTION_REPEAT, 6))
        }
        setInt(R.id.notification_previous, "setColorFilter", palette.onSurface)
        setInt(R.id.notification_next, "setColorFilter", palette.onSurface)
        setInt(R.id.notification_play_surface, "setColorFilter", palette.primary)
        setInt(R.id.notification_play_pause, "setColorFilter", palette.onPrimary)
        setOnClickPendingIntent(R.id.notification_root, openAppPendingIntent())
        setOnClickPendingIntent(R.id.notification_previous, serviceAction(ACTION_PREVIOUS, 1))
        setOnClickPendingIntent(R.id.notification_play_pause, serviceAction(ACTION_TOGGLE, 2))
        setOnClickPendingIntent(R.id.notification_next, serviceAction(ACTION_NEXT, 3))
        if (expanded) {
            setTextColor(R.id.notification_brand, palette.primary)
            setTextViewText(R.id.notification_album, state.current?.album.orEmpty())
            setTextColor(R.id.notification_album, palette.onSurfaceVariant)
            setViewVisibility(
                R.id.notification_album,
                if (state.current?.album.isNullOrBlank()) View.GONE else View.VISIBLE,
            )
        }
    }

internal fun MusicPlaybackService.glassBackground(widthDp: Int, heightDp: Int, palette: NotificationPalette): Bitmap {
        // Keep these bitmaps intentionally pixel-sized, not density-scaled:
        // RemoteViews crosses Binder and oversized bitmaps can exceed its
        // transaction limit. The ImageView stretches this small gradient.
        val width = widthDp.coerceAtLeast(1)
        val height = heightDp.coerceAtLeast(1)
        val radius = 30f
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        // Deeper accent blend than before: the old 0.20/0.13 wash read as
        // flat grey on wide hosts (DeX, cars) with white text on top.
        val start = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(palette.surface, palette.primary, 0.34f),
            242,
        )
        val mid = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(palette.surface, palette.primary, 0.18f),
            236,
        )
        val end = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(palette.surface, palette.tertiary, 0.26f),
            230,
        )
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(start, mid, end), floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(bounds, radius, radius, fill)
        // Crisp edge definition: hairline top highlight + soft inner shadow
        // so the card doesn't melt into the shade background when stretched.
        val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(palette.onSurface, 52)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        canvas.drawRoundRect(
            RectF(1f, 1f, width - 1f, height - 1f),
            radius,
            radius,
            highlight,
        )
        return bitmap
    }

internal fun MusicPlaybackService.scaledBitmap(source: Bitmap?, maxEdge: Int): Bitmap? {
        source ?: return null
        val largest = maxOf(source.width, source.height)
        if (largest <= maxEdge) return source
        val scale = maxEdge.toFloat() / largest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

internal fun MusicPlaybackService.serviceAction(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, MusicPlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

internal fun MusicPlaybackService.createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            // Lockscreen media cards on Vivo/Chinese ROMs are suppressed on
            // LOW importance. Importance is immutable once created, so v3
            // (DEFAULT without sound/vibration) replaces earlier channels outright.
            runCatching { nm.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
            runCatching { nm.deleteNotificationChannel(LEGACY_CHANNEL_ID_V1) }
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Native LastWave playback controls"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }.onFailure { error ->
            android.util.Log.w("MusicPlaybackService", "Notification channel unavailable", error)
        }
    }

internal data class NotificationPalette(
    val primary: Int,
    val onPrimary: Int,
    val surface: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val tertiary: Int,
) {
    companion object {
        fun from(scheme: androidx.compose.material3.ColorScheme) = NotificationPalette(
            primary = scheme.primary.toArgb(),
            onPrimary = scheme.onPrimary.toArgb(),
            surface = scheme.surfaceContainerHigh.toArgb(),
            onSurface = scheme.onSurface.toArgb(),
            onSurfaceVariant = scheme.onSurfaceVariant.toArgb(),
            tertiary = scheme.tertiary.toArgb(),
        )

        fun default() = NotificationPalette(
            primary = 0xFFFFB4AB.toInt(),
            onPrimary = 0xFF690005.toInt(),
            surface = 0xFF211A1A.toInt(),
            onSurface = 0xFFEDE0DE.toInt(),
            onSurfaceVariant = 0xFFD8C2BF.toInt(),
            tertiary = 0xFFE7C089.toInt(),
        )
    }
}
