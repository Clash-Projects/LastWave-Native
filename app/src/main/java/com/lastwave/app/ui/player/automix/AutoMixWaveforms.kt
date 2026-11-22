package com.lastwave.app.ui.player.automix

import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.data.plugin.SegmentedDashBridge
import com.lastwave.app.data.plugin.SegmentedStreamDescriptor
import com.lastwave.app.playback.automix.analysis.TrackAnalyzer
import com.lastwave.app.playback.automix.api.BlendPhase
import com.lastwave.app.playback.automix.api.MixDeckView
import com.lastwave.app.playback.automix.api.MixView
import com.lastwave.app.playback.automix.engine.MixCurve
import com.lastwave.app.playback.currentStandby
import com.lastwave.app.playback.mediaIdKey
import com.lastwave.app.playback.toPlayableTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import java.util.IdentityHashMap

internal enum class WaveformPreviewStatus { READY, LOADING }

internal data class AutoMixWaveformPreview(
    val view: MixView,
    val outgoingStatus: WaveformPreviewStatus,
    val incomingStatus: WaveformPreviewStatus,
)

@Composable
internal fun rememberAutoMixWaveforms(
    view: MixView,
    player: MusicPlayer,
    tracks: List<PlayableTrack>,
): AutoMixWaveformPreview {
    val latestTracks by rememberUpdatedState(tracks)
    val planKey = listOf(
        view.outgoing.title, view.outgoing.artist, view.outgoing.durationMs,
        view.incoming.title, view.incoming.artist, view.incoming.durationMs,
        view.cueOutMs, view.cueInMs, view.overlapMs, view.tempoRatio,
    )
    var outgoing by remember(player, planKey) { mutableStateOf(WaveformCache.preview(view, true)) }
    var incoming by remember(player, planKey) { mutableStateOf(WaveformCache.preview(view, false)) }
    val sources = remember(player, planKey) { WaveformPair() }
    // The studio view draws both whole songs, but the engine only analyses the cue windows.
    LaunchedEffect(player, planKey) {
        if (outgoing == null) launch {
            while (isActive && outgoing == null) {
                outgoing = loadWaveform(
                    player, view, sources, outgoing = true, tracks = { latestTracks },
                    fromMs = 0L, untilMs = view.outgoing.wholeSongEndMs(view.cueOutMs + view.overlapMs),
                )
                if (outgoing == null) delay(2_500L)
            }
        }
        if (incoming == null) launch {
            while (isActive && incoming == null) {
                incoming = loadWaveform(
                    player, view, sources, outgoing = false, tracks = { latestTracks },
                    fromMs = 0L, untilMs = view.incoming.wholeSongEndMs(view.cueInMs + (view.overlapMs * view.tempoRatio).toLong()),
                )
                if (incoming == null) delay(2_500L)
            }
        }
    }
    SideEffect { WaveformCache.alias(view, outgoing, incoming) }
    return AutoMixWaveformPreview(
        view = view.copy(
            outgoing = outgoing?.let(view.outgoing::withWaveform) ?: view.outgoing,
            incoming = incoming?.let(view.incoming::withWaveform) ?: view.incoming,
        ),
        // The engine's cue-window columns keep the lane drawn while the whole song decodes.
        outgoingStatus = if (outgoing != null || view.outgoing.bands.isNotEmpty()) WaveformPreviewStatus.READY else WaveformPreviewStatus.LOADING,
        incomingStatus = if (incoming != null || view.incoming.bands.isNotEmpty()) WaveformPreviewStatus.READY else WaveformPreviewStatus.LOADING,
    )
}

/** Unknown durations fall back to the end of the mix so the decoder still has a bounded range. */
private fun MixDeckView.wholeSongEndMs(mixEndMs: Long) = if (durationMs > 0L) durationMs else mixEndMs + PREVIEW_PAD_MS

private const val PREVIEW_PAD_MS = 3_000L
private val decoderPermit = Semaphore(1)
private data class WaveformWindow(val startMs: Long, val bands: List<Int>, val bpm: Float?, val camelotKey: String?)
private data class WaveformSource(
    val key: String,
    val spec: DataSpec,
    val expiresAtMs: Long?,
    val item: MediaItem,
    val descriptor: SegmentedStreamDescriptor?,
    val factory: DataSource.Factory,
)
private class DeckBinding {
    var item: MediaItem? = null
    var track: PlayableTrack? = null
    var source: WaveformSource? = null
}
private class WaveformPair {
    val outgoing = DeckBinding()
    val incoming = DeckBinding()
}

