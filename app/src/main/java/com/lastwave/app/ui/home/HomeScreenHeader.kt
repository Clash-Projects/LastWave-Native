package com.lastwave.app.ui.home

import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Download
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import com.lastwave.app.R
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.lastwave.app.playback.toPlayableTrack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.text.style.TextOverflow
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.common.TrackMiniTrayData
import com.lastwave.app.ui.common.TrackMiniTraySheet
import com.lastwave.app.ui.theme.ArtworkShape
import com.lastwave.app.ui.theme.BadgePillShape
import com.lastwave.app.ui.theme.ExpressiveHeroShape
import com.lastwave.app.ui.theme.ExpressivePillShape
import com.lastwave.app.ui.theme.HeroInnerShape
import com.lastwave.app.ui.theme.ListContainerShape
import com.lastwave.app.ui.theme.NowPlayingCardShape
import com.lastwave.app.ui.theme.StatPillShape
import com.lastwave.app.ui.theme.TrackRowShape
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically

import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import com.lastwave.app.ui.shell.FloatingNavDefaults
import coil.compose.SubcomposeAsyncImage
import com.lastwave.app.data.repository.HomeAlbum
import com.lastwave.app.data.repository.HomeArtistItem
import com.lastwave.app.data.repository.HomeSortMode
import com.lastwave.app.data.repository.HomeTrack
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Immutable shapes hoisted out of composition: previously each of these was
// constructed inline inside row/card composables, i.e. re-allocated for every
// row on every recomposition. Rows are the hottest path while scrolling, so
// they must not allocate.

@Composable
internal fun HeaderRow(
    displayUsername: String,
    isViewingFriend: Boolean,
    onClick: () -> Unit,
    viewModel: HomeViewModel,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Surface(
            onClick = onClick,
            shape = BadgePillShape,
            color = if (isViewingFriend) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    displayUsername.ifBlank { "—" },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isViewingFriend) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
                if (isViewingFriend) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.People,
                        contentDescription = stringResource(R.string.home_switch_profile_cd),
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        LiveListenTimer(viewModel)
    }
}

