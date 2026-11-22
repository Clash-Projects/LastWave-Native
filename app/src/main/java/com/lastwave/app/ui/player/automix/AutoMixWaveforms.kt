package com.lastwave.app.ui.player.automix

import android.net.Uri
import android.os.Process
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.lastwave.app.playback.ChunkedRangeDataSource
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.automix.analysis.AnalysisInput
import com.lastwave.app.playback.automix.analysis.PcmWindowDecoder
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

internal enum class WaveformPreviewStatus { READY, LOADING, UNAVAILABLE }

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
    var outgoing by remember(player, planKey) { mutableStateOf<WaveformWindow?>(null) }
    var incoming by remember(player, planKey) { mutableStateOf<WaveformWindow?>(null) }
    var outgoingStatus by remember(player, planKey) { mutableStateOf(WaveformPreviewStatus.LOADING) }
    var incomingStatus by remember(player, planKey) { mutableStateOf(WaveformPreviewStatus.LOADING) }
    val sources = remember(player, planKey) { WaveformPair() }
    val missingOutgoing = view.outgoing.bands.isEmpty()
    val missingIncoming = view.incoming.bands.isEmpty()
    val factory = remember(player) {
        // A preview owns its reads; it must never resolve, recache, or reconfigure a playback deck.
        val client = player.http.newBuilder()
            .cache(null)
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
            }
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
        val upstream = DefaultDataSource.Factory(
            player.appContext,
            OkHttpDataSource.Factory(client).setUserAgent(null)
                .setDefaultRequestProperties(MusicPlayer.NEUTRAL_MEDIA_HEADERS),
        )
        DataSource.Factory { ChunkedRangeDataSource(upstream.createDataSource()) }
    }
    LaunchedEffect(player, planKey, missingOutgoing, missingIncoming) {
        if (missingOutgoing && outgoing == null) launch {
            outgoingStatus = WaveformPreviewStatus.LOADING
            outgoing = loadWaveform(
                player, factory, view, sources, outgoing = true, tracks = { latestTracks },
                fromMs = view.cueOutMs - PREVIEW_PAD_MS,
                untilMs = view.cueOutMs + view.overlapMs + PREVIEW_PAD_MS,
            )
            outgoingStatus = if (outgoing == null) WaveformPreviewStatus.UNAVAILABLE else WaveformPreviewStatus.READY
        }
        if (missingIncoming && incoming == null) launch {
            incomingStatus = WaveformPreviewStatus.LOADING
            incoming = loadWaveform(
                player, factory, view, sources, outgoing = false, tracks = { latestTracks },
                fromMs = view.cueInMs - (PREVIEW_PAD_MS * view.tempoRatio).roundToLong(),
                untilMs = view.cueInMs + ((view.overlapMs + PREVIEW_PAD_MS) * view.tempoRatio).roundToLong(),
            )
            incomingStatus = if (incoming == null) WaveformPreviewStatus.UNAVAILABLE else WaveformPreviewStatus.READY
        }
    }
    return AutoMixWaveformPreview(
        view = view.copy(
            outgoing = if (missingOutgoing) outgoing?.let(view.outgoing::withWaveform) ?: view.outgoing else view.outgoing,
            incoming = if (missingIncoming) incoming?.let(view.incoming::withWaveform) ?: view.incoming else view.incoming,
        ),
        outgoingStatus = if (missingOutgoing) outgoingStatus else WaveformPreviewStatus.READY,
        incomingStatus = if (missingIncoming) incomingStatus else WaveformPreviewStatus.READY,
    )
}

private const val PREVIEW_PAD_MS = 3_000L
private val decoderPermit = Semaphore(1)
private data class WaveformWindow(val startMs: Long, val bands: List<Int>)
private data class WaveformSource(val key: String, val spec: DataSpec, val expiresAtMs: Long?)
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
    copy(bandsStartMs = window.startMs, bands = window.bands)

private object WaveformCache {
    private val windows = LinkedHashMap<String, WaveformWindow>(16, 0.75f, true)
    @Synchronized fun get(key: String) = windows[key]
    @Synchronized fun put(key: String, window: WaveformWindow) {
        windows[key] = window
        while (windows.size > 16) windows.remove(windows.keys.first())
    }
}

