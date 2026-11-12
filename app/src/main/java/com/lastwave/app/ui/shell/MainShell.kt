package com.lastwave.app.ui.shell

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import android.view.HapticFeedbackConstants
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalView
import android.graphics.Bitmap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.core.graphics.scale
import com.lastwave.app.ui.theme.drawInteractiveGlass
import com.lastwave.app.ui.theme.liquidGlass
import com.lastwave.app.ui.theme.rememberGlassInteraction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.IntBuffer
import kotlin.time.Duration.Companion.seconds
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.ui.common.AmbientAnimationsGate
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.common.PredictiveBackScreen
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.feed.FeedScreen
import com.lastwave.app.ui.discover.DiscoverScreen
import com.lastwave.app.ui.home.HomeScreen
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import com.lastwave.app.ui.playlist.PlaylistScreen
import com.lastwave.app.ui.search.SearchScreen
import androidx.compose.foundation.shape.CornerBasedShape
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.sign
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.LocalMd3NavBar
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor
import com.lastwave.app.ui.theme.LayerBackdrop
import com.lastwave.app.ui.theme.rememberLayerBackdrop
import com.lastwave.app.ui.theme.isLiquidGlassBackdropSupported
import com.lastwave.app.ui.theme.liquidGlassSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import javax.inject.Inject

/** Thin bridge exposing AppUpdateManager's live update state to MainShell */
@HiltViewModel
class MainShellViewModel @Inject constructor(
    val appUpdateManager: com.lastwave.app.data.update.AppUpdateManager,
    val ytAuthManager: com.lastwave.app.data.ytmusic.YtMusicAuthManager,
    val settingsPreferences: com.lastwave.app.data.local.SettingsPreferences,
) : ViewModel() {
    val updateInfo = appUpdateManager.updateInfo
    val ytConnection = ytAuthManager.connection
    val settings = settingsPreferences.settings

    fun dismissUpdate(version: String) {
        appUpdateManager.dismissUpdate(version)
    }

    fun openUpdate(context: android.content.Context) {
        appUpdateManager.openUpdate(context)
    }

    fun setSuppressYouTubeLoginPrompt(suppress: Boolean) {
        viewModelScope.launch {
            settingsPreferences.setSuppressYouTubeLoginPrompt(suppress)
        }
    }

    fun setDismissedJoinSupportPrompt(dismissed: Boolean = true) {
        viewModelScope.launch {
            settingsPreferences.setDismissedJoinSupportPrompt(dismissed)
        }
    }
}

private enum class MainTab(val labelRes: Int) {
    HOME(com.lastwave.app.R.string.nav_home),
    DISCOVER(com.lastwave.app.R.string.nav_discover),
    SEARCH(com.lastwave.app.R.string.search_cd),
    PLAYLISTS(com.lastwave.app.R.string.nav_playlists),
    STATS(com.lastwave.app.R.string.nav_stats),
}

/** Shared with any screen hosted inside [MainShell] so their scrolling
 *  lists know how much bottom content padding to reserve — the nav
 *  overlays content (it's not a Scaffold bottomBar reserving space), so
 *  each screen leaves this much room for its last item to clear it. */
object FloatingNavDefaults {
    val ContentBottomPadding = 112.dp

    /**
     * Full bottom clearance for edge-to-edge scrolling content: the floating
     * dock's visual height + margins ([ContentBottomPadding]) PLUS the live
     * navigation-bar (gesture area) inset. Screens that let their list draw
     * beneath the transparent gesture area must use this instead of the raw
     * constant, otherwise the last row hides behind the dock/gesture bar.
     */
    @Composable
    fun contentBottomPadding(): Dp {
        val hideNav = com.lastwave.app.ui.theme.LocalHideNavTabs.current
        val basePadding = when {
            hideNav -> 16.dp
            else -> ContentBottomPadding
        }
        return basePadding +
            LocalMiniPlayerScrollClearance.current +
            WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    }
}

