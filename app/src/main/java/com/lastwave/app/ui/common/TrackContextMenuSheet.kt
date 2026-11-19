package com.lastwave.app.ui.common

import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.style.TextAlign

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineStart

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.QueuePlayNext
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.lastwave.app.R
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.playback.toPlayableTrack
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.ui.navigation.ArtistAlbumNavigator
import com.lastwave.app.ui.player.LocalMusicPlayer
import com.lastwave.app.ui.player.LocalAddToPlaylist
import com.lastwave.app.ui.player.PlayerCastMenuRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which optional rows this instance of the sheet should show — matches the
 *  reference's per-screen menu variance (§1.7 / §6.5 / home.js's reduced
 *  menu): Home and Discover omit Delete Scrobble; Playlist/Search/
 *  Genre Detail include everything. */
data class TrackMenuCapabilities(
    val showDeleteScrobble: Boolean = true,
    val showRecommendationExclusion: Boolean = false,
    val showPlayAction: Boolean = true,
)

sealed interface TrackMenuTarget {
    data class Track(val name: String, val artist: String, val url: String) : TrackMenuTarget
    data class Artist(val name: String, val url: String) : TrackMenuTarget
    data class Album(val name: String, val artist: String, val url: String) : TrackMenuTarget
}

@HiltViewModel
class ArtistAlbumMenuViewModel @Inject constructor(
    private val navigator: ArtistAlbumNavigator,
) : ViewModel() {
    fun openArtist(name: String, browseId: String? = null) {
        navigator.openArtist(name, browseId)
    }

    fun openAlbum(title: String, artist: String = "", browseId: String? = null) {
        navigator.openAlbum(title, artist, browseId)
    }
}

/** Builds similar songs using YouTube Music + Last.fm and switches the queue to
 *  them: the playing song keeps playing and the mix replaces what came after it. */
@HiltViewModel
class StartMixMenuViewModel @Inject constructor(
    private val generateRepository: com.lastwave.app.data.generate.GenerateRepository,
    private val musicPlayer: com.lastwave.app.playback.MusicPlayer,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {
    fun startMix(trackName: String, artistName: String, videoId: String? = null) {
        if (trackName.isBlank() || artistName.isBlank()) return
        viewModelScope.launch {
            runCatching {
                android.widget.Toast.makeText(appContext, "Finding similar songs for $trackName…", android.widget.Toast.LENGTH_SHORT).show()
                val similar = generateRepository.fetchSimilarTracks(
                    track = trackName,
                    artist = artistName,
                    limit = 30,
                    seedVideoId = videoId,
                )
                if (similar.isNotEmpty()) {
                    val playable = similar.map { it.toPlayableTrack() }
                    musicPlayer.replaceUpcoming(playable)
                    android.widget.Toast.makeText(appContext, "Queue switched to a mix of ${playable.size} songs", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(appContext, "Could not find related tracks", android.widget.Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                android.util.Log.e("StartMix", "Failed to append mix tracks", error)
            }
        }
    }
}

enum class TrackDownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
}

@HiltViewModel
class DownloadMenuViewModel @Inject constructor(
    private val downloadManager: com.lastwave.app.data.download.TrackDownloadManager,
    settingsPreferences: com.lastwave.app.data.local.SettingsPreferences,
    private val routeNavigator: com.lastwave.app.ui.navigation.AppRouteNavigator,
) : ViewModel() {
    val activeDownloads = downloadManager.downloads

    /** Mirrors Settings -> Audio & Streaming -> Download Quality so the
     *  3-dot menu label always shows what will actually be downloaded. */
    val downloadQuality = settingsPreferences.settings
        .map { it.downloadQuality }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), 27)

    val musicVideoEnabled = settingsPreferences.settings
        .map { it.musicVideoEnabled }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), false)

    suspend fun checkStatus(title: String, artist: String): TrackDownloadStatus {
        if (downloadManager.isDownloading(title, artist)) return TrackDownloadStatus.DOWNLOADING
        if (downloadManager.isTrackDownloaded(title, artist)) return TrackDownloadStatus.DOWNLOADED
        return TrackDownloadStatus.NOT_DOWNLOADED
    }

    fun download(
        title: String,
        artist: String,
        album: String? = null,
        artworkUrl: String? = null,
        videoId: String? = null,
        durationMs: Long? = null,
    ) {
        downloadManager.downloadTrack(
            title = title,
            artist = artist,
            album = album,
            artworkUrl = artworkUrl,
            videoId = videoId,
            durationMs = durationMs,
        )
        routeNavigator.navigateTo(com.lastwave.app.ui.navigation.Screen.Downloads.route)
    }
}

