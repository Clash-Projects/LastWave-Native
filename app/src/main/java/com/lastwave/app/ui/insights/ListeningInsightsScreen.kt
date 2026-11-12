package com.lastwave.app.ui.insights

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.R
import com.lastwave.app.data.repository.ArtistInsight
import com.lastwave.app.data.repository.ListeningInsights
import com.lastwave.app.data.repository.ListeningInsightsRepository
import com.lastwave.app.data.repository.TrackInsight
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveGroup
import com.lastwave.app.ui.common.ExpressiveGroupTrackRow
import com.lastwave.app.ui.common.ExpressiveLoadingIndicator
import com.lastwave.app.ui.common.GroupPosition
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.shell.FloatingNavDefaults
import com.lastwave.app.ui.theme.ArtworkShape
import com.lastwave.app.ui.theme.ExpressiveHeroShape
import com.lastwave.app.ui.theme.HeroInnerShape
import com.lastwave.app.ui.theme.StatPillShape
import com.lastwave.app.util.formatListeningDuration
import kotlin.math.roundToInt

@Composable
fun ListeningInsightsDashboard(
    onOpenArtist: (String) -> Unit = {},
    topInset: Dp = 0.dp,
    bottomPadding: Dp = FloatingNavDefaults.contentBottomPadding(),
    viewModel: ListeningInsightsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val musicPlayer = LocalMusicPlayer.current

    when (val state = uiState) {
        is ListeningInsightsUiState.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topInset, bottom = bottomPadding)
                    .safeHorizontalContentPadding(),
                contentAlignment = Alignment.Center,
            ) {
                ExpressiveLoadingIndicator(
                    message = stringResource(R.string.listening_insights_loading),
                )
            }
        }

        is ListeningInsightsUiState.Empty -> {
            InsightsEmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topInset + 16.dp, bottom = bottomPadding)
                    .safeHorizontalContentPadding(),
            )
        }

        is ListeningInsightsUiState.Error -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topInset, bottom = bottomPadding, start = 24.dp, end = 24.dp)
                    .safeHorizontalContentPadding(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        is ListeningInsightsUiState.Content -> {
            val playbackQueue = remember(state.insights.topTracks) {
                state.insights.topTracks.map {
                    PlayableTrack(
                        title = it.title,
                        artist = it.artist,
                        artworkUrl = it.artworkUrl,
                        videoId = it.videoId,
                    )
                }
            }
            InsightsContent(
                insights = state.insights,
                topInset = topInset,
                bottomPadding = bottomPadding,
                onArtistClick = onOpenArtist,
                onTrackClick = { index ->
                    musicPlayer.playQueue(
                        tracks = playbackQueue,
                        startIndex = index,
                        sourceLabel = "Listening Insights",
                    )
                },
            )
        }
    }
}

