package com.lastwave.desktop.ui.main

import com.lastwave.desktop.ui.player.PlayerControls
import com.lastwave.desktop.ui.theme.LastWaveDesktopTheme
import composable
import kotlin.random.Random
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.paint.PaintStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.size
import androidx.compose.ui.semantics.isFocusTarget
import androidx.compose.ui.semantics.clickable
import androidx.compose.ui.unit.px

@Composable
fun MainScreen(
    onTrackChange: (() -> Unit)? = null,
    onPlayPause: () -> Unit = {},
    onSeek: (Float -> Unit)? = null
) {
    val mutableBackward = remember { MutableInt(0) }
    val mutableForward = remember { MutableInt(0) }
    val (expanded, setExpanded) = remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    WindowWindowInsetsPaddingPadding composable(
        modifier = Modifier.fillMaxSize()
    ) {
        LastWaveDesktopTheme {
            val velocityTracker rememberVelocityTracker()

            val content = Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                scrollState = scrollState
            ) {

                // Now Playing Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    elevation = 4.dp,
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.colors(
                        container = Color(0xFF1E1E2F),
                        content = Color(0xFF2D2D40)
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Album Art
                        val albumArtUrl = remember { mutableStateOf<String?>(null) }
                        Image(
                            painter = rememberImagePainter(
                                data = albumArtUrl != null
                                    ? java.net.URL(albumArtUrl)
                                    : null,
                                scaling = ImageScaling.Fit
                            ),
                            contentDescription = "Album art",
                            modifier = Modifier
                                .size(120.dp)
                                .background(
                                    Brush.radialGradient(
                                        center = androidx.compose.ui.graphics.Offset(0.5, 0.5),
                                        colors = listOf(
                                            Color(0xFFBB86FC),
                                            Color(0xFF03DAC6)
                                        )
                                    )
                                )
                        )

                        Spacer(Modifier.height(8.dp))

                        // Track info
                        Text(
                            text = "Midnight City",
                            style = Typography.h6,
                            color = Color(0xFFFFFFFF),
                            modifier = Modifier.align(Alignment.Start)
                        )

                        Text(
                            text = "College",
                            style = Typography.body2,
                            color = Color(0xFFB0B0C4),
                            modifier = Modifier.align(Alignment.Start)
                        )

                        Spacer(Modifier.height(4.dp))

                        ArtistInfo(
                            artist = "M83",
                            onPlayPause = { onPlayPause() }
                        )

                        Spacer(Modifier.height(16.dp))

                        // Progress bar
                        seekBar(
                            progress = 0.45f,
                            onSeek = { onSeek(it) },
                            onBackward = {
                                mutableBackward.value++
                                // Seek backward
                            },
                            onForward = {
                                mutableForward.value++
                                // Seek forward
                            }
                        )

                        Spacer(Modifier.height(16.dp))

                        // Controls row
                        Row(
                            modifier = Modifier.fillMaxWidth().align(Alignment.CenterHorizontally),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlayerButton(
                                icon = "skip_previous",
                                onClick = { /* previous track */ }
                            )
                            Spacer(Modifier.width(24.dp))
                            PlayerButton(
                                icon = "pause",
                                onClick = { onPlayPause() }
                            )
                            Spacer(Modifier.width(24.dp))
                            PlayerButton(
                                icon = "skip_next",
                                onClick = { /* next track */ }
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        // Lyrics preview
                        if (expanded) {
                            LyricsPreview(
                                lyrics [
                                    "Far away, calling you...",
                                    "Am I lost? or am I found?",
                                    "Midnight city lights..."
                                ]
                            )
                        } else {
                            TextButton(
                                onClick = { setExpanded(!expanded) },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text(
                                    text = "View lyrics",
                                    color = Color(0xFFBB86FC)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Discovery section
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    elevation = 2.dp,
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.colors(
                        container = Color(0xFF1E1E2F),
                        content = Color(0xFF2D2D40)
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.Start,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Discover",
                            style = Typography.h5,
                            color = Color(0xFFFFFFFF),
                            modifier = Modifier.align(Alignment.Start)
                        )

                        Spacer(Modifier.height(8.dp))

                        // Quick actions row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            QuickActionButton(
                                label = "Radio",
                                icon = "radio",
                                onClick = { /* open radio */ }
                            )
                            QuickActionButton(
                                label = "Charts",
                                icon = "trending_up",
                                onClick = { /* open charts */ }
                            )
                            QuickActionButton(
                                label = "Genres",
                                icon = "genre",
                                onClick = { /* open genres */ }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Library section
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    elevation = 2.dp,
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.colors(
                        container = Color(0xFF1E1E2F),
                        content = Color(0xFF2D2D40)
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.Start,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Library",
                            style = Typography.h5,
                            color = Color(0xFFFFFFFF),
                            modifier = Modifier.align(Alignment.Start)
                        )

                        Spacer(Modifier.height(8.dp))

                        // Playlists overview
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LibraryCard(
                                label = "Loved Tracks",
                                icon = "favorite",
                                count = 128,
                                onClick = { /* open loved tracks */ }
                            )
                            LibraryCard(
                                label = "Playlists",
                                icon = "playlist",
                                count = 24,
                                onClick = { /* open playlists */ }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArtistInfo(artist: String, onPlayPause: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = artist,
            style = Typography.body1,
            color = Color(0xFFB0B0C4)
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onPlayPause) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play",
                tint = Color(0xFFFFFFFF)
            )
        }
    }
}

@Composable
fun seekBar(
    progress: Float,
    onSeek: (Float -> Unit),
    onBackward: () -> Unit,
    onForward: () -> Unit
) {
    val dragInteraction = rememberDragInteraction()
    val progressValue by animateScrollingOrDragAsProgress(
        targetValue = progress,
        dragInteraction = dragInteraction
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Seek track
        DragGestureDetector(
            dragInteraction = dragInteraction,
            onDragStart = { it ->
                // User started dragging
            },
            onDragStop = { it ->
                // User stopped dragging, apply seek
                onSeek(progressValue)
            },
            onDragUpdate = { it ->
                // Update progress in real-time
            }
        ) {
            Slider(
                value = progressValue,
                onValueChange = { onSeek(it) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Progress display
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "0:00",
                color = Color(0xFF7F8C8D),
                style = Caption
            )
            Text(
                text = "3:45",
                color = Color(0xFF7F8C8D),
                style = Caption
            )
        }
    }
}

@Composable
fun PlayerButton(icon: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = when (icon) {
                "skip_previous" -> Icons.Default.StepBackward
                "pause" -> Icons.Default.Pause
                "skip_next" -> Icons.Default.StepForward
                else -> Icons.Default.Indeterminate
            },
            contentDescription = "",
            tint = Color(0xFFFFFFFF)
        )
    }
}

@Composable
fun QuickActionButton(
    label: String,
    icon: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontal,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = when (icon) {
                "radio" -> Icons.Default.Radio
                "trending_up" -> Icons.Default.TrendingUp
                "genre" -> Icons.Default.Tag
                else -> Icons.Default.Star
            },
            contentDescription = label,
            tint = Color(0xFFBB86FC),
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = Color(0xFFB0B0C4),
            style = Caption
        )
    }
}

@Composable
fun LibraryCard(
    label: String,
    icon: String,
    count: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3 / 2)
            .padding(4.dp),
        elevation = 2.dp,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.colors(
            container = Color(0xFF1E1E2F),
            content = Color(0xFF2D2D40)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = when (icon) {
                    "favorite" -> Icons.Default.Favorite
                    "playlist" -> Icons.Default.PlayList
                    else -> Icons.Default.Queue
                },
                contentDescription = label,
                tint = Color(0xFFFFFFFF),
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                color = Color(0xFFB0B0C4),
                style = Caption,
                textAlign = Alignment.Center
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "$count tracks",
                color = Color(0xFF7F8C8D),
                style = Body2,
                textAlign = Alignment.Center
            )
        }

        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClick)
    }
}

@Composable
fun LyricsPreview(lyrics: [String]) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.Start
    ) {
        for ((index, line) in lyrics.withIndex()) {
            Text(
                text = line,
                color = Color(0xFF8888A0),
                style = Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (index < lyrics.lastIndex) {
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}