private fun MixDeckView.withWaveform(window: WaveformWindow) =
    copy(bandsStartMs = window.startMs, bands = window.bands, bpm = bpm ?: window.bpm, camelotKey = camelotKey ?: window.camelotKey)

private object WaveformCache {
    private val windows = LinkedHashMap<String, WaveformWindow>(16, 0.75f, true)
    private val previews = IdentityHashMap<MixView, Array<WaveformWindow?>>()
    private val previewOrder = ArrayDeque<MixView>()
    @Synchronized fun get(key: String) = windows[key]
    @Synchronized fun preview(view: MixView, outgoing: Boolean) = previews[view]?.get(if (outgoing) 0 else 1)
    @Synchronized fun alias(view: MixView, outgoing: WaveformWindow?, incoming: WaveformWindow?) {
        if (outgoing == null && incoming == null) return
        val pair = previews[view] ?: arrayOfNulls<WaveformWindow>(2).also {
            previews[view] = it
            previewOrder.addLast(view)
        }
        outgoing?.let { pair[0] = it }
        incoming?.let { pair[1] = it }
        while (previewOrder.size > 16) previews.remove(previewOrder.removeFirst())
    }
    @Synchronized fun put(key: String, window: WaveformWindow) {
        windows[key] = window
        while (windows.size > 16) windows.remove(windows.keys.first())
    }
}

private suspend fun loadWaveform(
    player: MusicPlayer,
    view: MixView,
    pair: WaveformPair,
    outgoing: Boolean,
    tracks: () -> List<PlayableTrack>,
    fromMs: Long,
    untilMs: Long,
): WaveformWindow? {
    WaveformCache.preview(view, outgoing)?.let { return it }
    val deck = if (outgoing) view.outgoing else view.incoming
    val title = deck.title.replace('\n', ' ').replace('\r', ' ').take(100)
    val from = fromMs.coerceAtLeast(0L)
    val until = if (deck.durationMs > 0L) untilMs.coerceAtMost(deck.durationMs) else untilMs
    val length = until - from
    if (length <= 0L) return null
    var source: WaveformSource? = null
    var sourceError: String? = null
    val waitStarted = SystemClock.uptimeMillis()
    while (source == null && SystemClock.uptimeMillis() - waitStarted < 20_000L) {
        source = withContext(Dispatchers.Main.immediate) {
            try {
                bindPair(player, view, tracks(), pair)
                previewSource(player, pair, outgoing)
            } catch (error: RuntimeException) {
                currentCoroutineContext().ensureActive()
                sourceError = error.javaClass.simpleName
                null
            }
        }
        if (source == null) delay(400L)
    }
    val input = source ?: run {
        Log.w("AutoMixWaveform", "stage=source title='$title' reason=not_ready error=${sourceError ?: "none"} from=$from until=$until")
        return null
    }
    val cacheKey = "${input.key}\u0000$from\u0000$length"
    WaveformCache.get(cacheKey)?.let { return it }
    return decoderPermit.withPermit {
        WaveformCache.preview(view, outgoing)?.let { return@withPermit it }
        WaveformCache.get(cacheKey)?.let { return@withPermit it }
        var stage = "media3"
        try {
            val scopedFactory = ResolvingDataSource.Factory(input.factory) { spec ->
                spec.buildUpon().setHttpRequestHeaders(spec.httpRequestHeaders + input.spec.httpRequestHeaders).build()
            }
            val pcm = withTimeoutOrNull(120_000L) {
                decodeWaveformPcm(
                    player.appContext, scopedFactory, player.extractorsFactory, input.item,
                    from, until, player.moduleDrmFactory, input.descriptor,
                )
            } ?: run {
                Log.w("AutoMixWaveform", "stage=media3 title='$title' reason=timeout from=$from until=$until")
                return@withPermit null
            }
            stage = "spectrum"
            val window = withContext(Dispatchers.IO) {
                val originalPriority = Process.getThreadPriority(Process.myTid())
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    val analysis = TrackAnalyzer.analyze(pcm.pcm, pcm.sampleRate, pcm.startMs)
                    currentCoroutineContext().ensureActive()
                    // Relative normalization has no reference when every captured sample is exactly silent.
                    val bands = if (pcm.pcm.any { it != 0f }) analysis.bands else analysis.bands.map { 0 }
                    bands.takeIf { it.isNotEmpty() }?.let {
                        WaveformWindow(
                            analysis.startMs, it,
                            analysis.grid?.takeIf { grid -> grid.confidence >= 0.6f }?.bpm,
                            analysis.camelotKey?.takeIf { analysis.keyConfidence >= 0.5f },
                        )
                    }
                } finally {
                    Process.setThreadPriority(originalPriority)
                }
            }
            if (window != null) {
                WaveformCache.put(cacheKey, window)
                WaveformCache.alias(view, if (outgoing) window else null, if (outgoing) null else window)
                Log.i("AutoMixWaveform", "stage=ready title='$title' columns=${window.bands.size} from=${window.startMs}")
            } else Log.w("AutoMixWaveform", "stage=spectrum title='$title' reason=no_columns")
            window
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.Main.immediate) {
                (if (outgoing) pair.outgoing else pair.incoming).source = null
            }
            val code = (error as? PlaybackException)?.errorCodeName ?: error.javaClass.simpleName
            val causes = generateSequence<Throwable>(error) { it.cause }.take(5).map { cause ->
                if (cause is HttpDataSource.InvalidResponseCodeException) "HTTP_${cause.responseCode}" else cause.javaClass.simpleName
            }.joinToString(",")
            Log.w("AutoMixWaveform", "stage=$stage title='$title' code=$code causes=$causes from=$from until=$until")
            null
        }
    }
}

