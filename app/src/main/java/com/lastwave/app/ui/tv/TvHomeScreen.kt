package com.lastwave.app.ui.tv

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ShuffleOn
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import android.os.Build
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lastwave.app.R
import com.lastwave.app.data.local.db.SongPlayStatsEntity
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.player.LyricsUiState
import com.lastwave.app.ui.theme.ArtworkShape
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Leanback (Android TV) shell: a left icon rail (Home / Search) plus the
 * focused pane. D-pad modelled on a proven TV client:
 *
 *  - exactly one focus owner decides where focus starts (play button when a
 *    track is loaded, the rail's Home item otherwise) so the remote is never
 *    dead on launch;
 *  - LEFT from the content pane always lands on the rail, RIGHT from the rail
 *    re-enters the content, DOWN from the search field reaches the results
 *    (D-pad Up/Down are intercepted on the field itself);
 *  - horizontal shelves steer scroll so the focused card becomes the leading
 *    visible card, and default bring-into-view scrolling is disabled inside
 *    shelves to keep movement calm;
 *  - the remote's media keys (play/pause, next/previous, ff/rew) work from
 *    anywhere on the screen;
 *  - focused elements lift towards the viewer (spring scale + soft shadow,
 *    no outlines), like tvOS.
 *
 * All playback goes through the shared [com.lastwave.app.playback.MusicPlayer]
 * singleton, so the media session, queue and radio behave exactly as on phone.
 */

private val LiftScaleSpring = spring<Float>(dampingRatio = 0.72f, stiffness = 420f)
private val LiftShadowSpring = spring<Dp>(dampingRatio = 1f, stiffness = 420f)
private const val SHELF_GAP_DP = 16

/** tvOS-style focus: the focused element grows, casts a soft shadow and gains a brand ring. */
@Composable
internal fun Modifier.tvLift(
    focused: Boolean,
    shape: Shape,
    focusedScale: Float = 1.06f,
    elevation: Dp = 8.dp,
    pressed: Boolean = false,
): Modifier {
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 1f + (focusedScale - 1f) * 0.35f
            focused -> focusedScale
            else -> 1f
        },
        animationSpec = LiftScaleSpring,
        label = "tvLiftScale",
    )
    val shadow by animateDpAsState(
        targetValue = if (focused && !pressed) elevation else 0.dp,
        animationSpec = LiftShadowSpring,
        label = "tvLiftShadow",
    )
    val ring by animateColorAsState(
        targetValue = if (focused && !pressed) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(durationMillis = 120),
        label = "tvLiftRing",
    )
    return this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            shadowElevation = shadow.toPx()
            this.shape = shape
            clip = true
            ambientShadowColor = Color.Black
            spotShadowColor = Color.Black
        }
        // Visible focus line: users on a D-pad always know which element is active.
        .border(3.dp, ring, shape)
}

/**
 * A shelf must not let the framework scroll it per focus move: the steering
 * effect below is the only scroller, so movement stays calm and reversible.
 */
@OptIn(ExperimentalFoundationApi::class)
private object TvRowNoScrollSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

