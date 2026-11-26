package com.lastwave.app.ui.player.nowplaying

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.R
import com.lastwave.app.data.canvas.CanvasArtwork
import com.lastwave.app.data.playlist.isLikedIn
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.toGeneratedTrack
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.player.AudioControlsSheet
import com.lastwave.app.ui.player.BlurTransformation
import com.lastwave.app.ui.player.FluidArtworkBackground
import com.lastwave.app.ui.player.FullPlayerTab
import com.lastwave.app.ui.player.PlayerArtwork
import com.lastwave.app.ui.player.PlayerViewModel
import com.lastwave.app.ui.player.SignalPathDialog
import com.lastwave.app.ui.player.automix.AutoMixPopupHost
import com.lastwave.app.ui.player.automix.AutoMixPopupState
import com.lastwave.app.ui.player.automix.autoMixIdentity
import com.lastwave.app.ui.player.hapticClick
import com.lastwave.app.ui.player.rememberPlayerWindowActive
import com.lastwave.app.ui.settings.autoeq.AutoEqDialog
import com.lastwave.app.ui.theme.LocalIsDarkTheme
import com.lastwave.app.ui.theme.LocalLiquidGlassBackdrop
import com.lastwave.app.ui.theme.LocalLiquidGlassOverlayBackdrop
import com.lastwave.app.ui.theme.isLiquidGlassBackdropSupported
import com.lastwave.app.ui.theme.liquidGlassSource
import com.lastwave.app.ui.theme.rememberLayerBackdrop

/**
 * The new full player (Settings › Experimental). A screen of its own, not a
 * branch inside FullPlayer: it reads PlayerViewModel the way ExpandedPlayer does
 * and hands Queue and Lyrics to the classic player's tabs.
 *
 * Glass: the cover runs full-bleed over a blurred backdrop, chrome refracts it.
 * Material: the cover sits as a card on a tonal surface. Both take every colour
 * from the playing song.
 */
