@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lastwave.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/** Where Android currently sends media audio, as far as this feature cares. */
enum class InternalRoute { SPEAKER, WIRED, BLUETOOTH, USB, OTHER, UNKNOWN }

/** How the mixer is bypassed. */
enum class InternalBackend {
    /** Vendor `direct_pcm_out` profile via a 24-bit AudioTrack (UAPP HiRes Direct recipe). */
    DIRECT_TRACK,
    /** AAudio EXCLUSIVE (MMAP) stream — source rate, else 48 kHz, else device native. */
    MMAP,
}

/** Why the internal direct output is (un)available on the current route. */
enum class InternalDirectReason {
    AVAILABLE,
    ACTIVE,
    CHECKING,
    BLUETOOTH,
    USB,
    EXCLUSIVE_REFUSED,
    NO_AAUDIO,
    ANDROID_TOO_OLD,
    NATIVE_MISSING,
    /** Android accepted the track but mixed it anyway: no direct profile for it here. */
    MIXED_BY_ANDROID,
}

data class InternalDirectAvailability(
    val route: InternalRoute,
    val routeName: String,
    val reason: InternalDirectReason,
    val probe: InternalDirectStream.Probe? = null,
) {
    val available: Boolean
        get() = reason == InternalDirectReason.AVAILABLE || reason == InternalDirectReason.ACTIVE

    /**
     * Whether the settings toggle may be switched on. Only a permanent
     * impossibility (Android too old, native library missing) locks it.
     * Route- or format-specific results (Bluetooth codec, one format mixed
     * by Android, exclusive refused while something else held the output)
     * can change on the next track or route, and the user must be able to
     * turn the feature back on without restarting the app.
     */
    val canEnable: Boolean
        get() = reason != InternalDirectReason.ANDROID_TOO_OLD && reason != InternalDirectReason.NATIVE_MISSING
}

/** The direct stream actually playing, for the signal-path page and the badge. */
data class InternalDirectSession(
    val route: InternalRoute,
    val routeName: String,
    val sourceRateHz: Int,
    val sourceEncoding: Int,
    val sourceBits: Int,
    val deviceRateHz: Int,
    val deviceEncoding: Int,
    val deviceChannels: Int,
    val resampled: Boolean,
    val formatConverted: Boolean,
    val formatLossless: Boolean,
    val mmap: Boolean,
    val deviceBufferFrames: Int,
    val backend: InternalBackend = InternalBackend.MMAP,
    /** DIRECT_TRACK: Android itself confirmed the track is not mixed. MMAP: exclusivity verified. */
    val directVerified: Boolean = true,
)

/**
 * "Bypass the Android mixer without a USB DAC" (UAPP-style HiRes Direct).
 *
 * Two backends, tried in this order:
 *  1. [DirectTrackStream]: a 24-bit packed music AudioTrack at the source rate.
 *     Vendor policies (Qualcomm `direct_pcm_out`) put it on a DIRECT output
 *     that AudioFlinger never mixes — speaker, jack AND Bluetooth A2DP, 44.1 to
 *     384 kHz. Verified per track (Android refuses rate changes on direct tracks).
 *  2. AAudio EXCLUSIVE (MMAP) via [InternalDirectStream]: writes into the HAL
 *     buffer. Opens at the source rate when the HAL accepts it, otherwise at
 *     48 kHz — the one rate every HAL accepts — and only then at whatever
 *     rate the device natively runs at (see [fallbackRates]). Whatever does
 *     not match the source costs one native soxr conversion.
 *
 * Limits that remain: USB DACs use the usbdevfs exclusive output; on Bluetooth
 * the codec still re-encodes after the bypass; a vendor DSP after the HAL is
 * unverifiable. Fails closed: every refusal keeps the regular mixer path.
 */
