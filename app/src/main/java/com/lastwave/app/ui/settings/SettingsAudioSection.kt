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
internal fun CrossfadeDurationRow(
    seconds: Int,
    onSecondsChange: (Int) -> Unit,
    position: GroupPosition = GroupPosition.SINGLE,
    isHighlighted: Boolean = false,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var sliderValue by remember { mutableStateOf(seconds.coerceIn(1, 12).toFloat()) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(seconds) {
        if (!isDragging) sliderValue = seconds.coerceIn(1, 12).toFloat()
    }
    val shape = groupShape(position)

    val blendStyle = when (sliderValue.roundToInt()) {
        in 1..2 -> "Quick DJ overlap"
        in 3..5 -> "Standard smooth blend"
        in 6..8 -> "Long musical transition"
        else -> "Extended cinematic crossfade"
    }

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .settingHighlightGlow(isHighlighted = isHighlighted, shape = shape),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    Icons.Filled.Tune,
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.set_xfade_dur), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "${sliderValue.roundToInt()}s \u2022 $blendStyle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        "${sliderValue.roundToInt()}s",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Slider(
                value = sliderValue,
                onValueChange = { value ->
                    isDragging = true
                    val rounded = value.roundToInt().coerceIn(1, 12)
                    if (rounded.toFloat() != sliderValue) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        sliderValue = rounded.toFloat()
                        onSecondsChange(rounded)
                    }
                },
                onValueChangeFinished = {
                    isDragging = false
                },
                valueRange = 1f..12f,
                steps = 10,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.set_xfade_1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text(stringResource(R.string.set_xfade_6), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text(stringResource(R.string.set_xfade_12), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
internal fun AutoMixControlsCard(
    settings: com.lastwave.app.playback.automix.api.AutoMixSettings,
    onDurationModeChange: (com.lastwave.app.playback.automix.api.TransitionDurationMode) -> Unit,
    onSecondsChange: (Int) -> Unit,
    onSpectralSweepsChange: (Boolean) -> Unit,
    position: GroupPosition = GroupPosition.SINGLE,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val shape = groupShape(position)

    val isInitialAuto = settings.durationMode == com.lastwave.app.playback.automix.api.TransitionDurationMode.AUTO
    var sliderValue by remember { mutableStateOf(if (isInitialAuto) 11f else settings.targetDurationSeconds.coerceIn(12, 30).toFloat()) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(settings.durationMode, settings.targetDurationSeconds) {
        if (!isDragging) {
            sliderValue = if (settings.durationMode == com.lastwave.app.playback.automix.api.TransitionDurationMode.AUTO) {
                11f
            } else {
                settings.targetDurationSeconds.coerceIn(12, 30).toFloat()
            }
        }
    }

    val isAuto = sliderValue.roundToInt() <= 11
    val displayValue = if (isAuto) "Auto" else "${sliderValue.roundToInt()}s"
    val durationDesc = if (isAuto) {
        "Auto \u2022 Phrase-matched transition"
    } else {
        "${sliderValue.roundToInt()}s \u2022 Custom DJ blend"
    }

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Transition Duration",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        durationDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = displayValue,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Slider(
                value = sliderValue,
                onValueChange = { value ->
                    isDragging = true
                    val rounded = value.roundToInt()
                    if (rounded != sliderValue.roundToInt()) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    }
                    sliderValue = value

                    if (rounded <= 11) {
                        if (settings.durationMode != com.lastwave.app.playback.automix.api.TransitionDurationMode.AUTO) {
                            onDurationModeChange(com.lastwave.app.playback.automix.api.TransitionDurationMode.AUTO)
                        }
                    } else {
                        if (settings.durationMode != com.lastwave.app.playback.automix.api.TransitionDurationMode.MANUAL) {
                            onDurationModeChange(com.lastwave.app.playback.automix.api.TransitionDurationMode.MANUAL)
                        }
                        if (rounded != settings.targetDurationSeconds) {
                            onSecondsChange(rounded)
                        }
                    }
                },
                onValueChangeFinished = {
                    isDragging = false
                    val rounded = sliderValue.roundToInt()
                    if (rounded <= 11) {
                        sliderValue = 11f
                        onDurationModeChange(com.lastwave.app.playback.automix.api.TransitionDurationMode.AUTO)
                    } else {
                        sliderValue = rounded.toFloat()
                        onDurationModeChange(com.lastwave.app.playback.automix.api.TransitionDurationMode.MANUAL)
                        onSecondsChange(rounded)
                    }
                },
                valueRange = 11f..30f,
                steps = 18,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Auto", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text("20s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text("30s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(
                    icon = Icons.Filled.FilterTiltShift,
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("DJ Spectral Filter Sweeps", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text("3-band Linkwitz-Riley sub-bass swap and resonant filter sweeps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.spectralFilterSweepsEnabled,
                    onCheckedChange = onSpectralSweepsChange,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicHapticsControlsCard(
    config: MusicHapticsConfig,
    isHardwareAvailable: Boolean,
    isUsbExclusiveActive: Boolean,
    onModeChange: (MusicHapticsMode) -> Unit,
    onIntensityChange: (MusicHapticsIntensity) -> Unit,
    onEngineModeChange: (MusicHapticsEngineMode) -> Unit,
    onBatterySaverChange: (Boolean) -> Unit,
    position: GroupPosition = GroupPosition.SINGLE,
) {
    val shape = groupShape(position)

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            if (isUsbExclusiveActive) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text = "Paused while Bit-Perfect USB DAC bypass is active",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // 1. Mode Selector Chips
            Text(
                text = "Haptic Mode",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MusicHapticsMode.entries.forEach { mode ->
                    val isSelected = config.mode == mode
                    FilterChip(
                        selected = isSelected,
                        onClick = { onModeChange(mode) },
                        label = {
                            Text(
                                mode.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                text = config.mode.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(12.dp))

            // 2. Intensity Segmented Button Chips
            Text(
                text = "Vibration Intensity",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MusicHapticsIntensity.entries.forEach { intensity ->
                    val isSelected = config.intensity == intensity
                    FilterChip(
                        selected = isSelected,
                        onClick = { onIntensityChange(intensity) },
                        label = {
                            Text(
                                intensity.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                text = when (config.intensity) {
                    MusicHapticsIntensity.LIGHT -> "Subtle 35% pulse for relaxed listening"
                    MusicHapticsIntensity.MEDIUM -> "Balanced 65% pulse tuned for standard rhythm"
                    MusicHapticsIntensity.STRONG -> "Full 100% kick for heavy bass drops & transients"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(12.dp))

            // 3. Engine Routing Selector
            Text(
                text = "Engine Routing",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MusicHapticsEngineMode.entries.forEach { engine ->
                    val isSelected = config.engineMode == engine
                    val isEnabled = engine != MusicHapticsEngineMode.HARDWARE_NATIVE || isHardwareAvailable
                    FilterChip(
                        selected = isSelected,
                        enabled = isEnabled,
                        onClick = { onEngineModeChange(engine) },
                        label = {
                            Text(
                                when (engine) {
                                    MusicHapticsEngineMode.AUTO -> "Auto"
                                    MusicHapticsEngineMode.DSP_PRECISION -> "DSP"
                                    MusicHapticsEngineMode.HARDWARE_NATIVE -> "Hardware"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            val engineDesc = when (config.engineMode) {
                MusicHapticsEngineMode.AUTO -> if (isHardwareAvailable) "Hardware HapticGenerator for Whole Song; DSP for filtered modes" else "Precision Media3 DSP engine active"
                MusicHapticsEngineMode.DSP_PRECISION -> "Precision Media3 transient analyzer (consistent across all devices)"
                MusicHapticsEngineMode.HARDWARE_NATIVE -> if (isHardwareAvailable) "Android 12+ hardware HapticGenerator coupled to audio HAL" else "Unavailable on this hardware (falling back to DSP)"
            }
            Text(
                text = engineDesc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(12.dp))

            // 4. Battery Saver Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Pause on Battery Saver",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Preserves battery when Android Power Saver is active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = config.pauseOnBatterySaver,
                    onCheckedChange = onBatterySaverChange,
                )
            }
        }
    }
}



internal fun loudnessModeTitle(context: android.content.Context, mode: LoudnessMode): String = when (mode) {
    LoudnessMode.TRACK -> context.getString(R.string.set_loud_track)
    LoudnessMode.ALBUM -> context.getString(R.string.set_loud_album)
    else -> context.getString(R.string.set_loud_off)
}

internal fun loudnessModeSubtitle(context: android.content.Context, mode: LoudnessMode): String = when (mode) {
    LoudnessMode.TRACK -> context.getString(R.string.set_loud_track_sub)
    LoudnessMode.ALBUM -> context.getString(R.string.set_loud_album_sub)
    else -> context.getString(R.string.set_loud_off_sub)
}

@Composable
internal fun clarityPresetName(preset: com.lastwave.app.playback.ClarityPreset): String = stringResource(
    when (preset.key) {
        "speaker" -> R.string.set_preset_speaker
        "headphone" -> R.string.set_preset_headphone
        "dac" -> R.string.set_preset_dac
        else -> R.string.set_preset_reference
    },
)

@Composable
internal fun clarityPresetDescription(preset: com.lastwave.app.playback.ClarityPreset): String = stringResource(
    when (preset.key) {
        "speaker" -> R.string.set_preset_speaker_desc
        "headphone" -> R.string.set_preset_headphone_desc
        "dac" -> R.string.set_preset_dac_desc
        else -> R.string.set_preset_reference_desc
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LoudnessModeDialog(
    current: LoudnessMode,
    onSelect: (LoudnessMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_loud_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.set_loud_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                LoudnessMode.entries.forEach { mode ->
                    val selected = mode == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(mode) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = { onSelect(mode) })
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                loudnessModeTitle(LocalContext.current, mode),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                            )
                            Text(
                                loudnessModeSubtitle(LocalContext.current, mode),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClarityPresetDialog(
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_clarity_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Tunes the Studio Master Clarity chain to the output. Reference is the unmodified shipping curve.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                ClarityPresets.ALL.forEach { preset ->
                    val selected = preset.index == currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(preset.index) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = { onSelect(preset.index) })
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                clarityPresetName(preset),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                            )
                            Text(
                                clarityPresetDescription(preset),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
    )
}
