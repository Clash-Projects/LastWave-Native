package com.lastwave.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Modern "Now Playing" overlay card, dark glassmorphic theme.
 *
 * Pure props in → fully previewable at every size. A live player feeds it
 * title/artist/artwork/isPlaying/progress plus transport callbacks; the
 * card itself owns only breakpoint selection and scrub gestures.
 *
 * Breakpoints (spec px mapped to dp, the Android unit):
 * - Compact  (<280): art + play/pause + title only. Everything else is cut,
 *   never shrunk into illegibility.
 * - Standard (280–420): + artist, prev/next. Thin 2px progress line on the
 *   bottom edge (display only).
 * - Expanded (>420): full layout + seekable scrubber + PLAYING/PAUSED label.
 *
 * Safeguards honored throughout: single-line ellipsis text, 12dp minimum
 * inner padding, 32dp minimum tap targets (layouts switch before anything
 * would squeeze), one accent color (play button + progress fill only),
 * 16dp card / 8dp thumbnail radii, 4/8dp spacing grid.
 *
 * @param glassModifier hook for the app's real liquid-glass backdrop effect.
 *   Default is a static dark-glass gradient so the card (and its previews)
 *   look right with zero backdrop plumbing.
 */
@Composable
fun NowPlayingOverlayCard(
    title: String,
    artist: String,
    artworkModel: Any?,
    isPlaying: Boolean,
    progress: Float,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onCardClick: () -> Unit = {},
    accent: Color = MaterialTheme.colorScheme.primary,
    glassModifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val breakpoint = when {
            maxWidth < CompactMaxWidth -> OverlayBreakpoint.Compact
            maxWidth <= StandardMaxWidth -> OverlayBreakpoint.Standard
            else -> OverlayBreakpoint.Expanded
        }
        GlassCard(onCardClick = onCardClick, glassModifier = glassModifier) {
            when (breakpoint) {
                OverlayBreakpoint.Compact -> CompactContent(
                    title = title,
                    artworkModel = artworkModel,
                    isPlaying = isPlaying,
                    accent = accent,
                    onPlayPause = onPlayPause,
                )
                OverlayBreakpoint.Standard -> StandardContent(
                    title = title,
                    artist = artist,
                    artworkModel = artworkModel,
                    isPlaying = isPlaying,
                    progress = progress,
                    accent = accent,
                    onPlayPause = onPlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                )
                OverlayBreakpoint.Expanded -> ExpandedContent(
                    title = title,
                    artist = artist,
                    artworkModel = artworkModel,
                    isPlaying = isPlaying,
                    progress = progress,
                    accent = accent,
                    onPlayPause = onPlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSeek = onSeek,
                )
            }
        }
    }
}

private enum class OverlayBreakpoint { Compact, Standard, Expanded }

private val CompactMaxWidth = 280.dp
private val StandardMaxWidth = 420.dp

private val CardShape = RoundedCornerShape(16.dp)
private val ThumbShape = RoundedCornerShape(8.dp)

// Dark glass tokens. Fixed dark ramp (not theme-dependent): the spec calls
// for a dark glassmorphic theme, and white/gray neutrals on it clear 3:1
// contrast at every breakpoint.
private val GlassTop = Color(0xD92B3038)
private val GlassBottom = Color(0xD915181D)
private val GlassBorder = Color.White.copy(alpha = 0.12f)
private val TextPrimary = Color.White
private val TextSecondary = Color.White.copy(alpha = 0.6f)
private val TextFaint = Color.White.copy(alpha = 0.5f)
private val ControlGhost = Color.White.copy(alpha = 0.7f)
private val TrackFill = Color.White.copy(alpha = 0.14f)

@Composable
private fun GlassCard(
    onCardClick: () -> Unit,
    glassModifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .shadow(16.dp, CardShape)
            .clip(CardShape)
            .background(Brush.verticalGradient(listOf(GlassTop, GlassBottom)))
            .then(glassModifier)
            .border(1.dp, GlassBorder, CardShape)
            .clickable(onClick = onCardClick),
    ) {
        content()
    }
}

@Composable
private fun OverlayArtwork(model: Any?, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(ThumbShape)
            .background(Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(size * 0.45f),
            )
        }
    }
}