private fun bindPair(player: MusicPlayer, view: MixView, tracks: List<PlayableTrack>, pair: WaveformPair) {
    val live = player.state.value
    val active = player.activePlayer
    val oldDeck = player.outgoingPlayer
    val phase = live.deckBlendTelemetry.phase
    val handedOver = oldDeck != null || phase == BlendPhase.MIX_COMPLETED ||
        (phase == BlendPhase.BLENDING && live.deckBlendTelemetry.clampedProgress >= MixCurve.handOverPoint(view.shape))
    val beforeHandover = phase in listOf(BlendPhase.ARMED, BlendPhase.PRE_ANALYZING, BlendPhase.BLENDING) && !handedOver
    val nextIndex = active?.nextMediaItemIndex ?: -1
    val next = if (active != null && nextIndex in 0 until active.mediaItemCount) active.getMediaItemAt(nextIndex) else null
    val candidates = listOfNotNull(live.current) + tracks + live.queue
    val outItem = oldDeck?.currentMediaItem ?: active?.currentMediaItem?.takeIf { beforeHandover }
    val inItem = if (handedOver || !beforeHandover) active?.currentMediaItem else next
        ?: active?.let { player.currentStandby()?.currentMediaItem }
        ?: player.standbyQueue.getOrNull(player.standbyIndex)
    bindDeck(pair.outgoing, outItem, view.outgoing, candidates)
    bindDeck(pair.incoming, inItem, view.incoming, candidates)
    // Once a role has an identity, a handover cannot turn two matching titles into the same deck.
    for (binding in listOf(pair.outgoing, pair.incoming)) {
        val item = binding.item ?: continue
        if (binding.source != null) continue
        val updated = listOfNotNull(active?.currentMediaItem, oldDeck?.currentMediaItem) + player.standbyQueue
        updated.firstOrNull { it.mediaId == item.mediaId && it.localConfiguration?.uri?.scheme != "lastwave" }
            ?.let { binding.item = it }
    }
}

private fun bindDeck(binding: DeckBinding, item: MediaItem?, deck: MixDeckView, tracks: List<PlayableTrack>) {
    if (binding.source != null) return
    val exact = item?.takeIf { it.mediaMetadata.title?.toString() == deck.title && it.mediaMetadata.artist?.toString() == deck.artist }
    if (exact != null && (binding.item == null || binding.item?.mediaId == exact.mediaId ||
            binding.item?.localConfiguration?.uri?.scheme == "lastwave")
    ) {
        binding.item = exact
        binding.track = tracks.firstOrNull { it.mediaIdKey() == exact.mediaId } ?: exact.toPlayableTrack()
    } else if (binding.track == null) {
        binding.track = tracks.filter { it.title == deck.title && it.artist == deck.artist }
            .distinctBy { it.mediaIdKey() }.singleOrNull()
    }
}

