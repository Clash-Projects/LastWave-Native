package com.lastwave.app.ui.common

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.download.TrackDownloadManager
import com.lastwave.app.data.genre.GenreResolver
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.model.RecentTracksEnvelope
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.network.LastFmApiService
import com.lastwave.app.data.plugin.ModulePlaybackResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@Serializable
private data class ITunesTrackResult(
    val artistName: String? = null,
    val collectionName: String? = null,
    @SerialName("trackName") val trackName: String? = null,
    val primaryGenreName: String? = null,
    val releaseDate: String? = null,
    val trackNumber: Int? = null,
    val trackCount: Int? = null,
    val discNumber: Int? = null,
    val discCount: Int? = null,
    val trackTimeMillis: Long? = null,
    val copyright: String? = null,
    val contentAdvisoryRating: String? = null,
    @SerialName("isStreamable") val isStreamable: Boolean? = null,
)

@Serializable
private data class ITunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<ITunesTrackResult> = emptyList(),
)

data class TrackSpecs(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val qualityBadge: String = "Resolving...",
    val audioCodec: String = "Detecting...",
    val bitDepthSampleRate: String = "Analyzing...",
    val provider: String = "Lossless / YouTube",
    val isLossless: Boolean = false,
    val durationText: String = "--:--",
    val downloadedEntity: DownloadedTrackEntity? = null,
    val isDownloading: Boolean = false,
    val userPlayCount: Long = 0L,
    val globalPlayCount: Long = 0L,
    val listenersCount: Long = 0L,
    val lastPlayedText: String? = null,
    val isLoved: Boolean = false,
    val isScrobbleStatsLoaded: Boolean = false,
    // Rich metadata
    val genre: String? = null,
    val composer: String? = null,
    val releaseDate: String? = null,
    val label: String? = null,
    val trackNumber: Int? = null,
    val trackCount: Int? = null,
    val discNumber: Int? = null,
    val discCount: Int? = null,
    val contentRating: String? = null,
    val wikiSummary: String? = null,
    val isMetadataLoaded: Boolean = false,
)

