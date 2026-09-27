package com.lastwave.app.data.plugin

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.lastwave.app.data.plugin.SegmentedMpdBuilder
import com.lastwave.app.data.plugin.SegmentedStreamDescriptor
import com.lastwave.app.data.plugin.stableCacheKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chunk-by-chunk provider path into the existing MusicPlayer.
 *
 * Input: [SegmentedStreamDescriptor] from a provider module's `getPlayback()`
 * call (baseUrl + init range + N mediaRange segments + Widevine pssh/license).
 * Output: a [MediaItem] the current ExoPlayer graph can open directly:
 * MPD file uri + Widevine [MediaItem.DrmConfiguration] + stable cache key.
 *
 * Decryption stays inside the CDM: ExoPlayer fetches each chunk with a Range
 * header, MediaCrypto decrypts, and clear PCM flows to the existing audio
 * sink. No manual AES, no key exposure. Seeks map to segment indexes.
 */
@UnstableApi
@Singleton
class SegmentedDashBridge @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun isSegmentedCacheKey(cacheKey: String?): Boolean =
        cacheKey?.startsWith("segdrm:") == true

    /** MPD uri for [descriptor]; rewrites the file only when content changes. */
    fun mpdUri(descriptor: SegmentedStreamDescriptor): Uri =
        mpdUriForBase(descriptor, descriptor.stream.baseUrl)

    /** Offline variant: same chunks, local audio file as the base. */
    fun mpdUriForBase(descriptor: SegmentedStreamDescriptor, baseUrl: String): Uri {
        val mpd = when {
            baseUrl.startsWith("data:application/dash+xml;base64,") -> {
                String(android.util.Base64.decode(baseUrl.substringAfter("base64,"), android.util.Base64.DEFAULT), Charsets.UTF_8)
            }
            baseUrl.startsWith("<?xml") -> baseUrl
            else -> SegmentedMpdBuilder.build(descriptor, baseUrl)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(mpd.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        val dir = File(context.cacheDir, "segdrm_mpd").apply { mkdirs() }
        val file = File(dir, "seg_${descriptor.trackId}_${descriptor.stream.quality}_$digest.mpd")
        if (!file.exists()) file.writeText(mpd, Charsets.UTF_8)
        return Uri.fromFile(file)
    }

    fun drmConfiguration(descriptor: SegmentedStreamDescriptor): MediaItem.DrmConfiguration? =
        drmConfigurationFor(descriptor)

    companion object {
        /** Pure helper so non-injected call sites can attach DRM to items. */
        fun drmConfigurationFor(descriptor: SegmentedStreamDescriptor): MediaItem.DrmConfiguration? {
        val drm = descriptor.drm ?: return null
        if (drm.licenseUrl.isBlank() && drm.keySetIdB64.isBlank()) return null
        if (drm.pssh.isBlank() && drm.keySetIdB64.isBlank()) return null
        // Offline download: persisted keys unlock without network.
        drm.keySetIdB64.takeIf { it.isNotBlank() }?.let { keys ->
            val builder = MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                .setKeySetId(android.util.Base64.decode(keys, android.util.Base64.DEFAULT))
                .setMultiSession(false)
            if (drm.licenseUrl.isNotBlank()) builder.setLicenseUri(drm.licenseUrl)
            return builder.build()
        }
        if (drm.licenseUrl.isBlank() || drm.pssh.isBlank()) return null
        return MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
            .setLicenseUri(drm.licenseUrl)
            .setMultiSession(false)
            .setForceDefaultLicenseUri(true)
            .apply {
                val headers = descriptor.headers.toMutableMap()
                // ExoPlayer still needs a UA on the license POST.
                if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    headers["User-Agent"] = "LastWave/4.2.1"
                }
                setLicenseRequestHeaders(headers)
            }
            .build()
        }

        /**
         * Pure label mapping — no context needed, so it is unit-testable.
         *
         * Dolby Atmos is signalled by the codec the provider module emits
         * ("atmos" = E-AC-3 JOC spatial) as much as by the quality tier, so
         * check both. This must never fall through to a stereo/OPUS label or
         * the player + download badges silently drop the Atmos mix.
         */
        fun audioBadgeLabel(descriptor: SegmentedStreamDescriptor): String {
            if (descriptor.stream.codec.equals("atmos", ignoreCase = true)) return "DOLBY ATMOS"
            val codec = descriptor.stream.codec.lowercase()
            return when (descriptor.stream.quality.uppercase()) {
                "ATMOS", "DOLBY_ATMOS" -> "DOLBY ATMOS"
                "UHD", "HI_RES_96" -> "UHD FLAC"
                "HD" -> "HD FLAC"
                "SD" -> if (codec.contains("mp3")) "320k MP3" else "AAC 320"
                "LOW" -> if (codec.contains("opus")) "OPUS" else "HE-AAC"
                else -> when {
                    codec.contains("flac") -> if ((descriptor.stream.bitDepth) > 16 || (descriptor.stream.sampleRate) > 48000) "UHD FLAC" else "HD FLAC"
                    codec.contains("mp3") -> "320k MP3"
                    codec.contains("aac") || codec.contains("mp4a") -> if (descriptor.stream.bandwidth in 1..128000) "HE-AAC" else "AAC 320"
                    codec.contains("opus") -> "OPUS"
                    else -> "AUDIO"
                }
            }
        }

        /**
         * The codec actually declared inside the descriptor's DASH manifest, or
         * null when it cannot be read.
         *
         * This is the source of truth for what the bytes really are: the addon's
         * descriptor can claim `flac`/`UHD` even when the manifest it fetched is
         * AAC (Tidal silently answers the HIGH tier with `mp4a.40.2`), so both the
         * player badge and the download path must trust this over `stream.codec`.
         */
        fun declaredManifestCodec(descriptor: SegmentedStreamDescriptor): String? = runCatching {
            val baseUrl = descriptor.stream.baseUrl
            val xml = when {
                baseUrl.startsWith("data:application/dash+xml;base64,") -> String(
                    android.util.Base64.decode(baseUrl.substringAfter("base64,"), android.util.Base64.DEFAULT),
                    Charsets.UTF_8,
                )
                baseUrl.startsWith("<?xml") -> baseUrl
                else -> return@runCatching null
            }
            Regex("""codecs="([^"]+)"""").find(xml)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** Full MediaItem for a segmented track; caller sets title/artist metadata. */
    fun toMediaItem(
        descriptor: SegmentedStreamDescriptor,
        title: String,
        artist: String,
        album: String?,
        artworkUri: Uri?,
    ): MediaItem {
        val builder = MediaItem.Builder()
            .setMediaId("segdrm:${descriptor.trackId}")
            .setUri(mpdUri(descriptor))
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .setCustomCacheKey(descriptor.stableCacheKey())
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title.ifBlank { descriptor.title })
                    .setArtist(artist.ifBlank { descriptor.artist })
                    .setAlbumTitle(album ?: descriptor.album)
                    .setArtworkUri(artworkUri)
                    .setIsPlayable(true)
                    .build(),
            )
        drmConfiguration(descriptor)?.let(builder::setDrmConfiguration)
        return builder.build()
    }

    fun audioBadge(descriptor: SegmentedStreamDescriptor): String = audioBadgeLabel(descriptor)

    fun declaredManifestCodec(descriptor: SegmentedStreamDescriptor): String? =
        Companion.declaredManifestCodec(descriptor)
}