@Composable
internal fun NowPlayingScreen(
    viewModel: PlayerViewModel,
    onTabChange: (FullPlayerTab) -> Unit,
    onCollapse: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onDragStatusChange: (Boolean) -> Unit,
    isOpen: Boolean,
) {
    val state by viewModel.fullPlayerState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val canvasState by viewModel.canvasState.collectAsStateWithLifecycle()
    val likedTrackKeys by viewModel.likedTrackKeys.collectAsStateWithLifecycle()
    val track = state.current ?: return
    val player = viewModel.player
    // Same test Liked Songs storage uses: normalized title|artist OR YouTube id.
    val isLiked = track.toGeneratedTrack().isLikedIn(likedTrackKeys)
    val canvas = canvasState.takeIf { settings.canvasEnabled }
    val skin = currentPlayerSkin()
    val scheme = rememberSongColorScheme(track)
    val colors = animatedPlayerSkinColors(skin, scheme)
    val windowActive = rememberPlayerWindowActive()
    val dialogs = remember { PlayerDialogsState() }
    val autoMixUi = remember { AutoMixPopupState() }
    val signalPath by player.signalPath.collectAsStateWithLifecycle()
    val openBadge = {
        if (!autoMixUi.open(state.deckBlendTelemetry, viewModel.progressState.value, track.autoMixIdentity())) {
            dialogs.signalPath = true
        }
    }

    val swipeThreshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    val latestOnCollapse by rememberUpdatedState(onCollapse)
    val latestOnTabChange by rememberUpdatedState(onTabChange)
    var dismissY by remember { mutableFloatStateOf(0f) }
    var dismissing by remember { mutableStateOf(false) }
    val shownDismissY by animateFloatAsState(
        targetValue = dismissY,
        animationSpec = if (dismissing) snap() else ExpressiveMotion.spatialSpring(),
        label = "nowPlayingDismiss",
    )
    // The host stops drawing the shell under the player; report until the sheet lands.
    val sheetMoving by remember { derivedStateOf { dismissing || shownDismissY > 0.5f } }
    LaunchedEffect(sheetMoving) { onDragStatusChange(sheetMoving) }
    // A swipe-close keeps its offset for the close morph; a reopen must start level.
    LaunchedEffect(isOpen) { if (isOpen) dismissY = 0f }

    var skipX by remember { mutableFloatStateOf(0f) }
    var skipping by remember { mutableStateOf(false) }
    val shownSkipX by animateFloatAsState(
        targetValue = skipX,
        animationSpec = if (skipping) snap() else ExpressiveMotion.spatialDefault(),
        label = "nowPlayingSkip",
    )
    val skipSwipe = Modifier.pointerInput(player) {
        detectHorizontalDragGestures(
            onDragStart = { skipping = true },
            onDragEnd = {
                skipping = false
                when {
                    skipX < -swipeThreshold -> player.next()
                    skipX > swipeThreshold -> player.previous()
                }
                skipX = 0f
            },
            onDragCancel = {
                skipping = false
                skipX = 0f
            },
        ) { change, dx ->
            change.consume()
            skipX += dx
        }
    }

    val backdrop = rememberLayerBackdrop()
    val glassActive = skin == PlayerSkin.Glass && isLiquidGlassBackdropSupported()
    val artPaused = shownDismissY > 1f || !isOpen

    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalLiquidGlassBackdrop provides if (glassActive) backdrop else null,
            LocalLiquidGlassOverlayBackdrop provides if (glassActive) backdrop else null,
            LocalIsDarkTheme provides true,
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = shownDismissY.coerceAtLeast(0f)
                        alpha = (1f - shownDismissY.coerceAtLeast(0f) / (size.height.coerceAtLeast(1f) * 1.5f))
                            .coerceIn(0.72f, 1f)
                    }
                    .background(colors.background)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { dismissing = true },
                            onDragEnd = {
                                dismissing = false
                                if (dismissY > swipeThreshold) {
                                    latestOnCollapse()
                                } else {
                                    if (dismissY < -swipeThreshold) latestOnTabChange(FullPlayerTab.QUEUE)
                                    dismissY = 0f
                                }
                            },
                            onDragCancel = {
                                dismissing = false
                                dismissY = 0f
                            },
                        ) { change, dy ->
                            change.consume()
                            dismissY += dy
                        }
                    },
            ) {
                val wide = maxWidth > maxHeight
                val hero = skin == PlayerSkin.Glass && !wide
                if (skin == PlayerSkin.Glass) {
                    // Outside the glass capture: re-blurring the moving fluid under every
                    // control doubled frame cost in FullPlayer, and it is blurred already.
                    GlassBackdrop(
                        track = track,
                        isPlaying = state.isPlaying && isOpen,
                        rotatingBackgroundEnabled = settings.rotatingBackgroundEnabled,
                        fluidBeatSyncEnabled = settings.fluidBeatSyncEnabled,
                    )
                }
                Box(Modifier.matchParentSize().liquidGlassSource(if (glassActive) backdrop else null)) {
                    if (hero) {
                        HeroArtwork(
                            track = track,
                            canvas = canvas,
                            isPlaying = state.isPlaying,
                            paused = artPaused,
                            offsetX = shownSkipX,
                        )
                    }
                }

                val topBar: @Composable () -> Unit = {
                    TopBar(
                        skin = skin,
                        colors = colors,
                        sourceLabel = state.sourceLabel,
                        onCollapse = onCollapse,
                        onMore = { dialogs.trackMenu = true },
                    )
                }
                val panel: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier) {
                        state.error?.let { message ->
                            PlaybackErrorBanner(
                                message = message,
                                onRetry = player::retry,
                                onDismiss = player::clearError,
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                        }
                        NowPlayingPanel(
                            track = track,
                            state = state,
                            player = player,
                            progressState = viewModel.progressState,
                            seekbarStyle = settings.seekbarStyle,
                            wavySeekbarEnabled = settings.wavySeekbarEnabled,
                            skin = skin,
                            colors = colors,
                            scheme = scheme,
                            signalPath = signalPath,
                            autoMixUi = autoMixUi,
                            onOpenSignalPath = openBadge,
                            isLiked = isLiked,
                            onToggleLiked = { viewModel.toggleLiked(track) },
                            onOpenArtist = onOpenArtist,
                            marquee = state.isPlaying && windowActive,
                            onOpenLyrics = { onTabChange(FullPlayerTab.LYRICS) },
                            onOpenQueue = { onTabChange(FullPlayerTab.QUEUE) },
                            onOpenAudioControls = { dialogs.audioControls = true },
                        )
                    }
                }

                if (wide) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BoxWithConstraints(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .then(skipSwipe),
                            contentAlignment = Alignment.Center,
                        ) {
                            ArtworkCard(
                                track = track,
                                canvas = canvas,
                                isPlaying = state.isPlaying,
                                paused = artPaused,
                                size = minOf(maxWidth, maxHeight - 32.dp).coerceIn(0.dp, MAX_ART_SIZE),
                                offsetX = shownSkipX,
                            )
                        }
                        // Landscape phones are too short for the whole panel, so it scrolls.
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            topBar()
                            Spacer(Modifier.height(16.dp))
                            panel(Modifier)
                        }
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        topBar()
                        // With the hero, the cover is drawn behind; this space only takes the skip swipe.
                        BoxWithConstraints(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .then(skipSwipe),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!hero) {
                                ArtworkCard(
                                    track = track,
                                    canvas = canvas,
                                    isPlaying = state.isPlaying,
                                    paused = artPaused,
                                    size = minOf(maxWidth - 48.dp, maxHeight - 24.dp).coerceIn(0.dp, MAX_ART_SIZE),
                                    offsetX = shownSkipX,
                                )
                            }
                        }
                        panel(Modifier.padding(horizontal = 20.dp))
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
            PlayerDialogs(
                dialogs = dialogs,
                track = track,
                state = state,
                player = player,
                viewModel = viewModel,
                autoMixUi = autoMixUi,
                isOpen = isOpen,
                showAutoEqInVolumePanel = settings.showAutoEqInVolumePanel,
            )
        }
    }
}

