package com.lastwave.app.ui.feed.cards

import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import com.lastwave.app.data.music.YouTubeMusicTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fixed slots keep the carousel still while the one-page preview arrives. */
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
    cardWidth: Dp = 330.dp,
    isLoading: Boolean = false,
    previewFailed: Boolean = false,
    songPlayCounts: Map<String, String> = emptyMap(),
    playlistId: String? = null,
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val rowHeight = 56.dp * fontScale
    val coverUrl = collageUrls.firstOrNull(String::isNotBlank) ?: artworkUrl?.takeIf(String::isNotBlank)
    val tint = rememberCommunityPlaylistTint(playlistId, coverUrl)
    val defaultSurface = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.76f)
    val background by animateColorAsState(
        targetValue = tint?.copy(alpha = 0.22f)?.compositeOver(defaultSurface) ?: defaultSurface,
        animationSpec = tween(300), label = "community playlist tint",
    )
    Surface(
        modifier = modifier.width(cardWidth),
        shape = RoundedCornerShape(12.dp),
        color = background,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)),
    ) {
        Column(Modifier.background(Brush.linearGradient(listOf(
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f), Color.Transparent,
        ))).padding(14.dp)) {
            Column(
                Modifier.fillMaxWidth().cardPressable(onClick = onClick)
                    .semantics { contentDescription = "Open playlist $title" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CollageCover(collageUrls, artworkUrl, Modifier.size(cardWidth * 0.37f))
                Spacer(Modifier.height(14.dp))
            }
            Row(Modifier.fillMaxWidth().height(48.dp * fontScale), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f).cardPressable(onClick = onClick)
                        .semantics { contentDescription = "Open playlist $title" },
                    fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = onPlayClick, modifier = Modifier.size(48.dp), shape = CircleShape,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.PlayArrow, "Play playlist $title", Modifier.size(27.dp))
                    }
                }
            }
            Text(
                text = subtitle, modifier = Modifier.fillMaxWidth().height(22.dp * fontScale)
                    .cardPressable(onClick = onClick),
                fontSize = 14.sp, lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().height(rowHeight * 3 + 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    isLoading -> CommunityLoadingRows(rowHeight)
                    !previewFailed -> repeat(3) { index ->
                        val song = songs.getOrNull(index)
                        if (song == null) Spacer(Modifier.height(rowHeight)) else CommunitySongRow(
                            song, songPlayCounts[song.videoId], rowHeight,
                            onClick = { onSongClick(song) },
                            onMenuClick = onSongMenuClick?.let { { it(song) } },
                        )
                    }
                }
            }
        }
    }
}

// Store only colours, never bitmaps; a changed cover invalidates the playlist's cached tint.
private val communityPlaylistTints = LruCache<String, Pair<String, Color>>(128)

@Composable
private fun rememberCommunityPlaylistTint(playlistId: String?, coverUrl: String?): Color? {
    val context = LocalContext.current.applicationContext
    val tint = remember(playlistId, coverUrl) {
        mutableStateOf(playlistId?.let { communityPlaylistTints.get(it) }
            ?.takeIf { it.first == coverUrl }?.second)
    }
    LaunchedEffect(playlistId, coverUrl) {
        if (coverUrl == null || tint.value != null) return@LaunchedEffect
        val extracted = withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context).data(coverUrl)
                    .size(64, 64).precision(Precision.EXACT).allowHardware(false).build()
                val result = context.imageLoader.execute(request) as? SuccessResult
                val bitmap = (result?.drawable as? BitmapDrawable)?.bitmap ?: return@withContext null
                Palette.from(bitmap).resizeBitmapArea(64 * 64).maximumColorCount(8)
                    .clearFilters().generate().dominantSwatch?.rgb?.let(::tintFromColor)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        if (extracted != null) {
            playlistId?.let { communityPlaylistTints.put(it, coverUrl to extracted) }
            tint.value = extracted
        }
    }
    return tint.value
}

@Composable
private fun CollageCover(urls: List<String>, fallback: String?, modifier: Modifier) {
    val covers = urls.filter(String::isNotBlank).distinct().take(4)
    if (covers.isEmpty()) {
        PreviewArtwork(fallback, Icons.AutoMirrored.Filled.QueueMusic,
            modifier.clip(RoundedCornerShape(CardTokens.coverCornerRadius)))
        return
    }
    val tiles = List(4) { index ->
        covers.getOrNull(index) ?: covers.getOrNull(index % covers.size.coerceAtLeast(1)) ?: fallback
    }
    Column(modifier.clip(RoundedCornerShape(CardTokens.coverCornerRadius))) {
        repeat(2) { row ->
            Row(Modifier.weight(1f)) {
                repeat(2) { column ->
                    PreviewArtwork(tiles[row * 2 + column], Icons.AutoMirrored.Filled.QueueMusic,
                        Modifier.weight(1f).fillMaxSize(), fallback)
                }
            }
        }
    }
}

/** Supplied YouTube art only; preview cards do not need additional artwork-provider requests. */
@Composable
internal fun PreviewArtwork(url: String?, fallbackIcon: ImageVector, modifier: Modifier, fallbackUrl: String? = null) {
    val context = LocalContext.current
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Icon(fallbackIcon, null, Modifier.fillMaxSize(0.4f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!fallbackUrl.isNullOrBlank() && fallbackUrl != url) {
            AsyncImage(fallbackUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        AsyncImage(
            model = ImageRequest.Builder(context).data(url).crossfade(180).build(),
            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun CommunitySongRow(song: YouTubeMusicTrack, plays: String?, height: Dp, onClick: () -> Unit, onMenuClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(CardTokens.coverCornerRadius))
            .cardPressable(onClick = onClick).semantics { contentDescription = "Play ${song.title}, ${song.artist}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviewArtwork(song.artworkUrl, Icons.Filled.MusicNote,
            Modifier.size(44.dp).clip(RoundedCornerShape(CardTokens.coverCornerRadius)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(song.title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(song.artist.takeIf(String::isNotBlank), plays).joinToString(" • "),
                fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onMenuClick != null) IconButton(onClick = onMenuClick, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.MoreVert, "More options for ${song.title}", Modifier.size(21.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CommunityLoadingRows(height: Dp) {
    val transition = rememberInfiniteTransition(label = "playlist preview shimmer")
    val phase = transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "shimmer phase")
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    repeat(3) {
        Row(Modifier.fillMaxWidth().height(height).drawWithContent {
            drawContent()
            val x = size.width * (phase.value * 2 - 0.5f)
            drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.045f), Color.Transparent),
                start = Offset(x - size.width / 2, 0f), end = Offset(x, size.height)))
        }.semantics { contentDescription = "Loading playlist preview" }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)).background(base))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(0.8f).height(13.dp).clip(CircleShape).background(base))
                Box(Modifier.fillMaxWidth(0.6f).height(11.dp).clip(CircleShape).background(base))
            }
        }
    }
}