private fun previewSource(player: MusicPlayer, pair: WaveformPair, outgoing: Boolean): WaveformSource? {
    val binding = if (outgoing) pair.outgoing else pair.incoming
    val other = if (outgoing) pair.incoming else pair.outgoing
    val now = System.currentTimeMillis()
    binding.source?.takeIf { it.expiresAtMs == null || it.expiresAtMs > now }?.let { return it }
    val item = binding.item
    val exactUri = item?.localConfiguration?.uri
    val allStreams = player.preparedStreams.values.toList()
    val storedExact = item?.localConfiguration?.customCacheKey?.let(player.preparedStreams::get)
        ?: allStreams.firstOrNull { it.url == exactUri?.toString() }
    fun MusicPlayer.ResolvedStream.eligible() =
        (expiresAtEpochMs ?: youtubeCandidate?.expiresAtEpochMs)?.let { it > now } != false
    val streams = allStreams.filter { it.eligible() }
    val concreteUri = exactUri?.takeUnless { it.scheme == "lastwave" }
    val stream = storedExact?.takeIf { concreteUri != null || it.eligible() } ?: binding.track?.takeIf { concreteUri == null }?.let { matched ->
        streams.firstOrNull { it.youtubeCandidate?.videoId == matched.videoId && matched.videoId != null }
            ?: streams.firstOrNull { it.cacheKey.startsWith("lossless:${matched.mediaIdKey()}:") }
    }
    val uri = concreteUri ?: stream?.url?.let(Uri::parse)
        ?: binding.track?.playbackUrl?.let { if (it.startsWith("/")) Uri.fromFile(java.io.File(it)) else Uri.parse(it) }
        ?: return null
    if (uri.scheme !in listOf("http", "https", "file", "content", "data")) return null
    val sameTrack = binding.track?.let {
        (!it.videoId.isNullOrBlank() || !it.playbackUrl.isNullOrBlank()) && it.mediaIdKey() == other.track?.mediaIdKey()
    } == true
    if (!sameTrack && (uri == other.source?.spec?.uri || uri == other.item?.localConfiguration?.uri)) return null
    val userAgent = stream?.userAgent?.takeIf { it.isNotBlank() }
        ?: stream?.requestHeaders?.entries?.firstOrNull { it.key.equals("User-Agent", true) }?.value
        ?: stream?.youtubeCandidate?.userAgent
    if (uri.host?.endsWith("googlevideo.com") == true && userAgent.isNullOrBlank()) return null
    val headers = MusicPlayer.NEUTRAL_MEDIA_HEADERS.toMutableMap()
    stream?.requestHeaders?.forEach { (name, value) -> if (!name.equals("User-Agent", true)) headers[name] = value }
    userAgent?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
    val key = stream?.cacheKey ?: item?.localConfiguration?.customCacheKey ?: uri.toString()
    val descriptor = stream?.segmentedDrm
    val mime = if (descriptor != null) MimeTypes.APPLICATION_MPD else
        stream?.mimeType?.takeIf { it.isNotBlank() } ?: item?.localConfiguration?.mimeType ?: binding.track?.playbackMimeType
    val previewItem = (item?.buildUpon() ?: MediaItem.Builder())
        .setUri(uri)
        .setMediaId(item?.mediaId ?: binding.track?.mediaIdKey() ?: key)
        .setCustomCacheKey(key)
        .setMimeType(mime)
        .setClippingConfiguration(MediaItem.ClippingConfiguration.UNSET)
        .setDrmConfiguration(
            if (descriptor != null) SegmentedDashBridge.drmConfigurationFor(descriptor)
            else item?.localConfiguration?.drmConfiguration,
        )
        .build()
    return WaveformSource(
        key, DataSpec.Builder().setUri(uri).setKey(key).setHttpRequestHeaders(headers).build(),
        stream?.expiresAtEpochMs ?: stream?.youtubeCandidate?.expiresAtEpochMs,
        previewItem, descriptor, player.cacheDataSourceFactory,
    ).also { binding.source = it }
}