// Capsule matches the mini-player card shape whenever the dock is visible
// (mini collapses to a RoundedCornerShape(100) capsule when it isn't edge-to-edge).
private val DockShape: CornerBasedShape = RoundedCornerShape(percent = 100)
private val PillShape: Shape = CircleShape
private val NavigationAccent = Color(0xFFE8BDC7)
private val NavigationInactive = Color(0xFFA9A3A6)

private fun <T> navSpring() = ExpressiveMotion.spatialSpring<T>()

@Composable
fun MainShell(
    onOpenSettings: () -> Unit,
    onOpenGenres: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenFriendProfile: (username: String, displayName: String?, avatarUrl: String?) -> Unit = { _, _, _ -> },
    onOpenFeedPlaylist: (String) -> Unit,
    onOpenPlaylist: (Long) -> Unit = {},
    onOpenArtist: (String, String?) -> Unit,
    onOpenAlbum: (String, String, String?) -> Unit,
    onOpenSearchPlaylist: (String) -> Unit,
    onOpenGenerator: () -> Unit = {},
    onOpenNewReleases: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenPodcasts: () -> Unit = {},
    onOpenPodcastShow: (String) -> Unit = {},
    onOpenYouTubeLogin: () -> Unit = {},
    onReturnToHome: () -> Boolean = { false },
    mainShellViewModel: MainShellViewModel = hiltViewModel(),
) {
    val tabs = MainTab.entries
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val feedTapEvents = remember { kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Keep the dock selection stable through pager animation and synchronize
    // it with swipes only after the pager settles.
    var selectedTabIndex by remember { mutableIntStateOf(pagerState.currentPage) }
    var liquidNavigationJob by remember { mutableStateOf<Job?>(null) }
    var liquidNavigationRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(pagerState.settledPage, pagerState.isScrollInProgress, liquidNavigationJob) {
        if (!pagerState.isScrollInProgress && liquidNavigationJob?.isActive != true &&
            selectedTabIndex != pagerState.settledPage
        ) {
            selectedTabIndex = pagerState.settledPage
        }
    }
    val updateInfo by mainShellViewModel.updateInfo.collectAsStateWithLifecycle()
    val showUpdateBanner = updateInfo.isUpdateAvailable && !updateInfo.isDismissed
    val ytConnection by mainShellViewModel.ytConnection.collectAsStateWithLifecycle()
    val settings by mainShellViewModel.settings.collectAsStateWithLifecycle(initialValue = null)
    var promptDismissedThisSession by rememberSaveable { mutableStateOf(false) }
    var joinSupportDismissedThisSession by rememberSaveable { mutableStateOf(false) }

    val currentSettings = settings
    val shouldShowJoinSupport = currentSettings != null &&
        !currentSettings.hasDismissedJoinSupportPrompt &&
        !joinSupportDismissedThisSession

    if (shouldShowJoinSupport) {
        JoinSupportDialog(
            onDismiss = {
                mainShellViewModel.setDismissedJoinSupportPrompt(true)
                joinSupportDismissedThisSession = true
            },
        )
    }

    val shouldShowLoginPrompt = currentSettings != null &&
        !shouldShowJoinSupport &&
        !ytConnection.isConnected &&
        !currentSettings.suppressYouTubeLoginPrompt &&
        !promptDismissedThisSession

    if (shouldShowLoginPrompt) {
        var dontShowAgain by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = {
                if (dontShowAgain) mainShellViewModel.setSuppressYouTubeLoginPrompt(true)
                promptDismissedThisSession = true
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = if (LocalLiquidGlass.current) {
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            title = {
                Text(
                    text = "Get better recommendations",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "Log in to YouTube Music, or create a free account, to get personalized songs and queues.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { dontShowAgain = !dontShowAgain }
                            .padding(vertical = 4.dp),
                    ) {
                        Checkbox(
                            checked = dontShowAgain,
                            onCheckedChange = { dontShowAgain = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Don't show again",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (dontShowAgain) mainShellViewModel.setSuppressYouTubeLoginPrompt(true)
                        promptDismissedThisSession = true
                        onOpenYouTubeLogin()
                    },
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text("Take me to YT Music login")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (dontShowAgain) mainShellViewModel.setSuppressYouTubeLoginPrompt(true)
                        promptDismissedThisSession = true
                    },
                ) {
                    Text("OK, thanks")
                }
            },
        )
    }

    val backgroundColor = MaterialTheme.colorScheme.background
    val hideNavTabs = com.lastwave.app.ui.theme.LocalHideNavTabs.current
    val useLiquidNavigation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        LocalLiquidGlass.current && !hideNavTabs
    val navigationLiquidState = if (useLiquidNavigation) rememberLiquidState() else null
    // Only one renderer records the pager for navigation. The bar is hosted by
    // PlayerHost as a sibling, never below its own liquefiable source.
    val navigationBackdrop = if (!useLiquidNavigation) {
        rememberLayerBackdrop {
            drawRect(backgroundColor)
            drawContent()
        }
    } else null
    val navGlass = isLiquidGlassBackdropSupported()

    Box(Modifier.fillMaxSize()) {
        val homeIndex = tabs.indexOf(MainTab.HOME)
        val selectPage: (MainTab) -> Unit = { tab ->
            selectedTabIndex = tab.ordinal
            scope.launch { pagerState.animateScrollToPage(tab.ordinal) }
        }
        HorizontalPager(
            state = pagerState,
            // Keep every tab composed. With 1, jumping Playlists -> Home
            // (dock tap = instant scrollToPage) disposed and rebuilt the whole
            // Feed (sections, shelves, covers) on that frame, and Statistics
            // was rebuilt the same way from the far tab. Only 3 tabs, and
            // off-screen pages are not drawn, so this is cheap.
            beyondViewportPageCount = tabs.size - 1,
            modifier = Modifier.fillMaxSize().then(
                if (navigationLiquidState != null) {
                    Modifier.liquefiable(navigationLiquidState).background(backgroundColor)
                } else {
                    Modifier.liquidGlassSource(if (navGlass) navigationBackdrop else null)
                },
            ),
        ) { page ->
            val isCurrent = page == pagerState.currentPage
            PredictiveBackScreen(
                enabled = isCurrent && tabs[page] != MainTab.HOME,
                onBack = { selectPage(MainTab.HOME) },
            ) {
                when (tabs[page]) {
                    MainTab.HOME -> FeedScreen(
                        onOpenSettings = onOpenSettings,
                        onOpenSearch = { selectPage(MainTab.SEARCH) },
                        onOpenDiscover = { selectPage(MainTab.DISCOVER) },
                        onOpenPlaylist = onOpenPlaylist,
                        onOpenFeedPlaylist = onOpenFeedPlaylist,
                        onOpenGenerator = onOpenGenerator,
                        onOpenFriends = onOpenFriends,
                        onOpenFriendProfile = onOpenFriendProfile,
                        onOpenNewReleases = onOpenNewReleases,
                        onOpenDownloads = onOpenDownloads,
                        onOpenPodcasts = onOpenPodcasts,
                        onOpenPodcastShow = onOpenPodcastShow,
                        onOpenYouTubeLogin = onOpenYouTubeLogin,
                        feedTapEvents = feedTapEvents,
                    )
                    MainTab.DISCOVER -> DiscoverScreen(
                        onBack = { selectPage(MainTab.HOME) },
                    )
                    MainTab.SEARCH -> SearchScreen(
                        onBack = { selectPage(MainTab.HOME) },
                        onOpenArtist = onOpenArtist,
                        onOpenAlbum = onOpenAlbum,
                        onOpenPlaylist = onOpenSearchPlaylist,
                    )
                    MainTab.PLAYLISTS -> PlaylistScreen(
                        onOpenPlaylist = onOpenPlaylist,
                        onOpenGenerator = onOpenGenerator,
                    )
                    MainTab.STATS -> HomeScreen(
                        onOpenSettings = onOpenSettings,
                        onOpenSearch = { selectPage(MainTab.SEARCH) },
                        onOpenDiscover = { selectPage(MainTab.DISCOVER) },
                        onOpenGenres = onOpenGenres,
                        onOpenFriends = onOpenFriends,
                        onOpenDownloads = onOpenDownloads,
                    )
                }
            }
        }

        // App update prompt banner (only shown on app open when an update is available and not dismissed)
        AnimatedVisibility(
            visible = showUpdateBanner,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .adaptiveContentWidth(maxWidth = 600.dp)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .zIndex(10f),
        ) {
            UpdatePromptCard(
                version = updateInfo.latestVersion,
                onUpdate = { mainShellViewModel.openUpdate(context) },
                onDismiss = { mainShellViewModel.dismissUpdate(updateInfo.latestVersion) },
            )
        }

        val bottomNavSlot = com.lastwave.app.ui.player.LocalBottomNavSlot.current
        val hideNavTabs = com.lastwave.app.ui.theme.LocalHideNavTabs.current
        val currentSelectedIndex by androidx.compose.runtime.rememberUpdatedState(selectedTabIndex)
        val navHaptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        val currentOnSelect by androidx.compose.runtime.rememberUpdatedState { index: Int ->
            if (tabs[index] == MainTab.HOME) {
                val wasOnDetail = onReturnToHome()
                if (wasOnDetail) {
                    if (selectedTabIndex != homeIndex) selectedTabIndex = homeIndex
                    scope.launch { pagerState.scrollToPage(homeIndex) }
                    return@rememberUpdatedState
                }
                if (pagerState.currentPage != homeIndex) {
                    if (selectedTabIndex != homeIndex) selectedTabIndex = homeIndex
                    scope.launch { pagerState.scrollToPage(homeIndex) }
                    return@rememberUpdatedState
                }
                feedTapEvents.tryEmit(Unit)
            } else {
                selectPage(tabs[index])
            }
            Unit
        }
        androidx.compose.runtime.DisposableEffect(navigationBackdrop, tabs, hideNavTabs) {
            if (!hideNavTabs) {
                bottomNavSlot.value = { material3NavigationEnabled ->
                    Box(Modifier.fillMaxSize()) {
                        FloatingNavBar(
                            backdrop = navigationBackdrop,
                            tabs = tabs,
                            selectedIndex = currentSelectedIndex,
                            onSelect = currentOnSelect,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            } else {
                bottomNavSlot.value = { _ -> }
            }
            onDispose { bottomNavSlot.value = { _ -> } }
        }
    }
}

@Composable
private fun UpdatePromptCard(
    version: String,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 8.dp,
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = androidx.compose.ui.res.stringResource(com.lastwave.app.R.string.update_available),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(com.lastwave.app.R.string.update_ready_to_install, version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onUpdate),
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(com.lastwave.app.R.string.update),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = androidx.compose.ui.res.stringResource(com.lastwave.app.R.string.dismiss_update),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun FloatingNavBar(
    backdrop: LayerBackdrop?,
    tabs: List<MainTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Mini-player glass card construction, reused 1:1 here: transparent
    // Surface + the shared liquid-glass recipe (colorControls + blur + lens +
    // rim highlight + theme scrim) sampling the sibling content backdrop.
    val isGlass = LocalLiquidGlass.current && backdrop != null && isLiquidGlassBackdropSupported()
    val layer = rememberGraphicsLayer()
    val luminance = 0.5f // Fixed luminance like the mini-player (no GPU readback)

    Box(
        modifier = modifier
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = DockShape,
            // Transparent when glass: the glass modifier draws the frosted
            // sample, scrim and rim itself — a translucent fill would stack
            // a second flat layer on top of it (duplicate glass).
            color = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = if (isGlass) 0.dp else 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                // Soft native ambient shadow so the glass capsule floats above
                // the content like the reference material. RenderNode elevation
                // (no extra blur pass); drawn behind the glass card. The
                // fallback keeps its Material 4dp shadow instead.
                .shadow(
                    elevation = if (isGlass) 6.dp else 0.dp,
                    shape = DockShape,
                    clip = false,
                )
                .then(
                if (backdrop != null) {
                    Modifier.liquidGlass(
                        backdrop,
                        layer,
                        luminance,
                        shape = DockShape,
                        interactive = false,
                    )
                } else {
                    Modifier
                },
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(78.dp)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .selectableGroup(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        FloatingNavItem(
                            tab = tab,
                            selected = selectedIndex == index,
                            onClick = { onSelect(index) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingNavItem(
    tab: MainTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSearch = tab == MainTab.SEARCH
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember(tab) { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = tween(120),
        label = "navigationPressScale_${tab.name}",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) NavigationAccent else NavigationInactive,
        animationSpec = ExpressiveMotion.effectsDefault(),
        label = "navigationTint_${tab.name}",
    )
    val selectedIndicatorColor by animateColorAsState(
        targetValue = if (selected && !isSearch) {
            NavigationAccent.copy(alpha = 0.18f)
        } else {
            Color.Transparent
        },
        animationSpec = ExpressiveMotion.effectsDefault(),
        label = "navigationIndicator_${tab.name}",
    )
    val searchButtonColor by animateColorAsState(
        targetValue = if (selected) NavigationAccent else NavigationAccent.copy(alpha = 0.28f),
        animationSpec = ExpressiveMotion.effectsDefault(),
        label = "searchButtonColor",
    )
    val labelAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.72f,
        animationSpec = ExpressiveMotion.effectsDefault(),
        label = "navigationLabelAlpha_${tab.name}",
    )

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .selectable(
                selected = selected,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
                role = Role.Tab,
                interactionSource = interactionSource,
                // No ripple: an unbounded ripple emits a large circular blob
                // behind the tab on dark glass (and spills past the dock).
                // Press-scale + haptic feedback carry the touch response.
                indication = null,
            )
            .padding(horizontal = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isSearch) {
            Surface(
                shape = CircleShape,
                color = searchButtonColor,
                shadowElevation = if (selected) 4.dp else 0.dp,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = tab.icon(),
                        contentDescription = null,
                        tint = if (selected) Color(0xFF251B1E) else NavigationInactive,
                        modifier = Modifier.size(23.dp),
                    )
                }
            }
        } else {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = selectedIndicatorColor,
                    modifier = Modifier.size(width = minOf(52.dp, maxWidth), height = 40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = tab.icon(),
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = androidx.compose.ui.res.stringResource(tab.labelRes),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
            modifier = Modifier.graphicsLayer { alpha = labelAlpha },
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LegacyFloatingNavBar(
    backdrop: LayerBackdrop?,
    tabs: List<MainTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onOpenGenerator: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val liquidGlass = LocalLiquidGlass.current
    val md3NavBar = LocalMd3NavBar.current
    val glassHoverIndex = remember(liquidGlass) { mutableStateOf<Int?>(null) }
    val glassNavBounds = remember { mutableStateMapOf<Int, Rect>() }
    val dockInteraction = remember { MutableInteractionSource() }
    val fabInteraction = remember { MutableInteractionSource() }
    
    if (!md3NavBar) {
        val navContainerColor = liquidGlassContainerColor(
            MaterialTheme.colorScheme.surfaceContainer,
            enabled = liquidGlass,
            backdrop = backdrop,
        )
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .then(
                    if (liquidGlass) {
                        // Zero-radius CornerBasedShape, NOT RectangleShape: the
                        // glass lens SDF only accepts corner-based shapes and
                        // crashes the render pass for a plain rectangle.
                        Modifier.liquidGlassChrome(
                            RoundedCornerShape(0.dp),
                            liquidGlass,
                            LiquidGlassPreset.BottomNavigation,
                            backdrop,
                            interactionSource = dockInteraction,
                        )
                    } else {
                        Modifier
                    }
                ),
            shape = RoundedCornerShape(0.dp),
            color = navContainerColor,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = if (liquidGlass) 0.dp else 3.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(66.dp)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val onClick = remember(index) { { onSelect(index) } }
                        val selected = selectedIndex == index
                        val indicatorColor by animateColorAsState(
                            targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(
                                alpha = if (liquidGlass) 0.35f else 1f,
                            ) else Color.Transparent,
                            animationSpec = ExpressiveMotion.effectsDefault(),
                            label = "md3IndicatorColor_$index",
                        )
                        val indicatorWidth by animateDpAsState(
                            targetValue = if (selected) 64.dp else 32.dp,
                            animationSpec = navSpring(),
                            label = "md3IndicatorWidth_$index",
                        )
                        val iconTint by animateColorAsState(
                            targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = ExpressiveMotion.effectsDefault(),
                            label = "md3IconTint_$index",
                        )
                        val labelColor by animateColorAsState(
                            targetValue = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            animationSpec = ExpressiveMotion.effectsDefault(),
                            label = "md3LabelColor_$index",
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = remember(index) { MutableInteractionSource() },
                                    indication = androidx.compose.material3.ripple(bounded = false, radius = 32.dp),
                                    onClick = onClick,
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .height(32.dp)
                                    .width(indicatorWidth)
                                    .clip(CircleShape)
                                    .background(indicatorColor),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = tab.icon(),
                                    contentDescription = null,
                                    tint = iconTint,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = androidx.compose.ui.res.stringResource(tab.labelRes),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = labelColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        return
    }

    Box(
        modifier = modifier
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        // No animateContentSize here: the dock pills already animate their own
        // width and the FAB animates its enter/exit size. Animating this outer
        // wrapper at the same time squeezes the Row mid-transition, clipping
        // the dock to a narrow rectangle and pushing the FAB over the pill.
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = DockShape,
                color = liquidGlassContainerColor(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    enabled = liquidGlass,
                    backdrop = backdrop,
                ),
                tonalElevation = if (liquidGlass) 0.dp else 6.dp,
                shadowElevation = if (liquidGlass) 0.dp else 12.dp,
                modifier = Modifier.liquidGlassChrome(DockShape, liquidGlass, LiquidGlassPreset.BottomNavigation, backdrop, interactionSource = dockInteraction),
            ) {
                Row(
                    modifier = Modifier
                        .pointerInput(tabs, selectedIndex) {
                            val bridge = 8.dp.toPx()
                            val hitIndex: (Offset) -> Int? = { pos ->
                                glassNavBounds.entries
                                    .firstOrNull { it.value.inflate(bridge).contains(pos) }
                                    ?.key
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val initialHit = hitIndex(down.position)
                                if (initialHit != null && initialHit != selectedIndex) {
                                    onSelect(initialHit)
                                }
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) break
                                    val currentHit = hitIndex(change.position)
                                    if (currentHit != null && currentHit != selectedIndex) {
                                        onSelect(currentHit)
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val onClick = remember(index) { { onSelect(index) } }
                        LegacyFloatingNavItem(
                            label = androidx.compose.ui.res.stringResource(tab.labelRes),
                            icon = tab.icon(),
                            selected = selectedIndex == index,
                            onClick = onClick,
                            modifier = Modifier.onGloballyPositioned { coords ->
                                glassNavBounds[index] = coords.boundsInParent()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegacyFloatingNavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(
            alpha = if (LocalLiquidGlass.current) 0.28f else 1f,
        ) else Color.Transparent,
        animationSpec = ExpressiveMotion.effectsDefault(),
        label = "navItemBackground",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = ExpressiveMotion.effectsDefault(),
        label = "navItemContent",
    )
    // Animate the pill padding instead of jumping it: combined with the label
    // expand below this gives one smooth width change. (Previously this
    // Surface also had animateContentSize on top of the label expand — the two
    // competing width animations clipped the pill to a rectangle and cut the
    // label mid-switch.)
    val horizontalPadding by animateDpAsState(
        targetValue = if (selected) 18.dp else 12.dp,
        animationSpec = navSpring(),
        label = "navItemPadding",
    )

    Surface(
        onClick = onClick,
        shape = PillShape,
        color = backgroundColor,
        modifier = modifier.height(48.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(horizontal = horizontalPadding)
                .height(48.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(animationSpec = navSpring()) + expandHorizontally(
                    animationSpec = navSpring(),
                    expandFrom = Alignment.Start,
                    clip = false,
                ),
                exit = fadeOut(animationSpec = tween(90)) + shrinkHorizontally(
                    animationSpec = navSpring(),
                    shrinkTowards = Alignment.Start,
                    clip = false,
                ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

private fun MainTab.icon(): ImageVector = when (this) {
    MainTab.HOME -> Icons.Filled.Home
    MainTab.DISCOVER -> Icons.Filled.Explore
    MainTab.SEARCH -> Icons.Filled.Search
    MainTab.PLAYLISTS -> Icons.AutoMirrored.Filled.QueueMusic
    MainTab.STATS -> Icons.Filled.Leaderboard
}

@Composable
private fun JoinSupportDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Headphones,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "Join Support",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Connect with us",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Get real-time assistance, report bugs, discover new releases, and connect directly with the developer community.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(2.dp))

                // Telegram Channel: MaterialYouApp
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            openMaterialYouChannel(context)
                            onDismiss()
                        },
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.70f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF24A1DE).copy(alpha = 0.18f),
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Material You Channel",
                                    tint = Color(0xFF24A1DE),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Material You Apps",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "t.me/MaterialYouApp",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }

                // Telegram Support: ClashProjects
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            openTelegramSupport(context)
                            onDismiss()
                        },
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.70f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF24A1DE).copy(alpha = 0.18f),
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Telegram Support",
                                    tint = Color(0xFF24A1DE),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ClashProjects Support",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "@clashprojects on Telegram",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }

                // Discord Community
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            openDiscordSupport(context)
                            onDismiss()
                        },
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.70f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF5865F2).copy(alpha = 0.18f),
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Group,
                                    contentDescription = "Discord Support",
                                    tint = Color(0xFF5865F2),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "LastWave Discord",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Community & discussions",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = "Close",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    )
}

private fun openMaterialYouChannel(context: android.content.Context) {
    val tgIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("tg://resolve?domain=MaterialYouApp")).apply {
        setPackage("org.telegram.messenger")
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val genericTgIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("tg://resolve?domain=MaterialYouApp")).apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val webIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/MaterialYouApp")).apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val success = runCatching { context.startActivity(tgIntent); true }.getOrDefault(false) ||
        runCatching { context.startActivity(genericTgIntent); true }.getOrDefault(false) ||
        runCatching { context.startActivity(webIntent); true }.getOrDefault(false)
    if (!success) {
        android.widget.Toast.makeText(context, "No browser or Telegram app available", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun openTelegramSupport(context: android.content.Context) {
    val tgIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("tg://resolve?domain=clashprojects")).apply {
        setPackage("org.telegram.messenger")
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val genericTgIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("tg://resolve?domain=clashprojects")).apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val webIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/clashprojects")).apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val success = runCatching { context.startActivity(tgIntent); true }.getOrDefault(false) ||
        runCatching { context.startActivity(genericTgIntent); true }.getOrDefault(false) ||
        runCatching { context.startActivity(webIntent); true }.getOrDefault(false)
    if (!success) {
        android.widget.Toast.makeText(context, "No browser or Telegram app available", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun openDiscordSupport(context: android.content.Context) {
    val discordIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://discord.gg/DmyM2p2fMe")).apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val success = runCatching { context.startActivity(discordIntent); true }.getOrDefault(false)
    if (!success) {
        android.widget.Toast.makeText(context, "No browser available to open link", android.widget.Toast.LENGTH_SHORT).show()
    }
}
