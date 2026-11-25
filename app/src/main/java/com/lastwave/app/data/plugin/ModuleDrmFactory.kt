package com.lastwave.app.data.plugin

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the Widevine session manager for a module-resolved stream.
 *
 * JSON-only (LWP2) world: the legacy QuickJS module-license envelope
 * ("module" envelope ops executed in an embedded JS engine) has been
 * removed with the script runtime. License exchange is always a raw POST
 * of the CDM challenge to the descriptor's license URL with the
 * descriptor headers; decryption itself stays inside the CDM and keys
 * never leave it. Anything else (or a missing license URL) falls back to
 * raw POST. Offline key-set renewal reuses the same path.
 */
@OptIn(UnstableApi::class)
@Singleton
class ModuleDrmFactory @Inject constructor(
    private val okHttp: OkHttpClient,
) {
    fun sessionManagerFor(descriptor: SegmentedStreamDescriptor): DrmSessionManager {
        val drm = descriptor.drm ?: return DrmSessionManager.DRM_UNSUPPORTED
        return managerFor(descriptor)
    }

    /** Concrete manager (also used for offline license acquisition). */
    fun managerFor(descriptor: SegmentedStreamDescriptor): DefaultDrmSessionManager {
        val callback = ModuleEnvelopeCallback(okHttp, descriptor)
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .build(callback)
    }

    @UnstableApi
    private class ModuleEnvelopeCallback(
        private val okHttp: OkHttpClient,
        private val descriptor: SegmentedStreamDescriptor,
    ) : MediaDrmCallback {

        override fun executeProvisionRequest(
            uuid: UUID,
            request: ExoMediaDrm.ProvisionRequest,
        ): MediaDrmCallback.Response = MediaDrmCallback.Response(
            postBytes(
                url = request.defaultUrl,
                headers = descriptor.headers,
                body = request.data,
            ),
        )

        override fun executeKeyRequest(
            uuid: UUID,
            request: ExoMediaDrm.KeyRequest,
        ): MediaDrmCallback.Response {
            val drm = descriptor.drm ?: error("No DRM descriptor")
            val url = request.licenseServerUrl?.takeIf { it.isNotBlank() } ?: drm.licenseUrl
            require(url.isNotBlank()) { "DRM licenseUrl missing" }
            return MediaDrmCallback.Response(postBytes(url, descriptor.headers, request.data))
        }

        private fun postBytes(url: String, headers: Map<String, String>, body: ByteArray): ByteArray {
            val req = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .build()
            okHttp.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    throw HttpDataSource.InvalidResponseCodeException(
                        res.code,
                        res.message,
                        null,
                        res.headers.toMultimap(),
                        androidx.media3.datasource.DataSpec(android.net.Uri.parse(url)),
                        res.body?.bytes() ?: ByteArray(0),
                    )
                }
                return res.body?.bytes() ?: ByteArray(0)
            }
        }
    }
}