@Singleton
class InternalDirectOutput @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val openLock = Any()

    @Volatile private var wanted = false
    @Volatile private var stream: InternalDirectStream? = null
    @Volatile private var track: DirectTrackStream? = null

    @Volatile private var activeSourceRateHz = 0
    @Volatile private var activeSourceEncoding = -1
    @Volatile private var activeSourceChannels = 0
    @Volatile private var deviceRateHz = 0
    @Volatile private var deviceEncoding = -1
    @Volatile private var deviceChannels = 0
    @Volatile private var deviceBufferFrames = 0

    @Volatile private var startMediaTimeUs = 0L
    @Volatile private var startNeedsInit = true
    @Volatile private var baseConsumedFrames = 0L
    @Volatile private var lastBufferedSeenMs = 0L
    @Volatile private var playing = false

    /** MMAP per-route outcome cache: "srcRate/srcEnc" -> chosen (rate, encoding) or null when refused. */
    private val openCache = HashMap<String, Pair<Int, Int>?>()
    /** Routes ("route|rate|enc") where Android mixed the direct track: skip straight to MMAP. */
    private val mixedRoutes: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile private var cachedProbe: InternalDirectStream.Probe? = null
    private var probeJob: Job? = null

    private val _availability = MutableStateFlow(
        InternalDirectAvailability(InternalRoute.UNKNOWN, "", InternalDirectReason.CHECKING),
    )
    val availability: StateFlow<InternalDirectAvailability> = _availability.asStateFlow()

    private val _session = MutableStateFlow<InternalDirectSession?>(null)
    val session: StateFlow<InternalDirectSession?> = _session.asStateFlow()

    /** Last reason the stream could not engage while wanted (null once it engages). */
    @Volatile var lastFailure: InternalDirectReason? = null
        private set

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onRouteChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onRouteChanged()
    }

    init {
        runCatching { audioManager?.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper())) }
        // Route only: the MMAP endpoint is probed lazily (settings open or
        // feature enabled), never at app start for users who keep it off.
        refreshAvailability(probe = false)
    }

    fun setWanted(enabled: Boolean) {
        if (wanted == enabled) return
        wanted = enabled
        if (!enabled) {
            synchronized(openLock) { closeLocked() }
            lastFailure = null
        } else {
            // Re-enabling is an explicit retry: forget what earlier attempts
            // concluded (formats mixed by Android, a probe taken while our own
            // stream held the output) so this output is tested again.
            synchronized(openLock) {
                openCache.clear()
                mixedRoutes.clear()
                cachedProbe = null
            }
        }
        refreshAvailability()
    }

    fun isWanted(): Boolean = wanted

    fun isActive(): Boolean = _session.value != null && isAlive()

    fun hasOpenStream(): Boolean = stream != null || track != null

    fun isAlive(): Boolean {
        track?.let { return it.isAlive() }
        return stream?.counters()?.getOrNull(3) == 1L
    }

    /** Rate the output consumes (after any app-side resampling). 0 when idle. */
    fun deviceRateHz(): Int = if (_session.value != null) deviceRateHz else 0

    fun requiresResampling(): Boolean = _session.value?.resampled == true

    fun underruns(): Long {
        track?.let { return it.underruns() }
        return stream?.counters()?.getOrNull(2) ?: 0L
    }

    /** Current route as Android reports it for media playback. */
    fun currentRoute(): Pair<InternalRoute, String> {
        val manager = audioManager ?: return InternalRoute.UNKNOWN to ""
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                manager.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES).firstOrNull()
            }.getOrNull()
        } else {
            null
        } ?: guessRoute(manager)
        if (device == null) return InternalRoute.UNKNOWN to ""
        return classify(device.type) to (device.productName?.toString().orEmpty())
    }

    /**
     * Re-evaluates the route. With the direct-track backend (Android 12+) every
     * non-USB route is worth trying: the verdict comes from the track itself
     * during playback. Older systems probe the MMAP endpoint (debounced).
     */
    fun refreshAvailability(
        probe: Boolean = wanted || _availability.subscriptionCount.value > 0,
    ) {
        val (route, name) = currentRoute()
        val early = when {
            _session.value != null -> InternalDirectReason.ACTIVE
            DIRECT_TRACK_OK -> {
                val key = route.name
                if (mixedRoutes.any { it.startsWith("$key|") } && cachedProbe?.usable != true) {
                    InternalDirectReason.MIXED_BY_ANDROID
                } else {
                    InternalDirectReason.AVAILABLE
                }
            }
            !NATIVE_OK -> InternalDirectReason.NATIVE_MISSING
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> InternalDirectReason.ANDROID_TOO_OLD
            else -> null
        }
        if (early != null) {
            _availability.value = InternalDirectAvailability(route, name, early, cachedProbe)
            return
        }
        cachedProbe?.let { probeResult ->
            _availability.value = InternalDirectAvailability(route, name, reasonFor(probeResult), probeResult)
            return
        }
        _availability.value = InternalDirectAvailability(route, name, InternalDirectReason.CHECKING)
        if (!probe) return
        probeJob?.cancel()
        probeJob = scope.launch {
            delay(PROBE_DEBOUNCE_MS)
            val result = synchronized(openLock) {
                if (_session.value != null) null else InternalDirectStream.probe(OUT_CHANNELS)
            }
            val (r, n) = currentRoute()
            if (result != null) cachedProbe = result
            _availability.value = when {
                _session.value != null -> InternalDirectAvailability(r, n, InternalDirectReason.ACTIVE, cachedProbe)
                result == null -> InternalDirectAvailability(r, n, InternalDirectReason.NATIVE_MISSING)
                else -> InternalDirectAvailability(r, n, reasonFor(result), result)
            }
        }
    }

    /** Opens (or reuses) a direct output for [format]: direct track first, then MMAP. */
    fun configure(format: Format, bitDepthHint: Int?): Boolean {
        if (!wanted) return false
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW || format.sampleRate <= 0 ||
            format.channelCount !in 1..2
        ) return false
        val srcEnc = encodingOf(format.pcmEncoding) ?: return false
        val (route, routeName) = currentRoute()
        // USB allowed: the sink only gets here when usbdevfs exclusive did not engage.
        val sourceBits = effectiveBits(srcEnc, bitDepthHint)
        var mixed = false
        synchronized(openLock) {
            if (reuseLocked(format, srcEnc)) return true
            closeLocked()
            if (DIRECT_TRACK_OK) {
                when (openDirectTrackLocked(format, srcEnc, sourceBits, route, routeName)) {
                    TrackOutcome.ENGAGED -> {
                        lastFailure = null
                        return true.also { scope.launch { refreshAvailability() } }
                    }
                    TrackOutcome.MIXED -> mixed = true
                    TrackOutcome.FAILED -> Unit
                }
            }
            if (!NATIVE_OK) return fail(if (mixed) InternalDirectReason.MIXED_BY_ANDROID else InternalDirectReason.NATIVE_MISSING)
            val engaged = openMmapLocked(format, srcEnc, sourceBits, route, routeName)
            if (!engaged) {
                val probe = cachedProbe
                return fail(
                    when {
                        mixed -> InternalDirectReason.MIXED_BY_ANDROID
                        probe == null || probe.status != 0 -> InternalDirectReason.NO_AAUDIO
                        else -> InternalDirectReason.EXCLUSIVE_REFUSED
                    },
                )
            }
            lastFailure = null
        }
        refreshAvailability()
        return true
    }

    private enum class TrackOutcome { ENGAGED, MIXED, FAILED }

    /** Same wire configuration and still alive: keep the output (no reopen gap). */
    private fun reuseLocked(format: Format, srcEnc: Int): Boolean {
        if (_session.value == null || !isAlive()) return false
        if (activeSourceRateHz != format.sampleRate || activeSourceEncoding != srcEnc ||
            activeSourceChannels != format.channelCount
        ) return false
        track?.let {
            it.pause()
            it.flush()
        }
        stream?.let {
            it.pause()
            it.flush()
        }
        playing = false
        rebaseClock()
        lastFailure = null
        return true
    }

    private fun openDirectTrackLocked(
        format: Format,
        srcEnc: Int,
        sourceBits: Int,
        route: InternalRoute,
        routeName: String,
    ): TrackOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return TrackOutcome.FAILED
        val wire = DirectTrackStream.wireEncodingFor(srcEnc, sourceBits)
        val key = "${route.name}|${format.sampleRate}|$wire"
        if (key in mixedRoutes) return TrackOutcome.MIXED
        val created = DirectTrackStream(MEDIA_ATTRIBUTES)
        if (!created.open(format.sampleRate, format.channelCount, wire)) {
            created.release()
            return TrackOutcome.FAILED
        }
        val direct = created.probeDirect()
        if (direct == false) {
            // Android mixes it like any other track: no gain over the normal path.
            Log.w(TAG, "direct track mixed by Android on $route (${format.sampleRate}Hz ${InternalDirectStream.labelOf(wire)})")
            created.release()
            mixedRoutes += key
            return TrackOutcome.MIXED
        }
        track = created
        activeSourceRateHz = format.sampleRate
        activeSourceEncoding = srcEnc
        activeSourceChannels = format.channelCount
        deviceRateHz = format.sampleRate
        deviceEncoding = wire
        deviceChannels = format.channelCount
        deviceBufferFrames = 0
        playing = false
        rebaseClock()
        _session.value = InternalDirectSession(
            route = route,
            routeName = created.routedDeviceName()?.takeIf { it.isNotBlank() } ?: routeName,
            sourceRateHz = format.sampleRate,
            sourceEncoding = srcEnc,
            sourceBits = sourceBits,
            deviceRateHz = format.sampleRate,
            deviceEncoding = wire,
            deviceChannels = format.channelCount,
            resampled = false,
            formatConverted = wire != srcEnc,
            formatLossless = isLossless(srcEnc, sourceBits, wire),
            mmap = false,
            deviceBufferFrames = 0,
            backend = InternalBackend.DIRECT_TRACK,
            directVerified = direct == true,
        )
        Log.i(
            TAG,
            "INTERNAL DIRECT (track) engaged route=$route ${format.sampleRate}Hz " +
                "${InternalDirectStream.labelOf(srcEnc)} -> ${InternalDirectStream.labelOf(wire)} " +
                "verified=${direct == true}",
        )
        return TrackOutcome.ENGAGED
    }

    private fun openMmapLocked(
        format: Format,
        srcEnc: Int,
        sourceBits: Int,
        route: InternalRoute,
        routeName: String,
    ): Boolean {
        val created = InternalDirectStream()
        if (!created.isAvailable) {
            created.close()
            return false
        }
        val key = "${format.sampleRate}/$srcEnc"
        val remembered = openCache[key]
        val chosen: Pair<Int, Int>? = when {
            // This route already refused this source format: don't retry every track.
            openCache.containsKey(key) && remembered == null -> null
            remembered != null &&
                created.open(remembered.first, OUT_CHANNELS, remembered.second) == InternalDirectStream.OPEN_OK ->
                remembered
            else -> negotiate(created, format.sampleRate, srcEnc).also { openCache[key] = it }
        }
        if (chosen == null) {
            created.close()
            return false
        }
        val info = created.info()
        stream = created
        activeSourceRateHz = format.sampleRate
        activeSourceEncoding = srcEnc
        activeSourceChannels = format.channelCount
        deviceRateHz = chosen.first
        deviceEncoding = chosen.second
        deviceChannels = info?.channelCount ?: OUT_CHANNELS
        deviceBufferFrames = info?.deviceBufferFrames ?: 0
        playing = false
        rebaseClock()
        val resampled = chosen.first != format.sampleRate
        // Resampled audio reaches the stream as Float32 from soxr.
        val wireInput = if (resampled) InternalDirectStream.ENC_FLOAT else srcEnc
        _session.value = InternalDirectSession(
            route = route,
            routeName = routeName,
            sourceRateHz = format.sampleRate,
            sourceEncoding = srcEnc,
            sourceBits = sourceBits,
            deviceRateHz = chosen.first,
            deviceEncoding = chosen.second,
            deviceChannels = deviceChannels,
            resampled = resampled,
            formatConverted = wireInput != chosen.second || format.channelCount != deviceChannels,
            formatLossless = !resampled && isLossless(srcEnc, sourceBits, chosen.second),
            mmap = info?.mmap == true,
            deviceBufferFrames = deviceBufferFrames,
            backend = InternalBackend.MMAP,
            directVerified = true,
        )
        Log.i(
            TAG,
            "INTERNAL DIRECT (mmap) engaged route=$route src=${format.sampleRate}Hz/" +
                "${InternalDirectStream.labelOf(srcEnc)} -> device=${chosen.first}Hz/" +
                "${InternalDirectStream.labelOf(chosen.second)} resampled=$resampled mmap=${info?.mmap}",
        )
        return true
    }

    private fun negotiate(target: InternalDirectStream, srcRate: Int, srcEnc: Int): Pair<Int, Int>? {
        if (target.open(srcRate, OUT_CHANNELS, srcEnc) == InternalDirectStream.OPEN_OK) {
            return srcRate to srcEnc
        }
        val probe = InternalDirectStream.probe(OUT_CHANNELS)?.also { cachedProbe = it }
        if (probe == null || !probe.usable) return null
        if (probe.encoding != srcEnc &&
            target.open(srcRate, OUT_CHANNELS, probe.encoding) == InternalDirectStream.OPEN_OK
        ) {
            return srcRate to probe.encoding
        }
        // The source rate is refused. Try the rates in [fallbackRates] order:
        // the universal 48 kHz family first, the device's native rate last.
        for (rate in fallbackRates(srcRate, probe.sampleRateHz)) {
            if (target.open(rate, OUT_CHANNELS, probe.encoding) == InternalDirectStream.OPEN_OK) {
                return rate to probe.encoding
            }
        }
        return null
    }

    /**
     * Writes interleaved PCM in [media3Encoding] (Media3 C.ENCODING_*). Advances
     * [buffer] by what was queued. True when the whole buffer was taken.
     */
    fun write(buffer: ByteBuffer, presentationTimeUs: Long, media3Encoding: Int, channels: Int): Boolean {
        if (!buffer.hasRemaining()) return true
        val enc = encodingOf(media3Encoding) ?: return false
        track?.let { direct ->
            val anchor = if (startNeedsInit) direct.playedFrames() else 0L
            val before = buffer.position()
            val done = direct.write(buffer, enc, channels)
            if (startNeedsInit && (done || buffer.position() != before)) {
                startMediaTimeUs = presentationTimeUs.coerceAtLeast(0L)
                baseConsumedFrames = anchor
                startNeedsInit = false
            }
            return done
        }
        val target = stream ?: return false
        // Anchor the clock on the first frames actually accepted: right after a
        // flush the RT callback may still be dropping the old backlog.
        val anchorConsumed = if (startNeedsInit) target.counters()[0] else 0L
        val source = if (buffer.isDirect) buffer else directCopyOf(buffer)
        val before = source.position()
        val accepted = target.write(source, enc, channels)
        if (source !== buffer) {
            buffer.position(buffer.position() + (source.position() - before))
        }
        if (startNeedsInit && accepted > 0) {
            startMediaTimeUs = presentationTimeUs.coerceAtLeast(0L)
            baseConsumedFrames = anchorConsumed
            startNeedsInit = false
        }
        lastBufferedSeenMs = SystemClock.elapsedRealtime()
        return !buffer.hasRemaining()
    }

    fun play() {
        playing = true
        track?.play()
        stream?.start()
    }

    fun pause() {
        playing = false
        track?.pause()
        stream?.pause()
    }

    /** Seek/stop: drop everything queued and re-anchor the media clock on the next write. */
    fun flush() {
        track?.flush()
        stream?.flush()
        rebaseClock()
    }

    fun getCurrentPositionUs(): Long {
        if (startNeedsInit || deviceRateHz <= 0) return AudioSink.CURRENT_POSITION_NOT_SET
        track?.let { direct ->
            val played = (direct.playedFrames() - baseConsumedFrames).coerceAtLeast(0L)
            return startMediaTimeUs + played * C.MICROS_PER_SECOND / deviceRateHz
        }
        val target = stream ?: return AudioSink.CURRENT_POSITION_NOT_SET
        val consumed = target.counters()[0]
        val played = (consumed - baseConsumedFrames - deviceBufferFrames).coerceAtLeast(0L)
        return startMediaTimeUs + played * C.MICROS_PER_SECOND / deviceRateHz
    }

    /** Audio still queued (ring / AudioTrack), or the device buffer still draining. */
    fun hasPendingData(): Boolean {
        track?.let { return it.hasPendingData() }
        val target = stream ?: return false
        val buffered = target.counters()[1]
        if (buffered > 0L) {
            lastBufferedSeenMs = SystemClock.elapsedRealtime()
            return true
        }
        if (!playing || deviceRateHz <= 0) return false
        val drainMs = deviceBufferFrames * 1000L / deviceRateHz + DRAIN_MARGIN_MS
        return SystemClock.elapsedRealtime() - lastBufferedSeenMs < drainMs
    }

    /** Sink reset between items: keep the output for a same-format next item. */
    fun prepareForNextItem() {
        track?.let {
            it.pause()
            it.flush()
        }
        stream?.let {
            it.pause()
            it.flush()
        }
        playing = false
        rebaseClock()
    }

    fun reset() {
        synchronized(openLock) { closeLocked() }
        refreshAvailability()
    }

    private fun rebaseClock() {
        startNeedsInit = true
        startMediaTimeUs = 0L
        baseConsumedFrames = track?.playedFrames() ?: stream?.counters()?.getOrNull(0) ?: 0L
    }

    private fun closeLocked() {
        track?.release()
        track = null
        stream?.let {
            it.closeStream()
            it.close()
        }
        stream = null
        playing = false
        activeSourceRateHz = 0
        activeSourceEncoding = -1
        activeSourceChannels = 0
        deviceRateHz = 0
        deviceEncoding = -1
        deviceChannels = 0
        deviceBufferFrames = 0
        startNeedsInit = true
        _session.value = null
    }

    private fun fail(reason: InternalDirectReason): Boolean {
        lastFailure = reason
        Log.w(TAG, "INTERNAL DIRECT unavailable: $reason (mixer path kept)")
        return false
    }

    private fun onRouteChanged() {
        // A new route is a new output: forget what the old one accepted.
        // Off the main thread: configure() may hold the lock while opening.
        scope.launch {
            synchronized(openLock) {
                openCache.clear()
                mixedRoutes.clear()
                cachedProbe = null
            }
            refreshAvailability()
        }
    }

    private fun reasonFor(probe: InternalDirectStream.Probe): InternalDirectReason = when {
        probe.status != 0 -> InternalDirectReason.NO_AAUDIO
        !probe.exclusive -> InternalDirectReason.EXCLUSIVE_REFUSED
        else -> InternalDirectReason.AVAILABLE
    }

    private var scratch: ByteBuffer? = null

    private fun directCopyOf(buffer: ByteBuffer): ByteBuffer {
        val size = buffer.remaining()
        val existing = scratch
        val target = if (existing != null && existing.capacity() >= size) existing
        else ByteBuffer.allocateDirect(maxOf(size, 64 * 1024)).order(ByteOrder.nativeOrder()).also { scratch = it }
        target.clear()
        target.put(buffer.duplicate())
        target.flip()
        return target
    }

    companion object {
        private const val TAG = "InternalDirect"
        private const val OUT_CHANNELS = 2
        private const val PROBE_DEBOUNCE_MS = 600L
        private const val DRAIN_MARGIN_MS = 15L

        private val NATIVE_OK: Boolean get() = InternalDirectStream.nativeLoaded

        /** 24-bit packed / 32-bit AudioTrack encodings need Android 12. */
        private val DIRECT_TRACK_OK: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        // Lazy: the pure helpers below must stay usable in JVM unit tests.
        private val MEDIA_ATTRIBUTES: AudioAttributes by lazy {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        }

        fun encodingOf(media3Encoding: Int): Int? = when (media3Encoding) {
            C.ENCODING_PCM_16BIT -> InternalDirectStream.ENC_I16
            C.ENCODING_PCM_24BIT -> InternalDirectStream.ENC_I24
            C.ENCODING_PCM_32BIT -> InternalDirectStream.ENC_I32
            C.ENCODING_PCM_FLOAT -> InternalDirectStream.ENC_FLOAT
            else -> null
        }

        /** Real resolution of the source: FFmpeg hands 24-bit FLAC over as 32-bit words. */
        fun effectiveBits(encoding: Int, hint: Int?): Int {
            val container = InternalDirectStream.bitsOf(encoding)
            if (encoding == InternalDirectStream.ENC_FLOAT) return 32
            return if (hint != null && hint in 1 until container) hint else container
        }

        /** True when [devEnc] carries every bit of a [srcBits]-bit [srcEnc] source. */
        fun isLossless(srcEnc: Int, srcBits: Int, devEnc: Int): Boolean = when {
            srcEnc == devEnc -> true
            srcEnc == InternalDirectStream.ENC_FLOAT -> false
            devEnc == InternalDirectStream.ENC_FLOAT -> srcBits <= 24
            else -> InternalDirectStream.bitsOf(devEnc) >= srcBits
        }

        /**
         * Rates to try when the HAL refused the source rate, in order.
         *
         * 48 kHz comes first: it is the one rate every Android HAL accepts,
         * and the documented normal case for this backend ("MMAP ... often
         * fixed at 48 kHz, so 44.1 kHz sources get resampled"). The probe's
         * own rate — the device's native primary output rate — comes LAST:
         * on a 44.1-primary HAL it would otherwise resample every source,
         * 48 kHz YouTube streams included, DOWN to 44.1. The probe rate is
         * still kept as the final attempt so a device that genuinely cannot
         * open 48 kHz keeps its current behaviour instead of failing closed.
         *
         * The source rate is never repeated (it was just refused), and
         * duplicates are collapsed so a 48-primary HAL makes one attempt.
         */
        fun fallbackRates(sourceHz: Int, probeRateHz: Int): List<Int> = buildList {
            if (sourceHz <= 0) return@buildList
            if (48_000 != sourceHz) add(48_000)
            probeRateHz
                .takeIf { it > 0 && it != sourceHz && it != 48_000 }
                ?.let { add(it) }
        }

        fun classify(type: Int): InternalRoute = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID -> InternalRoute.BLUETOOTH
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET -> InternalRoute.USB
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> InternalRoute.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE -> InternalRoute.WIRED
            else -> InternalRoute.OTHER
        }

        /** Pre-Android 13 heuristic: media follows Bluetooth, then USB, then the jack. */
        private fun guessRoute(manager: AudioManager): AudioDeviceInfo? {
            val outputs = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
                .getOrDefault(emptyList())
            val order = listOf(InternalRoute.BLUETOOTH, InternalRoute.USB, InternalRoute.WIRED, InternalRoute.SPEAKER)
            for (wantedRoute in order) {
                outputs.firstOrNull { classify(it.type) == wantedRoute && it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    ?.let { return it }
            }
            return outputs.firstOrNull()
        }
    }
}
