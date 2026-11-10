package com.lastwave.app.ui.feed

import androidx.activity.compose.BackHandler
import com.lastwave.app.playback.toPlayableTrack
import androidx.compose.material.icons.filled.Download
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Mic
import com.lastwave.app.data.music.toYouTubeMusicTrack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.layout.onSizeChanged
import com.lastwave.app.ui.theme.liquidGlassSource
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.SharedFlow
import com.lastwave.app.ui.navigation.FEED_TAP_DEBOUNCE_MS
import com.lastwave.app.ui.navigation.FeedTapAction
import com.lastwave.app.ui.navigation.resolveFeedTapAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import com.lastwave.app.R
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lastwave.app.ui.feed.cards.CardTokens
import com.lastwave.app.ui.feed.cards.CardTextArea
import com.lastwave.app.ui.feed.cards.rememberSquareCardWidth
import com.lastwave.app.ui.feed.cards.rememberWideCardWidth
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lastwave.app.data.feed.FeedAlbum
import com.lastwave.app.data.feed.FeedArtist
import com.lastwave.app.data.feed.FeedQuickTile
import com.lastwave.app.data.feed.FeedSpotlight
import com.lastwave.app.util.ArtistHelper
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.local.HomeSection
import com.lastwave.app.data.local.DEFAULT_SECTION_ORDER
import com.lastwave.app.data.model.FriendEntry
import com.lastwave.app.data.model.RecentTrack
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.music.YouTubePlaylistSummary
import com.lastwave.app.data.music.HomeChip
import com.lastwave.app.data.music.HomeShelf
import com.lastwave.app.data.music.HomeShelfItem
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.ExpressiveShuffleButton
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.common.TrackContextMenuSheet
import com.lastwave.app.ui.common.TrackMiniTrayData
import com.lastwave.app.ui.common.TrackMiniTraySheet
import com.lastwave.app.ui.common.TopCurvedSectionClipShape
import com.lastwave.app.ui.common.WindowSizeClass
import com.lastwave.app.ui.common.rememberWindowSizeClass
import com.lastwave.app.ui.common.TrackMenuCapabilities
import com.lastwave.app.ui.common.TrackMenuTarget
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.navigation.ArtistAlbumNavigator
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.player.PlayingWaveBars
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.ui.podcast.PodcastShowRow



internal fun String.displayName(): String =
    split(" ", "-", "_").filter { it.isNotBlank() }.joinToString(" ") {
        it.replaceFirstChar { c -> c.uppercase() }
    }

internal fun relativeTime(context: android.content.Context, uts: String?): String? {
    val epoch = uts?.toLongOrNull() ?: return null
    val now = System.currentTimeMillis() / 1000
    val diff = (now - epoch).coerceAtLeast(0)
    return when {
        diff < 3600 -> context.getString(R.string.feed_ago_min, (diff / 60).coerceAtLeast(1).toInt())
        diff < 86400 -> context.getString(R.string.feed_ago_hour, (diff / 3600).toInt())
        diff < 86400 * 7 -> context.getString(R.string.feed_ago_day, (diff / 86400).toInt())
        diff < 86400 * 30 -> context.getString(R.string.feed_ago_week, (diff / (86400 * 7)).toInt())
        else -> context.getString(R.string.feed_ago_month, (diff / (86400 * 30)).toInt())
    }
}