@Composable
fun TvHomeRoot(viewModel: TvHomeViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableStateOf(TvTab.HOME) }
    val chrome by viewModel.musicPlayer.chromeState.collectAsStateWithLifecycle()
    val playFocus = remember { FocusRequester() }
    val railHomeFocus = remember { FocusRequester() }

    // First-run setup gate (persisted in TvSettings).
    val setupCompleted by TvSettings.setupCompleted.collectAsStateWithLifecycle()

    // Ambient accent for the pane-wide backdrop — same Palette extraction the
    // hero glow uses (Coil caches the decode, so this is a cache hit).
    val rootAmbientHex = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(chrome.current?.artworkUrl) {
        rootAmbientHex.value = chrome.current?.artworkUrl?.let { viewModel.extractAmbientAccent(it) }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val hasTrack = chrome.current != null

    // The single owner of initial focus: play button when a track is loaded,
    // the rail's Home item otherwise. Without this the remote is dead —
    // Compose delivers no key events until some node holds focus.
    LaunchedEffect(tab, hasTrack) {
        if (tab != TvTab.HOME) return@LaunchedEffect
        withFrameNanos { }
        runCatching {
            if (hasTrack) playFocus.requestFocus() else railHomeFocus.requestFocus()
        }
    }

    // Remote BACK returns from any tab to Home instead of leaving the app.
    BackHandler(enabled = tab != TvTab.HOME) {
        keyboard?.hide()
        tab = TvTab.HOME
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    // Remote media keys work from anywhere on the screen.
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                    -> {
                        if (hasTrack) viewModel.togglePlayPause()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
                    android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    -> {
                        if (hasTrack) viewModel.next()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
                    -> {
                        if (hasTrack) viewModel.previous()
                        true
                    }
                    else -> false
                }
            },
        color = MaterialTheme.colorScheme.background,
    ) {
        if (!setupCompleted) {
            // First-run setup: pointer question + both sign-ins (skippable).
            TvSetupScreen(onComplete = { })
        } else {
        Row(modifier = Modifier.fillMaxSize()) {
            TvNavRail(
                selectedTab = tab,
                onSelect = { tab = it },
                isPlaying = chrome.isPlaying,
                homeItemFocus = railHomeFocus,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // LEFT from anywhere in the content that would leave the
                    // pane lands on the rail's Home item — the rail is always
                    // one press away, in every row and every scroll position.
                    .focusProperties { left = railHomeFocus },
            ) {
                // BitCord-style backdrop: the current artwork blurred across
                // the top, dissolving into the page colour, with two soft
                // accent blobs keeping it alive below. Always tinted by the
                // track's palette colour, on every API level.
                TvArtworkBackdrop(
                    artworkUrl = chrome.current?.artworkUrl,
                    accentHex = rootAmbientHex.value,
                )
                // Smooth pane changes: the outgoing tab dissolves away while
                // the incoming one drifts up into place — fast, calm, no bounce.
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val enter = fadeIn(animationSpec = tween(durationMillis = 240, delayMillis = 50)) +
                            slideInVertically(animationSpec = tween(durationMillis = 240, delayMillis = 50)) { it / 24 }
                        enter.togetherWith(fadeOut(animationSpec = tween(durationMillis = 140)))
                    },
                    label = "tvTabPane",
                ) { pane ->
                    when (pane) {
                        TvTab.HOME -> TvHomeTab(
                            viewModel = viewModel,
                            playFocus = playFocus,
                            onSelectSearch = { tab = TvTab.SEARCH },
                            onOpenLyrics = { tab = TvTab.LYRICS },
                            onOpenQueue = { tab = TvTab.QUEUE },
                        )
                        TvTab.QUEUE -> TvQueueTab(viewModel = viewModel, railHomeFocus = railHomeFocus)
                        TvTab.LIBRARY -> TvLibraryTab(viewModel = viewModel, railHomeFocus = railHomeFocus)
                        TvTab.LYRICS -> TvLyricsTab(viewModel = viewModel, railHomeFocus = railHomeFocus)
                        TvTab.SEARCH -> TvSearchTab(viewModel)
                        TvTab.SETTINGS -> TvSettingsTab()
                    }
                }
                // Spotify-TV artist profile: full-pane overlay above the tabs.
                // Fade/slide entry; the pane itself is opaque, so the tab
                // underneath never shows through a half-drawn profile.
                // Fully qualified on purpose: inside this Column+Box nesting
                // the bare name resolves to the ColumnScope extension, which
                // the compiler rejects for a non-innermost implicit receiver.
                androidx.compose.animation.AnimatedVisibility(
                    visible = viewModel.artistName != null,
                    enter = fadeIn(animationSpec = tween(durationMillis = 180)) +
                        slideInVertically(animationSpec = tween(durationMillis = 180)) { it / 6 },
                    exit = fadeOut(animationSpec = tween(durationMillis = 120)),
                ) {
                    TvArtistScreen(viewModel = viewModel)
                }
            }
            // Spotify-TV pattern: a persistent mini now-playing strip under
            // every browse pane (Home keeps its full hero instead). It is
            // informational only — never a focus target — so the remote's
            // navigation grammar is unchanged.
            AnimatedVisibility(
                visible = hasTrack && tab != TvTab.HOME,
                enter = fadeIn(animationSpec = tween(durationMillis = 220)) +
                    slideInVertically(animationSpec = tween(durationMillis = 220)) { it / 3 },
                exit = fadeOut(animationSpec = tween(durationMillis = 140)),
            ) {
                TvMiniNowPlayingBar(viewModel = viewModel)
            }
            }
        }
        }
    }
}

private enum class TvTab { HOME, QUEUE, LIBRARY, LYRICS, SEARCH, SETTINGS }

/** Brand mark: lime disc with the four-bar equalizer, drawn in place (no bitmap). */
@Composable
private fun TvLogoBadge(modifier: Modifier = Modifier) {
    // The original LastWave mark, exactly as the launcher draws it: the true
    // 4-bar logo vector on the brand lime disc (no canvas approximation).
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xFFFFC9FB00)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_logo),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(0.72f),
        )
    }
}

@Composable
private fun TvNavRail(
    selectedTab: TvTab,
    onSelect: (TvTab) -> Unit,
    isPlaying: Boolean,
    homeItemFocus: FocusRequester,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(112.dp)
            .padding(vertical = 40.dp)
            .focusGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TvLogoBadge(modifier = Modifier.size(56.dp))
        Spacer(modifier = Modifier.weight(1f))
        TvRailItem(
            icon = Icons.Rounded.Home,
            label = stringResource(R.string.tv_tab_home),
            selected = selectedTab == TvTab.HOME,
            modifier = Modifier.focusRequester(homeItemFocus),
            onClick = { onSelect(TvTab.HOME) },
        )
        TvRailItem(
            icon = Icons.Rounded.Search,
            label = stringResource(R.string.tv_tab_search),
            selected = selectedTab == TvTab.SEARCH,
            onClick = { onSelect(TvTab.SEARCH) },
        )
        TvRailItem(
            icon = Icons.Rounded.LibraryMusic,
            label = stringResource(R.string.tv_tab_library),
            selected = selectedTab == TvTab.LIBRARY,
            onClick = { onSelect(TvTab.LIBRARY) },
        )
        TvRailItem(
            icon = Icons.Rounded.Settings,
            label = stringResource(R.string.tv_tab_settings),
            selected = selectedTab == TvTab.SETTINGS,
            onClick = { onSelect(TvTab.SETTINGS) },
        )
        Spacer(modifier = Modifier.weight(1f))
        // "Playing" badge fades and pops in instead of appearing abruptly.
        AnimatedVisibility(
            visible = isPlaying,
            enter = fadeIn(animationSpec = tween(durationMillis = 300)) +
                scaleIn(
                    initialScale = 0.6f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ),
            exit = fadeOut(animationSpec = tween(durationMillis = 180)),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp).alpha(0.9f),
            )
        }
    }
}