@Composable
private fun InsightsEmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(72.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Headset,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.listening_insights_empty_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.listening_insights_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun InsightsContent(
    insights: ListeningInsights,
    topInset: Dp,
    bottomPadding: Dp,
    onArtistClick: (String) -> Unit,
    onTrackClick: (index: Int) -> Unit,
) {
    val skipPercent = (insights.skipRate * 100f).roundToInt()
    val hasSkipData = insights.totalSessions > 0
    val hasEnoughForSkipInsight = insights.totalSessions >= ListeningInsightsRepository.MIN_SESSIONS_FOR_SKIP_INSIGHT

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp + topInset,
            bottom = bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .safeHorizontalContentPadding(),
    ) {
        item(key = "hero", contentType = "hero") {
            ListeningHeroCard(totalListeningTimeMs = insights.totalListeningTimeMs)
        }

        item(key = "stats_grid", contentType = "stats_grid") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    InsightStatCard(
                        icon = Icons.Filled.PlayArrow,
                        label = stringResource(R.string.listening_insights_total_plays),
                        value = formatStatCount(insights.totalCompletedPlays),
                        modifier = Modifier.weight(1f),
                    )
                    InsightStatCard(
                        icon = Icons.Filled.MusicNote,
                        label = stringResource(R.string.listening_insights_unique_tracks),
                        value = formatStatCount(insights.uniqueTracks),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    InsightStatCard(
                        icon = Icons.Filled.People,
                        label = stringResource(R.string.listening_insights_unique_artists),
                        value = formatStatCount(insights.uniqueArtists),
                        modifier = Modifier.weight(1f),
                    )
                    InsightStatCard(
                        icon = Icons.Filled.SkipNext,
                        label = stringResource(R.string.listening_insights_skip_rate),
                        value = if (hasSkipData) "$skipPercent%" else "—",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (hasSkipData) {
            item(key = "skip_insight", contentType = "skip_insight") {
                SkipInsightBanner(
                    text = if (hasEnoughForSkipInsight) {
                        stringResource(R.string.listening_insights_skip_summary, skipPercent)
                    } else {
                        stringResource(R.string.listening_insights_skip_insufficient)
                    },
                )
            }
        }

        if (insights.topArtists.isNotEmpty()) {
            item(key = "top_artists", contentType = "top_artists") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeading(stringResource(R.string.listening_insights_top_artists))
                    ExpressiveGroup(rowCount = insights.topArtists.size) { index, position ->
                        val artist = insights.topArtists[index]
                        TopArtistRow(
                            rank = index + 1,
                            artist = artist,
                            position = position,
                            onClick = { onArtistClick(artist.name) },
                        )
                    }
                }
            }
        }

        if (insights.topTracks.isNotEmpty()) {
            item(key = "top_tracks", contentType = "top_tracks") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeading(stringResource(R.string.listening_insights_top_tracks))
                    ExpressiveGroup(rowCount = insights.topTracks.size) { index, position ->
                        val track = insights.topTracks[index]
                        TopTrackRow(
                            track = track,
                            position = position,
                            onClick = { onTrackClick(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListeningHeroCard(totalListeningTimeMs: Long) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(360)) + expandVertically(animationSpec = tween(360)),
    ) {
        Surface(
            shape = ExpressiveHeroShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.padding(16.dp)) {
                Surface(
                    shape = HeroInnerShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.listening_insights_hero_overline),
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = formatListeningDuration(totalListeningTimeMs),
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.listening_insights_hero_caption),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightStatCard(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = StatPillShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SkipInsightBanner(text: String) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Insights,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun TopArtistRow(
    rank: Int,
    artist: ArtistInsight,
    position: GroupPosition,
    onClick: () -> Unit,
) {
    val rankContainer: Color
    val rankContent: Color
    when (rank) {
        1 -> {
            rankContainer = MaterialTheme.colorScheme.primary
            rankContent = MaterialTheme.colorScheme.onPrimary
        }
        2 -> {
            rankContainer = MaterialTheme.colorScheme.secondaryContainer
            rankContent = MaterialTheme.colorScheme.onSecondaryContainer
        }
        3 -> {
            rankContainer = MaterialTheme.colorScheme.tertiaryContainer
            rankContent = MaterialTheme.colorScheme.onTertiaryContainer
        }
        else -> {
            rankContainer = MaterialTheme.colorScheme.surfaceContainerHighest
            rankContent = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    val tracksLabel = pluralStringResource(R.plurals.listening_insights_tracks_count, artist.uniqueTracks, artist.uniqueTracks)
    val playsLabel = pluralStringResource(R.plurals.listening_insights_plays_count, artist.totalPlays, artist.totalPlays)

    ExpressiveGroupTrackRow(
        title = artist.name,
        subtitle = if (artist.totalPlays > 0) "$tracksLabel • $playsLabel" else tracksLabel,
        position = position,
        onClick = onClick,
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(rankContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = rank.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = rankContent,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    ArtworkImage(
                        name = "artist:${artist.name}",
                        artist = artist.name,
                        embeddedUrl = artist.artworkUrl,
                        fallbackIcon = Icons.Filled.People,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        },
        trailing = {
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatListeningDuration(artist.totalListeningTimeMs),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        },
    )
}

@Composable
private fun TopTrackRow(
    track: TrackInsight,
    position: GroupPosition,
    onClick: () -> Unit,
) {
    val playsLabel = pluralStringResource(R.plurals.listening_insights_plays_count, track.playCount, track.playCount)
    val durationLabel = formatListeningDuration(track.listeningTimeMs)

    ExpressiveGroupTrackRow(
        title = track.title,
        subtitle = track.artist.ifBlank { playsLabel },
        position = position,
        onClick = onClick,
        leading = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(ArtworkShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                ArtworkImage(
                    name = track.title,
                    artist = track.artist,
                    embeddedUrl = track.artworkUrl,
                    fallbackIcon = Icons.Filled.MusicNote,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        trailing = {
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = durationLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (track.playCount > 0) {
                    Text(
                        text = playsLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

private fun formatStatCount(value: Int): String = if (value <= 0) "0" else "%,d".format(value)

