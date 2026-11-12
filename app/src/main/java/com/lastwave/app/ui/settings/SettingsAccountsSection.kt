package com.lastwave.app.ui.settings

import androidx.compose.material3.HorizontalDivider

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Vibration
import com.lastwave.app.data.local.musicHapticsConfig
import com.lastwave.app.playback.haptics.MusicHapticsConfig
import com.lastwave.app.playback.haptics.MusicHapticsEngineMode
import com.lastwave.app.playback.haptics.MusicHapticsIntensity
import com.lastwave.app.playback.haptics.MusicHapticsMode
import com.lastwave.app.ui.player.QualityGlyphs
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.FolderOpen
import com.lastwave.app.ui.jam.LastWaveJamSheet
import com.lastwave.app.ui.jam.LastWaveJamViewModel
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import com.lastwave.app.ui.common.HeaderActionIcon
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import com.lastwave.app.util.BatteryOptimizationHelper
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterTiltShift
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Waves
import com.lastwave.app.data.local.LyricsProvider
import com.lastwave.app.data.local.RecommendationSource
import com.lastwave.app.ui.player.LyricsOffsetDialog
import com.lastwave.app.ui.player.LyricsSizeDialog
import androidx.compose.material.icons.filled.FormatSize
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.SpeakerPhone
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.blur
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.lastwave.app.data.local.AppLanguage
import com.lastwave.app.data.local.nativeDisplayName
import com.lastwave.app.data.lyrics.TRANSLATION_LANGUAGES
import com.lastwave.app.data.lyrics.translationLanguageName
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import java.util.Locale

import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import com.lastwave.app.R
import com.lastwave.app.data.local.AccentMode
import com.lastwave.app.data.local.ThemeMode
import com.lastwave.app.data.local.AppIcon
import com.lastwave.app.data.local.EQ_BAND_FREQS_HZ
import com.lastwave.app.data.local.EQ_MAX_GAIN_DB
import com.lastwave.app.data.local.EqualizerPresets
import com.lastwave.app.data.local.EqualizerSettings
import com.lastwave.app.playback.ClarityPresets
import com.lastwave.app.playback.LoudnessMode
import com.lastwave.app.data.local.eqBandLabel
import com.lastwave.app.ui.common.ConnectedButtonGroup
import com.lastwave.app.ui.common.ConnectedButtonItem
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.theme.ExpressivePillShape
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.DarkMode
import kotlin.math.roundToInt
import kotlin.math.roundToLong