@Composable
internal fun ExpressiveGreetingHeader(
    greeting: String,
    formattedDate: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = 32.sp,
                lineHeight = 38.sp,
                letterSpacing = (-0.5).sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (formattedDate.isNotBlank()) {
            Text(
                text = formattedDate.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 1.3.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
internal fun InfiniteRadioHero(
    quickPicks: List<YouTubeMusicTrack>,
    onPlay: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val heroArt = quickPicks.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl
    val heroShape = RoundedCornerShape(
        topStart = 28.dp,
        topEnd = 28.dp,
        bottomStart = 0.dp,
        bottomEnd = 0.dp,
    )
    Surface(
        shape = heroShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 3.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (!heroArt.isNullOrBlank()) {
                AsyncImage(
                    model = heroArt,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(208.dp)
                        .alpha(0.32f),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(208.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                                MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                "MADE FOR YOU",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.1.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Text(
                    text = "Infinite Radio",
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, letterSpacing = (-0.4).sp),
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = "An endless station shaped by your listening",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onPlay()
                        },
                        enabled = quickPicks.isNotEmpty(),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                        modifier = Modifier.height(44.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.feed_play_cd), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun TasteStrip(
    tags: List<String>,
    launching: String?,
    onTagClick: (String) -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        FeedSectionHeader(
            title = stringResource(R.string.feed_your_sound),
            subtitle = stringResource(R.string.feed_your_sound_sub),
            onLongClick = onLongClick,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            items(tags, key = { it }, contentType = { "filter_tag" }) { tag ->
                val isLoading = launching?.equals(tag.displayName(), ignoreCase = true) == true
                Surface(
                    onClick = { onTagClick(tag) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                    modifier = Modifier,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Text(
                            tag.displayName(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (isLoading) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.feed_start_radio_cd),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PinnedChipsBar(
    chips: List<HomeChip>,
    selectedChip: HomeChip?,
    onChipClick: (HomeChip?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chips.isEmpty()) return
    val haptics = LocalHapticFeedback.current

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 1. (All) button chip at index 0
            item(key = "chip_all") {
                val isAllSelected = selectedChip == null
                val bgColor by androidx.compose.animation.animateColorAsState(
                    targetValue = if (isAllSelected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                    label = "allTabBg",
                )
                val textColor by androidx.compose.animation.animateColorAsState(
                    targetValue = if (isAllSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "allTabText",
                )

                Surface(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onChipClick(null)
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = bgColor,
                    modifier = Modifier.height(38.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 16.dp),
                    ) {
                        Text(
                            text = "All",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium,
                            color = textColor,
                            maxLines = 1,
                        )
                    }
                }
            }

            // 2. The mood / activity chips
            items(chips, key = { it.title }, contentType = { "filter_chip" }) { chip ->
                val isSelected = selectedChip == chip || selectedChip?.title.equals(chip.title, ignoreCase = true)
                val bgColor by androidx.compose.animation.animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                    label = "tabBg",
                )
                val textColor by androidx.compose.animation.animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "tabText",
                )

                Surface(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onChipClick(chip)
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = bgColor,
                    modifier = Modifier.height(38.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 14.dp),
                    ) {
                        Text(
                            text = chip.title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = textColor,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun QuickTilesGrid(
    tiles: List<FeedQuickTile>,
    onTileClick: (FeedQuickTile) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = tiles,
            key = { "tile_${it.title}_${it.collection}" },
            contentType = { "quick_tile" },
        ) { tile ->
            QuickTileCard(
                tile = tile,
                onClick = { onTileClick(tile) },
            )
        }
    }
}

@Composable
internal fun QuickTileCard(
    tile: FeedQuickTile,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tileShape = RoundedCornerShape(18.dp)

    // Per-type vibrant gradient pairs for the artwork/icon box matching modern expressive designs
    val isYtLikedTile = tile.collection == "yt_liked" || tile.playlistId == "yt_liked"
    val isLocalLikedTile = tile.isLiked && !isYtLikedTile
    val isLikedTile = isYtLikedTile || isLocalLikedTile
    val isMixTile = tile.collection == "mix" || tile.collection == "discover_mix" || tile.title.contains("Discover Mix", ignoreCase = true)
    val isMyMixTile = tile.collection == "my_mix" || tile.title.equals("My Mix", ignoreCase = true)
    val isDiscoverTile = tile.collection == "radio" || tile.title.equals("Discover", ignoreCase = true)
    val isNewReleasesTile = tile.collection == "new_releases"

    val iconGradient = when {
        isLikedTile -> Brush.linearGradient(
            colors = listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary),
        )
        isMixTile -> Brush.linearGradient(
            colors = listOf(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.secondary),
        )
        isMyMixTile -> Brush.linearGradient(
            colors = listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
        )
        isDiscoverTile -> Brush.linearGradient(
            colors = listOf(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.tertiary),
        )
        isNewReleasesTile -> Brush.linearGradient(
            colors = listOf(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error),
        )
        else -> Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
    }

    Surface(
        onClick = onClick,
        shape = tileShape,
        color = when {
            isLikedTile -> MaterialTheme.colorScheme.primaryContainer
            isMixTile -> MaterialTheme.colorScheme.secondaryContainer
            isMyMixTile -> MaterialTheme.colorScheme.surfaceVariant
            isDiscoverTile -> MaterialTheme.colorScheme.tertiaryContainer
            isNewReleasesTile -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.75f)
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.10f),
        ),
        modifier = modifier
            .width(136.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(iconGradient),
                contentAlignment = Alignment.Center,
            ) {
                if (isYtLikedTile) {
                    Icon(
                        Icons.Filled.ThumbUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                } else if (isLocalLikedTile) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp),
                    )
                } else if (isMixTile) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(36.dp),
                    )
                } else if (isMyMixTile) {
                    Icon(
                        Icons.Filled.AllInclusive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(36.dp),
                    )
                } else if (isDiscoverTile) {
                    Icon(
                        Icons.Filled.Explore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(36.dp),
                    )
                } else if (isNewReleasesTile) {
                    Icon(
                        Icons.Filled.NewReleases,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(36.dp),
                    )
                } else {
                    ArtworkImage(
                        name = tile.title,
                        artist = tile.subtitle ?: "",
                        embeddedUrl = tile.artworkUrl,
                        fallbackIcon = Icons.Filled.Album,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 2.dp, end = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = tile.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val finalSubtitle = if (isNewReleasesTile) "Fresh drops" else tile.subtitle ?: if (tile.actionVideoId != null) "Track" else "Playlist"
                if (finalSubtitle.isNotEmpty()) {
                    Text(
                        text = finalSubtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuickPicksRows(
    tracks: List<YouTubeMusicTrack>,
    currentPlayingVideoId: String?,
    isPlaying: Boolean,
    onTrackClick: (Int) -> Unit,
    onMenuClick: (YouTubeMusicTrack) -> Unit,
    onLongClick: (YouTubeMusicTrack, Int) -> Unit = { _, _ -> },
) {
    val sizeClass = rememberWindowSizeClass()
    val haptics = LocalHapticFeedback.current
    val widthFraction = when (sizeClass) {
        WindowSizeClass.COMPACT -> 0.88f
        WindowSizeClass.MEDIUM -> 0.48f
        WindowSizeClass.EXPANDED -> 0.32f
    }
    val columns = remember(tracks) { tracks.chunked(3) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(top = 12.dp),
    ) {
        itemsIndexed(columns) { columnIndex, column ->
            Column(
                modifier = Modifier.fillParentMaxWidth(widthFraction),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                column.forEachIndexed { rowIndex, track ->
                    val overallIndex = columnIndex * 3 + rowIndex
                    val isCurrent = track.videoId.isNotBlank() && track.videoId == currentPlayingVideoId
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else Color.Transparent,
                        border = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onTrackClick(overallIndex) },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onLongClick(track, overallIndex)
                                },
                            ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(modifier = Modifier.size(48.dp)) {
                                ArtworkImage(
                                    name = track.title,
                                    artist = ArtistHelper.primaryArtist(track.artist),
                                    embeddedUrl = track.artworkUrl,
                                    fallbackIcon = Icons.Filled.MusicNote,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                                )
                                if (isCurrent && isPlaying) {
                                    PlayingWaveBars(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(2.dp)
                                            .size(24.dp, 18.dp),
                                    )
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    track.title,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.5.sp),
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(start = 2.dp),
                                )
                                Text(
                                    ArtistHelper.primaryArtist(track.artist),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 2.dp),
                                )
                            }
                            IconButton(
                                onClick = { onMenuClick(track) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.feed_more_cd, track.title),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