/** Rail entry: focus lifts the disc; no outlines. */
@Composable
private fun TvRailItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    // ArchiveTune's rail grammar: wide soft pills, a bouncy lift on focus,
    // and a distinct surface colour per state (focused / selected / idle).
    val pillScale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "tvRailPillScale",
    )
    // Focus colours glide instead of snapping — the pill reads as one
    // continuously morphing surface as the remote moves between entries.
    val pillColor by animateColorAsState(
        targetValue = when {
            isFocused -> MaterialTheme.colorScheme.secondaryContainer
            selected -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(durationMillis = 150),
        label = "tvRailPillColor",
    )
    val pillIconTint by animateColorAsState(
        targetValue = when {
            isFocused -> MaterialTheme.colorScheme.onSecondaryContainer
            selected -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 150),
        label = "tvRailIconTint",
    )
    val pillLabelColor by animateColorAsState(
        targetValue = when {
            isFocused -> MaterialTheme.colorScheme.onSecondaryContainer
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 150),
        label = "tvRailLabelColor",
    )
    Column(
        modifier = modifier.width(104.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .scale(pillScale)
                .tvLift(focused = isFocused, shape = RoundedCornerShape(28.dp), focusedScale = 1f, elevation = 7.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(pillColor)
                .onFocusChanged { isFocused = it.isFocused }
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = pillIconTint,
                modifier = Modifier.size(26.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = pillLabelColor,
                maxLines = 1,
            )
        }
    }
}

/**
 * BitCord's ArtworkBackdrop, adapted to the TV pane: the current artwork
 * blurred across the top (API 31+ only — RenderEffect), dissolving down into
 * the page colour through the artwork's own tint, and two soft radial accent
 * blobs below so the lower half never reads as dead black. The blobs are a
 * plain Canvas draw — no image, no blur — so they cost the same on every
 * device.
 */
@Composable
private fun TvArtworkBackdrop(
    artworkUrl: String?,
    accentHex: String?,
    modifier: Modifier = Modifier,
) {
    val parsedTint = accentHex
        ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        ?: MaterialTheme.colorScheme.primary
    // Palette shifts glide over ~0.7 s when the track (and its colour) changes.
    val tint by animateColorAsState(
        targetValue = parsedTint,
        animationSpec = tween(durationMillis = 700),
        label = "tvBackdropTint",
    )
    val background = MaterialTheme.colorScheme.background
    // Real-time blur is the single most expensive thing on low-end TV GPUs.
    // Low-RAM devices skip it and keep the tinted blobs + gradient — still
    // colourful, zero blur cost. Everywhere else the radius is trimmed.
    val backdropContext = LocalContext.current
    val canAffordBlur = remember(backdropContext) {
        val am = backdropContext.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as? android.app.ActivityManager
        am?.isLowRamDevice != true
    }
    Box(modifier.background(Color.Transparent)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canAffordBlur) {
            // Artwork changes dissolve instead of hard-cutting.
            Crossfade(
                targetState = artworkUrl,
                animationSpec = tween(durationMillis = 380),
                label = "tvBackdropArt",
            ) { url ->
                if (url != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(url)
                            // The backdrop is blurred to nothing — decoding
                            // full-res art here only wastes memory.
                            .size(960)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.62f)
                            // Overscaled so the blur's clamped edges never reach a
                            // visible one.
                            .graphicsLayer {
                                scaleX = 1.4f
                                scaleY = 1.4f
                            }
                            .blur(40.dp, BlurredEdgeTreatment.Unbounded),
                    )
                }
            }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            fun blob(color: Color, at: Offset, radiusFraction: Float) {
                val center = Offset(at.x * size.width, at.y * size.height)
                val radius = size.width * radiusFraction
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color, color.copy(alpha = 0f)),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
            blob(tint.copy(alpha = 0.10f), Offset(0.10f, 0.52f), 0.85f)
            blob(tint.copy(alpha = 0.07f), Offset(0.95f, 0.80f), 0.95f)
        }
        // Dissolve the blurred art (and tint) into the page colour.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to tint.copy(alpha = 0.24f),
                        0.22f to tint.copy(alpha = 0.34f),
                        0.50f to tint.copy(alpha = 0.55f),
                        0.72f to background,
                    ),
                ),
        )
    }
}