@Composable
internal fun YouTubeAccountRow(
    accountName: String,
    channelHandle: String?,
    onDisconnect: () -> Unit,
    position: GroupPosition,
    isHighlighted: Boolean = false,
) {
    val shape = groupShape(position)
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .settingHighlightGlow(isHighlighted = isHighlighted, shape = shape),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                Icons.Filled.SmartDisplay,
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "YouTube Music Account",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    accountName.ifBlank { "Connected" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (!channelHandle.isNullOrBlank()) {
                    Text(
                        channelHandle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(
                onClick = onDisconnect,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(Icons.Filled.Logout, contentDescription = stringResource(R.string.set_disconnect_cd))
            }
        }
    }
}

@Composable
internal fun SpotifyAccountRow(
    onDisconnect: () -> Unit,
    onClick: () -> Unit,
    position: GroupPosition,
    isHighlighted: Boolean = false,
) {
    val shape = groupShape(position)
    Card(
        onClick = onClick,
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .settingHighlightGlow(isHighlighted = isHighlighted, shape = shape),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(
                Icons.Filled.PlayCircle,
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Spotify Account",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Connected (sp_dc)",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    "Tap to manage session",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(
                onClick = onDisconnect,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(Icons.Filled.Logout, contentDescription = stringResource(R.string.set_disconnect_cd))
            }
        }
    }
}

internal fun lastSyncSuffix(lastSyncAtMillis: Long): String {
    if (lastSyncAtMillis <= 0L) return ""
    return " \u2022 synced ${relativeTime(lastSyncAtMillis)}"
}

internal fun relativeTime(timestampMillis: Long): String {
    if (timestampMillis <= 0L) return "never"
    val delta = System.currentTimeMillis() - timestampMillis
    val minutes = delta / 60_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}

internal const val LAST_FM_CREATE_KEY_URL = "https://www.last.fm/api/account/create"

@Composable
internal fun LastFmIntegrationCard(
    isConnected: Boolean,
    username: String,
    avatarUrl: String?,
    connecting: Boolean,
    awaitingApproval: Boolean,
    hasApiKey: Boolean,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    onDisconnect: () -> Unit,
    onSaveKeys: (String, String) -> Unit,
    onRemoveKey: () -> Unit,
    onOpenCreateKeyPage: () -> Unit,
    isHighlighted: Boolean = false,
) {
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    // No shared key exists, so the form starts open until a key is saved.
    var showKeyForm by remember(hasApiKey) { mutableStateOf(!hasApiKey) }
    var keyInput by remember { mutableStateOf("") }
    var secretInput by remember { mutableStateOf("") }

    Card(
        shape = CardOuterShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .settingHighlightGlow(isHighlighted = isHighlighted, shape = CardOuterShape),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (isConnected) 14.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isConnected) {
                    if (avatarUrl != null) {
                        coil.compose.AsyncImage(
                            model = avatarUrl,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        IconBadge(
                            Icons.Filled.CloudSync,
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (isConnected && username.isNotBlank()) username.take(1).uppercase() else "L",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Last.fm",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (isConnected && username.isNotBlank()) username else "Not connected",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    if (!isConnected) {
                        Text(
                            "Optional • Stats use local listening when disconnected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (isConnected) {
                    Spacer(Modifier.width(8.dp))
                    FilledTonalIconButton(
                        onClick = { showDisconnectConfirm = true },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(Icons.Filled.Logout, contentDescription = stringResource(R.string.set_disconnect_cd))
                    }
                }
            }
            if (!isConnected) {
                when {
                    awaitingApproval || connecting -> {
                        com.lastwave.app.ui.common.ExpressiveLoadingIndicator(
                            message = if (connecting) "Connecting to Last.fm…" else "Waiting for approval in the browser…",
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                    }
                    else -> {
                        Button(
                            onClick = onConnect,
                            enabled = hasApiKey,
                            shape = ExpressivePillShape,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.set_lfm_connect)) }
                        Text(
                            if (hasApiKey) "Approve in your browser. You can disconnect anytime — Stats keep working locally."
                            else "Add your API key below first, then connect.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (!isConnected) {
                // ── Bring-your-own-key (required, no shared key): Last.fm
                //    rate-limits per API key, so each person adds their own key
                //    here before connecting.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.set_lfm_key_label),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (hasApiKey) stringResource(R.string.set_lfm_key_saved)
                            else stringResource(R.string.set_lfm_key_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { showKeyForm = !showKeyForm }) {
                        Text(if (showKeyForm) "Hide" else if (hasApiKey) "Change" else "Add key")
                    }
                }
                if (showKeyForm) {
                    androidx.compose.material3.OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it.trim() },
                        label = { Text(stringResource(R.string.set_lfm_apikey)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = secretInput,
                        onValueChange = { secretInput = it.trim() },
                        label = { Text(stringResource(R.string.set_lfm_secret)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                onSaveKeys(keyInput, secretInput)
                                keyInput = ""
                                secretInput = ""
                                showKeyForm = false
                            },
                            enabled = keyInput.length >= 16 && secretInput.length >= 16,
                            shape = ExpressivePillShape,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.set_lfm_save)) }
                        if (hasApiKey) {
                            OutlinedButton(
                                onClick = onRemoveKey,
                                shape = ExpressivePillShape,
                            ) { Text(stringResource(R.string.set_lfm_remove)) }
                        }
                    }
                    TextButton(onClick = onOpenCreateKeyPage) {
                        Text(stringResource(R.string.set_lfm_get))
                    }
                } else {
                    TextButton(onClick = onOpenCreateKeyPage) {
                        Text(stringResource(R.string.set_lfm_how))
                    }
                }
            }
            }
        }
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text(stringResource(R.string.set_lfm_disc_title)) },
            text = { Text(stringResource(R.string.set_lfm_disc_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectConfirm = false
                    onDisconnect()
                }) { Text(stringResource(R.string.set_lfm_disc_btn)) }
            },
            dismissButton = { TextButton(onClick = { showDisconnectConfirm = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