@Composable
private fun PlayButton(isPlaying: Boolean, size: Dp, accent: Color, onClick: () -> Unit) {
    // Largest interactive element on the card; never below the 32dp floor.
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(size)
            .background(accent, CircleShape),
    ) {
        Icon(
            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = Color.White,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}

@Composable
private fun SkipButton(previous: Boolean, size: Dp, onClick: () -> Unit) {
    // Ghost treatment: neutral white icon, no fill — subtle by design while
    // staying far above 3:1 against the dark glass.
    IconButton(onClick = onClick, modifier = Modifier.size(size)) {
        Icon(
            if (previous) Icons.Filled.SkipPrevious else Icons.Filled.SkipNext,
            contentDescription = if (previous) "Previous" else "Next",
            tint = ControlGhost,
            modifier = Modifier.size(size * 0.6f),
        )
    }
}

@Composable
private fun TrackTitle(text: String, sizeSp: Int) {
    Text(
        text = text.ifBlank { "Unknown track" },
        fontSize = sizeSp.sp,
        fontWeight = FontWeight.Bold,
        color = TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ArtistName(text: String) {
    Text(
        text = text.ifBlank { "Unknown artist" },
        fontSize = 13.sp,
        color = TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// ── Compact: art + title + play. Artist, prev/next, progress, status cut. ──

@Composable
private fun CompactContent(
    title: String,
    artworkModel: Any?,
    isPlaying: Boolean,
    accent: Color,
    onPlayPause: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OverlayArtwork(model = artworkModel, size = 40.dp)
        Box(Modifier.weight(1f)) {
            TrackTitle(text = title, sizeSp = 15)
        }
        PlayButton(
            isPlaying = isPlaying,
            size = 48.dp,
            accent = accent,
            onClick = onPlayPause,
        )
    }
}

// ── Standard: + artist, prev/next. 2px display-only line, bottom edge. ──

@Composable
private fun StandardContent(
    title: String,
    artist: String,
    artworkModel: Any?,
    isPlaying: Boolean,
    progress: Float,
    accent: Color,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OverlayArtwork(model = artworkModel, size = 48.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TrackTitle(text = title, sizeSp = 16)
                ArtistName(text = artist)
            }
            SkipButton(previous = true, size = 40.dp, onClick = onPrevious)
            PlayButton(isPlaying = isPlaying, size = 52.dp, accent = accent, onClick = onPlayPause)
            SkipButton(previous = false, size = 40.dp, onClick = onNext)
        }
        EdgeProgressLine(progress = progress, accent = accent)
    }
}

/** Thin display-only fill pinned to the card's bottom edge (standard). */
@Composable
private fun EdgeProgressLine(progress: Float, accent: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(TrackFill),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .graphicsLayer {
                    // Scale a measured layer instead of resizing it: no
                    // measure/layout pass on every progress tick.
                    scaleX = progress.coerceIn(0f, 1f)
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                }
                .background(accent),
        )
    }
}

// ── Expanded: full layout + seekable scrubber + status label. ──

@Composable
private fun ExpandedContent(
    title: String,
    artist: String,
    artworkModel: Any?,
    isPlaying: Boolean,
    progress: Float,
    accent: Color,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OverlayArtwork(model = artworkModel, size = 56.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TrackTitle(text = title, sizeSp = 18)
                ArtistName(text = artist)
            }
            SkipButton(previous = true, size = 44.dp, onClick = onPrevious)
            PlayButton(isPlaying = isPlaying, size = 56.dp, accent = accent, onClick = onPlayPause)
            SkipButton(previous = false, size = 44.dp, onClick = onNext)
        }
        StatusLabel(isPlaying = isPlaying)
        SeekScrubber(progress = progress, accent = accent, onSeek = onSeek)
    }
}

@Composable
private fun StatusLabel(isPlaying: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = if (isPlaying) "PLAYING" else "PAUSED",
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.5.sp,
        color = TextFaint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * Seekable scrubber: 2px visual line + 10dp handle, but a 32dp-tall touch
 * zone so taps and drags never demand precision. Tap seeks, drag scrubs
 * live and commits on release.
 */
@Composable
private fun SeekScrubber(progress: Float, accent: Color, onSeek: (Float) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
        var dragFraction by remember { mutableFloatStateOf(Float.NaN) }
        val shown = if (dragFraction.isNaN()) progress.coerceIn(0f, 1f) else dragFraction
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(widthPx) {
                    detectTapGestures { offset ->
                        onSeek((offset.x / widthPx).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(widthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragFraction = progress.coerceIn(0f, 1f) },
                        onDragEnd = {
                            if (!dragFraction.isNaN()) onSeek(dragFraction)
                            dragFraction = Float.NaN
                        },
                        onDragCancel = { dragFraction = Float.NaN },
                    ) { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / widthPx).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(TrackFill),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(shown)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            // Travel is inset by the handle diameter so it never overruns
            // the card edge at 0% or 100%.
            Box(
                modifier = Modifier
                    .padding(start = ((maxWidth - 10.dp) * shown).coerceAtLeast(0.dp))
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
        }
    }
}

// ── Previews: one per breakpoint (+ paused expanded). ──

private const val PreviewTitle = "Midnight Velvet (Extended Late Night Mix)"
private const val PreviewArtist = "Cassian Vale & The Northern Static"

@Preview(name = "Compact · 260", widthDp = 260, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewCompact() {
    MaterialTheme {
        NowPlayingOverlayCard(
            title = PreviewTitle,
            artist = PreviewArtist,
            artworkModel = null,
            isPlaying = true,
            progress = 0.35f,
            onPlayPause = {},
            onPrevious = {},
            onNext = {},
            onSeek = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Standard · 360", widthDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewStandard() {
    MaterialTheme {
        NowPlayingOverlayCard(
            title = PreviewTitle,
            artist = PreviewArtist,
            artworkModel = null,
            isPlaying = true,
            progress = 0.35f,
            onPlayPause = {},
            onPrevious = {},
            onNext = {},
            onSeek = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Expanded · 480 playing", widthDp = 480, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewExpandedPlaying() {
    MaterialTheme {
        NowPlayingOverlayCard(
            title = PreviewTitle,
            artist = PreviewArtist,
            artworkModel = null,
            isPlaying = true,
            progress = 0.35f,
            onPlayPause = {},
            onPrevious = {},
            onNext = {},
            onSeek = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Expanded · 480 paused", widthDp = 480, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewExpandedPaused() {
    MaterialTheme {
        NowPlayingOverlayCard(
            title = PreviewTitle,
            artist = PreviewArtist,
            artworkModel = null,
            isPlaying = false,
            progress = 0.62f,
            onPlayPause = {},
            onPrevious = {},
            onNext = {},
            onSeek = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
