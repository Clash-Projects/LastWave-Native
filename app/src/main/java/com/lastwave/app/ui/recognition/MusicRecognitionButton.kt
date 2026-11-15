package com.lastwave.app.ui.recognition

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lastwave.app.R
import com.lastwave.app.data.recognition.RecognizedSong
import com.lastwave.app.ui.common.HeaderActionIcon

/**
 * Header action that listens to the room and identifies the playing song.
 * [key] keeps each screen's recognition state separate when several are composed
 * at once (pager tabs).
 */
@Composable
fun MusicRecognitionButton(key: String) {
    val viewModel: MusicRecognitionViewModel = hiltViewModel(key = key)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.start() else viewModel.onPermissionDenied()
    }
    val begin = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.start() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    HeaderActionIcon(Icons.Filled.GraphicEq, stringResource(R.string.recognize_music), begin)

    if (state != RecognitionState.Idle) {
        RecognitionSheet(
            state = state,
            onRetry = begin,
            onDismiss = viewModel::dismiss,
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                viewModel.dismiss()
            },
            onSearch = viewModel::searchFor,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecognitionSheet(
    state: RecognitionState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onSearch: (RecognizedSong) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state) {
                RecognitionState.Listening -> {
                    PulsingMic()
                    Text(stringResource(R.string.recognize_listening), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.recognize_listening_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.recognize_cancel)) }
                }
                RecognitionState.Identifying -> {
                    PulsingMic()
                    Text(stringResource(R.string.recognize_identifying), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.recognize_cancel)) }
                }
                is RecognitionState.Found -> {
                    val song = state.song
                    if (song.coverUrl != null) {
                        AsyncImage(
                            model = song.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(176.dp).clip(RoundedCornerShape(16.dp)),
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(song.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (song.artist.isNotBlank()) {
                            Text(song.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!song.album.isNullOrBlank()) {
                            Text(song.album, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Button(onClick = { onSearch(song) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.recognize_search))
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.recognize_close)) }
                }
                is RecognitionState.Failed -> {
                    Text(
                        stringResource(state.error.messageRes()),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    if (state.error == RecognitionError.PERMISSION) {
                        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.recognize_open_settings))
                        }
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.recognize_retry)) }
                    } else {
                        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.recognize_retry))
                        }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.recognize_cancel)) }
                }
                RecognitionState.Idle -> Unit
            }
        }
    }
}

/** Active-listening cue: the ring breathes while the microphone is open. */
@Composable
private fun PulsingMic() {
    val pulse = rememberInfiniteTransition(label = "listen")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
        label = "scale",
    )
    val alpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
        label = "alpha",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
        Box(
            Modifier
                .size(72.dp)
                .scale(scale)
                .graphicsLayer { this.alpha = alpha }
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Box(
            Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
        }
    }
}

private fun RecognitionError.messageRes() = when (this) {
    RecognitionError.PERMISSION -> R.string.recognize_error_permission
    RecognitionError.MICROPHONE -> R.string.recognize_error_mic
    RecognitionError.SILENCE -> R.string.recognize_error_silence
    RecognitionError.NO_MATCH -> R.string.recognize_error_no_match
    RecognitionError.RATE_LIMITED -> R.string.recognize_error_rate_limited
    RecognitionError.NETWORK -> R.string.recognize_error_network
}
