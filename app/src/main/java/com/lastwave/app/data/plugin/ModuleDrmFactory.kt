package com.lastwave.app.data.plugin

import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the Widevine session manager for a module-resolved stream.
 *
 * Decryptor authority stays inside the module: when the descriptor declares
 * `envelope: "module"`, the CDM challenge/response envelope is built and
 * parsed by module ops — the host only POSTs bytes and hands results to the
 * CDM. Anything else (or a missing module) falls back to a raw POST.
 */
@OptIn(UnstableApi::class)
@Singleton
class ModuleDrmFactory @Inject constructor(
    private val okHttp: OkHttpClient,
    private val manager: ModuleManager,
    private val runner: ModuleRunner,
) {
    fun sessionManagerFor(descriptor: SegmentedStreamDescriptor): DrmSessionManager {
        val drm = descriptor.drm ?: return DrmSessionManager.DRM_UNSUPPORTED
        return managerFor(descriptor)
    }

    /** Concrete manager (also used for offline license acquisition). */
    fun managerFor(descriptor: SegmentedStreamDescriptor): DefaultDrmSessionManager {
        val handle = runCatching {
            runBlocking { manager.findHandleById(descriptor.provider) }
        }.getOrNull()
        val callback = ModuleEnvelopeCallback(okHttp, runner, descriptor, handle)
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .build(callback)
    }

    @UnstableApi
    private class ModuleEnvelopeCallback(
        private val okHttp: OkHttpClient,
        private val runner: ModuleRunner,
        private val descriptor: SegmentedStreamDescriptor,
        private val handle: ProviderHandle?,
    ) : MediaDrmCallback {

        override fun executeProvisionRequest(
            uuid: UUID,
            request: ExoMediaDrm.ProvisionRequest,
        ): ByteArray = postBytes(
            url = request.defaultUrl,
            headers = descriptor.headers,
            body = request.data,
        )

        override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
            val drm = descriptor.drm ?: error("No DRM descriptor")
            val url = request.licenseServerUrl?.takeIf { it.isNotBlank() } ?: drm.licenseUrl
            require(url.isNotBlank()) { "DRM licenseUrl missing" }
            val h = handle
            return if (h != null && drm.envelope == "module") {
                // Module authority: build envelope -> POST -> parse (blocking
                // CDM thread; engine lock lives on IO threads, no deadlock).
                val challengeB64 = Base64.encodeToString(request.data, Base64.NO_WRAP)
                val built = JSONObject(runBlocking {
                    runner.buildLicenseRequest(h, challengeB64, url, descriptor.headers)
                })
                val respBytes = postBytes(
                    url = built.optString("url", url).ifBlank { url },
                    headers = jsonToMap(built.optJSONObject("headers")) + descriptor.headers,
                    body = Base64.decode(built.getString("bodyB64"), Base64.DEFAULT),
                )
                val parsed = JSONObject(runBlocking {
                    runner.parseLicenseResponse(h, Base64.encodeToString(respBytes, Base64.NO_WRAP))
                })
                Base64.decode(parsed.getString("licenseB64"), Base64.DEFAULT)
            } else {
                postBytes(url, descriptor.headers, request.data)
            }
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

        private fun jsonToMap(o: JSONObject?): Map<String, String> {
            if (o == null) return emptyMap()
            return o.keys().asSequence().associateWith { o.optString(it) }
        }
    }
}