@Composable
internal fun LiveListenTimer(viewModel: HomeViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listenElapsedSeconds by viewModel.listenElapsedSeconds.collectAsStateWithLifecycle()
    val totalSeconds = (uiState.stats?.timerBaseSeconds ?: 0) + listenElapsedSeconds.toLong()
    val isPlaying = uiState.nowPlaying != null

    Surface(
        shape = BadgePillShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Icon(
                Icons.Filled.Headset,
                contentDescription = stringResource(R.string.home_listen_time_cd),
                modifier = Modifier.size(18.dp),
                tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                formatTimer(totalSeconds),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

internal fun formatTimer(totalSeconds: Long): String {
    // This is an ESTIMATED LIFETIME total (scrobble count × an average
    // track length — see HomeRepository.HomeStats.timerBaseSeconds), not a
    // session/session-elapsed timer, so it legitimately runs into weeks or
    // months for anyone with a large scrobble history — 18,838 scrobbles
    // at ~3.5 min average really is ~46 days of total listening. The raw
    // "DD:HH:MM:SS" digits made that read as a broken/runaway counter
    // instead of what it actually is; spelling out the units (matching how
    // the rest of the app writes durations elsewhere) makes the same
    // number immediately legible as "45 days" instead of a wall of colons.
    if (totalSeconds <= 0) return "--"
    val d = totalSeconds / 86400
    val h = (totalSeconds % 86400) / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        d > 0 -> "${d}d ${h}h ${m}m"
        h > 0 -> "${h}h ${m}m ${s}s"
        else -> "${m}m ${s}s"
    }
}

@Composable
fun ProfileAvatar(avatarUrl: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
            .padding(2.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxSize(),
        ) {
        if (!avatarUrl.isNullOrBlank()) {
            ArtworkImage(
                name = "profile",
                artist = "avatar",
                embeddedUrl = avatarUrl,
                fallbackIcon = Icons.Filled.AccountCircle,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MixHeader(sortMode: HomeSortMode, onSortModeChange: (HomeSortMode) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    // Real, felt tactile feedback: haptic now fires the instant the pill is
    // pressed (finger down), not only after the click completes — firing it
    // solely inside onClick meant it landed at the same moment the dropdown
    // menu opened and covered the pill, which could read as "nothing
    // happened" since the press-scale's own short spring had barely started
    // by then. A dedicated LaunchedEffect on the raw pressed state decouples
    // the haptic from whatever the click itself goes on to do.
    val pillInteractionSource = remember { MutableInteractionSource() }
    val pillPressed by pillInteractionSource.collectIsPressedAsState()
    LaunchedEffect(pillPressed) {
        if (pillPressed) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
    }
    // A more pronounced dip than the shared rememberGroupPressScale (tuned
    // for full-width group rows) — a small pill needs a bigger relative
    // shrink to actually read as a press at this size.
    val pillScale by animateFloatAsState(
        targetValue = if (pillPressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "sortPillPressScale",
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "List",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        Box {
            Surface(
                onClick = { menuOpen = true },
                shape = BadgePillShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 1.dp,
                interactionSource = pillInteractionSource,
                modifier = Modifier
                    .heightIn(min = 34.dp)
                    .graphicsLayer {
                        scaleX = pillScale
                        scaleY = pillScale
                    },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        iconForSortMode(sortMode),
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        sortModeLabel(sortMode),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                // Lowered from 10dp: a heavy shadowElevation on a popup
                // paints a mostly-rectangular drop shadow around the
                // rounded card (the shadow's own corner falloff is much
                // subtler than the card's actual corner radius), which is
                // exactly what read as "squarish" — a light shadow plus a
                // bit more tonalElevation for legibility fixes that
                // without losing depth entirely.
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                SortOption(Icons.Filled.Schedule, "Recent", sortMode == HomeSortMode.RECENT) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSortModeChange(HomeSortMode.RECENT); menuOpen = false
                }
                SortOption(Icons.Filled.BarChart, "Most Played", sortMode == HomeSortMode.MOST_PLAYED) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSortModeChange(HomeSortMode.MOST_PLAYED); menuOpen = false
                }
                SortOption(Icons.Filled.DateRange, "Last 7 Days", sortMode == HomeSortMode.LAST_7_DAYS) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSortModeChange(HomeSortMode.LAST_7_DAYS); menuOpen = false
                }
                SortOption(Icons.Filled.CalendarMonth, "Last 30 Days", sortMode == HomeSortMode.LAST_30_DAYS) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSortModeChange(HomeSortMode.LAST_30_DAYS); menuOpen = false
                }
                SortOption(Icons.Filled.People, "Top Artists", sortMode == HomeSortMode.TOP_ARTISTS) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSortModeChange(HomeSortMode.TOP_ARTISTS); menuOpen = false
                }
                SortOption(Icons.Filled.Album, "Top Albums", sortMode == HomeSortMode.TOP_ALBUMS) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSortModeChange(HomeSortMode.TOP_ALBUMS); menuOpen = false
                }
            }
        }
    }
}

internal fun iconForSortMode(mode: HomeSortMode): androidx.compose.ui.graphics.vector.ImageVector = when (mode) {
    HomeSortMode.RECENT -> Icons.Filled.Schedule
    HomeSortMode.MOST_PLAYED -> Icons.Filled.BarChart
    HomeSortMode.TOP_ALBUMS -> Icons.Filled.Album
    HomeSortMode.TOP_ARTISTS -> Icons.Filled.People
    HomeSortMode.LAST_7_DAYS -> Icons.Filled.DateRange
    HomeSortMode.LAST_30_DAYS -> Icons.Filled.CalendarMonth
}

internal fun sortModeLabel(mode: HomeSortMode) = when (mode) {
    HomeSortMode.RECENT -> "Recent"
    HomeSortMode.MOST_PLAYED -> "Most Played"
    HomeSortMode.TOP_ALBUMS -> "Top Albums"
    HomeSortMode.TOP_ARTISTS -> "Top Artists"
    HomeSortMode.LAST_7_DAYS -> "Last 7 Days"
    HomeSortMode.LAST_30_DAYS -> "Last 30 Days"
}

@Composable
internal fun SortOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        },
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (active) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        onClick = onClick,
        modifier = if (active) {
            Modifier
                .padding(horizontal = 6.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
        } else {
            Modifier.padding(horizontal = 6.dp).height(40.dp)
        },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
    )
}

@Composable
internal fun DateHeaderRow(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
    )
}
