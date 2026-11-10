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
import com.lastwave.app.data.local.CELLULAR_QUALITY_AUTO
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
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.SignalCellularAlt
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



/**
 * Faithful port of settings.js (par 8): Last.fm account management, appearance
 * (AMOLED / Dynamic Color / Monochrome / accent presets / custom color
 * wheel), iTunes/ListenBrainz artwork toggles, data management (clear
 * recommendation exclusions, clear all data), backup & restore, and app info.
 *
 * Visuals only: restyled into a Material 3 Expressive presentation
 * (larger touch targets, per-row cards, tonal icon badges, spring-based
 * press feedback). Every setting, callback, and piece of state below is
 * unchanged from the original implementation.
 */

enum class SettingsTab(
    @androidx.annotation.StringRes val titleRes: Int,
    @androidx.annotation.StringRes val subtitleRes: Int,
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    AUDIO(
        titleRes = R.string.set_cat_audio,
        subtitleRes = R.string.set_cat_audio_sub,
        title = "Audio & Playback",
        subtitle = "Streaming quality, Audio engine, Equalizer, Output & Loudness",
        icon = Icons.Filled.GraphicEq,
    ),
    APPEARANCE(
        titleRes = R.string.set_cat_appear,
        subtitleRes = R.string.set_cat_appear_sub,
        title = "Appearance & Visuals",
        subtitle = "Themes, Colors, Artwork, Lyrics",
        icon = Icons.Filled.Palette,
    ),
    YOUTUBE(
        titleRes = R.string.set_cat_yt,
        subtitleRes = R.string.set_cat_yt_sub,
        title = "YouTube & Sync",
        subtitle = "Account connection, 24/7 Library sync, Channels, History",
        icon = Icons.Filled.CloudSync,
    ),
    SPOTIFY(
        titleRes = R.string.set_cat_spotify,
        subtitleRes = R.string.set_cat_spotify_sub,
        title = "Spotify",
        subtitle = "Account connection, Canvas, Playlist imports & Liked Songs sync",
        icon = Icons.Filled.PlayCircle,
    ),
    LAST_FM(
        titleRes = R.string.set_cat_lfm,
        subtitleRes = R.string.set_cat_lfm_sub,
        title = "Last.fm",
        subtitle = "Account connection, Scrobbling sync & API credentials",
        icon = Icons.Filled.Album,
    ),
    LIBRARY(
        titleRes = R.string.set_cat_lib,
        subtitleRes = R.string.set_cat_lib_sub,
        title = "Library & Content",
        subtitle = "Home layout, Playlist imports, Downloads, Exclusions",
        icon = Icons.Filled.QueueMusic,
    ),
    DATA_BACKUP(
        titleRes = R.string.set_cat_data,
        subtitleRes = R.string.set_cat_data_sub,
        title = "Data Management",
        subtitle = "Cache cleanup, Export & Import settings, Database tools",
        icon = Icons.Filled.Backup,
    ),
    ABOUT(
        titleRes = R.string.set_cat_about,
        subtitleRes = R.string.set_cat_about_sub,
        title = "About & Community",
        subtitle = "Version info, Telegram community, GitHub repository",
        icon = Icons.Filled.Info,
    ),
}

