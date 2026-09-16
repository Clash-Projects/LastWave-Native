package com.lastwave.app.data.plugin

import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class OfflineKeys(
    /** Persisted CDM key set, base64. Device-bound, useless off-device. */
    val keySetIdB64: String,
    /** Wall-clock ms when the stored license stops unlocking. */
    val licenseExpiresAtMs: Long,
)

/**
 * Persistent Widevine licenses for downloaded module tracks.
 *
 * Acquired once at download time (blocking CDM call on IO); playback restores
 * the stored key set with zero network until expiry. License transport still
 * flows through the module envelope via the session manager's callback.
 */
@Singleton
class ModuleOfflineLicense @Inject constructor(
    private val drmFactory: ModuleDrmFactory,
) {
    suspend fun acquire(descriptor: SegmentedStreamDescriptor): OfflineKeys =
        withContext(Dispatchers.IO) {
            val drm = descriptor.drm ?: error("No DRM descriptor")
            require(drm.pssh.isNotBlank()) { "No pssh for offline license" }
            val manager = drmFactory.managerFor(descriptor)
            val helper = OfflineLicenseHelper(manager, DrmSessionEventListener.EventDispatcher())
            try {
                val keySetId = helper.downloadLicense(formatFor(descriptor))
                OfflineKeys(
                    keySetIdB64 = Base64.encodeToString(keySetId, Base64.NO_WRAP),
                    licenseExpiresAtMs = remainingMs(helper, keySetId),
                )
            } finally {
                runCatching { helper.release() }
            }
        }

    /** Best-effort renewal of stored keys; null when offline/rejected. */
    suspend fun renew(descriptor: SegmentedStreamDescriptor, keySetIdB64: String): OfflineKeys? =
        withContext(Dispatchers.IO) {
            runCatching {
                val manager = drmFactory.managerFor(descriptor)
                val helper = OfflineLicenseHelper(manager, DrmSessionEventListener.EventDispatcher())
                try {
                    val keySetId = Base64.decode(keySetIdB64, Base64.DEFAULT)
                    val renewed = helper.renewLicense(keySetId)
                    OfflineKeys(
                        keySetIdB64 = Base64.encodeToString(renewed, Base64.NO_WRAP),
                        licenseExpiresAtMs = remainingMs(helper, renewed),
                    )
                } finally {
                    runCatching { helper.release() }
                }
            }.getOrNull()
        }

    private fun remainingMs(
        helper: OfflineLicenseHelper,
        keySetId: ByteArray,
    ): Long {
        val remainingSec = runCatching { helper.getLicenseDurationRemainingSec(keySetId).first }
            .getOrNull() ?: 0L
        if (remainingSec <= 0L) return 0L
        return System.currentTimeMillis() + remainingSec * 1000L
    }

    private fun formatFor(descriptor: SegmentedStreamDescriptor): Format {
        val drm = descriptor.drm ?: error("No DRM descriptor")
        val pssh = Base64.decode(drm.pssh, Base64.DEFAULT)
        val schemeData = DrmInitData.SchemeData(C.WIDEVINE_UUID, MimeTypes.AUDIO_MP4, pssh)
        return Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_MP4)
            .setDrmInitData(DrmInitData("cenc", schemeData))
            .build()
    }
}
