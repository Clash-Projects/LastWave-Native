package com.lastwave.app.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.sp
import com.lastwave.app.ui.common.ExpressiveMotion
import kotlinx.coroutines.Job
import android.content.ContextWrapper
import android.util.Log
import android.view.SurfaceView
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.mutableDoubleStateOf
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import com.lastwave.app.playback.ChunkedRangeDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.lastwave.app.R
import com.lastwave.app.data.music.YouTubeVideoStream
import com.lastwave.app.data.music.TextMatch
import com.lastwave.app.data.video.SyncedVideoRepository
import com.lastwave.app.data.video.VideoSearchCandidate
import com.lastwave.app.data.video.extractYoutubeVideoId
import com.lastwave.app.data.video.shouldPreloadVideo
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.common.EdgeToEdgeDialogWindow
import com.lastwave.app.ui.common.ExpressiveLoadingIndicator
import com.lastwave.app.ui.common.adaptiveContentWidth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs


internal enum class VideoSeekSide { REWIND, FORWARD }

@Composable
internal fun VideoCandidatePickerDialog(
    viewModel: SyncedVideoViewModel,
    track: PlayableTrack,
) {
    val state by viewModel.videoPickerState.collectAsStateWithLifecycle()
    var videoLink by remember(track.title, track.artist) { mutableStateOf("") }
    when (val picker = state) {
        VideoPickerUiState.Hidden -> Unit
        VideoPickerUiState.Loading -> AlertDialog(
            onDismissRequest = viewModel::dismissVideoPicker,
            title = { Text("Find the right video") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Searching YouTube for ${track.title}…")
                    }
                    Spacer(Modifier.height(12.dp))
                    VideoLinkInput(
                        videoLink = videoLink,
                        onVideoLinkChange = { videoLink = it },
                        onUseLink = { viewModel.selectVideoUrl(videoLink) },
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::dismissVideoPicker) { Text("Cancel") } },
        )
        is VideoPickerUiState.Error -> AlertDialog(
            onDismissRequest = viewModel::dismissVideoPicker,
            title = { Text("Video search failed") },
            text = { Text(picker.message) },
            confirmButton = { TextButton(onClick = viewModel::dismissVideoPicker) { Text("OK") } },
        )
        is VideoPickerUiState.Results -> AlertDialog(
            onDismissRequest = viewModel::dismissVideoPicker,
            title = { Text("Choose a video") },
            text = {
                Column {
                    Text(
                        "Pick the correct upload for ${track.title}. Your choice is saved for this song.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    VideoLinkInput(
                        videoLink = videoLink,
                        onVideoLinkChange = { videoLink = it },
                        onUseLink = { viewModel.selectVideoUrl(videoLink) },
                    )
                    Spacer(Modifier.height(4.dp))
                    if (picker.candidates.isEmpty()) {
                        Text(
                            "No likely matches found. Paste the exact YouTube link above.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        LazyColumn(modifier = Modifier.height(320.dp)) {
                            items(picker.candidates, key = { it.videoId }) { candidate ->
                                Surface(
                                    onClick = { viewModel.selectVideo(candidate) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (candidate.videoId == picker.savedVideoId) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else Color.Transparent,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                        Text(
                                            candidate.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            buildString {
                                                append(candidate.channel)
                                                candidate.durationSeconds?.let { append(" • ").append(formatTime(it * 1_000L)) }
                                                append(" • likely match")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = viewModel::clearVideoOverride) { Text("Use automatic") }
                    TextButton(onClick = viewModel::dismissVideoPicker) { Text("Close") }
                }
            },
        )
    }
}

@Composable
internal fun VideoLinkInput(
    videoLink: String,
    onVideoLinkChange: (String) -> Unit,
    onUseLink: () -> Unit,
) {
    val pastedVideoId = extractYoutubeVideoId(videoLink)
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = videoLink,
            onValueChange = onVideoLinkChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("YouTube video link or ID") },
            placeholder = { Text("https://youtu.be/…") },
            supportingText = {
                Text(if (pastedVideoId == null) "Paste a YouTube link or 11-character video ID" else "Video ID: $pastedVideoId")
            },
        )
        TextButton(
            onClick = onUseLink,
            enabled = pastedVideoId != null,
            modifier = Modifier.align(Alignment.End),
        ) { Text("Use this link") }
    }
}

@Composable
internal fun VideoTimelineRow(
    currentPosition: Long,
    durationMs: Long,
    isUserScrubbing: Boolean,
    scrubProgress: Float,
    onScrubChange: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    onToggleFullscreen: () -> Unit,
    isFullscreen: Boolean,
) {
    val durationSafe = durationMs.coerceAtLeast(1L)
    val sliderValue = if (isUserScrubbing) scrubProgress else (currentPosition.toFloat() / durationSafe).coerceIn(0f, 1f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatTime(currentPosition),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isFullscreen) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliderValue,
            onValueChange = onScrubChange,
            onValueChangeFinished = onScrubFinished,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = if (isFullscreen) Color.White.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        )
        Text(
            text = formatTime(durationMs),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isFullscreen) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        IconButton(
            onClick = onToggleFullscreen,
            modifier = Modifier.size(34.dp),
        ) {
            Icon(
                imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                contentDescription = if (isFullscreen) stringResource(R.string.video_exit_fullscreen) else stringResource(R.string.video_enter_fullscreen),
                tint = if (isFullscreen) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun VideoQualityDialog(
    bundle: com.lastwave.app.data.music.SyncedVideoStreamBundle,
    selectedHeight: Int?,
    onSelectQuality: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .adaptiveContentWidth(maxWidth = 380.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp, horizontal = 18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.HighQuality,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.video_quality_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(Modifier.height(14.dp))

                val isAutoSelected = selectedHeight == null || selectedHeight <= 0
                QualityOptionRow(
                    label = stringResource(R.string.video_quality_auto_recommended),
                    subtitle = "${bundle.defaultStream.displayQuality} • Best for smooth sync",
                    isSelected = isAutoSelected,
                    onClick = {
                        onSelectQuality(null)
                        onDismiss()
                    },
                )

                Spacer(Modifier.height(4.dp))

                val heights = bundle.availableStreams
                    .mapNotNull { it.height }
                    .distinct()
                    .sortedDescending()

                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(heights) { h ->
                        val isSelected = !isAutoSelected && selectedHeight == h
                        val stream = bundle.availableStreams.firstOrNull { it.height == h }
                        val label = stream?.displayQuality ?: "${h}p"
                        val subtitle = when {
                            h >= 2160 -> "Ultra HD 4K"
                            h >= 1440 -> "Quad HD 2K"
                            h >= 1080 -> "Full HD"
                            h >= 720 -> "High Definition"
                            else -> "Standard Definition"
                        }
                        QualityOptionRow(
                            label = label,
                            subtitle = subtitle,
                            isSelected = isSelected,
                            onClick = {
                                onSelectQuality(h)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun QualityOptionRow(
    label: String,
    subtitle: String? = null,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