private suspend fun loadWaveform(
    player: MusicPlayer,
    factory: DataSource.Factory,
    view: MixView,
    pair: WaveformPair,
    outgoing: Boolean,
    tracks: () -> List<PlayableTrack>,
    fromMs: Long,
    untilMs: Long,
): WaveformWindow? {
    val deck = if (outgoing) view.outgoing else view.incoming
    val from = fromMs.coerceAtLeast(0L)
    val until = if (deck.durationMs > 0L) untilMs.coerceAtMost(deck.durationMs) else untilMs
    val length = until - from
    if (length <= 0L) return null
    var source: WaveformSource? = null
    val waitStarted = SystemClock.uptimeMillis()
    while (source == null && SystemClock.uptimeMillis() - waitStarted < 20_000L) {
        source = withContext(Dispatchers.Main.immediate) {
            try {
                bindPair(player, view, tracks(), pair)
                previewSource(player, pair, outgoing)
            } catch (_: RuntimeException) {
                currentCoroutineContext().ensureActive()
                null
            }
        }
        if (source == null) delay(400L)
    }
    val input = source ?: return null
    val cacheKey = "${input.key}\u0000$from\u0000$length"
    WaveformCache.get(cacheKey)?.let { return it }
    return decoderPermit.withPermit {
        WaveformCache.get(cacheKey)?.let { return@withPermit it }
        withContext(Dispatchers.IO) {
            val originalPriority = Process.getThreadPriority(Process.myTid())
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                withTimeoutOrNull(15_000L) {
                    PcmWindowDecoder(AnalysisInput(factory, input.spec)).use { decoder ->
                        val pcm = decoder.decode(from, length) ?: return@use null
                        val analysis = TrackAnalyzer.analyze(pcm.pcm, pcm.sampleRate, pcm.startMs)
                        currentCoroutineContext().ensureActive()
                        analysis.bands.takeIf { it.isNotEmpty() }?.let { bands ->
                            WaveformWindow(analysis.startMs, bands).also { WaveformCache.put(cacheKey, it) }
                        }
                    }
                }
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                null
            } finally {
                Process.setThreadPriority(originalPriority)
            }
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
    fun MusicPlayer.ResolvedStream.eligible() = segmentedDrm == null &&
        (expiresAtEpochMs ?: youtubeCandidate?.expiresAtEpochMs)?.let { it > now } != false
    fun Uri.expiredGoogleUrl() = host?.endsWith("googlevideo.com") == true &&
        getQueryParameter("expire")?.toLongOrNull()?.let { it * 1_000 <= now } == true
    val streams = allStreams.filter { it.eligible() }
    val concreteUri = exactUri?.takeIf {
        it.scheme != "lastwave" && !it.expiredGoogleUrl() &&
            item?.localConfiguration?.drmConfiguration == null && storedExact?.eligible() != false
    }
    val stream = storedExact?.takeIf { it.eligible() } ?: binding.track?.takeIf { concreteUri == null }?.let { matched ->
        streams.firstOrNull { it.youtubeCandidate?.videoId == matched.videoId && matched.videoId != null }
            ?: streams.firstOrNull { it.cacheKey.startsWith("lossless:${matched.mediaIdKey()}:") }
    }
    val uri = concreteUri ?: stream?.url?.let(Uri::parse)
        ?: binding.track?.playbackUrl?.let { if (it.startsWith("/")) Uri.fromFile(java.io.File(it)) else Uri.parse(it) }
        ?: return null
    if (uri.scheme !in listOf("http", "https", "file", "content")) return null
    if (storedExact?.let { it.url == uri.toString() && !it.eligible() } == true) return null
    val sameTrack = binding.track?.let {
        (!it.videoId.isNullOrBlank() || !it.playbackUrl.isNullOrBlank()) && it.mediaIdKey() == other.track?.mediaIdKey()
    } == true
    if (!sameTrack && (uri == other.source?.spec?.uri || uri == other.item?.localConfiguration?.uri)) return null
    val userAgent = stream?.userAgent?.takeIf { it.isNotBlank() }
        ?: stream?.requestHeaders?.entries?.firstOrNull { it.key.equals("User-Agent", true) }?.value
        ?: stream?.youtubeCandidate?.userAgent
    if (uri.host?.endsWith("googlevideo.com") == true) {
        if (userAgent.isNullOrBlank() || uri.expiredGoogleUrl()) return null
    }
    val headers = MusicPlayer.NEUTRAL_MEDIA_HEADERS.toMutableMap()
    stream?.requestHeaders?.forEach { (name, value) -> if (!name.equals("User-Agent", true)) headers[name] = value }
    userAgent?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
    val key = stream?.cacheKey ?: item?.localConfiguration?.customCacheKey ?: uri.toString()
    return WaveformSource(
        key, DataSpec.Builder().setUri(uri).setKey(key).setHttpRequestHeaders(headers).build(),
        stream?.expiresAtEpochMs ?: stream?.youtubeCandidate?.expiresAtEpochMs,
    ).also { binding.source = it }
}