@HiltViewModel
class TrackDetailsViewModel @Inject constructor(
    private val moduleResolver: ModulePlaybackResolver,
    private val innerTube: InnerTubeMusicApi,
    private val downloadedTrackDao: DownloadedTrackDao,
    private val downloadManager: TrackDownloadManager,
    private val lastFmApi: LastFmApiService,
    private val sessionPreferences: SessionPreferences,
    private val genreResolver: GenreResolver,
    private val okHttpClient: OkHttpClient,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val _specs = MutableStateFlow<TrackSpecs?>(null)
    val specs: StateFlow<TrackSpecs?> = _specs.asStateFlow()

    fun load(title: String, artist: String, album: String? = null, artworkUrl: String? = null) {
        viewModelScope.launch {
            val downloaded = downloadedTrackDao.findByTitleAndArtist(title, artist)
            val isDownloading = downloadManager.isDownloading(title, artist)

            _specs.value = TrackSpecs(
                title = title,
                artist = artist,
                album = album,
                artworkUrl = artworkUrl,
                downloadedEntity = downloaded,
                isDownloading = isDownloading,
            )

            // Resolve real audio resolution specs + Last.fm Scrobble stats + rich metadata in background
            withContext(Dispatchers.IO) {
                // 0. Kick off genre + iTunes metadata in parallel
                val genreDeferred = async {
                    runCatching { genreResolver.resolve(title, artist) }.getOrDefault("")
                }
                val itunesDeferred = async {
                    runCatching { fetchITunesMetadata(title, artist) }.getOrNull()
                }

                // 1. Fetch Last.fm Scrobble Stats & History
                val session = runCatching { sessionPreferences.session.first() }.getOrNull()
                var wikiSummary: String? = null
                if (session != null && session.apiKey.isNotBlank()) {
                    var userPlays = 0L
                    var globalPlays = 0L
                    var listeners = 0L
                    var loved = false

                    runCatching {
                        val params = mutableMapOf(
                            "method" to "track.getInfo",
                            "track" to title,
                            "artist" to artist,
                            "autocorrect" to "1",
                            "api_key" to session.apiKey,
                            "format" to "json",
                        )
                        if (session.username.isNotBlank()) {
                            params["username"] = session.username
                        }
                        val response = lastFmApi.get(params)
                        if (response.isSuccessful) {
                            val body = response.body()?.string().orEmpty()
                            val trackObj = json.parseToJsonElement(body).jsonObject["track"]?.jsonObject
                            userPlays = trackObj?.get("userplaycount")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                            globalPlays = trackObj?.get("playcount")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                            listeners = trackObj?.get("listeners")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                            loved = trackObj?.get("userloved")?.jsonPrimitive?.contentOrNull == "1"
                            // Extract wiki summary
                            val wikiObj = trackObj?.get("wiki")?.jsonObject
                            wikiSummary = wikiObj?.get("summary")?.jsonPrimitive?.contentOrNull
                                ?.replace(Regex("<[^>]*>"), "")  // strip HTML tags
                                ?.replace(Regex("Read more on Last\\.fm.*"), "")  // strip footer link
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                        }
                    }

                    // 2. Fetch Last Played timestamp specifically for this track
                    val lastPlayed = runCatching {
                        val trackScrobblesResp = lastFmApi.get(
                            mapOf(
                                "method" to "user.gettrackscrobbles",
                                "user" to session.username,
                                "artist" to artist,
                                "track" to title,
                                "limit" to "1",
                                "api_key" to session.apiKey,
                                "format" to "json",
                            ),
                        )
                        if (trackScrobblesResp.isSuccessful) {
                            val body = trackScrobblesResp.body()?.string().orEmpty()
                            val root = json.parseToJsonElement(body).jsonObject["trackscrobbles"]?.jsonObject
                            val trackElem = root?.get("track")
                            val trackObj = when (trackElem) {
                                is JsonArray -> trackElem.firstOrNull()?.jsonObject
                                is JsonObject -> trackElem
                                else -> null
                            }
                            val isNowPlaying = trackObj?.get("@attr")?.jsonObject?.get("nowplaying")?.jsonPrimitive?.contentOrNull == "true"
                            if (isNowPlaying) {
                                "Playing now"
                            } else {
                                val uts = trackObj?.get("date")?.jsonObject?.get("uts")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                                uts?.let { formatRelativeTime(it * 1000L) }
                            }
                        } else null
                    }.getOrNull()

                    // Fallback to recent tracks if gettrackscrobbles had no match
                    val recentFallback = if (lastPlayed == null) {
                        runCatching {
                            val recentResp = lastFmApi.get(
                                mapOf(
                                    "method" to "user.getrecenttracks",
                                    "user" to session.username,
                                    "limit" to "50",
                                    "api_key" to session.apiKey,
                                    "format" to "json",
                                ),
                            )
                            if (recentResp.isSuccessful) {
                                val body = recentResp.body()?.string().orEmpty()
                                val parsed = json.decodeFromString<RecentTracksEnvelope>(body)
                                val match = parsed.recenttracks?.track?.tracks?.firstOrNull {
                                    it.name.equals(title, ignoreCase = true) && it.artist.displayName.equals(artist, ignoreCase = true)
                                }
                                if (match?.isNowPlaying == true) {
                                    "Playing now"
                                } else {
                                    match?.date?.uts?.toLongOrNull()?.let { uts ->
                                        formatRelativeTime(uts * 1000L)
                                    }
                                }
                            } else null
                        }.getOrNull()
                    } else null

                    val lastPlayedDisplay = when {
                        lastPlayed != null -> lastPlayed
                        recentFallback != null -> recentFallback
                        userPlays > 0 -> "In library"
                        else -> "Never"
                    }

                    _specs.value = _specs.value?.copy(
                        userPlayCount = userPlays,
                        globalPlayCount = globalPlays,
                        listenersCount = listeners,
                        isLoved = loved,
                        lastPlayedText = lastPlayedDisplay,
                        isScrobbleStatsLoaded = true,
                        wikiSummary = wikiSummary,
                    )
                }

                // 3. Audio stream resolution via provider module (.lwp engine)
                val descriptor = runCatching {
                    moduleResolver.resolve(title, artist, 27)
                }.getOrNull()

                if (descriptor != null && descriptor.stream.baseUrl.isNotBlank()) {
                    val s = descriptor.stream
                    val isAtmos = s.codec.equals("atmos", ignoreCase = true)
                    val isLossless = isAtmos || (!s.codec.equals("opus", ignoreCase = true) &&
                        !s.codec.equals("mp3", ignoreCase = true) &&
                        !s.codec.equals("aac", ignoreCase = true) &&
                        !s.codec.contains("mp4a", ignoreCase = true))
                    val badge = if (isAtmos) {
                        "DOLBY ATMOS"
                    } else if (isLossless) {
                        if (s.bitDepth > 16 || s.sampleRate > 48000) "24-BIT HI-RES" else "CD LOSSLESS"
                    } else if (s.codec.equals("mp3", ignoreCase = true)) {
                        "320k MP3"
                    } else if (s.codec.equals("aac", ignoreCase = true) || s.codec.contains("mp4a", ignoreCase = true)) {
                        if (s.bandwidth in 1..128000) "HE-AAC" else "AAC 320"
                    } else s.codec.uppercase()
                    val codec = if (isAtmos) {
                        "Dolby Atmos (E-AC-3 JOC Spatial)"
                    } else if (s.codec.equals("mp3", ignoreCase = true)) {
                        "MPEG Layer 3 (MP3)"
                    } else if (s.codec.equals("aac", ignoreCase = true) || s.codec.contains("mp4a", ignoreCase = true)) {
                        "Advanced Audio Coding (AAC)"
                    } else {
                        "Free Lossless Audio Codec (FLAC)"
                    }
                    val depthRate = if (isAtmos) {
                        "24-bit / ${if (s.sampleRate > 0) s.sampleRate / 1000.0 else 48.0} kHz (6 Channels Spatial)"
                    } else {
                        "${if (s.bitDepth > 0) s.bitDepth else 16}-bit / ${if (s.sampleRate > 0) s.sampleRate / 1000.0 else 44.1} kHz (${if (s.bandwidth > 0) s.bandwidth / 1000 else 1411} kbps)"
                    }
                    val durText = downloaded?.durationMs?.takeIf { it > 0L }?.let { ms ->
                        val dur = (ms / 1000).toInt()
                        "%d:%02d".format(dur / 60, dur % 60)
                    } ?: "\u2014"

                    _specs.value = _specs.value?.copy(
                        qualityBadge = badge,
                        audioCodec = codec,
                        bitDepthSampleRate = depthRate,
                        provider = "Lossless Provider Module",
                        isLossless = isLossless,
                        durationText = durText,
                    )
                } else {
                    // Fallback YouTube stream specs
                    val bestMatch = runCatching { innerTube.findBestMatch(title, artist) }.getOrNull()
                    val videoId = bestMatch?.videoId
                    val ytStream = videoId?.let { runCatching { innerTube.resolveAudioStream(it) }.getOrNull() }

                    val rawMime = ytStream?.mimeType.orEmpty()
                    val codec = if (rawMime.contains("mp4") || rawMime.contains("m4a")) "Advanced Audio Coding (AAC)" else "Opus Interactive Audio"
                    val badge = if (rawMime.contains("mp4") || rawMime.contains("m4a")) "M4A 256k" else "OPUS 160k"
                    val bitrate = ytStream?.bitrate?.takeIf { it > 0 }?.let { "${(it + 500) / 1000} kbps" } ?: "160 kbps"

                    _specs.value = _specs.value?.copy(
                        qualityBadge = badge,
                        audioCodec = codec,
                        bitDepthSampleRate = "16-bit / 48.0 kHz ($bitrate)",
                        provider = "YouTube Music CDN",
                        isLossless = false,
                    )
                }

                // 4. Merge genre + iTunes metadata
                val genre = genreDeferred.await()
                val itunes = itunesDeferred.await()
                val releaseFormatted = itunes?.releaseDate?.let { formatReleaseDate(it) }
                val label = itunes?.copyright
                    ?.replace(Regex("^[©℗]\\s*\\d{4}\\s*"), "")  // strip "© 2023 "
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                val contentRating = itunes?.contentAdvisoryRating?.let { advisory ->
                    when {
                        advisory.equals("Explicit", ignoreCase = true) -> "Explicit"
                        advisory.equals("Clean", ignoreCase = true) -> "Clean"
                        advisory.equals("notExplicit", ignoreCase = true) -> null
                        advisory.isBlank() -> null
                        else -> advisory
                    }
                }

                _specs.value = _specs.value?.copy(
                    genre = genre.takeIf { it.isNotBlank() },
                    composer = itunes?.artistName?.takeIf {
                        // Only show composer if iTunes returns a different artist (featuring, etc.)
                        // Otherwise it's redundant. The copyright field is more useful.
                        false
                    },
                    releaseDate = releaseFormatted,
                    label = label,
                    trackNumber = itunes?.trackNumber,
                    trackCount = itunes?.trackCount,
                    discNumber = itunes?.discNumber,
                    discCount = itunes?.discCount,
                    contentRating = contentRating,
                    isMetadataLoaded = true,
                )
            }
        }
    }

    private fun formatRelativeTime(millis: Long): String {
        val diff = System.currentTimeMillis() - millis
        if (diff < 0) return "Just now"
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            minutes < 2 -> "Just now"
            minutes < 60 -> "$minutes min ago"
            hours == 1L -> "1 hour ago"
            hours < 24 -> "$hours hrs ago"
            days == 1L -> "Yesterday"
            days < 7 -> "$days days ago"
            days < 30 -> "${days / 7}w ago"
            days < 365 -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
            else -> SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(millis))
        }
    }

    private fun fetchITunesMetadata(title: String, artist: String): ITunesTrackResult? {
        val term = "$title $artist"
        val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(term, "UTF-8")}&media=music&entity=song&limit=3"
        val request = Request.Builder().url(url).build()
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) return null
        val body = response.body?.string().orEmpty()
        val parsed = json.decodeFromString<ITunesSearchResponse>(body)
        // Best match: prefer exact title+artist match
        return parsed.results.firstOrNull { result ->
            result.trackName.equals(title, ignoreCase = true) &&
                result.artistName?.contains(artist, ignoreCase = true) == true
        } ?: parsed.results.firstOrNull()
    }

    private fun formatReleaseDate(isoDate: String): String? {
        if (isoDate.isBlank()) return null
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            val date = inputFormat.parse(isoDate) ?: return isoDate.take(4)
            SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(date)
        } catch (_: Exception) {
            isoDate.take(4).takeIf { it.length == 4 }
        }
    }

    fun downloadNow(title: String, artist: String, album: String?, artworkUrl: String?) {
        downloadManager.downloadTrack(title, artist, album, artworkUrl)
        _specs.value = _specs.value?.copy(isDownloading = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailsSheet(
    title: String,
    artist: String,
    album: String? = null,
    artworkUrl: String? = null,
    onDismiss: () -> Unit,
    onPlayTrack: (() -> Unit)? = null,
    viewModel: TrackDetailsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val specs by viewModel.specs.collectAsStateWithLifecycle()

    LaunchedEffect(title, artist) {
        viewModel.load(title, artist, album, artworkUrl)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
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
        EdgeToEdgeDialogWindow()
        val currentSpecs = specs ?: TrackSpecs(title = title, artist = artist, album = album, artworkUrl = artworkUrl)
        val numberFormatter = NumberFormat.getNumberInstance(Locale.getDefault())

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .adaptiveContentWidth(maxWidth = 640.dp)
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp + safeDrawingBottomPadding())
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Hero Track Header (Artwork + Title + Artist + Album + Format Badge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .shadow(10.dp, RoundedCornerShape(18.dp), spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                ) {
                    ArtworkImage(
                        name = title,
                        artist = artist,
                        embeddedUrl = artworkUrl,
                        fallbackIcon = Icons.Filled.MusicNote,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!album.isNullOrBlank()) {
                        Text(
                            text = album,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = currentSpecs.qualityBadge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            // 2. Action Buttons (Play & Download)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (onPlayTrack != null) {
                    Button(
                        onClick = {
                            onPlayTrack()
                            onDismiss()
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play Track", fontWeight = FontWeight.Bold)
                    }
                }

                FilledTonalButton(
                    onClick = {
                        viewModel.downloadNow(title, artist, album, artworkUrl)
                    },
                    enabled = currentSpecs.downloadedEntity == null && !currentSpecs.isDownloading,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f).height(46.dp),
                ) {
                    Icon(
                        if (currentSpecs.downloadedEntity != null) Icons.Filled.CheckCircle else Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            currentSpecs.downloadedEntity != null -> "Downloaded"
                            currentSpecs.isDownloading -> "Downloading..."
                            else -> "Download"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // 3. Listening Stats 2x2 Grid
            Text(
                text = "Listening Statistics",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatCard(
                    title = "Your Scrobbles",
                    value = if (currentSpecs.userPlayCount > 0) "${numberFormatter.format(currentSpecs.userPlayCount)} plays" else if (currentSpecs.isScrobbleStatsLoaded) "0 plays" else "...",
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    title = "Last Played",
                    value = currentSpecs.lastPlayedText ?: "Checking...",
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatCard(
                    title = "Global Plays",
                    value = if (currentSpecs.globalPlayCount > 0) numberFormatter.format(currentSpecs.globalPlayCount) else "\u2014",
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    title = "Listeners",
                    value = if (currentSpecs.listenersCount > 0) numberFormatter.format(currentSpecs.listenersCount) else "\u2014",
                    modifier = Modifier.weight(1f),
                )
            }

            // 4. Track Information Card (Genre, Release, Label, etc.)
            if (currentSpecs.isMetadataLoaded && listOfNotNull(
                    currentSpecs.genre,
                    currentSpecs.releaseDate,
                    currentSpecs.label,
                    currentSpecs.contentRating,
                    currentSpecs.trackNumber?.toString(),
                ).isNotEmpty()
            ) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Track Information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }

                        currentSpecs.genre?.let { DetailRow(label = "Genre", value = it) }
                        currentSpecs.composer?.let { DetailRow(label = "Composer", value = it) }
                        currentSpecs.releaseDate?.let { DetailRow(label = "Released", value = it) }
                        currentSpecs.label?.let { DetailRow(label = "Label", value = it) }
                        if (currentSpecs.trackNumber != null) {
                            val trackText = if (currentSpecs.trackCount != null && currentSpecs.trackCount > 0) {
                                "${currentSpecs.trackNumber} of ${currentSpecs.trackCount}"
                            } else {
                                "${currentSpecs.trackNumber}"
                            }
                            DetailRow(label = "Track", value = trackText)
                        }
                        if (currentSpecs.discNumber != null && (currentSpecs.discCount ?: 1) > 1) {
                            val discText = if (currentSpecs.discCount != null) {
                                "${currentSpecs.discNumber} of ${currentSpecs.discCount}"
                            } else {
                                "${currentSpecs.discNumber}"
                            }
                            DetailRow(label = "Disc", value = discText)
                        }
                        currentSpecs.contentRating?.let {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Rating",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (it == "Explicit") MaterialTheme.colorScheme.errorContainer
                                    else MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (it == "Explicit") MaterialTheme.colorScheme.onErrorContainer
                                        else MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. About / Wiki Summary
            if (!currentSpecs.wikiSummary.isNullOrBlank()) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    var expanded by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .animateContentSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Album, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = currentSpecs.wikiSummary,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (currentSpecs.wikiSummary.length > 200) {
                            Text(
                                text = if (expanded) "Show less" else "Read more",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { expanded = !expanded },
                            )
                        }
                    }
                }
            }

            // 6. Audio & Stream Specifications Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.HighQuality, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Stream & Audio Specs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    DetailRow(label = "Codec", value = currentSpecs.audioCodec)
                    DetailRow(label = "Resolution", value = currentSpecs.bitDepthSampleRate)
                    DetailRow(label = "Provider", value = currentSpecs.provider)
                    if (currentSpecs.durationText != "--:--") {
                        DetailRow(label = "Duration", value = currentSpecs.durationText)
                    }
                }
            }

            // 7. Offline Storage Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Offline Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    DetailRow(
                        label = "Status",
                        value = if (currentSpecs.downloadedEntity != null) "Downloaded" else "Not downloaded",
                    )
                    if (currentSpecs.downloadedEntity != null) {
                        DetailRow(
                            label = "File Size",
                            value = "%.1f MB".format(currentSpecs.downloadedEntity.fileSizeBytes / (1024.0 * 1024.0)),
                        )
                        DetailRow(
                            label = "File Path",
                            value = currentSpecs.downloadedEntity.filePath,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