private val SWIPE_THRESHOLD = 88.dp
private val MAX_ART_SIZE = 420.dp

@Stable
private class PlayerDialogsState {
    var trackMenu by mutableStateOf(false)
    var signalPath by mutableStateOf(false)
    var audioControls by mutableStateOf(false)
    var autoEq by mutableStateOf(false)
}

@Composable
private fun TopBar(
    skin: PlayerSkin,
    colors: PlayerSkinColors,
    sourceLabel: String,
    onCollapse: () -> Unit,
    onMore: () -> Unit,
) {
    // Material keeps header buttons bare; glass gives them a lens like every other control.
    val chrome = if (skin == PlayerSkin.Glass) colors.container else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkinButton(skin = skin, onClick = onCollapse, container = chrome, contentColor = colors.content, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.ExpandMore, stringResource(R.string.now_playing_minimize), Modifier.size(28.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.now_playing_playing_from),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                fontWeight = FontWeight.Bold,
                color = colors.contentMuted,
            )
            Text(
                sourceLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SkinButton(skin = skin, onClick = onMore, container = chrome, contentColor = colors.content, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.now_playing_more), Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ArtworkCard(
    track: PlayableTrack,
    canvas: CanvasArtwork?,
    isPlaying: Boolean,
    paused: Boolean,
    size: Dp,
    offsetX: Float,
) {
    PlayerArtwork(
        track = track,
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                translationX = offsetX
                rotationZ = offsetX / 80f
            },
        corner = 28.dp,
        canvas = canvas,
        isPlaying = isPlaying,
        pausedForTransition = paused,
    )
}

@Composable
private fun HeroArtwork(
    track: PlayableTrack,
    canvas: CanvasArtwork?,
    isPlaying: Boolean,
    paused: Boolean,
    offsetX: Float,
) {
    PlayerArtwork(
        track = track,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer {
                translationX = offsetX
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                // Fade the lower edge into the backdrop instead of cutting the cover off.
                drawRect(
                    brush = Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
                // The header sits on the cover; keep its white text readable on bright art.
                drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.25f to Color.Transparent))
            },
        corner = 0.dp,
        canvas = canvas,
        isPlaying = isPlaying,
        pausedForTransition = paused,
    )
}