/** Short label for the configured download tier — must stay in sync with the
 *  Download Quality tiers in SettingsScreen (28/27/7/6/5/4/-1). */
fun downloadLabelForQuality(context: android.content.Context, quality: Int): String =
    context.getString(R.string.menu_download_tier, downloadTierForQuality(context, quality))

private fun downloadTierForQuality(context: android.content.Context, quality: Int): String {
    val tier = when (quality) {
        28 -> context.getString(R.string.menu_quality_atmos)
        27, 7 -> context.getString(R.string.menu_quality_hires)
        6 -> context.getString(R.string.menu_quality_cd)
        5 -> context.getString(R.string.menu_quality_standard)
        4 -> context.getString(R.string.menu_quality_saver)
        -1 -> context.getString(R.string.menu_quality_youtube)
        else -> context.getString(R.string.menu_quality_hires)
    }
    return tier
}

/** Same idea, for the Genre row — every caller (Home, Discover, Playlist,
 *  Search) gets "tap the genre to open it in Genres" for free, without
 *  each of them needing to pass onExploreGenre + a NavController down
 *  through their own screen. */
@HiltViewModel
class ExploreGenreMenuViewModel @Inject constructor(private val genreExplorer: com.lastwave.app.ui.genres.GenreExplorer) : ViewModel() {
    fun explore(genre: String) {
        genreExplorer.explore(genre)
    }
}

internal fun extractYouTubeVideoId(urlOrId: String?): String? {
    if (urlOrId.isNullOrBlank()) return null
    val trimmed = urlOrId.trim()
    if (trimmed.length == 11 && trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
        return trimmed
    }
    return try {
        val uri = android.net.Uri.parse(trimmed)
        val host = uri.host?.lowercase().orEmpty()
        if (host.contains("youtu.be")) {
            uri.pathSegments.firstOrNull()?.takeIf { it.length == 11 }
        } else if (host.contains("youtube.com")) {
            uri.getQueryParameter("v")?.takeIf { it.isNotBlank() }
                ?: uri.pathSegments.let { segments ->
                    val idx = segments.indexOf("shorts").takeIf { it != -1 }
                        ?: segments.indexOf("embed").takeIf { it != -1 }
                        ?: segments.indexOf("v").takeIf { it != -1 }
                    if (idx != null && idx >= 0 && idx + 1 < segments.size) segments[idx + 1].takeIf { it.length == 11 } else null
                }
        } else null
    } catch (_: Exception) {
        null
    }
}

