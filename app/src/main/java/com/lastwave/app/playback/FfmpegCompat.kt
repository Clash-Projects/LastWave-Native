package com.lastwave.app.playback

import android.system.Os
import android.system.OsConstants

/**
 * Whether the bundled FFmpeg decoder may be loaded on this device.
 *
 * libffmpegJNI.so in org.jellyfin.media3:media3-ffmpeg-decoder:1.2.1+1 is the
 * only native library in the APK aligned to 4 KB pages; everything else is
 * 16 KB-aligned. On a 16 KB-page kernel (Android 15+ hardware) it can only run
 * under the platform's page-size compatibility mode, and playback sessions there
 * died with SIGSEGV/SEGV_ACCERR seconds after the decoder started. Those
 * devices use the platform decoders instead (Opus, AAC, FLAC, MP3 and Vorbis
 * are all built into Android).
 *
 * Remove this gate once the FFmpeg extension is upgraded to a 16 KB-aligned
 * build (that needs the matching Media3 version).
 */
object FfmpegCompat {
    private const val LEGACY_PAGE_SIZE = 4096L

    val isUsable: Boolean by lazy {
        val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(LEGACY_PAGE_SIZE)
        pageSize <= LEGACY_PAGE_SIZE
    }
}