private fun streamingQualityLabel(quality: Int): String = when (quality) {
    CELLULAR_QUALITY_AUTO -> "Auto"
    28 -> "Dolby Atmos"
    27, 7 -> "Hi-Res Lossless"
    6, 5 -> "Standard (320 kbps)"
    4 -> "Data Saver"
    -1 -> "YouTube Music"
    else -> "Hi-Res Lossless"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onLoggedOut: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
    onOpenModules: () -> Unit = {},
    onOpenHomeSections: () -> Unit = {},
    onOpenExcludedSongs: () -> Unit = {},
    onOpenYouTubeImport: () -> Unit = {},
    onOpenYouTubeLogin: () -> Unit = {},
    onOpenExternalImport: () -> Unit = {},
    onOpenSpotifyLogin: () -> Unit = {},
    onOpenSpotifyTokenImport: () -> Unit = {},
    onOpenJam: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    jamViewModel: LastWaveJamViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val jamUiState by jamViewModel.uiState.collectAsStateWithLifecycle()
    val avatarUrl by viewModel.avatarUrl.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val misc by viewModel.misc.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val downloadCount by viewModel.downloadCount.collectAsStateWithLifecycle()
    val downloadTotalBytes by viewModel.downloadTotalBytes.collectAsStateWithLifecycle()
    val ytConnection by viewModel.ytConnection.collectAsStateWithLifecycle()
    val ytSyncEnabled by viewModel.ytSyncEnabled.collectAsStateWithLifecycle()
    val ytHistorySyncEnabled by viewModel.ytHistorySyncEnabled.collectAsStateWithLifecycle()
    val ytSyncState by viewModel.ytSyncState.collectAsStateWithLifecycle()
    val ytLastSyncAt by viewModel.ytLastSyncAt.collectAsStateWithLifecycle()
    val syncedPlaylistIds by viewModel.syncedPlaylistIds.collectAsStateWithLifecycle()
    val allPlaylists by viewModel.allPlaylists.collectAsStateWithLifecycle()
    val ytAccountPlaylists by viewModel.ytAccountPlaylists.collectAsStateWithLifecycle()
    val hiddenYtLibraryPlaylistIds by viewModel.hiddenYtLibraryPlaylistIds.collectAsStateWithLifecycle()
    val ytChannels by viewModel.ytChannels.collectAsStateWithLifecycle()
    val ytChannelsLoading by viewModel.ytChannelsLoading.collectAsStateWithLifecycle()
    val spotifyConnected by viewModel.spotifyConnected.collectAsStateWithLifecycle()
    val eq by viewModel.equalizer.collectAsStateWithLifecycle()
    val usbExclusiveEnabled by viewModel.usbExclusiveEnabled.collectAsStateWithLifecycle()
    val internalDirectEnabled by viewModel.internalDirectEnabled.collectAsStateWithLifecycle()
    val forceSoftwareVolume by viewModel.forceSoftwareVolume.collectAsStateWithLifecycle()
    val internalDirectAvailability by viewModel.internalDirectAvailability.collectAsStateWithLifecycle()
    val loudness by viewModel.loudness.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val discordPresenceEnabled by viewModel.discordPresenceEnabled.collectAsStateWithLifecycle()
    val isLastFmConnected by viewModel.isLastFmConnected.collectAsStateWithLifecycle()
    val hasApiKey by viewModel.hasApiKey.collectAsStateWithLifecycle()
    val lastFmAuthUrl by viewModel.lastFmAuthUrl.collectAsStateWithLifecycle()
    val lastFmConnecting by viewModel.lastFmConnecting.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Last.fm web auth (Settings → Integrations): open the auth URL in Custom
    // Tabs the moment SettingsViewModel produces one.
    androidx.compose.runtime.LaunchedEffect(lastFmAuthUrl) {
        lastFmAuthUrl?.let { url ->
            runCatching {
                androidx.browser.customtabs.CustomTabsIntent.Builder().build()
                    .launchUrl(context, android.net.Uri.parse(url))
            }.onFailure {
                viewModel.showToast(context.getString(R.string.toast_no_browser_lfm))
                viewModel.cancelLastFmConnect()
            }
        }
    }
    var showQualityDialog by remember { mutableStateOf(false) }
    var editingCellularQuality by remember { mutableStateOf(false) }
    var showDownloadQualityDialog by remember { mutableStateOf(false) }
    var showEqSheet by remember { mutableStateOf(false) }
    var showAutoEq by remember { mutableStateOf(false) }
    var showLyricsProviderDialog by remember { mutableStateOf(false) }
    var showLyricsOffsetDialog by remember { mutableStateOf(false) }
    var showLyricsSizeDialog by remember { mutableStateOf(false) }
    var showLyricsAutoHideDialog by remember { mutableStateOf(false) }
    var showTranslationLanguageDialog by remember { mutableStateOf(false) }
    var showLoudnessDialog by remember { mutableStateOf(false) }
    var showClarityPresetDialog by remember { mutableStateOf(false) }
    var showSyncPlaylistsSheet by remember { mutableStateOf(false) }
    var showYtLibraryVisibilitySheet by remember { mutableStateOf(false) }
    var showYtChannelSheet by remember { mutableStateOf(false) }
    var showYtDisconnectConfirm by remember { mutableStateOf(false) }
    var showSpotifyDisconnectConfirm by remember { mutableStateOf(false) }
    var showRecommendationSourceDialog by remember { mutableStateOf(false) }
    var showCookieLoginDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showJamSheet by remember { mutableStateOf(false) }
    var activeToggleDetail by remember { mutableStateOf<ToggleDetailInfo?>(null) }
    val currentLanguage = remember(misc.appLanguageTag) { AppLanguage.fromTag(misc.appLanguageTag) }

    // Sends the user to Android's own Notification Listener access screen
    // — the one permission this feature needs that the app can never grant

    // "*/*" rather than "application/json": many document providers (Drive,
    // Downloads, some file managers) report a .json file as
    // application/octet-stream or text/plain, and GetContent's mime filter
    // hides anything that doesn't match — the user's own backup file would
    // silently not show up. Real validation happens right after the file is
    // read (stagePendingRestore), so being permissive here is safe.
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.handleRestorePicked(uri)
        }
    }

    val csvPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.handleCsvPicked(uri)
        }
    }

    var showYouTubeImportSheet by remember { mutableStateOf(false) }

    // Lets the user pick exactly where the backup file is saved (SAF), so
    // it's guaranteed to be somewhere restoreLauncher's picker can browse
    // back to later — unlike a silent write into app-private storage.
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportBackup(uri, appVersionName(context))
    }

    CompositionLocalProvider(
        LocalToggleDetailViewer provides { activeToggleDetail = it },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 760.dp),
        ) {
        var activeTab by remember { mutableStateOf<SettingsTab?>(null) }
        var isSearchBarVisible by rememberSaveable { mutableStateOf(false) }
        var highlightedSettingId by remember { mutableStateOf<String?>(null) }
        val focusManager = LocalFocusManager.current

        val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
        val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()

        LaunchedEffect(highlightedSettingId) {
            if (highlightedSettingId != null) {
                delay(3200L)
                highlightedSettingId = null
            }
        }

        BackHandler(enabled = searchQuery.isNotBlank() || isSearchBarVisible || activeTab != null) {
            if (searchQuery.isNotBlank() || isSearchBarVisible) {
                viewModel.clearSearch()
                isSearchBarVisible = false
            } else if (activeTab != null) {
                activeTab = null
            }
        }

        ExpressiveHeader(
            title = if (searchQuery.isNotBlank()) stringResource(R.string.settings_search_title) else (activeTab?.title ?: stringResource(R.string.settings)),
            onBack = {
                if (searchQuery.isNotBlank() || isSearchBarVisible) {
                    viewModel.clearSearch()
                    isSearchBarVisible = false
                } else if (activeTab != null) {
                    activeTab = null
                } else {
                    onBack()
                }
            },
            actions = {
                HeaderActionIcon(
                    icon = if (isSearchBarVisible || searchQuery.isNotBlank()) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (isSearchBarVisible || searchQuery.isNotBlank()) stringResource(R.string.settings_search_clear) else stringResource(R.string.settings_search_title),
                    onClick = {
                        if (isSearchBarVisible || searchQuery.isNotBlank()) {
                            viewModel.clearSearch()
                            isSearchBarVisible = false
                        } else {
                            isSearchBarVisible = true
                        }
                    },
                )
            },
        )

        AnimatedVisibility(
            visible = isSearchBarVisible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            SettingsSearchBar(
                query = searchQuery,
                onQueryChange = viewModel::onSearchQueryChange,
                onClear = {
                    if (searchQuery.isNotBlank()) {
                        viewModel.clearSearch()
                    } else {
                        isSearchBarVisible = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        if (searchQuery.isNotBlank()) {
            SettingsSearchResultsList(
                query = searchQuery,
                results = searchResults,
                onResultClick = { entry ->
                    focusManager.clearFocus()
                    viewModel.clearSearch()
                    isSearchBarVisible = false
                    activeTab = entry.parentTab
                    highlightedSettingId = entry.id
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
        AnimatedContent(
            targetState = activeTab,
            transitionSpec = {
                if (targetState != null && initialState == null) {
                    (slideInHorizontally { width -> width / 4 } + fadeIn()).togetherWith(
                        slideOutHorizontally { width -> -width / 4 } + fadeOut(),
                    )
                } else if (targetState == null && initialState != null) {
                    (slideInHorizontally { width -> -width / 4 } + fadeIn()).togetherWith(
                        slideOutHorizontally { width -> width / 4 } + fadeOut(),
                    )
                } else {
                    fadeIn().togetherWith(fadeOut())
                }
            },
            label = "SettingsTabTransition",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { tab ->
            val listState = remember(tab) { LazyListState() }

            LaunchedEffect(highlightedSettingId, tab) {
                if (highlightedSettingId != null && tab != null) {
                    val targetIndex = getTargetSectionIndex(highlightedSettingId)
                    if (targetIndex > 0) {
                        delay(80L)
                        listState.animateScrollToItem(targetIndex)
                    } else if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
                        delay(80L)
                        listState.animateScrollToItem(0)
                    }
                }
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom = 32.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize().safeHorizontalContentPadding(),
            ) {
                when (tab) {
                    null -> {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionLabel("Collaboration & Social")
                                SettingsGroup(rowCount = 1) { _, position ->
                                    SettingsActionCard(
                                        icon = Icons.Filled.Group,
                                        iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                        iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        title = stringResource(R.string.settings_jam_title),
                                        subtitle = if (jamUiState.isConnected) {
                                            "In Session: #${jamUiState.roomCode} \u2022 ${jamUiState.participants.size} listening together"
                                        } else {
                                            stringResource(R.string.settings_jam_sub)
                                        },
                                        onClick = onOpenJam,
                                        position = position,
                                    )
                                }
                            }
                        }

                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionLabel("Services & Add-ons")
                                SettingsGroup(rowCount = 4) { index, position ->
                                    when (index) {
                                        0 -> SettingsActionCard(
                                            icon = Icons.Filled.Extension,
                                            iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                            title = stringResource(R.string.settings_modules_title),
                                            subtitle = stringResource(R.string.settings_modules_sub),
                                            onClick = onOpenModules,
                                            position = position,
                                            isHighlighted = (highlightedSettingId == "services.modules" || highlightedSettingId == "library.modules"),
                                        )
                                        1 -> SettingsActionCard(
                                            icon = Icons.Filled.Album,
                                            iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            title = stringResource(R.string.set_cat_lfm),
                                            subtitle = if (isLastFmConnected) "Connected as ${session.username} \u2022 Scrobbling active" else "Connect account, scrobbles & statistics sync",
                                            onClick = { activeTab = SettingsTab.LAST_FM },
                                            position = position,
                                        )
                                        2 -> SettingsActionCard(
                                            icon = Icons.Filled.CloudSync,
                                            iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            title = stringResource(R.string.set_cat_yt),
                                            subtitle = if (ytConnection.isConnected) {
                                                "Connected as ${ytConnection.accountName} • 24/7 library sync"
                                            } else {
                                                "YouTube Music account, channels, history & 24/7 sync"
                                            },
                                            onClick = { activeTab = SettingsTab.YOUTUBE },
                                            position = position,
                                        )
                                        else -> SettingsActionCard(
                                            icon = Icons.Filled.PlayCircle,
                                            iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            title = stringResource(R.string.set_cat_spotify),
                                            subtitle = if (spotifyConnected) {
                                                "Connected (sp_dc) • Canvas, playlist imports & Liked Songs sync"
                                            } else {
                                                "Account connection, Canvas, playlist imports & Liked Songs sync"
                                            },
                                            onClick = { activeTab = SettingsTab.SPOTIFY },
                                            position = position,
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionLabel("Playback & UI")
                                SettingsGroup(rowCount = 3) { index, position ->
                                    when (index) {
                                        0 -> SettingsActionCard(
                                            icon = Icons.Filled.Palette,
                                            iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                            title = stringResource(R.string.set_cat_appear),
                                            subtitle = stringResource(R.string.set_cat_appear_sub),
                                            onClick = { activeTab = SettingsTab.APPEARANCE },
                                            position = position,
                                        )
                                        1 -> SettingsActionCard(
                                            icon = Icons.Filled.GraphicEq,
                                            iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            title = stringResource(R.string.set_cat_audio),
                                            subtitle = stringResource(R.string.set_cat_audio_sub),
                                            onClick = { activeTab = SettingsTab.AUDIO },
                                            position = position,
                                        )
                                        else -> SettingsActionCard(
                                            icon = Icons.Filled.QueueMusic,
                                            iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            title = stringResource(R.string.set_cat_lib),
                                            subtitle = run {
                                                val count = downloadCount
                                                if (count > 0) "$count songs downloaded • Imports, Home layout" else "Home layout, Playlist imports, Downloads, Exclusions"
                                            },
                                            onClick = { activeTab = SettingsTab.LIBRARY },
                                            position = position,
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionLabel("System & About")
                                SettingsGroup(rowCount = 2) { index, position ->
                                    when (index) {
                                        0 -> SettingsActionCard(
                                            icon = Icons.Filled.Backup,
                                            iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                            title = stringResource(R.string.set_cat_data),
                                            subtitle = stringResource(R.string.set_cat_data_sub),
                                            onClick = { activeTab = SettingsTab.DATA_BACKUP },
                                            position = position,
                                        )
                                        else -> SettingsActionCard(
                                            icon = Icons.Filled.Info,
                                            iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            title = stringResource(R.string.set_cat_about),
                                            subtitle = if (updateInfo.isUpdateAvailable) "Update Available (${updateInfo.latestVersion}) \u2022 v${appVersionName(context)}" else "v${appVersionName(context)} \u2022 Language, Community, Source code",
                                            onClick = { activeTab = SettingsTab.ABOUT },
                                            position = position,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SettingsTab.AUDIO -> {
                        item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(stringResource(R.string.settings_section_audio))
                    val wifiQualitySubtitle = streamingQualityLabel(misc.wifiStreamingQuality)
                    val cellularQualitySubtitle = streamingQualityLabel(misc.cellularStreamingQuality)
                    val downloadQualitySubtitle = when (misc.downloadQuality) {
                        28 -> "Dolby Atmos (Spatial Immersive Audio)"
                        27 -> "Hi-Res (24-bit / 192 kHz FLAC)"
                        7 -> "Hi-Res (24-bit / 96 kHz FLAC)"
                        6 -> "CD Lossless (16-bit / 44.1 kHz FLAC)"
                        5 -> "Standard (320 kbps MP3)"
                        4 -> "Data Saver (96 kbps HE-AAC)"
                        -1 -> "YouTube Music (AAC / Opus)"
                        else -> "Hi-Res (24-bit / 192 kHz FLAC)"
                    }

                    val isIgnored = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                    val audioRows = buildList<@Composable (GroupPosition) -> Unit> {
                        add { position ->
                            SettingsActionCard(
                                icon = Icons.Filled.Wifi,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = "Wi-Fi streaming quality",
                                subtitle = wifiQualitySubtitle,
                                onClick = {
                                    editingCellularQuality = false
                                    showQualityDialog = true
                                },
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.streaming_quality" || highlightedSettingId == "audio.wifi_streaming_quality"),
                            )
                        }
                        add { position ->
                            SettingsActionCard(
                                icon = Icons.Filled.SignalCellularAlt,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = "Cellular streaming quality",
                                subtitle = cellularQualitySubtitle,
                                onClick = {
                                    editingCellularQuality = true
                                    showQualityDialog = true
                                },
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.cellular_streaming_quality"),
                            )
                        }
                        add { position ->
                            SettingsActionCard(
                                icon = Icons.Filled.CloudDownload,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = stringResource(R.string.settings_download_quality),
                                subtitle = "$downloadQualitySubtitle \u2022 Lossless & YouTube",
                                onClick = { showDownloadQualityDialog = true },
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.download_quality"),
                            )
                        }
                        add { position ->
                            SettingsToggleCard(
                                icon = Icons.Filled.Tune,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = stringResource(R.string.settings_bit_perfect),
                                subtitle = if (misc.isBitPerfectEnabled) {
                                    stringResource(R.string.settings_bit_perfect_on_detail)
                                } else {
                                    stringResource(R.string.settings_bit_perfect_off_detail)
                                },
                                checked = misc.isBitPerfectEnabled,
                                onCheckedChange = viewModel::setBitPerfectEnabled,
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.bit_perfect"),
                            )
                        }
                        val hfSettings by viewModel.autoMixSettings.collectAsStateWithLifecycle()
                        add { position ->
                            SettingsToggleCard(
                                icon = Icons.Filled.Bolt,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = "AutoMix",
                                subtitle = if (hfSettings.autoMixEnabled) {
                                    if (hfSettings.durationMode == com.lastwave.app.playback.automix.api.TransitionDurationMode.AUTO) {
                                        "Active \u2022 Phrase-matched transition (Auto)"
                                    } else {
                                        "Active \u2022 Custom DJ blend (${hfSettings.targetDurationSeconds}s)"
                                    }
                                } else {
                                    "Beat-matched, key-locked seamless dual-deck mixing"
                                },
                                checked = hfSettings.autoMixEnabled,
                                onCheckedChange = viewModel::setAutoMixEnabled,
                                position = position,
                            )
                        }
                        if (hfSettings.autoMixEnabled) {
                            add { position ->
                                AutoMixControlsCard(
                                    settings = hfSettings,
                                    onDurationModeChange = viewModel::setAutoMixDurationMode,
                                    onSecondsChange = viewModel::setAutoMixTargetSeconds,
                                    onSpectralSweepsChange = viewModel::setAutoMixSpectralSweeps,
                                    position = position,
                                )
                            }
                        }
                        add { position ->
                            SettingsToggleCard(
                                icon = QualityGlyphs.MusicHaptics,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = "Music Haptics",
                                subtitle = if (misc.musicHapticsEnabled) {
                                    "${misc.musicHapticsMode.label} \u2022 ${misc.musicHapticsIntensity.label} intensity"
                                } else {
                                    "Physical rhythm synchronization via phone's haptic motor"
                                },
                                checked = misc.musicHapticsEnabled,
                                onCheckedChange = viewModel::setMusicHapticsEnabled,
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.music_haptics"),
                            )
                        }
                        if (misc.musicHapticsEnabled) {
                            add { position ->
                                MusicHapticsControlsCard(
                                    config = misc.musicHapticsConfig,
                                    isHardwareAvailable = viewModel.isHardwareHapticGeneratorAvailable,
                                    isUsbExclusiveActive = viewModel.isUsbExclusiveActive,
                                    onModeChange = viewModel::setMusicHapticsMode,
                                    onIntensityChange = viewModel::setMusicHapticsIntensity,
                                    onEngineModeChange = viewModel::setMusicHapticsEngineMode,
                                    onBatterySaverChange = viewModel::setMusicHapticsPauseOnBatterySaver,
                                    position = position,
                                )
                            }
                        }
                        add { position ->
                            SettingsToggleCard(
                                icon = Icons.Filled.GraphicEq,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = "Autoplay similar songs",
                                subtitle = if (misc.autoplaySimilarEnabled) {
                                    "When the queue ends, keep playing similar songs"
                                } else {
                                    "Stop when the queue ends; nothing is added automatically"
                                },
                                checked = misc.autoplaySimilarEnabled,
                                onCheckedChange = viewModel::setAutoplaySimilarEnabled,
                                position = position,
                            )
                        }
                        add { position ->
                            SettingsToggleCard(
                                icon = Icons.Filled.GraphicEq,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = stringResource(R.string.settings_crossfade),
                                subtitle = if (misc.crossfadeEnabled && misc.isBitPerfectEnabled) {
                                    "Paused while Bit-Perfect is enabled"
                                } else if (misc.crossfadeEnabled) {
                                    "Smooth transition between tracks \u2022 ${misc.crossfadeSeconds} sec"
                                } else {
                                    "Blend the end of a track into the next one"
                                },
                                checked = misc.crossfadeEnabled,
                                onCheckedChange = viewModel::setCrossfadeEnabled,
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.crossfade"),
                            )
                        }
                        if (misc.crossfadeEnabled) {
                            add { position ->
                                CrossfadeDurationRow(
                                    seconds = misc.crossfadeSeconds,
                                    onSecondsChange = viewModel::setCrossfadeSeconds,
                                    position = position,
                                    isHighlighted = (highlightedSettingId == "audio.crossfade_duration"),
                                )
                            }
                        }
                        add { position ->
                            SettingsToggleCard(
                                icon = Icons.Filled.Lyrics,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = stringResource(R.string.settings_keep_lyrics_screen_awake),
                                subtitle = stringResource(R.string.settings_keep_lyrics_screen_awake_desc),
                                checked = misc.keepLyricsScreenAwake,
                                onCheckedChange = viewModel::setKeepLyricsScreenAwake,
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.keep_lyrics_screen_awake"),
                            )
                        }
                        add { position ->
                            SettingsToggleCard(
                                icon = Icons.Filled.StopCircle,
                                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                title = stringResource(R.string.settings_stop_on_recents),
                                subtitle = if (misc.stopPlaybackOnTaskRemoved) {
                                    stringResource(R.string.settings_stop_on_recents_on)
                                } else {
                                    stringResource(R.string.settings_stop_on_recents_off)
                                },
                                checked = misc.stopPlaybackOnTaskRemoved,
                                onCheckedChange = viewModel::setStopPlaybackOnTaskRemoved,
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.stop_on_recents"),
                            )
                        }
                        add { position ->
                            SettingsToggleCard(
                                icon = Icons.Filled.Lyrics,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = stringResource(R.string.settings_download_lyrics),
                                subtitle = if (misc.downloadLyrics) {
                                    "Save .lrc companion files & embed lyrics in downloads"
                                } else {
                                    "Do not fetch or save lyrics when downloading"
                                },
                                checked = misc.downloadLyrics,
                                onCheckedChange = viewModel::setDownloadLyrics,
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.download_lyrics"),
                            )
                        }
                        if (!isIgnored) {
                            add { position ->
                                SettingsActionCard(
                                    icon = Icons.Filled.Bolt,
                                    iconContainer = MaterialTheme.colorScheme.errorContainer,
                                    iconTint = MaterialTheme.colorScheme.onErrorContainer,
                                    title = stringResource(R.string.settings_battery_title),
                                    subtitle = stringResource(R.string.set_battery_restricted),
                                    onClick = { BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context) },
                                    position = position,
                                    isHighlighted = (highlightedSettingId == "audio.battery_optimization"),
                                )
                            }
                        }
                    }
                    SettingsGroup(rowCount = audioRows.size) { index, position ->
                        audioRows[index](position)
                    }
                }
            }

                        item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("Output & Loudness")
                    val clarityPreset = remember(misc.clarityPreset) { ClarityPresets.fromIndex(misc.clarityPreset) }
                    val loudnessSubtitle = when (loudness.mode) {
                        LoudnessMode.TRACK -> "Track \u2022 Match every track to -14 LUFS"
                        LoudnessMode.ALBUM -> "Album \u2022 Keep intentional album dynamics"
                        else -> "Off \u2022 Play tagged tracks at original level"
                    }
                    SettingsGroup(rowCount = 11) { index, position ->
                        when (index) {
                            0 -> SettingsToggleCard(
                                icon = Icons.Filled.Usb,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = stringResource(R.string.set_usb_title),
                                subtitle = if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                                    stringResource(R.string.set_usb_old)
                                } else if (usbExclusiveEnabled || misc.isBitPerfectEnabled) {
                                    stringResource(R.string.set_usb_on)
                                } else {
                                    stringResource(R.string.set_usb_off)
                                },
                                checked = usbExclusiveEnabled,
                                onCheckedChange = viewModel::setUsbExclusiveEnabled,
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.usb_exclusive"),
                            )
                            1 -> {
                                // Probe what this output accepts only once the row is on screen.
                                LaunchedEffect(Unit) { viewModel.refreshInternalDirectAvailability() }
                                SettingsToggleCard(
                                icon = Icons.Filled.SpeakerPhone,
                                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                title = stringResource(R.string.settings_internal_direct),
                                subtitle = when {
                                    !internalDirectEnabled -> stringResource(R.string.settings_internal_direct_off)
                                    internalDirectAvailability.available ->
                                        stringResource(R.string.settings_internal_direct_on)
                                    else -> stringResource(
                                        R.string.settings_internal_direct_inactive,
                                        stringResource(com.lastwave.app.playback.internalFailureDetail(internalDirectAvailability.reason)),
                                    )
                                },
                                checked = internalDirectEnabled,
                                // Greyed out only when this device can never bypass the mixer.
                                // A route- or format-specific refusal must not lock the toggle:
                                // turning it off used to grey it out until the app restarted.
                                enabled = internalDirectEnabled || internalDirectAvailability.canEnable,
                                onCheckedChange = viewModel::setInternalDirectEnabled,
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.internal_direct"),
                            )
                            }
                            2 -> SettingsToggleCard(
                                icon = Icons.Filled.VolumeUp,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = stringResource(R.string.settings_force_sw_volume),
                                subtitle = if (forceSoftwareVolume) {
                                    stringResource(R.string.settings_force_sw_volume_on)
                                } else {
                                    stringResource(R.string.settings_force_sw_volume_off)
                                },
                                checked = forceSoftwareVolume,
                                onCheckedChange = viewModel::setForceSoftwareVolume,
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.force_software_volume"),
                            )
                            3 -> SettingsActionCard(
                                icon = Icons.Filled.VolumeUp,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = stringResource(R.string.set_loud_title),
                                subtitle = loudnessSubtitle,
                                onClick = { showLoudnessDialog = true },
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.loudness_normalization"),
                            )
                            4 -> SettingsActionCard(
                                icon = Icons.Filled.GraphicEq,
                                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                title = stringResource(R.string.settings_equalizer),
                                subtitle = if (misc.isBitPerfectEnabled && eq.enabled) {
                                    stringResource(R.string.set_eq_on_preset, eq.presetName)
                                } else if (eq.enabled) {
                                    stringResource(R.string.set_eq_on_band, eq.presetName)
                                } else {
                                    "Shape your sound across 15 frequencies"
                                },
                                onClick = { showEqSheet = true },
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.equalizer"),
                            )
                            5 -> SettingsToggleCard(
                                icon = Icons.Filled.VolumeUp,
                                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                title = stringResource(R.string.set_sys_fx_title),
                                subtitle = if (misc.systemEffectsMode) {
                                    stringResource(R.string.set_sys_fx_on)
                                } else {
                                    stringResource(R.string.set_sys_fx_off)
                                },
                                checked = misc.systemEffectsMode,
                                onCheckedChange = viewModel::setSystemEffectsMode,
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.system_audio_effects"),
                            )
                            6 -> SettingsToggleCard(
                                icon = Icons.Filled.Waves,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = stringResource(R.string.settings_studio_clarity),
                                subtitle = if (misc.isStudioMasterClarityEnabled) {
                                    stringResource(R.string.settings_studio_clarity_on)
                                } else {
                                    stringResource(R.string.settings_studio_clarity_off)
                                },
                                checked = misc.isStudioMasterClarityEnabled,
                                onCheckedChange = viewModel::setStudioMasterClarity,
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.studio_clarity"),
                            )
                            7 -> SettingsActionCard(
                                icon = Icons.Filled.Tune,
                                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                title = stringResource(R.string.set_clarity_title),
                                subtitle = stringResource(
                                    R.string.set_clarity_row,
                                    clarityPresetName(clarityPreset),
                                    clarityPresetDescription(clarityPreset),
                                ),
                                onClick = { showClarityPresetDialog = true },
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.clarity_preset"),
                            )
                            8 -> SettingsToggleCard(
                                icon = Icons.Filled.GraphicEq,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = stringResource(R.string.set_clarity_bypass),
                                subtitle = if (misc.clarityAtmosBypass) {
                                    "Clarity bypasses on multichannel/spatial sources"
                                } else {
                                    "Off \u2022 Clarity processes every source"
                                },
                                checked = misc.clarityAtmosBypass,
                                onCheckedChange = viewModel::setClarityAtmosBypass,
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.clarity_spatial_bypass"),
                            )
                            9 -> SettingsActionCard(
                                icon = Icons.Filled.Headphones,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = stringResource(R.string.autoeq_title),
                                subtitle = com.lastwave.app.ui.settings.autoeq.autoEqSummary(),
                                onClick = { showAutoEq = true },
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.autoeq"),
                            )
                            10 -> SettingsToggleCard(
                                icon = Icons.Filled.Tune,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = "Show Auto EQ in volume panel",
                                subtitle = "Show AutoEQ and Parametric EQ shortcut in Now Playing audio controls sheet",
                                checked = misc.showAutoEqInVolumePanel,
                                onCheckedChange = viewModel::setShowAutoEqInVolumePanel,
                                position = position,
                                isHighlighted = (highlightedSettingId == "audio.show_autoeq_in_volume_panel"),
                            )
                        }
                    }
                }
            }
                    }

                    SettingsTab.APPEARANCE -> {
                        item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(stringResource(R.string.settings_section_appearance))
                    SettingsGroup(rowCount = 11) { index, position ->
                        when (index) {
                            0 -> ThemeModeSelectorCard(
                                currentThemeMode = theme?.themeMode ?: ThemeMode.SYSTEM,
                                onSelectThemeMode = viewModel::setThemeMode,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.theme_mode"),
                            )
                            1 -> AppIconSelectorCard(
                                currentIcon = theme?.appIcon ?: AppIcon.DEFAULT,
                                onSelectAppIcon = viewModel::setAppIcon,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.app_icon"),
                            )
                            2 -> SettingsToggleCard(
                                icon = Icons.Filled.AutoAwesome,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = stringResource(R.string.settings_lastwave_theme),
                                subtitle = stringResource(R.string.settings_lastwave_theme_sub),
                                checked = theme?.isLastWaveTheme ?: false,
                                onCheckedChange = viewModel::setLastWaveTheme,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.lastwave_theme"),
                            )
                            3 -> SettingsToggleCard(
                                icon = Icons.Filled.Contrast,
                                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                title = stringResource(R.string.settings_amoled),
                                subtitle = stringResource(R.string.settings_amoled_sub),
                                checked = theme?.amoled ?: false,
                                enabled = theme?.themeMode != ThemeMode.LIGHT,
                                onCheckedChange = viewModel::setAmoled,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.amoled"),
                            )
                            4 -> SettingsToggleCard(
                                icon = Icons.Filled.Palette,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = stringResource(R.string.settings_dynamic_color),
                                subtitle = stringResource(R.string.settings_dynamic_color_sub),
                                checked = theme?.mode == AccentMode.DYNAMIC && !(theme?.isLastWaveTheme ?: false),
                                onCheckedChange = { enabled ->
                                    if (enabled && (theme?.isLastWaveTheme == true)) {
                                        viewModel.setLastWaveTheme(false)
                                    }
                                    viewModel.setAccentMode(if (enabled) AccentMode.DYNAMIC else AccentMode.MANUAL)
                                },
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.dynamic_color"),
                            )
                            5 -> SettingsToggleCard(
                                icon = Icons.Filled.BubbleChart,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = stringResource(R.string.settings_liquid_glass),
                                subtitle = stringResource(R.string.settings_liquid_glass_sub),
                                checked = theme?.liquidGlass ?: true,
                                onCheckedChange = viewModel::setLiquidGlass,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.liquid_glass"),
                            )
                            6 -> SettingsToggleCard(
                                icon = Icons.Filled.Apps,
                                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                title = stringResource(R.string.settings_md3_nav_bar),
                                subtitle = stringResource(R.string.settings_md3_nav_bar_sub),
                                checked = theme?.md3NavBar ?: false,
                                onCheckedChange = viewModel::setMd3NavBar,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.md3_nav_bar"),
                            )
                            7 -> SettingsToggleCard(
                                icon = Icons.Filled.VisibilityOff,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = stringResource(R.string.settings_hide_nav_tabs),
                                subtitle = stringResource(R.string.settings_hide_nav_tabs_sub),
                                checked = theme?.hideNavTabs ?: false,
                                onCheckedChange = viewModel::setHideNavTabs,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.hide_nav_tabs"),
                            )
                            8 -> SettingsToggleCard(
                                icon = Icons.Filled.Album,
                                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                title = stringResource(R.string.settings_dynamic_now_playing),
                                subtitle = stringResource(R.string.settings_dynamic_now_playing_sub),
                                checked = misc.dynamicNowPlayingEnabled,
                                onCheckedChange = viewModel::setDynamicNowPlaying,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.dynamic_now_playing"),
                            )
                            9 -> SettingsToggleCard(
                                icon = Icons.Filled.TextFields,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = stringResource(R.string.settings_app_font),
                                subtitle = stringResource(R.string.settings_app_font_sub),
                                checked = misc.useCustomFont,
                                onCheckedChange = viewModel::setUseCustomFont,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.app_font"),
                            )
                            10 -> SettingsToggleCard(
                                icon = Icons.Filled.Vibration,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = stringResource(R.string.settings_ui_haptics),
                                subtitle = stringResource(R.string.settings_ui_haptics_sub),
                                checked = theme?.uiHaptics ?: true,
                                onCheckedChange = viewModel::setUiHaptics,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.ui_haptics"),
                            )
                        }
                    }
                }
            }

                        item {
                Column {
                    SectionLabel(stringResource(R.string.settings_section_accent))
                    Spacer(Modifier.height(10.dp))
                    Card(
                        shape = CardOuterShape,
                        colors = CardDefaults.cardColors(
                            containerColor = if (highlightedSettingId == "appearance.accent_preset") MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .settingHighlightGlow(
                                isHighlighted = (highlightedSettingId == "appearance.accent_preset"),
                                shape = CardOuterShape,
                            ),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            AccentPresetGrid(
                                currentMode = theme?.mode ?: AccentMode.MANUAL,
                                selectedHex = theme?.accentColorHex,
                                onPickPreset = { hex ->
                                    if (theme?.isLastWaveTheme == true) {
                                        viewModel.setLastWaveTheme(false)
                                    }
                                    viewModel.setManualAccent(Color(android.graphics.Color.parseColor(hex)))
                                },
                                onPickMono = {
                                    if (theme?.isLastWaveTheme == true) {
                                        viewModel.setLastWaveTheme(false)
                                    }
                                    viewModel.setAccentMode(AccentMode.MONOCHROME)
                                },
                                onPickCustom = viewModel::openColorWheel,
                            )
                        }
                    }
                }
            }

                        item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(stringResource(R.string.settings_section_experimental))
                    SettingsGroup(rowCount = 11) { index, position ->
                        when (index) {
                            0 -> SettingsToggleCard(
                                icon = Icons.Filled.Lyrics,
                                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                title = "Word-by-word lyrics",
                                subtitle = if (misc.wordByWordLyrics) {
                                    "Real-time syllable sync & karaoke highlight"
                                } else {
                                    "Line-by-line synced lyrics"
                                },
                                checked = misc.wordByWordLyrics,
                                onCheckedChange = viewModel::setWordByWordLyrics,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.word_by_word_lyrics"),
                            )
                            1 -> SettingsToggleCard(
                                icon = Icons.Filled.Waves,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = stringResource(R.string.settings_wavy_seekbar),
                                subtitle = if (misc.wavySeekbarEnabled) {
                                    stringResource(R.string.settings_wavy_seekbar_on)
                                } else {
                                    stringResource(R.string.settings_wavy_seekbar_off)
                                },
                                checked = misc.wavySeekbarEnabled,
                                onCheckedChange = viewModel::setWavySeekbarEnabled,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.wavy_seekbar"),
                            )
                            2 -> SettingsToggleCard(
                                icon = Icons.Filled.RestartAlt,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = stringResource(R.string.settings_rotating_background),
                                subtitle = if (misc.rotatingBackgroundEnabled) {
                                    stringResource(R.string.settings_rotating_background_on)
                                } else {
                                    stringResource(R.string.settings_rotating_background_off)
                                },
                                checked = misc.rotatingBackgroundEnabled,
                                onCheckedChange = viewModel::setRotatingBackgroundEnabled,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.rotating_background"),
                            )
                            3 -> SettingsToggleCard(
                                icon = Icons.Filled.RestartAlt,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = stringResource(R.string.settings_lyrics_rotating_background),
                                subtitle = if (misc.lyricsRotatingBackgroundEnabled) {
                                    stringResource(R.string.settings_lyrics_rotating_background_on)
                                } else {
                                    stringResource(R.string.settings_lyrics_rotating_background_off)
                                },
                                checked = misc.lyricsRotatingBackgroundEnabled,
                                onCheckedChange = viewModel::setLyricsRotatingBackgroundEnabled,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.lyrics_rotating_background"),
                            )
                            4 -> SettingsActionCard(
                                icon = Icons.Filled.Lyrics,
                                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                title = stringResource(R.string.settings_lyrics_provider),
                                subtitle = "${misc.lyricsProvider.title} \u2022 ${misc.lyricsProvider.subtitle}",
                                onClick = { showLyricsProviderDialog = true },
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.lyrics_provider"),
                            )
                            5 -> SettingsActionCard(
                                icon = Icons.Filled.Timer,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = stringResource(R.string.set_lyrics_offset_title),
                                subtitle = if (misc.lyricsOffsetMs == 0L) {
                                    stringResource(R.string.set_offset_off)
                                } else {
                                    stringResource(
                                        R.string.set_offset_value,
                                        if (misc.lyricsOffsetMs > 0) "+" else "",
                                        misc.lyricsOffsetMs,
                                    )
                                },
                                onClick = { showLyricsOffsetDialog = true },
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.lyrics_offset"),
                            )
                            6 -> SettingsActionCard(
                                icon = Icons.Filled.FormatSize,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = stringResource(R.string.set_lyrics_size_title),
                                subtitle = stringResource(
                                    R.string.set_size_value,
                                    (misc.lyricsFontScale * 100).roundToInt(),
                                    when {
                                        misc.lyricsFontScale < 0.85f -> stringResource(R.string.set_size_small)
                                        misc.lyricsFontScale in 0.85f..1.15f -> stringResource(R.string.set_size_standard)
                                        misc.lyricsFontScale in 1.16f..1.35f -> stringResource(R.string.set_size_large)
                                        else -> stringResource(R.string.set_size_xlarge)
                                    },
                                ),
                                onClick = { showLyricsSizeDialog = true },
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.lyrics_size"),
                            )
                            7 -> SettingsActionCard(
                                icon = Icons.Filled.VisibilityOff,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = stringResource(R.string.set_lyrics_autohide_title),
                                subtitle = if (misc.lyricsButtonsAutoHideSeconds == 0) {
                                    stringResource(R.string.set_lyrics_autohide_never)
                                } else {
                                    stringResource(
                                        R.string.set_lyrics_autohide_value,
                                        misc.lyricsButtonsAutoHideSeconds,
                                    )
                                },
                                onClick = { showLyricsAutoHideDialog = true },
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.lyrics_autohide"),
                            )
                            8 -> SettingsActionCard(
                                icon = Icons.Filled.Translate,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = stringResource(R.string.set_translation_language_title),
                                subtitle = if (misc.translationLanguage.isBlank()) {
                                    stringResource(R.string.set_translation_language_follows)
                                } else {
                                    translationLanguageName(misc.translationLanguage, Locale.getDefault())
                                },
                                onClick = { showTranslationLanguageDialog = true },
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.translation_language"),
                            )
                            9 -> SettingsToggleCard(
                                icon = Icons.Filled.SmartDisplay,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = stringResource(R.string.settings_music_video),
                                subtitle = if (misc.musicVideoEnabled) {
                                    stringResource(R.string.settings_music_video_on)
                                } else {
                                    stringResource(R.string.settings_music_video_off)
                                },
                                checked = misc.musicVideoEnabled,
                                onCheckedChange = viewModel::setMusicVideoEnabled,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.music_video"),
                            )
                            10 -> SettingsToggleCard(
                                icon = Icons.Filled.PlayCircle,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = "Button Now playing",
                                subtitle = if (misc.buttonNowPlayingEnabled) {
                                    "Floating button mode active • Card hidden"
                                } else {
                                    "Classic bottom now playing card"
                                },
                                checked = misc.buttonNowPlayingEnabled,
                                onCheckedChange = viewModel::setButtonNowPlayingEnabled,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.button_now_playing"),
                            )
                        }
                    }
                }
            }

                        item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(stringResource(R.string.settings_canvas_enabled))
                    val canvasRowCount = if (misc.canvasEnabled) 4 else 2
                    SettingsGroup(rowCount = canvasRowCount) { index, position ->
                        when (index) {
                            0 -> SettingsToggleCard(
                                icon = Icons.Filled.SmartDisplay,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = stringResource(R.string.settings_canvas_enabled),
                                subtitle = stringResource(R.string.settings_canvas_enabled_sub),
                                checked = misc.canvasEnabled,
                                onCheckedChange = viewModel::setCanvasEnabled,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.canvas_enabled"),
                            )
                            1 -> SettingsToggleCard(
                                icon = Icons.Filled.Visibility,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = stringResource(R.string.settings_canvas_full_bleed),
                                subtitle = stringResource(R.string.settings_canvas_full_bleed_sub),
                                checked = misc.canvasFullBleed,
                                onCheckedChange = viewModel::setCanvasFullBleed,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.canvas_full_bleed"),
                            )
                            2 -> SettingsToggleCard(
                                icon = Icons.Filled.AutoAwesome,
                                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                title = stringResource(R.string.settings_canvas_gradient_blur),
                                subtitle = stringResource(R.string.settings_canvas_gradient_blur_sub),
                                checked = misc.canvasGradientBlurEnabled,
                                onCheckedChange = viewModel::setCanvasGradientBlurEnabled,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.canvas_gradient_blur"),
                            )
                            3 -> SettingsToggleCard(
                                icon = Icons.Filled.CloudDownload,
                                iconContainer = MaterialTheme.colorScheme.surfaceVariant,
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                title = stringResource(R.string.settings_canvas_cellular),
                                subtitle = stringResource(R.string.settings_canvas_cellular_sub),
                                checked = misc.canvasOverCellular,
                                onCheckedChange = viewModel::setCanvasOverCellular,
                                position = position,
                                isHighlighted = (highlightedSettingId == "appearance.canvas_cellular"),
                            )
                        }
                    }
                }
            }

                    }

                    SettingsTab.YOUTUBE -> {
                        item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(stringResource(R.string.settings_section_youtube))
                    val ytConnected = ytConnection.isConnected
                    val syncSubtitle = when (val sync = ytSyncState) {
                        is com.lastwave.app.data.ytmusic.YtSyncState.Running ->
                            "Syncing ${sync.current}/${sync.total} \u2022 ${sync.label}"
                        is com.lastwave.app.data.ytmusic.YtSyncState.Completed ->
                            "Playlists mirror to your account \u2022 synced ${relativeTime(sync.atMillis)}"
                        is com.lastwave.app.data.ytmusic.YtSyncState.Failed ->
                            "Last pass failed \u2014 will retry automatically"
                        else ->
                            if (!ytConnected) "Connect an account first"
                            else if (ytSyncEnabled) "Selected playlists mirror to your account, 24/7" + lastSyncSuffix(ytLastSyncAt)
                            else "Keep your YT Music library in sync with LastWave"
                    }
                    // Display name of the active channel: the picked entry when
                    // the channel list was loaded, otherwise the stored name.
                    val currentChannelName = ytChannels.firstOrNull {
                        it.channelId == ytConnection.onBehalfOfUser &&
                            it.authUserIndex == ytConnection.authUserIndex &&
                            it.pageId == ytConnection.pageId
                    }?.accountName ?: ytConnection.accountName
                    val ytRowCount = if (ytConnected) 7 else 3
                    SettingsGroup(rowCount = ytRowCount) { index, position ->
                        if (!ytConnected) {
                            when (index) {
                                0 -> SettingsActionCard(
                                    icon = Icons.Filled.CloudSync,
                                    iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                    iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    title = stringResource(R.string.settings_connect_yt),
                                    subtitle = stringResource(R.string.settings_connect_yt_sub),
                                    onClick = onOpenYouTubeLogin,
                                    position = position,
                                    isHighlighted = (highlightedSettingId == "youtube.account"),
                                )
                                1 -> SettingsActionCard(
                                    icon = Icons.Filled.Key,
                                    iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                    iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    title = stringResource(R.string.settings_cookie_login),
                                    subtitle = stringResource(R.string.settings_cookie_login_sub),
                                    onClick = { showCookieLoginDialog = true },
                                    position = position,
                                    isHighlighted = (highlightedSettingId == "youtube.cookie_login"),
                                )
                                2 -> SettingsToggleCard(
                                    icon = Icons.Filled.CloudSync,
                                    iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                    iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    title = stringResource(R.string.settings_yt_sync),
                                    subtitle = syncSubtitle,
                                    checked = false,
                                    onCheckedChange = viewModel::setYtSyncEnabled,
                                    position = position,
                                    isHighlighted = (highlightedSettingId == "youtube.sync"),
                                )
                            }
                        } else {
                            when (index) {
                                0 -> YouTubeAccountRow(
                                    accountName = ytConnection.accountName,
                                    channelHandle = ytConnection.channelHandle,
                                    onDisconnect = { showYtDisconnectConfirm = true },
                                    position = position,
                                    isHighlighted = (highlightedSettingId == "youtube.account"),
                                )
                                1 -> SettingsToggleCard(
                                    icon = Icons.Filled.CloudSync,
                                    iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                    iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    title = stringResource(R.string.settings_yt_sync),
                                    subtitle = syncSubtitle,
                                    checked = ytSyncEnabled,
                                    onCheckedChange = viewModel::setYtSyncEnabled,
                                    position = position,
                                    isHighlighted = (highlightedSettingId == "youtube.sync"),
                                )
                                2 -> SettingsActionCard(
                                    icon = Icons.Filled.SwitchAccount,
                                    iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                    iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    title = stringResource(R.string.set_yt_channel_row),
                                    subtitle = currentChannelName.ifBlank { stringResource(R.string.set_channel_default) },
                                    onClick = {
                                        viewModel.loadYtChannels()
                                        showYtChannelSheet = true
                                    },
                                    position = position,
                                    isHighlighted = (highlightedSettingId == "youtube.channel"),
                                )
                            3 -> if (ytConnected) {
                                val selectedCount = syncedPlaylistIds?.size ?: allPlaylists.size
                                val syncCountText = if (syncedPlaylistIds == null || selectedCount == allPlaylists.size) {
                                    stringResource(R.string.set_sync_all, allPlaylists.size)
                                } else {
                                    stringResource(R.string.set_sync_partial, selectedCount, allPlaylists.size)
                                }
                                SettingsActionCard(
                                    icon = Icons.Filled.FormatListBulleted,
                                    iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                    iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    title = stringResource(R.string.settings_select_playlists),
                                    subtitle = syncCountText,
                                    onClick = { showSyncPlaylistsSheet = true },
                                    position = position,
                                    isHighlighted = (highlightedSettingId == "youtube.select_playlists"),
                                )
                            } else {
                                SettingsActionCard(
                                    icon = Icons.Filled.QueueMusic,
                                    iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                    iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    title = stringResource(R.string.set_import_yt),
                                    subtitle = stringResource(R.string.set_import_yt_sub),
                                    onClick = onOpenYouTubeImport,
                                    position = position,
                                    isHighlighted = (highlightedSettingId == "youtube.import"),
                                )
                            }
                            4 -> if (ytConnected) {
                                val shownCount = ytAccountPlaylists.count { it.id !in hiddenYtLibraryPlaylistIds }
                                val visibilitySubtitle = if (shownCount == ytAccountPlaylists.size) {
                                    stringResource(R.string.set_yt_shown_all, ytAccountPlaylists.size)
                                } else {
                                    stringResource(R.string.set_yt_shown_partial, shownCount, ytAccountPlaylists.size)
                                }
                                SettingsActionCard(
                                    icon = Icons.Filled.Visibility,
                                    iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                    iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    title = stringResource(R.string.settings_yt_shown),
                                    subtitle = visibilitySubtitle,
                                    onClick = { showYtLibraryVisibilitySheet = true },
                                    position = position,
                                    isHighlighted = (highlightedSettingId == "youtube.shown_playlists"),
                                )
                            }
                            5 -> SettingsActionCard(
                                icon = Icons.Filled.QueueMusic,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = stringResource(R.string.settings_make_local),
                                subtitle = stringResource(R.string.settings_make_local_sub),
                                onClick = onOpenYouTubeImport,
                                position = position,
                                isHighlighted = (highlightedSettingId == "youtube.import"),
                            )
                            6 -> if (ytConnected) {
                                SettingsToggleCard(
                                    icon = Icons.Filled.History,
                                    iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                    iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    title = stringResource(R.string.settings_yt_history),
                                    subtitle = if (ytHistorySyncEnabled) {
                                        stringResource(R.string.set_yt_hist_on)
                                    } else {
                                        stringResource(R.string.set_yt_hist_off)
                                    },
                                    checked = ytHistorySyncEnabled,
                                    onCheckedChange = viewModel::setYtHistorySyncEnabled,
                                    position = position,
                                    isHighlighted = (highlightedSettingId == "youtube.history"),
                                )
                            }
                        }
                    }
                }
            }
        }

        }

                    SettingsTab.SPOTIFY -> {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionLabel("Spotify")
                                val spotifyCanvasEnabled by viewModel.spotifyCanvasEnabled.collectAsStateWithLifecycle()
                                val spotifyPreferCanvas by viewModel.spotifyPreferCanvas.collectAsStateWithLifecycle()
                                SettingsGroup(rowCount = if (spotifyConnected) 5 else 3) { index, position ->
                                    when (index) {
                                        0 -> if (spotifyConnected) {
                                            SpotifyAccountRow(
                                                onDisconnect = { showSpotifyDisconnectConfirm = true },
                                                onClick = onOpenSpotifyLogin,
                                                position = position,
                                                isHighlighted = (highlightedSettingId == "spotify.account"),
                                            )
                                        } else {
                                            SettingsActionCard(
                                                icon = Icons.Filled.PlayCircle,
                                                iconContainer = MaterialTheme.colorScheme.surfaceVariant,
                                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                title = "Connect Spotify Account",
                                                subtitle = "Sign in to enable official Spotify Canvas video loops & library sync",
                                                onClick = onOpenSpotifyLogin,
                                                position = position,
                                                isHighlighted = (highlightedSettingId == "spotify.account"),
                                            )
                                        }
                                        1 -> SettingsActionCard(
                                            icon = Icons.Filled.QueueMusic,
                                            iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            title = "Your Spotify Playlists",
                                            subtitle = "Import your Spotify playlists and Liked Songs into this app's library",
                                            onClick = onOpenExternalImport,
                                            position = position,
                                            isHighlighted = (highlightedSettingId == "spotify.import"),
                                        )
                                        2 -> SettingsToggleCard(
                                            icon = Icons.Filled.SmartDisplay,
                                            iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            title = "Spotify Canvas (Looping background videos)",
                                            subtitle = "Fetch and play Spotify's looping visual videos in background",
                                            checked = spotifyCanvasEnabled,
                                            onCheckedChange = viewModel::setSpotifyCanvasEnabled,
                                            position = position,
                                            isHighlighted = (highlightedSettingId == "spotify.canvas"),
                                        )
                                        3 -> SettingsToggleCard(
                                            icon = Icons.Filled.AutoAwesome,
                                            iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            title = "Prefer Spotify Canvas",
                                            subtitle = "Ask Spotify first, before Apple Music, Tidal and community artwork",
                                            checked = spotifyPreferCanvas,
                                            onCheckedChange = viewModel::setSpotifyPreferCanvas,
                                            position = position,
                                            isHighlighted = (highlightedSettingId == "spotify.prefer_canvas"),
                                        )
                                        4 -> SettingsToggleCard(
                                            icon = Icons.Filled.CloudSync,
                                            iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                            title = stringResource(R.string.settings_spotify_live_playlists_title),
                                            subtitle = stringResource(R.string.settings_spotify_live_playlists_sub),
                                            checked = misc.spotifyPlaylistsSyncEnabled,
                                            onCheckedChange = viewModel::setSpotifyPlaylistsSyncEnabled,
                                            position = position,
                                            isHighlighted = (highlightedSettingId == "spotify.live_playlists"),
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionLabel(stringResource(R.string.settings_section_recommendations))
                                SettingsGroup(rowCount = 1) { _, position ->
                                    SettingsActionCard(
                                        icon = Icons.Filled.AutoAwesome,
                                        iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                        iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                        title = stringResource(R.string.settings_recommendations_title),
                                        subtitle = if (spotifyConnected) {
                                            "${stringResource(misc.recommendationSource.titleRes)} • ${stringResource(misc.recommendationSource.subtitleRes)}"
                                        } else {
                                            stringResource(R.string.settings_recommendations_sub_disconnected)
                                        },
                                        onClick = { showRecommendationSourceDialog = true },
                                        position = position,
                                        isHighlighted = (highlightedSettingId == "spotify.recommendations"),
                                    )
                                }
                            }
                        }

                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionLabel("Session Token")
                                SettingsGroup(rowCount = 1) { _, position ->
                                    SettingsActionCard(
                                        icon = Icons.Filled.Key,
                                        iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                        iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                        title = "Import sp_dc token",
                                        subtitle = "Paste a session cookie from your browser instead of signing in here",
                                        onClick = onOpenSpotifyTokenImport,
                                        position = position,
                                        isHighlighted = (highlightedSettingId == "spotify.token"),
                                    )
                                }
                            }
                        }
                    }

                    SettingsTab.LAST_FM -> {
                        item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("Integrations / Scrobbling")
                    // Last.fm is optional here — never a gate. Connected:
                    // global scrobbles + stats sync. Disconnected: Stats and
                    // recommendations run local-first from Room.
                    LastFmIntegrationCard(
                        isConnected = isLastFmConnected,
                        username = session.username,
                        avatarUrl = avatarUrl,
                        connecting = lastFmConnecting,
                        awaitingApproval = lastFmAuthUrl != null,
                        hasApiKey = hasApiKey,
                        isHighlighted = (highlightedSettingId in listOf("lastfm.connect", "lastfm.api_credentials")),
                        onConnect = { viewModel.beginLastFmConnect() },
                        onCancel = viewModel::cancelLastFmConnect,
                        onDisconnect = viewModel::disconnectLastFm,
                        onSaveKeys = viewModel::saveApiCredentials,
                        onRemoveKey = viewModel::clearApiKey,
                        onOpenCreateKeyPage = {
                            runCatching {
                                androidx.browser.customtabs.CustomTabsIntent.Builder().build()
                                    .launchUrl(context, android.net.Uri.parse(LAST_FM_CREATE_KEY_URL))
                            }.onFailure {
                                viewModel.showToast(context.getString(R.string.toast_no_browser_open_lfm))
                            }
                        },
                    )
                    // Discord presence, next to Last.fm because both are "tell
                    // other services what I'm listening to" integrations and
                    // neither is required for playback. Unlike Last.fm this
                    // needs no account in LastWave — it publishes as the
                    // LastWave application through the Discord app itself, so
                    // the copy says exactly that.
                    SettingsToggleCard(
                        icon = Icons.Filled.Public,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                        title = stringResource(R.string.set_discord_title),
                        subtitle = if (discordPresenceEnabled) {
                            stringResource(R.string.set_discord_on)
                        } else {
                            stringResource(R.string.set_discord_off)
                        },
                        checked = discordPresenceEnabled,
                        onCheckedChange = viewModel::setDiscordPresenceEnabled,
                        isHighlighted = (highlightedSettingId == "discord.presence"),
                    )
                }
            }
                    }



                    SettingsTab.LIBRARY -> {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionLabel("Home & Organization")
                                SettingsGroup(rowCount = 2) { index, position ->
                                    when (index) {
                                        0 -> SettingsActionCard(
                                            icon = Icons.Filled.Dashboard,
                                            iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            title = stringResource(R.string.settings_home_sections),
                                            subtitle = run {
                                                val total = com.lastwave.app.ui.settings.ALL_DEFAULT_UNIVERSAL_SECTIONS.size
                                                val hiddenCount = (misc.hiddenHomeSections + misc.ytMusicHiddenSections.map { "yt:$it" }).size
                                                val visible = (total - hiddenCount).coerceAtLeast(0)
                                                stringResource(R.string.home_sections_visible, visible, total)
                                            },
                                            onClick = onOpenHomeSections,
                                            position = position,
                                            isHighlighted = (highlightedSettingId == "library.home_sections"),
                                        )
                                        else -> SettingsActionCard(
                                            icon = Icons.Filled.RestartAlt,
                                            iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            title = stringResource(R.string.settings_excluded_songs),
                                            subtitle = stringResource(R.string.set_excl_count, state.recommendationExclusionCount),
                                            onClick = onOpenExcludedSongs,
                                            position = position,
                                            isHighlighted = (highlightedSettingId == "library.excluded_songs"),
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionLabel(stringResource(R.string.settings_section_content))
                                SettingsGroup(rowCount = 1) { _, position ->
                                    SettingsToggleCard(
                                        icon = Icons.Filled.Mic,
                                        iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                        iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                        title = stringResource(R.string.settings_podcasts),
                                        subtitle = if (misc.podcastsEnabled) {
                                            stringResource(R.string.settings_podcasts_sub_on)
                                        } else {
                                            stringResource(R.string.settings_podcasts_sub_off)
                                        },
                                        checked = misc.podcastsEnabled,
                                        onCheckedChange = viewModel::setPodcastsEnabled,
                                        position = position,
                                        isHighlighted = (highlightedSettingId == "library.podcasts"),
                                    )
                                }
                            }
                        }

                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionLabel(stringResource(R.string.settings_section_imports))
                                SettingsGroup(rowCount = 3) { index, position ->
                                    when (index) {
                                        0 -> SettingsActionCard(
                                            icon = Icons.Filled.QueueMusic,
                                            iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            title = stringResource(R.string.set_import_yt),
                                            subtitle = stringResource(R.string.set_import_yt_sub),
                                            onClick = onOpenYouTubeImport,
                                            position = position,
                                            isHighlighted = (highlightedSettingId == "library.import_yt"),
                                        )
                                        1 -> SettingsActionCard(
                                            icon = Icons.Filled.QueueMusic,
                                            iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            title = stringResource(R.string.set_import_spotify),
                                            subtitle = stringResource(R.string.set_import_spotify_sub),
                                            onClick = onOpenExternalImport,
                                            position = position,
                                            isHighlighted = (highlightedSettingId == "library.import_external"),
                                        )
                                        else -> SettingsActionCard(
                                            icon = Icons.Filled.FileDownload,
                                            iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                            iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                            title = stringResource(R.string.settings_import_file),
                                            subtitle = stringResource(R.string.settings_import_file_sub),
                                            onClick = {
                                                runCatching {
                                                    csvPickerLauncher.launch(arrayOf("text/*", "text/csv", "application/csv", "audio/x-mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl", "*/*"))
                                                }.onFailure { viewModel.showToast(context.getString(R.string.toast_no_picker)) }
                                            },
                                            position = position,
                                            isHighlighted = (highlightedSettingId == "library.import_file"),
                                        )
                                    }
                                }
                            }
                        }



                    }

                    SettingsTab.DATA_BACKUP -> {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionLabel(stringResource(R.string.settings_section_storage))
                                SettingsGroup(rowCount = 1) { _, position ->
                                    SettingsActionCard(
                                        icon = Icons.Filled.FolderOpen,
                                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                        iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        title = stringResource(R.string.settings_storage_title),
                                        subtitle = stringResource(R.string.settings_storage_sub),
                                        onClick = onOpenStorage,
                                        position = position,
                                    )
                                }
                            }
                        }

                        item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(stringResource(R.string.settings_section_backup))
                    SettingsGroup(rowCount = 2) { index, position ->
                        when (index) {
                            0 -> SettingsActionCard(
                                icon = Icons.Filled.Backup,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = stringResource(R.string.settings_backup),
                                subtitle = stringResource(R.string.settings_backup_sub),
                                onClick = {
                                    runCatching { backupLauncher.launch("lastwave-backup-${System.currentTimeMillis()}.json") }
                                        .onFailure { viewModel.showToast(context.getString(R.string.toast_no_picker)) }
                                },
                                position = position,
                                isHighlighted = (highlightedSettingId == "backup.create"),
                            )
                            1 -> SettingsActionCard(
                                icon = Icons.Filled.CloudDownload,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = stringResource(R.string.settings_restore),
                                subtitle = stringResource(R.string.settings_restore_sub),
                                onClick = {
                                    runCatching { restoreLauncher.launch(arrayOf("*/*")) }
                                        .onFailure { viewModel.showToast(context.getString(R.string.toast_no_picker)) }
                                },
                                position = position,
                                isHighlighted = (highlightedSettingId == "backup.restore"),
                            )
                        }
                    }
                }
            }

                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionLabel(stringResource(R.string.settings_section_data))
                                SettingsGroup(rowCount = 1) { index, position ->
                                    when (index) {
                                        0 -> SettingsActionCard(
                                            icon = Icons.Filled.Delete,
                                            iconContainer = MaterialTheme.colorScheme.errorContainer,
                                            iconTint = MaterialTheme.colorScheme.onErrorContainer,
                                            title = stringResource(R.string.settings_clear_all),
                                            subtitle = stringResource(R.string.settings_clear_all_sub),
                                            danger = true,
                                            onClick = viewModel::requestClearAllData,
                                            position = position,
                                            isHighlighted = (highlightedSettingId == "backup.clear_all"),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SettingsTab.ABOUT -> {
                        item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(stringResource(R.string.settings_section_language))
                    SettingsGroup(rowCount = 1) { _, position ->
                        SettingsActionCard(
                            icon = Icons.Filled.Language,
                            iconContainer = MaterialTheme.colorScheme.primaryContainer,
                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                            title = stringResource(R.string.settings_language_title),
                            subtitle = currentLanguage.nativeDisplayName(),
                            onClick = { showLanguageDialog = true },
                            position = position,
                            isHighlighted = (highlightedSettingId == "about.language"),
                        )
                    }
                }
            }

                        item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(stringResource(R.string.settings_section_about))
                    SettingsGroup(rowCount = 4) { index, position ->
                        when (index) {
                            0 -> SettingsActionCard(
                                icon = Icons.AutoMirrored.Filled.Send,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = stringResource(R.string.settings_updates_support),
                                subtitle = stringResource(R.string.set_tg_sub),
                                onClick = {
                                    if (!openTelegramChannel(context, "clashprojects")) {
                                        viewModel.showToast(context.getString(R.string.toast_no_browser_tg))
                                    }
                                },
                                position = position,
                                isHighlighted = (highlightedSettingId == "about.telegram_support"),
                            )
                            1 -> SettingsActionCard(
                                icon = Icons.Filled.Group,
                                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                title = stringResource(R.string.set_discord_support),
                                subtitle = stringResource(R.string.set_discord_support_sub),
                                onClick = {
                                    val discordIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/TMCEPSUNk2"))
                                    if (!startActivitySafely(context, discordIntent)) {
                                        viewModel.showToast(context.getString(R.string.toast_no_browser))
                                    }
                                },
                                position = position,
                                isHighlighted = (highlightedSettingId == "about.discord_support"),
                            )
                            2 -> SettingsActionCard(
                                icon = Icons.Filled.AutoAwesome,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                title = stringResource(R.string.settings_more_from_us),
                                subtitle = stringResource(R.string.set_more_apps_sub),
                                onClick = {
                                    if (!openTelegramChannel(context, "MaterialYouApp")) {
                                        viewModel.showToast(context.getString(R.string.toast_no_browser_tg))
                                    }
                                },
                                position = position,
                                isHighlighted = (highlightedSettingId == "about.more_apps"),
                            )
                            3 -> SettingsActionCard(
                                icon = Icons.Filled.Code,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = stringResource(R.string.settings_diagnostics),
                                subtitle = stringResource(R.string.settings_diagnostics_sub),
                                onClick = { viewModel.exportDiagnostics() },
                                position = position,
                                isHighlighted = (highlightedSettingId == "about.diagnostics"),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))

                    // Prominent Update Available Banner Card (if newer version detected)
                    if (updateInfo.isUpdateAvailable) {
                        Surface(
                            shape = CardOuterShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shadowElevation = 3.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CardOuterShape)
                                .clickable { viewModel.openUpdate(context) },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CloudDownload,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Update Available!",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Text(
                                        text = "Version ${updateInfo.latestVersion} is ready to install",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                ) {
                                    Text(
                                        text = "Update",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    AboutCard(
                        versionName = appVersionName(context),
                        appIcon = theme?.appIcon ?: AppIcon.DEFAULT,
                    )

                    SettingsActionCard(
                        icon = Icons.Filled.CloudDownload,
                        iconContainer = if (updateInfo.isUpdateAvailable) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                        iconTint = if (updateInfo.isUpdateAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer,
                        title = if (updateInfo.isUpdateAvailable) "Update Ready (${updateInfo.latestVersion})" else "Check for Updates",
                        subtitle = when {
                            updateInfo.isChecking -> "Checking GitHub releases..."
                            updateInfo.isUpdateAvailable -> {
                                val fileName = updateInfo.downloadFileName
                                if (!fileName.isNullOrBlank()) "Tap to download $fileName" else "Tap to download new version"
                            }
                            !updateInfo.message.isNullOrBlank() -> updateInfo.message.orEmpty()
                            else -> "Current version: ${appVersionName(context)}"
                        },
                        onClick = {
                            if (updateInfo.isUpdateAvailable) {
                                viewModel.openUpdate(context)
                            } else {
                                viewModel.checkForUpdates()
                            }
                        },
                        isHighlighted = (highlightedSettingId == "about.check_updates"),
                    )

                    SettingsActionCard(
                        icon = Icons.Filled.Code,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                        title = stringResource(R.string.settings_source_code),
                        subtitle = "github.com/Clash-Projects/LastWave-native",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Clash-Projects/LastWave-native"))
                            if (!startActivitySafely(context, intent)) {
                                viewModel.showToast(context.getString(R.string.toast_no_browser_plain))
                            }
                        },
                        isHighlighted = (highlightedSettingId == "about.source_code"),
                    )

                    SettingsActionCard(
                        icon = Icons.Filled.Download,
                        iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                        iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                        title = stringResource(R.string.settings_desktop_app),
                        subtitle = "github.com/Clash-Projects/LastWave-Desktop",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Clash-Projects/LastWave-Desktop"))
                            if (!startActivitySafely(context, intent)) {
                                viewModel.showToast(context.getString(R.string.toast_no_browser_plain))
                            }
                        },
                        isHighlighted = (highlightedSettingId == "about.desktop_app"),
                    )

                    Spacer(Modifier.height(14.dp))
                    SectionLabel("More from us")
                    Spacer(Modifier.height(8.dp))
                    MoreFromUsCard(
                        onOpenUrl = { url ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            if (!startActivitySafely(context, intent)) {
                                viewModel.showToast(context.getString(R.string.toast_no_browser_plain))
                            }
                        }
                    )
                }
            }
                    }
                }
            }
        }
        }
    }
    }

    // -- Custom color wheel dialog (par 8.4) --
    if (state.showColorWheel) {
        ColorWheelSheet(onDismiss = viewModel::dismissColorWheel, onApply = viewModel::applyCustomColor)
    }

    // -- Clear-all-data confirm --
    if (state.showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearAllConfirm,
            title = { Text(stringResource(R.string.dialog_clear_all_title)) },
            text = { Text(stringResource(R.string.dialog_clear_all_text)) },
            confirmButton = { TextButton(onClick = { viewModel.confirmClearAllData(onLoggedOut) }) { Text(stringResource(R.string.dialog_clear_all_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissClearAllConfirm) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    // -- YouTube Music disconnect confirm --
    if (showYtDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showYtDisconnectConfirm = false },
            title = { Text(stringResource(R.string.dialog_disconnect_yt_title)) },
            text = { Text(stringResource(R.string.dialog_disconnect_yt_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showYtDisconnectConfirm = false
                    viewModel.disconnectYouTube()
                }) { Text(stringResource(R.string.common_disconnect)) }
            },
            dismissButton = {
                TextButton(onClick = { showYtDisconnectConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showRecommendationSourceDialog) {
        RecommendationSourceDialog(
            current = if (spotifyConnected) misc.recommendationSource else RecommendationSource.YOUTUBE_MUSIC,
            spotifyConnected = spotifyConnected,
            onSelect = {
                viewModel.setRecommendationSource(it)
                showRecommendationSourceDialog = false
            },
            onDismiss = { showRecommendationSourceDialog = false },
        )
    }

    // -- Spotify disconnect confirm --
    if (showSpotifyDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showSpotifyDisconnectConfirm = false },
            title = { Text("Disconnect Spotify?") },
            text = { Text("This will remove your Spotify session and log you out. Spotify Canvas loops and synced playlists will be disabled until you reconnect.") },
            confirmButton = {
                TextButton(onClick = {
                    showSpotifyDisconnectConfirm = false
                    viewModel.disconnectSpotify()
                    runCatching {
                        val cm = android.webkit.CookieManager.getInstance()
                        for (domain in listOf("https://spotify.com", "https://accounts.spotify.com", "https://open.spotify.com")) {
                            val cookies = cm.getCookie(domain) ?: continue
                            for (cookie in cookies.split(";")) {
                                val name = cookie.substringBefore("=").trim()
                                if (name.isNotEmpty()) {
                                    cm.setCookie(domain, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/")
                                }
                            }
                        }
                        cm.flush()
                    }
                }) { Text(stringResource(R.string.common_disconnect)) }
            },
            dismissButton = {
                TextButton(onClick = { showSpotifyDisconnectConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showCookieLoginDialog) {
        CookieLoginDialog(
            onDismiss = { showCookieLoginDialog = false },
            onConnect = { cookies ->
                viewModel.connectWithCookies(cookies) { success, _ ->
                    if (success) showCookieLoginDialog = false
                }
            },
        )
    }

    // -- Restore confirm --
    if (state.showRestoreConfirm) {        val isPlaylistMirror = state.pendingRestoreKind == PendingRestoreKind.PLAYLIST_MIRROR
        AlertDialog(
            onDismissRequest = viewModel::dismissRestoreConfirm,
            title = { Text(if (isPlaylistMirror) "Sync playlist JSON?" else "Restore backup?") },
            text = {
                Text(
                    if (isPlaylistMirror) {
                        "This will merge ${state.pendingRestorePlaylistCount ?: 0} playlist(s) from the local JSON file and reconnect automatic syncing."
                    } else {
                        "This will replace your current data with ${state.pendingRestorePlaylistCount ?: 0} playlist(s) and all settings from the backup file."
                    },
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.confirmRestore(onBack) }) { Text(if (isPlaylistMirror) stringResource(R.string.common_sync) else stringResource(R.string.common_restore)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissRestoreConfirm) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    // -- App language picker (Settings -> Language) styled like reference screenshot --
    if (showLanguageDialog) {
        var selectedLanguage by remember(currentLanguage) { mutableStateOf(currentLanguage) }
        androidx.compose.ui.window.Dialog(onDismissRequest = { showLanguageDialog = false }) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.Language,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_language_dialog_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(18.dp))

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp),
                        ) {
                            items(AppLanguage.SELECTABLE) { language ->
                                val isSelected = language == selectedLanguage
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            else Color.Transparent
                                        )
                                        .clickable { selectedLanguage = language }
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (language == AppLanguage.SYSTEM) {
                                        Icon(
                                            Icons.Filled.Android,
                                            contentDescription = null,
                                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    } else {
                                        val flag = when (language) {
                                            AppLanguage.ENGLISH -> "🇺🇸"
                                            AppLanguage.TURKISH -> "🇹🇷"
                                            AppLanguage.CHINESE_SIMPLIFIED -> "🇨🇳"
                                            AppLanguage.RUSSIAN -> "🇷🇺"
                                            AppLanguage.PORTUGUESE_BRAZIL -> "🇧🇷"
                                            AppLanguage.SPANISH -> "🇪🇸"
                                            AppLanguage.INDONESIAN -> "🇮🇩"
                                            AppLanguage.HINDI -> "🇮🇳"
                                            AppLanguage.GERMAN -> "🇩🇪"
                                            AppLanguage.FRENCH -> "🇫🇷"
                                            AppLanguage.ITALIAN -> "🇮🇹"
                                            AppLanguage.JAPANESE -> "🇯🇵"
                                            AppLanguage.KOREAN -> "🇰🇷"
                                            AppLanguage.ARABIC -> "🇸🇦"
                                            AppLanguage.VIETNAMESE -> "🇻🇳"
                                            else -> "🌐"
                                        }
                                        Text(
                                            flag,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Text(
                                        language.nativeDisplayName(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                                               else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selectedLanguage = language },
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { showLanguageDialog = false },
                            shape = CircleShape,
                        ) {
                            Text(
                                stringResource(R.string.common_cancel),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                viewModel.setAppLanguage(selectedLanguage)
                                showLanguageDialog = false
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                        ) {
                            Text(
                                stringResource(android.R.string.ok),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }

    // -- Enable Scrobbling password dialog --
    if (state.showSessionKeyDialog) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = viewModel::dismissSessionKeyDialog,
            title = { Text(stringResource(R.string.set_scrobble_title)) },
            text = {
                Column {
                    Text(
                        "Last.fm only allows scrobbling through a signed session, and the only way to get one without a browser is with your password. It's sent once, directly to Last.fm over HTTPS, and never stored.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.set_scrobble_pass)) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        isError = state.sessionKeyError != null,
                        supportingText = state.sessionKeyError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.submitPassword(password) },
                    enabled = !state.sessionKeyLoading,
                ) {
                    if (state.sessionKeyLoading) {
                        com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator(size = 18.dp)
                    } else {
                        Text(stringResource(R.string.set_scrobble_enable))
                    }
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissSessionKeyDialog) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (showAutoEq) {
        com.lastwave.app.ui.settings.autoeq.AutoEqDialog(
            onDismiss = { showAutoEq = false },
            onOpenGraphicEq = {
                showAutoEq = false
                showEqSheet = true
            },
        )
    }

    if (showJamSheet) {
        LastWaveJamSheet(
            viewModel = jamViewModel,
            onDismiss = { showJamSheet = false },
        )
    }

    // -- Experimental 15-band equalizer --
    if (showEqSheet) {
        EqualizerSheet(
            eq = eq,
            onDismiss = { showEqSheet = false },
            onSetEnabled = viewModel::setEqualizerEnabled,
            onPickPreset = viewModel::applyEqPreset,
            onBandPreview = viewModel::previewEqBandGain,
            onBandChange = viewModel::setEqBandGain,
            onOpenAutoEq = {
                showEqSheet = false
                showAutoEq = true
            },
        )
    }



    // -- Lyrics provider picker: preferred source first, automatic
    // fallback to the rest when it returns nothing --
    if (showLyricsProviderDialog) {
        LyricsProviderDialog(
            current = misc.lyricsProvider,
            onSelect = {
                viewModel.setLyricsProvider(it)
                showLyricsProviderDialog = false
            },
            onDismiss = { showLyricsProviderDialog = false },
        )
    }

    // -- Lyrics sync offset stepper: shifts highlight/focus only --
    if (showLyricsOffsetDialog) {
        LyricsOffsetDialog(
            currentMs = misc.lyricsOffsetMs,
            onSelect = { viewModel.setLyricsOffsetMs(it) },
            onDismiss = { showLyricsOffsetDialog = false },
        )
    }

    // -- Lyrics text size dialog: scales font size with live preview --
    if (showLyricsSizeDialog) {
        LyricsSizeDialog(
            currentScale = misc.lyricsFontScale,
            onSelect = { viewModel.setLyricsFontScale(it) },
            onDismiss = { showLyricsSizeDialog = false },
        )
    }

    // -- Floating lyrics buttons: idle time before they fade out --
    if (showLyricsAutoHideDialog) {
        LyricsAutoHideDialog(
            currentSeconds = misc.lyricsButtonsAutoHideSeconds,
            onSelect = {
                viewModel.setLyricsButtonsAutoHideSeconds(it)
                showLyricsAutoHideDialog = false
            },
            onDismiss = { showLyricsAutoHideDialog = false },
        )
    }

    // -- Language the lyrics translate button turns songs into --
    if (showTranslationLanguageDialog) {
        TranslationLanguageDialog(
            current = misc.translationLanguage,
            onSelect = {
                viewModel.setTranslationLanguage(it)
                showTranslationLanguageDialog = false
            },
            onDismiss = { showTranslationLanguageDialog = false },
        )
    }

    if (showLoudnessDialog) {
        LoudnessModeDialog(
            current = loudness.mode,
            onSelect = {
                viewModel.setLoudnessMode(it)
                showLoudnessDialog = false
            },
            onDismiss = { showLoudnessDialog = false },
        )
    }

    if (showClarityPresetDialog) {
        ClarityPresetDialog(
            currentIndex = misc.clarityPreset,
            onSelect = {
                viewModel.setClarityPreset(it)
                showClarityPresetDialog = false
            },
            onDismiss = { showClarityPresetDialog = false },
        )
    }

    // -- Selective Playlist Sync sheet --
    if (showSyncPlaylistsSheet) {
        SyncPlaylistsSheet(
            playlists = allPlaylists,
            syncedIds = syncedPlaylistIds,
            onToggleSync = viewModel::togglePlaylistSync,
            onSelectAll = viewModel::selectAllPlaylistsForSync,
            onDismiss = { showSyncPlaylistsSheet = false },
        )
    }

    if (showYtLibraryVisibilitySheet) {
        YouTubeLibraryVisibilitySheet(
            playlists = ytAccountPlaylists,
            hiddenIds = hiddenYtLibraryPlaylistIds,
            onSetVisible = viewModel::setYtLibraryPlaylistVisible,
            onSetAllVisible = viewModel::setAllYtLibraryPlaylistsVisible,
            onDismiss = { showYtLibraryVisibilitySheet = false },
        )
    }

    if (showYtChannelSheet) {
        YouTubeChannelSheet(
            channels = ytChannels,
            isLoading = ytChannelsLoading,
            selectedChannelId = ytConnection.onBehalfOfUser,
            selectedAuthUser = ytConnection.authUserIndex,
            selectedPageId = ytConnection.pageId,
            onReload = viewModel::loadYtChannels,
            onSelect = {
                viewModel.selectYtChannel(it)
                showYtChannelSheet = false
            },
            onDismiss = { showYtChannelSheet = false },
        )
    }

    if (showQualityDialog) {
        val tiers = listOfNotNull(
            Triple(
                CELLULAR_QUALITY_AUTO,
                "Auto",
                "Adapts to your connection • Up to your Wi-Fi quality" to "AUTO",
            ).takeIf { editingCellularQuality },
            Triple(28, "Dolby Atmos", "Spatial Immersive Audio • Master Audio" to "ATMOS"),
            Triple(27, "Hi-Res Lossless", "Up to 24-bit / 192 kHz • Lossless FLAC" to "HI-RES"),
            Triple(5, "Standard Quality", "Up to 320 kbps • MP3 / AAC" to "320 kbps"),
            Triple(-1, "YouTube Music", "128-256 kbps • YouTube Music AAC / Opus stream" to "YOUTUBE"),
            Triple(4, "Data Saver", "Low-bitrate audio to reduce mobile data use" to "DATA SAVER"),
        )
        val selectedQuality = if (editingCellularQuality) misc.cellularStreamingQuality else misc.wifiStreamingQuality
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

        ModalBottomSheet(
            onDismissRequest = { showQualityDialog = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            dragHandle = {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .padding(top = 12.dp, bottom = 8.dp)
                        .size(width = 36.dp, height = 4.dp),
                ) {}
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .adaptiveContentWidth(maxWidth = 640.dp)
                    .align(Alignment.CenterHorizontally)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp + safeDrawingBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.HighQuality,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            if (editingCellularQuality) "Cellular streaming quality" else "Wi-Fi streaming quality",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Choose the preferred quality for this connection",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text(
                    "YouTube Music plays strictly as-is (never upgrades). Dolby Atmos plays only when selected — otherwise it never plays. Atmos falls back to Hi-Res → CD → 320 kbps → YouTube. Hi-Res steps down to lower lossless tiers, then YouTube last.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    tiers.forEach { (qualityId, title, meta) ->
                        val (subtitle, badge) = meta
                        val isSelected = selectedQuality == qualityId
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick)
                                if (editingCellularQuality) {
                                    viewModel.setCellularStreamingQuality(qualityId)
                                } else {
                                    viewModel.setWifiStreamingQuality(qualityId)
                                }
                                showQualityDialog = false
                            },
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            shadowElevation = if (isSelected) 3.dp else 0.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        ) {
                                            Text(
                                                badge,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = stringResource(R.string.set_selected_cd),
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDownloadQualityDialog) {
        val downloadTiers = listOf(
            Triple(28, "Dolby Atmos", "Spatial Immersive Audio • Master Audio" to "ATMOS"),
            Triple(27, "Hi-Res Audio (Max)", "Up to 24-bit / 192 kHz • Studio Master FLAC" to "24-BIT / 192k"),
            Triple(7, "Hi-Res Audio", "24-bit / 96 kHz • Studio FLAC" to "24-BIT / 96k"),
            Triple(6, "CD Lossless", "16-bit / 44.1 kHz • Bit-Exact CD FLAC" to "16-BIT / 44.1k"),
            Triple(5, "Standard Quality", "320 kbps • High-Bitrate MP3 / AAC" to "320 kbps"),
            Triple(4, "Data Saver", "96 kbps • High Efficiency AAC" to "96 kbps"),
            Triple(-1, "YouTube Music", "128-256 kbps • YouTube Music AAC / Opus stream" to "YOUTUBE"),
        )
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

        ModalBottomSheet(
            onDismissRequest = { showDownloadQualityDialog = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            dragHandle = {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .padding(top = 12.dp, bottom = 8.dp)
                        .size(width = 36.dp, height = 4.dp),
                ) {}
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .adaptiveContentWidth(maxWidth = 640.dp)
                    .align(Alignment.CenterHorizontally)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp + safeDrawingBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.CloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            "Download Quality",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Select offline audio resolution & bit depth",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text(
                    "YouTube Music downloads strictly as-is (never upgrades). Dolby Atmos downloads only when selected — otherwise it never downloads. Atmos falls back to Hi-Res → CD → 320 kbps → YouTube. Hi-Res steps down to lower tiers, then YouTube last.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    downloadTiers.forEach { (qualityId, title, meta) ->
                        val (subtitle, badge) = meta
                        val isSelected = misc.downloadQuality == qualityId
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick)
                                viewModel.setDownloadQuality(qualityId)
                                showDownloadQualityDialog = false
                            },
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            shadowElevation = if (isSelected) 3.dp else 0.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        ) {
                                            Text(
                                                badge,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = stringResource(R.string.set_selected_cd),
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showYouTubeImportSheet) {
        YouTubeImportSheet(
            onDismiss = { showYouTubeImportSheet = false },
            innerTube = viewModel.innerTube,
            importManager = viewModel.playlistImportManager,
            onImportSuccess = { saved ->
                viewModel.showToast(context.getString(R.string.toast_imported, saved.title, saved.tracks.size))
            },
        )
    }

    state.toastMessage?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(3000)
            viewModel.dismissToast()
        }
        Box(
            Modifier
                .fillMaxSize()
                .safeHorizontalContentPadding()
                .padding(
                    bottom = 24.dp +
                        LocalMiniPlayerScrollClearance.current +
                        safeDrawingBottomPadding(),
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(shape = ExpressivePillShape, color = MaterialTheme.colorScheme.inverseSurface, tonalElevation = 6.dp) {
                Text(msg, color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
    }

    if (activeToggleDetail != null) {
        val detail = activeToggleDetail!!
        Dialog(
            onDismissRequest = { activeToggleDetail = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                // Scrim background (unblurred content on top)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { activeToggleDetail = null },
                        ),
                )
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .widthIn(max = 420.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (detail.checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.size(44.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        imageVector = if (detail.checked) Icons.Filled.Check else Icons.Filled.Info,
                                        contentDescription = null,
                                        tint = if (detail.checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (detail.checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                            ) {
                                Text(
                                    text = if (detail.checked) "Enabled" else "Disabled",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (detail.checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = detail.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = detail.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 22.sp,
                        )
                        Spacer(Modifier.height(20.dp))
                        FilledTonalButton(
                            onClick = { activeToggleDetail = null },
                            modifier = Modifier.align(Alignment.End),
                            shape = CircleShape,
                        ) {
                            Text("Close", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