@HiltViewModel
class TrackLinkViewModel @Inject constructor(
    private val innerTube: com.lastwave.app.data.music.InnerTubeMusicApi,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {
    fun copyYouTubeLink(
        title: String,
        artist: String,
        existingVideoId: String?,
        url: String?,
    ) {
        val directId = existingVideoId?.takeIf { it.isNotBlank() } ?: extractYouTubeVideoId(url)
        if (directId != null) {
            copyToClipboard("https://www.youtube.com/watch?v=$directId")
            return
        }
        viewModelScope.launch {
            val link = runCatching {
                val match = innerTube.findBestMatchOrNull(title, artist, prefetchStreams = false)
                if (match != null && match.videoId.isNotBlank()) {
                    "https://www.youtube.com/watch?v=${match.videoId}"
                } else {
                    val query = "$title $artist".trim()
                    val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                    "https://www.youtube.com/results?search_query=$encoded"
                }
            }.getOrElse {
                val query = "$title $artist".trim()
                val encoded = runCatching { java.net.URLEncoder.encode(query, "UTF-8") }.getOrDefault("")
                "https://www.youtube.com/results?search_query=$encoded"
            }
            copyToClipboard(link)
        }
    }

    private fun copyToClipboard(link: String) {
        val clipboard = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("YouTube Link", link)
        clipboard?.setPrimaryClip(clip)
        android.widget.Toast.makeText(appContext, appContext.getString(R.string.link_copied), android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** music.youtube.com rather than youtube.com: if YouTube Music is installed
 *  it's registered as that domain's Android App Link target, so a plain
 *  ACTION_VIEW opens the app directly — no explicit package targeting (and
 *  the manifest <queries> visibility declaration that would need) required.
 *  Falls back to the YouTube Music website when the app isn't installed. */
private fun openUrl(context: Context, url: String) {
    if (url.isBlank()) return
    try {
        val targetUri = if (url.startsWith("http://") || url.startsWith("https://")) {
            android.net.Uri.parse(url)
        } else {
            android.net.Uri.parse("https://$url")
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, targetUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // Fallback or handle gracefully
    }
}

private fun buildLastFmUrl(target: TrackMenuTarget): String {
    return when (target) {
        is TrackMenuTarget.Track -> if (target.url.isNotBlank() && !target.url.startsWith("/")) target.url else try {
            "https://www.last.fm/music/${java.net.URLEncoder.encode(target.artist, "UTF-8")}/_/${java.net.URLEncoder.encode(target.name, "UTF-8")}"
        } catch (e: Exception) { "" }
        is TrackMenuTarget.Artist -> if (target.url.isNotBlank()) target.url else try {
            "https://www.last.fm/music/${java.net.URLEncoder.encode(target.name, "UTF-8")}"
        } catch (e: Exception) { "" }
        is TrackMenuTarget.Album -> if (target.url.isNotBlank()) target.url else try {
            "https://www.last.fm/music/${java.net.URLEncoder.encode(target.artist, "UTF-8")}/${java.net.URLEncoder.encode(target.name, "UTF-8")}"
        } catch (e: Exception) { "" }
    }
}

/** Shared overflow sheet; callbacks keep playback and navigation owned by the caller. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrackContextMenuSheet(
    target: TrackMenuTarget,
    capabilities: TrackMenuCapabilities,
    playableTrack: PlayableTrack? = null,
    onDismiss: () -> Unit,
    playbackSourceLabel: String = "LastWave",
    onPlayInLastWave: (() -> Unit)? = null,
    onStartMix: ((trackName: String, artistName: String) -> Unit)? = null,
    onExploreGenre: ((genre: String) -> Unit)? = null,
    onDeleteScrobble: ((trackName: String, artistName: String) -> Unit)? = null,
    onRefreshArtwork: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onRemoveFromQueue: (() -> Unit)? = null,
    onWatchVideo: (() -> Unit)? = null,
    canWatchVideo: Boolean = true,
    genreResolverViewModel: GenreRowViewModel = hiltViewModel(),
    startMixViewModel: StartMixMenuViewModel = hiltViewModel(),
    exploreGenreViewModel: ExploreGenreMenuViewModel = hiltViewModel(),
    downloadViewModel: DownloadMenuViewModel = hiltViewModel(),
    artistAlbumViewModel: ArtistAlbumMenuViewModel = hiltViewModel(),
    trackLinkViewModel: TrackLinkViewModel = hiltViewModel(),
    exclusionViewModel: RecommendationExclusionMenuViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var dismissing by remember { mutableStateOf(false) }
    fun dismissMenu() {
        if (dismissing) return
        dismissing = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                sheetState.hide()
            } finally {
                onDismiss()
            }
        }
    }
    val musicPlayer = LocalMusicPlayer.current
    val addToPlaylist = LocalAddToPlaylist.current
    var showDetailsSheet by remember { mutableStateOf(false) }
    var showJamSheet by remember { mutableStateOf(false) }
    var showSyncedVideoDialog by remember { mutableStateOf(false) }
    val musicVideoEnabled by downloadViewModel.musicVideoEnabled.collectAsStateWithLifecycle()
    var showTimerDialog by remember { mutableStateOf(false) }
    var resolvedGenre by remember(target) { mutableStateOf<String?>(null) }
    var resolvingGenre by remember(target) { mutableStateOf(false) }
    val activeDownloads by downloadViewModel.activeDownloads.collectAsStateWithLifecycle()
    val downloadQuality by downloadViewModel.downloadQuality.collectAsStateWithLifecycle()
    val downloadTier = downloadTierForQuality(context, downloadQuality)
    var isDownloaded by remember(target) { mutableStateOf(false) }

    LaunchedEffect(target, activeDownloads) {
        if (target is TrackMenuTarget.Track) {
            isDownloaded = runCatching {
                downloadViewModel.checkStatus(target.name, target.artist)
            }.getOrDefault(TrackDownloadStatus.NOT_DOWNLOADED) == TrackDownloadStatus.DOWNLOADED
        }
    }

    LaunchedEffect(target, playableTrack?.mediaType) {
        if (target is TrackMenuTarget.Track && playableTrack?.mediaType != com.lastwave.app.playback.MediaType.PODCAST_EPISODE) {
            resolvingGenre = true
            resolvedGenre = runCatching { genreResolverViewModel.resolve(target.name, target.artist) }.getOrNull()
            resolvingGenre = false
        }
    }

    fun exploreGenre(genre: String) {
        if (onExploreGenre != null) onExploreGenre(genre)
        else exploreGenreViewModel.explore(genre)
    }

    if (showDetailsSheet && target is TrackMenuTarget.Track) {
        val playable = playableTrack ?: PlayableTrack(title = target.name, artist = target.artist)
        TrackDetailsSheet(
            title = target.name,
            artist = target.artist,
            album = playable.album,
            artworkUrl = playable.artworkUrl,
            videoId = playable.videoId,
            durationMs = playable.durationMs,
            onDismiss = {
                showDetailsSheet = false
                onDismiss()
            },
            onPlayTrack = {
                onPlayInLastWave?.invoke() ?: musicPlayer.play(playable, sourceLabel = playbackSourceLabel)
            },
        )
        return
    }

    if (showSyncedVideoDialog && target is TrackMenuTarget.Track) {
        val playable = playableTrack ?: PlayableTrack(title = target.name, artist = target.artist)
        com.lastwave.app.ui.player.SyncedVideoPlayerDialog(
            track = playable,
            musicPlayer = musicPlayer,
            onDismiss = {
                showSyncedVideoDialog = false
                onDismiss()
            },
        )
        return
    }

    if (showJamSheet) {
        com.lastwave.app.ui.jam.LastWaveJamSheet(
            onDismiss = {
                showJamSheet = false
                onDismiss()
            },
        )
        return
    }

    if (showTimerDialog) {
        var customMinutes by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
        val customDuration = customMinutes.toIntOrNull()?.takeIf { it > 0 }
        var showCustomDialog by remember { mutableStateOf(false) }

        if (showCustomDialog) {
            AlertDialog(
                onDismissRequest = { showCustomDialog = false },
                title = { Text(stringResource(R.string.menu_custom_sleep_timer)) },
                text = {
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { customMinutes = it },
                        label = { Text(stringResource(R.string.menu_minutes)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = customMinutes.isNotEmpty() && customDuration == null,
                        supportingText = {
                            if (customMinutes.isNotEmpty() && customDuration == null) {
                                Text(stringResource(R.string.menu_positive_number))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = customDuration != null,
                        onClick = {
                            customDuration?.let(musicPlayer::setSleepTimerMinutes)
                            showCustomDialog = false
                            showTimerDialog = false
                        },
                    ) { Text(stringResource(R.string.menu_set_timer)) }
                },
                dismissButton = {
                    TextButton(onClick = { showCustomDialog = false }) { Text(stringResource(R.string.common_cancel)) }
                }
            )
        }

        androidx.compose.ui.window.Dialog(onDismissRequest = { showTimerDialog = false }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.menu_sleep_timer), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        androidx.compose.material3.IconButton(
                            onClick = { showCustomDialog = true },
                            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        ) {
                            Icon(Icons.Filled.Add, stringResource(R.string.menu_custom_timer))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    listOf(0, 15, 30, 60).forEach { minutes ->
                        TextButton(
                            onClick = {
                                musicPlayer.setSleepTimerMinutes(minutes)
                                showTimerDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (minutes == 0) stringResource(R.string.menu_timer_off) else stringResource(R.string.menu_timer_minutes, minutes)) }
                    }
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        EdgeToEdgeDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .adaptiveContentWidth(maxWidth = 600.dp)
                .align(Alignment.CenterHorizontally)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp + safeDrawingBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (target is TrackMenuTarget.Track) {
                val t = target
                val playable = playableTrack ?: PlayableTrack(title = t.name, artist = t.artist)
                val isSong = playable.mediaType == com.lastwave.app.playback.MediaType.SONG
                val isLocalFile = com.lastwave.app.data.generate.LocalAudioFormats
                    .importedPathOrNull(playable.playbackUrl) != null
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ArtworkImage(
                            name = t.name,
                            artist = t.artist,
                            embeddedUrl = playable.artworkUrl,
                            fallbackIcon = Icons.Filled.MusicNote,
                            modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.medium),
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                            if (!isSong && t.artist.isNotBlank()) {
                                Text(t.artist, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        IconButton(onClick = ::dismissMenu, enabled = !dismissing) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.common_close))
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (isSong) {
                            com.lastwave.app.util.ArtistHelper.splitArtists(t.artist).forEach { artist ->
                                MenuMetadataAction(Icons.Outlined.Person, artist, menuEnabled = !dismissing) {
                                    artistAlbumViewModel.openArtist(artist)
                                    dismissMenu()
                                }
                            }
                            playable.album?.takeIf { it.isNotBlank() }?.let { album ->
                                MenuMetadataAction(Icons.Outlined.Album, album, menuEnabled = !dismissing) {
                                    artistAlbumViewModel.openAlbum(album, playable.artist.ifBlank { t.artist })
                                    dismissMenu()
                                }
                            }
                        }
                        val genre = resolvedGenre?.trim()
                        val hasGenre = !genre.isNullOrBlank() && !genre.equals("unknown", ignoreCase = true)
                        if (resolvingGenre || hasGenre) {
                            MenuMetadataAction(
                                icon = Icons.Outlined.Sell,
                                label = if (resolvingGenre) stringResource(R.string.menu_resolving_genre) else genre!!,
                                loading = resolvingGenre,
                                enabled = !resolvingGenre && hasGenre,
                                menuEnabled = !dismissing,
                            ) {
                                exploreGenre(genre!!)
                                dismissMenu()
                            }
                        }
                    }
                }
                if (isSong) StartMixCard(enabled = !dismissing) {
                    if (onStartMix != null) onStartMix(t.name, t.artist)
                    else startMixViewModel.startMix(t.name, t.artist, playable.videoId)
                    dismissMenu()
                }
                QuickActionsRow(
                    enabled = !dismissing,
                    onPlayNext = { musicPlayer.playNext(playable); dismissMenu() },
                    onAddToPlaylist = { addToPlaylist(playable); dismissMenu() },
                    onAddToQueue = { musicPlayer.addToQueue(playable); dismissMenu() },
                )
                val actions = buildList<@Composable () -> Unit> {
                    val downloadKey = com.lastwave.app.data.download.TrackDownloadManager.makeDownloadKey(t.name, t.artist)
                    val isDownloading = activeDownloads[downloadKey]?.let { !it.isFinished } == true
                    if (!isLocalFile) add {
                        when {
                            isDownloaded -> MenuActionRow(
                                Icons.Filled.CheckCircle, stringResource(R.string.menu_downloaded),
                                enabled = !dismissing,
                            ) {
                                android.widget.Toast.makeText(context, context.getString(R.string.menu_already_downloaded),
                                    android.widget.Toast.LENGTH_SHORT).show()
                                dismissMenu()
                            }
                            isDownloading -> MenuActionRow(
                                Icons.Outlined.Download, stringResource(R.string.menu_downloading),
                                enabled = !dismissing,
                            ) {
                                android.widget.Toast.makeText(context, context.getString(R.string.menu_download_progress),
                                    android.widget.Toast.LENGTH_SHORT).show()
                                dismissMenu()
                            }
                            else -> MenuActionRow(
                                Icons.Outlined.Download, stringResource(R.string.details_download),
                                supportingText = downloadTier,
                                enabled = !dismissing,
                            ) {
                                downloadViewModel.download(
                                    title = t.name, artist = t.artist, album = playable.album,
                                    artworkUrl = playable.artworkUrl, videoId = playable.videoId,
                                    durationMs = playable.durationMs,
                                )
                                dismissMenu()
                            }
                        }
                    }
                    if (isSong && musicVideoEnabled && canWatchVideo) add {
                        MenuActionRow(Icons.Outlined.SmartDisplay, stringResource(R.string.menu_watch_video), enabled = !dismissing) {
                            if (onWatchVideo != null) {
                                onWatchVideo()
                                dismissMenu()
                            } else {
                                showSyncedVideoDialog = true
                            }
                        }
                    }
                    add {
                        MenuActionRow(Icons.Outlined.Timer, stringResource(R.string.menu_sleep_timer), enabled = !dismissing) {
                            showTimerDialog = true
                        }
                    }
                    add { PlayerCastMenuRow(musicPlayer, enabled = !dismissing) }
                    add {
                        MenuActionRow(Icons.Outlined.Group, stringResource(R.string.menu_quick_jam), enabled = !dismissing) {
                            showJamSheet = true
                        }
                    }
                    add {
                        MenuActionRow(Icons.Outlined.Info, stringResource(R.string.menu_audio_details), enabled = !dismissing) {
                            showDetailsSheet = true
                        }
                    }
                    if (capabilities.showPlayAction) add {
                        MenuActionRow(Icons.Outlined.PlayCircle, stringResource(R.string.menu_quick_play), enabled = !dismissing) {
                            onPlayInLastWave?.invoke() ?: musicPlayer.play(playable, sourceLabel = playbackSourceLabel)
                            dismissMenu()
                        }
                    }
                }
                MenuActionGrid(actions)
                MenuActionGrid(maxColumns = 1, actions = buildList<@Composable () -> Unit> {
                    add {
                        MenuActionRow(Icons.Outlined.Link, stringResource(R.string.menu_copy_link), quiet = true, enabled = !dismissing) {
                            val directId = playable.videoId?.takeIf { it.isNotBlank() } ?: extractYouTubeVideoId(t.url)
                            if (directId != null) {
                                val link = "https://www.youtube.com/watch?v=$directId"
                                clipboard.setText(AnnotatedString(link))
                                android.widget.Toast.makeText(context, context.getString(R.string.link_copied), android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                trackLinkViewModel.copyYouTubeLink(t.name, t.artist, playable.videoId, t.url)
                            }
                            dismissMenu()
                        }
                    }
                    if (onRefreshArtwork != null) add {
                        MenuActionRow(Icons.Outlined.Refresh, stringResource(R.string.menu_refresh_artwork), quiet = true, enabled = !dismissing) {
                            onRefreshArtwork()
                            dismissMenu()
                        }
                    }
                    if (capabilities.showRecommendationExclusion && !isLocalFile && isSong) add {
                        MenuActionRow(Icons.Outlined.ThumbDown, stringResource(R.string.menu_dont_recommend), quiet = true, enabled = !dismissing) {
                            exclusionViewModel.exclude(playable)
                            dismissMenu()
                        }
                    }
                    if (onRemoveFromPlaylist != null) add {
                        MenuActionRow(Icons.Outlined.Delete, stringResource(R.string.menu_remove_from_playlist), danger = true, enabled = !dismissing) {
                            onRemoveFromPlaylist()
                            dismissMenu()
                        }
                    }
                    if (onRemoveFromQueue != null) add {
                        MenuActionRow(Icons.Outlined.Delete, stringResource(R.string.menu_remove_from_queue), danger = true, enabled = !dismissing) {
                            onRemoveFromQueue()
                            dismissMenu()
                        }
                    }
                })
            } else if (target is TrackMenuTarget.Artist) {
                MenuActionGrid(maxColumns = 1, actions = listOf<@Composable () -> Unit>(
                    {
                        MenuActionRow(Icons.Outlined.Person, stringResource(R.string.menu_view_artist), enabled = !dismissing) {
                            artistAlbumViewModel.openArtist(target.name)
                            dismissMenu()
                        }
                    },
                    {
                        MenuActionRow(Icons.Outlined.Language, stringResource(R.string.menu_open_lastfm), enabled = !dismissing) {
                            openUrl(context, buildLastFmUrl(target))
                            dismissMenu()
                        }
                    },
                ))
            } else if (target is TrackMenuTarget.Album) {
                MenuActionGrid(maxColumns = 1, actions = buildList<@Composable () -> Unit> {
                    add {
                        MenuActionRow(Icons.Outlined.Album, stringResource(R.string.menu_view_album), enabled = !dismissing) {
                            artistAlbumViewModel.openAlbum(target.name, target.artist)
                            dismissMenu()
                        }
                    }
                    if (target.artist.isNotBlank()) add {
                        MenuActionRow(Icons.Outlined.Person, stringResource(R.string.menu_view_artist_name, target.artist), enabled = !dismissing) {
                            artistAlbumViewModel.openArtist(target.artist)
                            dismissMenu()
                        }
                    }
                    add {
                        MenuActionRow(Icons.Outlined.Language, stringResource(R.string.menu_open_lastfm), enabled = !dismissing) {
                            openUrl(context, buildLastFmUrl(target))
                            dismissMenu()
                        }
                    }
                })
            }
        }
    }
}

@Composable
private fun StartMixCard(enabled: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = CircleShape,
        interactionSource = interactionSource,
        modifier = Modifier.fillMaxWidth().expressivePressScale(interactionSource, targetScale = 0.98f),
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Shuffle, null, Modifier.size(24.dp))
            Text(stringResource(R.string.menu_start_mix_short), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp))
        }
    }
}

@Composable
private fun QuickActionsRow(
    enabled: Boolean,
    onPlayNext: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onAddToQueue: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickActionCard(Icons.Outlined.QueuePlayNext, stringResource(R.string.menu_quick_play_next), Modifier.weight(1f), enabled, onPlayNext)
        QuickActionCard(Icons.Outlined.PlaylistAdd, stringResource(R.string.menu_quick_add_playlist), Modifier.weight(1f), enabled, onAddToPlaylist)
        QuickActionCard(Icons.AutoMirrored.Outlined.QueueMusic, stringResource(R.string.menu_add_queue), Modifier.weight(1f), enabled, onAddToQueue)
    }
}

@Composable
private fun QuickActionCard(icon: ImageVector, label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val iconCorner by animateDpAsState(
        targetValue = if (pressed) 16.dp else 24.dp,
        animationSpec = ExpressiveMotion.spatialFast(),
        label = "menuShortcutShape",
    )
    // Whole tile is the target (old menu's look); its corners morph on press instead of an icon chip's.
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(iconCorner),
        color = MaterialTheme.colorScheme.surfaceContainer,
        interactionSource = interactionSource,
        modifier = modifier.fillMaxHeight().expressivePressScale(interactionSource, targetScale = 0.98f),
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, null, Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MenuActionGrid(actions: List<@Composable () -> Unit>, maxColumns: Int = 2) {
    if (actions.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxColumns == 1 || actions.size == 1 || maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.3f) 1 else 2
        val rows = actions.chunked(columns)
        // One segment per row so the GroupGap reads as the divider, matching Settings' grouped rows.
        ExpressiveGroup(rows.size) { i, position ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = groupShape(position),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    rows[i].forEach { action -> Box(Modifier.weight(1f)) { action() } }
                    if (rows[i].size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MenuActionRow(
    icon: ImageVector,
    label: String,
    danger: Boolean = false,
    supportingText: String? = null,
    quiet: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val contentColor = when {
        danger -> MaterialTheme.colorScheme.error
        quiet -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        contentColor = contentColor,
        interactionSource = interactionSource,
        modifier = Modifier.fillMaxWidth().expressivePressScale(interactionSource, targetScale = 0.98f),
    ) {
        Row(
            Modifier.heightIn(min = if (quiet) 48.dp else 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GroupIconBadge(icon, MaterialTheme.colorScheme.surfaceContainerHighest, contentColor, size = 40.dp, iconSize = 20.dp)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                if (supportingText != null) {
                    Text(supportingText, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun MenuMetadataAction(
    icon: ImageVector,
    label: String,
    loading: Boolean = false,
    enabled: Boolean = true,
    menuEnabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled && menuEnabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (loading) {
            Spacer(Modifier.width(8.dp))
            ExpressiveInlineLoadingIndicator(size = 14.dp)
        }
    }
}