@Composable
private fun GlassBackdrop(
    track: PlayableTrack,
    isPlaying: Boolean,
    rotatingBackgroundEnabled: Boolean,
    fluidBeatSyncEnabled: Boolean,
) {
    FluidArtworkBackground(
        track = track,
        isPlaying = isPlaying,
        modifier = Modifier.fillMaxSize(),
        rotatingBackgroundEnabled = rotatingBackgroundEnabled,
        fluidBeatSyncEnabled = fluidBeatSyncEnabled,
        fallback = {
            // Modifier.blur needs API 31; older devices get a pre-blurred bitmap.
            val legacyBlur = remember {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    listOf(BlurTransformation(radius = 18, maxDimension = 100))
                } else {
                    emptyList()
                }
            }
            PlayerArtwork(
                track = track,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.35f
                        scaleY = 1.35f
                    }
                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(20.dp) else Modifier),
                corner = 0.dp,
                decodeSizePx = 200,
                transformations = legacyBlur,
            )
            // Keeps white text readable over bright covers.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.52f)))
        },
    )
}

@Composable
private fun PlaybackErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = hapticClick(action = onRetry),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(stringResource(R.string.now_playing_tap_to_retry), style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = hapticClick(action = onDismiss)) {
                Icon(Icons.Filled.Close, stringResource(R.string.now_playing_dismiss_error), Modifier.size(18.dp))
            }
        }
    }
}

/** The sheets and dialogs the screen's controls open, the same ones FullPlayer uses. */
@Composable
private fun PlayerDialogs(
    dialogs: PlayerDialogsState,
    track: PlayableTrack,
    state: MusicPlayerState,
    player: MusicPlayer,
    viewModel: PlayerViewModel,
    autoMixUi: AutoMixPopupState,
    isOpen: Boolean,
    showAutoEqInVolumePanel: Boolean,
) {
    if (dialogs.trackMenu) {
        TrackContextMenuSheet(
            target = TrackMenuTarget.Track(track.title, track.artist, ""),
            capabilities = TrackMenuCapabilities(
                showDeleteScrobble = false,
                showRecommendationExclusion = true,
                showPlayAction = false,
            ),
            playableTrack = track,
            onDismiss = { dialogs.trackMenu = false },
            onPlayInLastWave = { player.play(track, sourceLabel = state.sourceLabel) },
            canWatchVideo = false,
        )
    }
    if (dialogs.signalPath) {
        val signalPath by player.signalPath.collectAsStateWithLifecycle()
        val usbDac by player.usbDacState.collectAsStateWithLifecycle()
        val dac = usbDac.dac
        val internalAvailability by player.internalDirectAvailability.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { player.refreshInternalDirectAvailability() }
        SignalPathDialog(
            report = signalPath,
            needsUsbPermission = dac != null && dac.hasUsbPeripheral && !dac.usbPermissionGranted,
            onRequestUsbAccess = player::requestUsbPermission,
            onDismiss = { dialogs.signalPath = false },
            internalAvailability = internalAvailability,
            internalEnabled = player.isInternalDirectPrefEnabled,
            internalUnderruns = if (signalPath.internalDirectActive) player.internalDirectUnderruns() else null,
            usbDiagnostic = if (dac != null) player::usbDiagnosticReport else null,
        )
    }
    if (dialogs.audioControls) {
        AudioControlsSheet(
            player = player,
            state = state,
            onDismiss = { dialogs.audioControls = false },
            onOpenAutoEq = { dialogs.autoEq = true },
            showAutoEq = showAutoEqInVolumePanel,
        )
    }
    if (dialogs.autoEq) {
        AutoEqDialog(onDismiss = { dialogs.autoEq = false })
    }
    AutoMixPopupHost(
        ui = autoMixUi,
        telemetry = state.deckBlendTelemetry,
        progressState = viewModel.progressState,
        trackKey = track.autoMixIdentity(),
        isPlayerOpen = isOpen,
        player = player,
        tracks = remember(state.queue, track) { if (track in state.queue) state.queue else state.queue + track },
    )
}
