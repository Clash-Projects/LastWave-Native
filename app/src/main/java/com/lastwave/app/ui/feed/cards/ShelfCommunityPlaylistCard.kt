/*
 * CommunityPlaylistCard: glass card with a 2x2 collage of covers, playlist
 * title + round play button, subtitle line, then up to 3 song rows.
 */
package com.lastwave.app.ui.feed.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.ui.common.ArtworkImage/**
 * Community playlist card: 2x2 cover collage, title with a round play button,
 * "author • views • N songs" subtitle, then up to 3 song rows. Degrades
 * gracefully — fewer than 4 covers tiles what exists; fewer than 3 songs shows
 * what it has.
 */
@Composable
fun ShelfCommunityPlaylistCard(
    title: String,
    subtitle: String,
    collageUrls: List<String>,
    songs: List<YouTubeMusicTrack>,
    artworkUrl: String?,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    onSongClick: (YouTubeMusicTrack) -> Unit,
    modifier: Modifier = Modifier,
    onSongMenuClick: ((YouTubeMusicTrack) -> Unit)? = null,
    cardWidth: Dp = 300.dp,
) {
    Surface(
        modifier = modifier.width(cardWidth),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .cardPressable(onClick = onClick)
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CollageCover(
                    urls = collageUrls,
                    fallbackUrl = artworkUrl,
                    title = title,
                    modifier = Modifier.size(96.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Surface(
                    onClick = onPlayClick,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            if (songs.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                songs.take(3).forEachIndexed { index, song ->
                    CommunitySongRow(
                        song = song,
                        onClick = { onSongClick(song) },
                        onMenuClick = onSongMenuClick?.let { { it(song) } },
                    )
                    if (index < songs.take(3).lastIndex) Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/** 2x2 collage of covers; falls back to the single playlist cover when short. */
@Composable
private fun CollageCover(
    urls: List<String>,
    fallbackUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.clip(RoundedCornerShape(14.dp))) {
        if (urls.size >= 2) {
            val cells = (0 until 4).map { urls.getOrNull(it) }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    CollageCell(cells[0], title, Modifier.weight(1f))
                    CollageCell(cells[1] ?: cells[0], title, Modifier.weight(1f))
                }
                Row(Modifier.weight(1f)) {
                    CollageCell(cells[2] ?: cells[1] ?: cells[0], title, Modifier.weight(1f))
                    CollageCell(cells[3] ?: cells[2] ?: cells[0], title, Modifier.weight(1f))
                }
            }
        } else {
            ArtworkImage(
                name = title,
                artist = "",
                embeddedUrl = fallbackUrl ?: urls.firstOrNull(),
                fallbackIcon = Icons.Filled.QueueMusic,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CollageCell(url: String?, title: String, modifier: Modifier = Modifier) {
    if (url.isNullOrBlank()) {
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest))
        return
    }
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).data(url).crossfade(300).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize(),
    )
}

/** One song row inside a community playlist card. */
@Composable
private fun CommunitySongRow(
    song: YouTubeMusicTrack,
    onClick: () -> Unit,
    onMenuClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .cardPressable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CardArtwork(
            title = song.title,
            subtitle = song.artist,
            artworkUrl = song.artworkUrl,
            fallbackIcon = Icons.Filled.MusicNote,
            modifier = Modifier.size(40.dp),
            cornerRadius = 10.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onMenuClick != null) {
            IconButton(onClick = onMenuClick, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