@Composable
private fun TvHomeTab(
    viewModel: TvHomeViewModel,
    playFocus: FocusRequester,
    onSelectSearch: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item(key = "greeting") { TvGreeting() }
        item(key = "hero") {
            TvNowPlayingHero(
                viewModel = viewModel,
                playFocus = playFocus,
                onSelectSearch = onSelectSearch,
                onOpenLyrics = onOpenLyrics,
                onOpenQueue = onOpenQueue,
            )
        }
        if (viewModel.homeSuggestions.isNotEmpty()) {
            item(key = "suggested-header") {
                TvSectionTitle(stringResource(R.string.tv_suggested_for_you))
            }
            item(key = "suggested-row") {
                TvSuggestionShelf(tracks = viewModel.homeSuggestions, onPlay = viewModel::playTrack)
            }
        } else if (viewModel.suggestionsLoading) {
            // ArchiveTune/DollBloom-style skeleton shelf: the feed loads with
            // a visible pulse instead of the section silently missing.
            item(key = "suggested-skeleton-header") {
                TvSectionTitle(stringResource(R.string.tv_suggested_for_you))
            }
            item(key = "suggested-skeleton") { TvSkeletonShelf() }
        }
        if (viewModel.topTracks.isNotEmpty()) {
            item(key = "top-header") { TvSectionTitle(stringResource(R.string.tv_top_tracks)) }
            item(key = "top-row") {
                TvShelfRow(rows = viewModel.topTracks, onPlay = viewModel::playHistoryRow)
            }
        }
        if (viewModel.recentTracks.isNotEmpty()) {
            item(key = "recent-header") { TvSectionTitle(stringResource(R.string.tv_recently_played)) }
            item(key = "recent-row") {
                TvShelfRow(rows = viewModel.recentTracks, onPlay = viewModel::playHistoryRow)
            }
        }
        if (viewModel.topTracks.isEmpty() && viewModel.recentTracks.isEmpty() && viewModel.historyLoaded) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.tv_empty_history),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TvGreeting() {
    val hour = LocalTime.now().hour
    val greeting = when (hour) {
        in 5..11 -> stringResource(R.string.tv_greeting_morning)
        in 12..16 -> stringResource(R.string.tv_greeting_afternoon)
        in 17..21 -> stringResource(R.string.tv_greeting_evening)
        else -> stringResource(R.string.tv_greeting_night)
    }
    Column {
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val formatter = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()) }
        Text(
            text = remember { LocalDate.now().format(formatter) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun TvSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** Now-playing hero: the screen's single focal point — art, words, transport. */
@Composable
private fun TvNowPlayingHero(
    viewModel: TvHomeViewModel,
    playFocus: FocusRequester,
    onSelectSearch: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val chrome by viewModel.musicPlayer.chromeState.collectAsStateWithLifecycle()
    val shuffle by viewModel.shuffleEnabled.collectAsStateWithLifecycle()
    val repeatMode by viewModel.repeatMode.collectAsStateWithLifecycle()
    val track: PlayableTrack? = chrome.current
    val canvas = viewModel.canvas
    // Ambient color from the track artwork — the same Palette extraction the
    // phone's dynamic theme uses, fetched off the main thread on artwork
    // change and animated so track switches cross-fade the glow.
    val ambientHex = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(track?.artworkUrl) {
        ambientHex.value = track?.artworkUrl?.let { viewModel.extractAmbientAccent(it) }
    }
    val ambientBase = ambientHex.value
        ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        ?: MaterialTheme.colorScheme.primary
    val heroActive = track != null && chrome.isPlaying
    // Static-artwork tracks (no Canvas) get a slightly stronger glow —
    // there is more empty panel for the album color to fill.
    val heroWash = when {
        !heroActive -> 0.10f
        canvas != null -> 0.22f
        else -> 0.28f
    }
    val heroHalo = when {
        !heroActive -> 0.12f
        canvas != null -> 0.32f
        else -> 0.38f
    }
    val ambient by animateColorAsState(
        targetValue = ambientBase,
        animationSpec = tween(durationMillis = 900),
        label = "tvHeroAmbient",
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        // Ambient wash behind the whole hero panel, tinted by the artwork.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            ambient.copy(alpha = heroWash),
                            ambient.copy(alpha = heroWash / 3f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            // Motion artwork (Spotify Canvas & friends) when a clip exists for the
            // track; static cover art otherwise.
            Box(modifier = Modifier.size(220.dp)) {
                // Radial glow bleeding out from behind the canvas,
                // tinted by the artwork color.
                Box(
                    modifier = Modifier
                        .requiredSize(360.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    ambient.copy(alpha = heroHalo),
                                    ambient.copy(alpha = 0.16f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
                // Original album art is the floor layer: it dissolves between
                // covers on track change and stays visible until the Canvas
                // clip actually renders.
                Crossfade(
                    targetState = track?.artworkUrl,
                    animationSpec = tween(durationMillis = 420),
                    label = "tvHeroArt",
                ) { url ->
                    TvArtwork(artworkUrl = url, size = 220.dp)
                }
                if (canvas != null) {
                    // CanvasVideoPlayer holds its surface fully transparent
                    // until the first video frame (and on playback error), so
                    // the artwork above stays visible while the clip loads —
                    // and fades in over the cover only once it truly works.
                    com.lastwave.app.ui.player.CanvasVideoPlayer(
                        videoUrl = canvas.url,
                        isPlaying = chrome.isPlaying,
                        enableScrim = false,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(ArtworkShape),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (track == null) {
                    // Nothing playing: this panel is itself a focus target so the
                    // content pane is always reachable with the remote. Activating
                    // it opens Search.
                    val interaction = remember { MutableInteractionSource() }
                    var promptFocused by remember { mutableStateOf(false) }
    val promptPanelColor by animateColorAsState(
        targetValue = if (promptFocused) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(durationMillis = 160),
        label = "tvPromptPanel",
    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvLift(focused = promptFocused, shape = RoundedCornerShape(20.dp), focusedScale = 1.02f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(promptPanelColor)
                            .onFocusChanged { promptFocused = it.isFocused }
                            .clickable(interactionSource = interaction, indication = null, onClick = onSelectSearch)
                            .padding(horizontal = 24.dp, vertical = 28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.tv_nothing_playing),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.tv_nothing_playing_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    // Spotify-TV affordance: the artist name is itself a
                    // focusable link into the artist profile.
                    TvHeroArtistLink(
                        title = track.artist,
                        onClick = { viewModel.openArtist(track.artist) },
                    )
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // D-pad scrub row: focus it and press LEFT/RIGHT for ±10 s.
                    // Focus is unmissable: the whole strip gets a brand ring, a
                    // tinted pill backdrop, the bar doubles in height and the
                    // thumb blooms (BitChord-style slider affordance).
                    TvHeroProgressBlock(viewModel = viewModel)

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TvTransportButton(
                            icon = if (shuffle) Icons.Rounded.ShuffleOn else Icons.Rounded.Shuffle,
                            contentDescription = stringResource(R.string.tv_shuffle),
                            active = shuffle,
                            onClick = viewModel::toggleShuffle,
                        )
                        TvTransportButton(
                            icon = Icons.Rounded.SkipPrevious,
                            contentDescription = stringResource(R.string.tv_previous),
                            onClick = viewModel::previous,
                        )
                        TvTransportButton(
                            icon = if (chrome.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (chrome.isPlaying) {
                                stringResource(R.string.tv_pause)
                            } else {
                                stringResource(R.string.tv_play)
                            },
                            containerColor = MaterialTheme.colorScheme.primary,
                            iconTint = MaterialTheme.colorScheme.onPrimary,
                            iconSize = 40.dp,
                            buttonSize = 72.dp,
                            modifier = Modifier.focusRequester(playFocus),
                            onClick = viewModel::togglePlayPause,
                        )
                        TvTransportButton(
                            icon = Icons.Rounded.SkipNext,
                            contentDescription = stringResource(R.string.tv_next),
                            onClick = viewModel::next,
                        )
                        TvTransportButton(
                            icon = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            contentDescription = stringResource(R.string.tv_repeat),
                            active = repeatMode != Player.REPEAT_MODE_OFF,
                            onClick = viewModel::cycleRepeatMode,
                        )
                    }
                }
            }
            if (track != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TvHeroLyricsButton(onClick = onOpenLyrics)
                    TvHeroQueueButton(onClick = onOpenQueue)
                }
            }
        }
    }
}

/**
 * The hero's position-driven section — live synced lyric line above the
 * D-pad seek strip, then the strip and the timestamps — composed in one
 * small scope so the frequent position ticks never recompose the hero's
 * artwork, titles or transport controls.
 */
@Composable
private fun TvHeroProgressBlock(viewModel: TvHomeViewModel) {
    val progress by viewModel.musicPlayer.progressState.collectAsStateWithLifecycle()
    val seekBarFocus = remember { FocusRequester() }
    var seekBarFocused by remember { mutableStateOf(false) }
    val seekPill by animateColorAsState(
        targetValue = if (seekBarFocused) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 160),
        label = "tvSeekPill",
    )
    val seekRing by animateColorAsState(
        targetValue = if (seekBarFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(durationMillis = 160),
        label = "tvSeekRing",
    )
    // Live "now singing" line shown above the seek strip. Only synced lyrics
    // surface here — plain or missing lyrics render nothing (the round
    // Lyrics button on the right still opens the full view). Deriving from
    // the position flow keeps per-tick work to a single scan, mirroring the
    // Lyrics tab's own behaviour.
    val heroLyricLines = (viewModel.lyricsState as? LyricsUiState.Success)
        ?.takeIf { it.isSynced }
        ?.lines
        .orEmpty()
    val heroLyricLine by remember(heroLyricLines) {
        derivedStateOf {
            val positionMs = progress.positionMs
            var text: String? = null
            for (line in heroLyricLines) {
                if (line.timeMs <= positionMs) text = line.text else break
            }
            text
        }
    }


    // Live synced lyric line above the progress bar: fades and slides
    // between lines in sync with playback, and disappears entirely when a
    // track has no synced lyrics.
    AnimatedContent(
        targetState = heroLyricLine,
        transitionSpec = {
            (fadeIn(animationSpec = tween(durationMillis = 220)) +
                slideInVertically(animationSpec = tween(durationMillis = 220)) { it / 3 })
                .togetherWith(fadeOut(animationSpec = tween(durationMillis = 120)))
        },
        label = "tvHeroLyricLine",
    ) { line ->
        if (line != null) {
            Text(
                text = line,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .focusRequester(seekBarFocus)
            .focusable()
            .onFocusChanged { seekBarFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val delta = when (event.key) {
                    Key.DirectionRight -> 10_000L
                    Key.DirectionLeft -> -10_000L
                    else -> return@onPreviewKeyEvent false
                }
                viewModel.seekTo(
                    (progress.positionMs + delta).coerceIn(0L, progress.durationMs),
                )
                true
            }
            .clip(RoundedCornerShape(percent = 50))
            .background(seekPill)
            .border(
                width = 3.dp,
                color = seekRing,
                shape = RoundedCornerShape(percent = 50),
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val fraction = if (progress.durationMs > 0) {
            (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
        } else 0f
        val smoothFraction by animateFloatAsState(
            targetValue = fraction,
            animationSpec = tween(durationMillis = 120, easing = LinearEasing),
            label = "tvSeekFraction",
        )
        val barHeight by animateDpAsState(
            targetValue = if (seekBarFocused) 12.dp else 6.dp,
            animationSpec = LiftShadowSpring,
            label = "tvSeekHeight",
        )
        val thumbSize by animateDpAsState(
            targetValue = if (seekBarFocused) 26.dp else 14.dp,
            animationSpec = LiftShadowSpring,
            label = "tvSeekThumb",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(smoothFraction)
                .height(barHeight)
                .clip(RoundedCornerShape(percent = 50))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .offset(x = (maxWidth - thumbSize) * smoothFraction)
                .size(thumbSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onPrimary)
                .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = DateUtils.formatElapsedTime(progress.positionMs / 1000),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = DateUtils.formatElapsedTime(progress.durationMs / 1000),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Round Lyrics shortcut on the hero's right edge — OK opens the Lyrics tab. */
@Composable
private fun TvHeroLyricsButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    val heroDiscColor by animateColorAsState(
        targetValue = if (isFocused) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(durationMillis = 160),
        label = "tvHeroDiscColor",
    )
    val heroLabelColor by animateColorAsState(
        targetValue = if (isFocused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 160),
        label = "tvHeroLabelColor",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .tvLift(focused = isFocused, shape = CircleShape, focusedScale = 1.12f, elevation = 8.dp)
                .clip(CircleShape)
                .background(heroDiscColor)
                .onFocusChanged { isFocused = it.isFocused }
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = stringResource(R.string.tv_tab_lyrics),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            text = stringResource(R.string.tv_tab_lyrics),
            style = MaterialTheme.typography.labelMedium,
            color = heroLabelColor,
        )
    }
}

/** Round Queue shortcut under the Lyrics button — OK opens the Queue tab. */
@Composable
private fun TvHeroQueueButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    val heroDiscColor by animateColorAsState(
        targetValue = if (isFocused) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(durationMillis = 160),
        label = "tvHeroDiscColor",
    )
    val heroLabelColor by animateColorAsState(
        targetValue = if (isFocused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 160),
        label = "tvHeroLabelColor",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .tvLift(focused = isFocused, shape = CircleShape, focusedScale = 1.12f, elevation = 8.dp)
                .clip(CircleShape)
                .background(heroDiscColor)
                .onFocusChanged { isFocused = it.isFocused }
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                contentDescription = stringResource(R.string.tv_tab_queue),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            text = stringResource(R.string.tv_tab_queue),
            style = MaterialTheme.typography.labelMedium,
            color = heroLabelColor,
        )
    }
}

private val TransportShape = CircleShape

@Composable
private fun TvTransportButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    containerColor: Color = Color.Transparent,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    iconSize: Dp = 28.dp,
    buttonSize: Dp = 56.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    val transportBackground by animateColorAsState(
        targetValue = when {
            isFocused && containerColor == Color.Transparent -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> containerColor
        },
        animationSpec = tween(durationMillis = 150),
        label = "tvTransportBackground",
    )
    val transportTint by animateColorAsState(
        targetValue = when {
            isFocused && containerColor == Color.Transparent -> MaterialTheme.colorScheme.onSurface
            active && containerColor == Color.Transparent -> MaterialTheme.colorScheme.primary
            else -> iconTint
        },
        animationSpec = tween(durationMillis = 150),
        label = "tvTransportTint",
    )
    Box(
        modifier = modifier
            .size(buttonSize)
            .tvLift(
                focused = isFocused,
                shape = TransportShape,
                focusedScale = 1.14f,
                elevation = 6.dp,
            )
            .clip(TransportShape)
            .background(transportBackground)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = transportTint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Horizontal shelf with BitChord-style focus steering: entering the row lands
 * on the leading visible card, the focused card is scrolled to the lead
 * position, and the framework's per-focus bring-into-view scroll is disabled
 * inside the shelf so nothing double-scrolls.
 */
@OptIn(ExperimentalFoundationApi::class)
/**
 * BitChord-style suggestion shelf: a horizontal run of [TvTrackCard]s fed by the
 * YouTube Music home feed (chart fallback), placed at the very top of the home
 * tab so the screen opens with concrete music, not an empty shell.
 */
@Composable
private fun TvSuggestionShelf(tracks: List<YouTubeMusicTrack>, onPlay: (YouTubeMusicTrack) -> Unit) {
    val state = rememberLazyListState()
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var leadIndex by remember { mutableIntStateOf(0) }
    val entry = remember { FocusRequester() }
    val density = LocalDensity.current

    LaunchedEffect(focusedIndex) {
        if (focusedIndex < 0) return@LaunchedEffect
        val info = state.layoutInfo
        val itemSize = info.visibleItemsInfo.firstOrNull()?.size ?: return@LaunchedEffect
        val gap = with(density) { SHELF_GAP_DP.dp.toPx() }
        val slots = ((info.viewportSize.width + gap) / (itemSize + gap)).toInt().coerceAtLeast(1)
        val next = when {
            focusedIndex < leadIndex -> focusedIndex
            focusedIndex > leadIndex + slots - 1 -> focusedIndex - slots + 1
            else -> leadIndex
        }
        if (next != leadIndex || state.firstVisibleItemIndex != next || state.firstVisibleItemScrollOffset != 0) {
            leadIndex = next
            state.animateScrollToItem(next)
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides TvRowNoScrollSpec) {
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(SHELF_GAP_DP.dp),
            modifier = Modifier
                .fillMaxWidth()
                .focusProperties { onEnter = { runCatching { entry.requestFocus() } } }
                .focusGroup(),
        ) {
            itemsIndexed(tracks, key = { index, track -> "$index-${track.videoId}" }) { index, track ->
                Box(
                    modifier = Modifier
                        .then(if (index == leadIndex) Modifier.focusRequester(entry) else Modifier)
                        .onFocusChanged { if (it.hasFocus) focusedIndex = index },
                ) {
                    TvTrackCard(
                        title = track.title,
                        artist = track.artist,
                        artworkUrl = track.artworkUrl,
                        onClick = { onPlay(track) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvShelfRow(rows: List<SongPlayStatsEntity>, onPlay: (SongPlayStatsEntity) -> Unit) {
    val state = rememberLazyListState()
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var leadIndex by remember { mutableIntStateOf(0) }
    val entry = remember { FocusRequester() }
    val density = LocalDensity.current

    LaunchedEffect(focusedIndex) {
        if (focusedIndex < 0) return@LaunchedEffect
        val info = state.layoutInfo
        val itemSize = info.visibleItemsInfo.firstOrNull()?.size ?: return@LaunchedEffect
        val gap = with(density) { SHELF_GAP_DP.dp.toPx() }
        val slots = ((info.viewportSize.width + gap) / (itemSize + gap)).toInt().coerceAtLeast(1)
        val next = when {
            focusedIndex < leadIndex -> focusedIndex
            focusedIndex > leadIndex + slots - 1 -> focusedIndex - slots + 1
            else -> leadIndex
        }
        if (next != leadIndex || state.firstVisibleItemIndex != next || state.firstVisibleItemScrollOffset != 0) {
            leadIndex = next
            state.animateScrollToItem(next)
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides TvRowNoScrollSpec) {
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(SHELF_GAP_DP.dp),
            modifier = Modifier
                .fillMaxWidth()
                .focusProperties { onEnter = { runCatching { entry.requestFocus() } } }
                .focusGroup(),
        ) {
            itemsIndexed(rows, key = { index, row -> "$index-${row.trackKey}" }) { index, row ->
                Box(
                    modifier = Modifier
                        .then(if (index == leadIndex) Modifier.focusRequester(entry) else Modifier)
                        .onFocusChanged { if (it.hasFocus) focusedIndex = index },
                ) {
                    TvTrackCard(
                        title = row.title,
                        artist = row.artist,
                        artworkUrl = row.artworkUrl,
                        onClick = { onPlay(row) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvTrackCard(
    title: String,
    artist: String,
    artworkUrl: String?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    // Gentle Ken-Burns push on focus — the card breathes towards you.
    val artZoom by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1f,
        animationSpec = tween(durationMillis = 220),
        label = "tvCardArtZoom",
    )
    Column(
        modifier = Modifier
            .width(184.dp)
            .tvLift(focused = isFocused, shape = ArtworkShape, focusedScale = 1.07f, elevation = 10.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(184.dp)
                .clip(ArtworkShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (artworkUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(artworkUrl)
                        .size(400)
                        .crossfade(220)
                        .build(),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = artZoom
                            scaleY = artZoom
                        },
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
            }
            // Spotify-TV affordance: a play badge blooms on the focused card.
            val badgeScale by animateFloatAsState(
                targetValue = if (isFocused) 1f else 0.4f,
                animationSpec = LiftScaleSpring,
                label = "tvCardBadgeScale",
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .scale(badgeScale)
                    .alpha(if (isFocused) 1f else 0f)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TvArtwork(artworkUrl: String?, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(ArtworkShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (artworkUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(artworkUrl)
                    // Decode no larger than this composable draws, so hero and
                    // small rows share one cache key per artwork size class.
                    .size(with(LocalDensity.current) { size.roundToPx() })
                    .crossfade(220)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp),
            )
        }
    }
}

/** Remote-keyboard search: results land as a vertical list under the field. */
@Composable
private fun TvSearchTab(viewModel: TvHomeViewModel) {
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        fieldFocus.requestFocus()
        keyboard?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tv_search_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldFocus)
                // One line, so Up and Down have nothing to do inside the
                // field: they always move focus. Without this a D-pad gets
                // trapped in the field and the results are unreachable.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            // Results take over: dismiss the IME so the rows
                            // are fully visible while focus moves down.
                            keyboard?.hide()
                            focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                            true
                        }
                        else -> false
                    }
                },
            placeholder = { Text(stringResource(R.string.tv_search_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        )
        when {
            viewModel.isSearching -> Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            viewModel.searchResults.isEmpty() && query.isNotBlank() -> Text(
                text = stringResource(R.string.tv_search_no_results),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item(key = "artist-chips") {
                    // Spotify-TV grammar: distinct artists from the results
                    // surface as chips on top; OK opens the artist profile.
                    val artists = remember(viewModel.searchResults) {
                        viewModel.searchResults.map { it.artist }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .take(6)
                    }
                    if (artists.size >= 2) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(artists, key = { index, artist -> "$index-$artist" }) { _, artist ->
                                TvArtistChip(label = artist, onClick = { viewModel.openArtist(artist) })
                            }
                        }
                    }
                }
                itemsIndexed(viewModel.searchResults, key = { index, track -> "$index-${track.videoId}" }) { _, track ->
                    TvSearchResultRow(track = track, onPlay = { viewModel.playTrack(track) })
                }
            }
        }
    }
}

@Composable
private fun TvSearchResultRow(track: YouTubeMusicTrack, onPlay: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    val rowBackground by animateColorAsState(
        targetValue = if (isFocused) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        animationSpec = tween(durationMillis = 160),
        label = "tvSearchRowBackground",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvLift(
                focused = isFocused,
                shape = RoundedCornerShape(16.dp),
                focusedScale = 1.015f,
                elevation = 5.dp,
            )
            .clip(RoundedCornerShape(16.dp))
            .background(rowBackground)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onPlay)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TvArtwork(artworkUrl = track.artworkUrl, size = 56.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = stringResource(R.string.tv_play),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * Spotify-TV-style persistent mini now-playing strip: art, title, a hairline
 * progress bar and elapsed/total. Informational only — never a focus target,
 * so the D-pad grammar of every pane stays exactly as before; media keys and
 * the Home hero drive playback.
 */
@Composable
private fun TvMiniNowPlayingBar(viewModel: TvHomeViewModel) {
    val chrome by viewModel.musicPlayer.chromeState.collectAsStateWithLifecycle()
    val track = chrome.current ?: return
    // BitCord's bottom fade scrim: content dissolves into the strip instead
    // of the strip drawing a hard line under the pane.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.35f to MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                    1f to MaterialTheme.colorScheme.background,
                ),
            )
            .padding(start = 48.dp, end = 48.dp, top = 10.dp, bottom = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TvArtwork(artworkUrl = track.artworkUrl, size = 44.dp)
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (chrome.isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        TvMiniProgress(viewModel = viewModel)
    }
}

/** Hairline progress under the mini strip — isolated so position ticks recompose only this. */
@Composable
private fun TvMiniProgress(viewModel: TvHomeViewModel) {
    val progress by viewModel.musicPlayer.progressState.collectAsStateWithLifecycle()
    val fraction = if (progress.durationMs > 0) {
        (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
    } else 0f
    val smoothFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "tvMiniFraction",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(3.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(smoothFraction)
                    .height(3.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Text(
            text = DateUtils.formatElapsedTime(progress.positionMs / 1000),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = DateUtils.formatElapsedTime(progress.durationMs / 1000),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** ArchiveTune/DollBloom-style pulsing skeleton shelf, shown while the feed loads. */
@Composable
private fun TvSkeletonShelf() {
    val pulse = rememberInfiniteTransition(label = "tvSkeletonPulse").animateFloat(
        initialValue = 0.4f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tvSkeletonAlpha",
    ).value
    Row(horizontalArrangement = Arrangement.spacedBy(SHELF_GAP_DP.dp)) {
        repeat(5) {
            Column(modifier = Modifier.width(184.dp)) {
                Box(
                    modifier = Modifier
                        .size(184.dp)
                        .alpha(pulse)
                        .clip(ArtworkShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                )
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .width(128.dp)
                        .height(14.dp)
                        .alpha(pulse)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .width(84.dp)
                        .height(12.dp)
                        .alpha(pulse)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                )
            }
        }
    }
}

/**
 * Spotify-TV artist profile: a hero band tinted by the first song's art, the
 * artist name over it, a Play button (radio follows), and top songs as rows.
 * Opened from the hero artist link or the search artist chips; BACK closes.
 */
@Composable
private fun TvArtistScreen(viewModel: TvHomeViewModel) {
    val name = viewModel.artistName ?: return
    BackHandler { viewModel.closeArtist() }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(name) {
        withFrameNanos { }
        runCatching { playFocus.requestFocus() }
    }
    LazyColumn(
        // Opaque pane: this profile overlays the tab content, so without a
        // solid background the underlying screen shows through every gap.
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "artist-hero") {
            val heroUrl = viewModel.artistSongs.firstOrNull()?.artworkUrl
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(ArtworkShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                if (heroUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(heroUrl)
                            .size(900)
                            .crossfade(220)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.08f
                                scaleY = 1.08f
                            },
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.72f),
                            ),
                        ),
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(20.dp),
                )
            }
        }
        item(key = "artist-play") {
            TvTransportButton(
                icon = Icons.Rounded.PlayArrow,
                contentDescription = stringResource(R.string.tv_artist_play),
                containerColor = MaterialTheme.colorScheme.primary,
                iconTint = MaterialTheme.colorScheme.onPrimary,
                iconSize = 34.dp,
                buttonSize = 64.dp,
                modifier = Modifier.focusRequester(playFocus),
                onClick = {
                    viewModel.artistSongs.firstOrNull()?.let { viewModel.playTrack(it) }
                },
            )
        }
        when {
            viewModel.artistLoading -> item(key = "artist-loading") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = stringResource(R.string.tv_artist_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            viewModel.artistSongs.isEmpty() -> item(key = "artist-empty") {
                Text(
                    text = stringResource(R.string.tv_artist_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                item(key = "artist-top") { TvSectionTitle(stringResource(R.string.tv_artist_top_songs)) }
                itemsIndexed(viewModel.artistSongs, key = { index, track -> "$index-${track.videoId}" }) { _, track ->
                    TvSearchResultRow(track = track, onPlay = { viewModel.playTrack(track) })
                }
            }
        }
    }
}

/** Spotify-TV affordance: the hero's artist name is itself a link. */
@Composable
private fun TvHeroArtistLink(title: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .tvLift(focused = isFocused, shape = RoundedCornerShape(8.dp), focusedScale = 1.03f, elevation = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Artist chip above search results — the pill grammar from Library sort. */
@Composable
private fun TvArtistChip(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    val chipColor by animateColorAsState(
        targetValue = when {
            isFocused -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(durationMillis = 150),
        label = "tvArtistChipColor",
    )
    Box(
        modifier = Modifier
            .tvLift(focused = isFocused, shape = RoundedCornerShape(20.dp), focusedScale = 1.05f, elevation = 5.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(chipColor)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isFocused) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}
