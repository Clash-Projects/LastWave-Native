package com.lastwave.app.ui.tv

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.ui.navigation.Screen
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.player.PlayerViewModel
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import com.lastwave.app.ui.tv.focus.tvFocusHighlight

enum class TvNavDestination(
    val route: String,
    val title: String,
    val icon: ImageVector,
) {
    HOME(Screen.Home.route, "Home", Icons.Default.Home),
    SEARCH(Screen.Search.route, "Search", Icons.Default.Search),
    PLAYLISTS(Screen.Playlist.route, "Playlists", Icons.AutoMirrored.Filled.QueueMusic),
    DOWNLOADS(Screen.Downloads.route, "Downloads", Icons.Default.CloudDownload),
    SETTINGS(Screen.Settings.route, "Settings", Icons.Default.Settings),
}

val LocalTvOpenPlayer = compositionLocalOf<() -> Unit> { {} }

@Composable
fun TvShell(
    navController: NavHostController,
    playerViewModel: PlayerViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Home.route

    val chromeState by playerViewModel.chromeState.collectAsStateWithLifecycle()
    var isRailFocused by remember { mutableStateOf(false) }
    var showPlayerOverlay by rememberSaveable { mutableStateOf(false) }

    // Auto-open player overlay when playback starts for a new track
    var lastObservedTrackKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(chromeState.current?.title, chromeState.current?.artist, chromeState.current?.videoId, chromeState.isPlaying) {
        val current = chromeState.current
        if (current != null && chromeState.isPlaying) {
            val trackKey = "${current.videoId.orEmpty()}|${current.artist}|${current.title}"
            if (trackKey != lastObservedTrackKey) {
                lastObservedTrackKey = trackKey
                showPlayerOverlay = true
            }
        }
    }

    // Comprehensive TV Back Handling:
    // 1. If player overlay is open -> close it.
    // 2. Else if inside a detail subscreen (album, artist, playlist detail) -> pop stack.
    // 3. Else if on another top tab (search, playlists, settings, downloads) -> return to Home.
    // 4. Else on Home screen -> press back twice within 2s to exit app.
    val context = LocalContext.current
    var lastBackPressedTime by remember { mutableLongStateOf(0L) }

    BackHandler(enabled = true) {
        when {
            showPlayerOverlay -> {
                showPlayerOverlay = false
            }
            navController.previousBackStackEntry != null && currentRoute != Screen.Home.route -> {
                navController.popBackStack()
            }
            currentRoute != Screen.Home.route -> {
                navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.Home.route) { inclusive = true }
                    launchSingleTop = true
                }
            }
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressedTime < 2000L) {
                    (context as? Activity)?.finish()
                } else {
                    lastBackPressedTime = now
                    Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val railWidth = 204.dp
    val railFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }

    CompositionLocalProvider(
        LocalTvOpenPlayer provides { showPlayerOverlay = true }
    ) {

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .focusProperties {
                    canFocus = !showPlayerOverlay
                }
        ) {
            // TV Left Navigation Rail
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .width(railWidth)
                    .fillMaxHeight(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 20.dp)
                        .focusRequester(railFocusRequester)
                        .focusProperties {
                            right = contentFocusRequester
                        }
                        .focusGroup()
                        .focusRestorer(),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Top: App Branding Icon & Navigation destinations
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Brand Logo Mark
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Equalizer,
                                    contentDescription = "LastWave",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "LastWave",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Navigation Items
                        TvNavDestination.entries.forEach { destination ->
                            val isSelected = currentRoute == destination.route
                            TvRailItem(
                                icon = destination.icon,
                                label = destination.title,
                                isSelected = isSelected,
                                onClick = {
                                    if (currentRoute != destination.route) {
                                        if (destination.route == Screen.Home.route) {
                                            val popped = navController.popBackStack(Screen.Home.route, inclusive = false)
                                            if (!popped) {
                                                navController.navigate(Screen.Home.route) {
                                                    popUpTo(0) { saveState = false }
                                                    launchSingleTop = true
                                                }
                                            }
                                        } else {
                                            navController.navigate(destination.route) {
                                                popUpTo(Screen.Home.route) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    }
                                    contentFocusRequester.requestFocus()
                                },
                            )
                        }
                    }

                    // Bottom: Now Playing widget pill (if currently playing or track loaded)
                    val currentTrack = chromeState.current
                    if (currentTrack != null) {
                        TvNowPlayingPill(
                            title = currentTrack.title,
                            artist = currentTrack.artist,
                            isPlaying = chromeState.isPlaying,
                            onClick = { showPlayerOverlay = true },
                        )
                    }
                }
            }

            // Right: Content Viewport
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusRequester(contentFocusRequester)
                    .focusProperties {
                        left = railFocusRequester
                    }
                    .focusGroup()
                    .focusRestorer(),
            ) {
                content()
            }
        }

        // Fullscreen TV Player Overlay
        AnimatedVisibility(
            visible = showPlayerOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            TvPlayerOverlay(
                onClose = { showPlayerOverlay = false },
            )
        }
    }
}
}

@Composable
private fun TvRailItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val backgroundColor = when {
        isFocused -> MaterialTheme.colorScheme.primaryContainer
        isSelected -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }

    val contentColor = when {
        isFocused -> MaterialTheme.colorScheme.onPrimaryContainer
        isSelected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusHighlight(
                scale = 1.04f,
                shape = RoundedCornerShape(12.dp),
            )
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
                fontSize = 15.sp,
            ),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TvNowPlayingPill(
    title: String,
    artist: String,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusHighlight(
                scale = 1.04f,
                shape = RoundedCornerShape(12.dp),
            )
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Equalizer,
            contentDescription = "Now Playing",
            tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.then(
                    if (isFocused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
                ),
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
