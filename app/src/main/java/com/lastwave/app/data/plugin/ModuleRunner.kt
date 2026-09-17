package com.lastwave.app.data.plugin

import android.util.Base64
import android.util.Log
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.dokar.quickjs.binding.function as bindGlobalFn

/**
 * Executes provider-module calls inside QuickJS.
 *
 * Control-plane only: one pooled engine per module, entry source decrypted
 * (LWP1) and evaluated ONCE, then each song costs a single awaited call
 * (resolvePlayback / refreshPlayback) returning a JSON descriptor. Media3
 * streams from the CDN afterwards with zero module involvement until expiry.
 *
 * The host bridge is flat JSON strings (no object-proxy API risk):
 * __lw_httpRequest / __lw_rsaSign / __lw_b64decode / __lw_b64encode /
 * __lw_storeGet / __lw_storeSet / __lw_logWrite / __lw_uuid / __lw_sleepMs,
 * re-exposed to module code as globalThis.LastWave.*. Blocking calls run on
 * Dispatchers.IO; per-engine evaluation timeout; a poisoned engine is dropped
 * and rebuilt on next use.
 */
@Singleton
class ModuleRunner @Inject constructor(
    private val okHttp: OkHttpClient,
    private val manager: ModuleManager,
    private val crypto: ModuleCrypto,
) {
    companion object {
        private const val TAG = "ModuleRunner"
        private const val EVAL_TIMEOUT_MS = 60_000L
    }

    private data class PooledEngine(
        val js: QuickJs,
        val lock: Mutex = Mutex(),
        var loaded: Boolean = false,
    )

    private val engines = ConcurrentHashMap<String, PooledEngine>()

    private fun providerTarget(handle: ProviderHandle): String {
        val g = globalOf(handle)
        return "((globalThis.LastWave && globalThis.LastWave.$g) || globalThis.$g || globalThis.LastWaveProvider || globalThis.AmazonProvider)"
    }

    /** Single op per song: match + playback, descriptor JSON or "null". */
    suspend fun resolvePlayback(
        handle: ProviderHandle,
        title: String,
        artist: String,
        durationSec: Int,
        quality: String,
    ): String = withEngine(handle) { js ->
        val target = "{\"title\":${q(title)},\"artist\":${q(artist)}," +
            "\"durationSec\":$durationSec,\"quality\":${q(quality)}}"
        Log.d(TAG, "resolvePlayback: target=$target")
        val res = js.evaluate<String>(
            "${providerTarget(handle)}.resolvePlayback($target).then(r=>JSON.stringify(r));",
            "resolve.js",
        )
        Log.d(TAG, "resolvePlayback result len: ${res.length}")
        res
    }

    /** Cheap re-resolve for an expired source: straight to manifest, no search. */
    suspend fun refreshPlayback(
        handle: ProviderHandle,
        trackId: String,
        quality: String,
        title: String,
        artist: String,
        album: String,
        durationSec: Int,
    ): String = withEngine(handle) { js ->
        val ref = "{\"trackId\":${q(trackId)},\"quality\":${q(quality)}," +
            "\"title\":${q(title)},\"artist\":${q(artist)}," +
            "\"album\":${q(album)},\"durationSec\":$durationSec}"
        js.evaluate<String>(
            "${providerTarget(handle)}.refreshPlayback($ref).then(r=>JSON.stringify(r));",
            "refresh.js",
        )
    }

    /** Module-owned license envelope: CDM challenge bytes in, POST parts out. */
    suspend fun buildLicenseRequest(
        handle: ProviderHandle,
        challengeB64: String,
        licenseUrl: String,
        headers: Map<String, String>,
    ): String = withEngine(handle) { js ->
        val ctx = "{\"licenseUrl\":${q(licenseUrl)},\"headers\":${JSONObject(headers).toString()}}"
        js.evaluate<String>(
            "${providerTarget(handle)}.buildLicenseRequest(${q(challengeB64)},$ctx).then(r=>JSON.stringify(r));",
            "license_build.js",
        )
    }

    /** Module-owned license parsing: raw response bytes in, CDM license out. */
    suspend fun parseLicenseResponse(handle: ProviderHandle, responseB64: String): String =
        withEngine(handle) { js ->
            js.evaluate<String>(
                "${providerTarget(handle)}.parseLicenseResponse(${q(responseB64)},{ }).then(r=>JSON.stringify(r));",
                "license_parse.js",
            )
        }

    /** Cached per-module download policy (one engine call per process). */
    private val policyCache = ConcurrentHashMap<String, ModulePolicy>()

    suspend fun modulePolicy(handle: ProviderHandle): ModulePolicy? {
        policyCache[handle.id]?.let { return it }
        val raw = runCatching {
            withEngine(handle) { js ->
                js.evaluate<String>(
                    "${providerTarget(handle)}.getDownloadPolicy().then(r=>JSON.stringify(r));",
                    "policy.js",
                )
            }
        }.getOrNull() ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val labels = mutableMapOf<String, String>()
            o.optJSONObject("labels")?.keys()?.forEach { k -> labels[k] = o.optJSONObject("labels").optString(k) }
            ModulePolicy(
                transcode = o.optString("transcode", ""),
                labels = labels,
            ).also { policyCache[handle.id] = it }
        }.getOrNull()
    }

    private fun globalOf(handle: ProviderHandle): String =
        handle.manifest.global.ifBlank { "LastWaveProvider" }

    private suspend fun withEngine(
        handle: ProviderHandle,
        block: suspend (QuickJs) -> String,
    ): String = withContext(Dispatchers.IO) {
        val pooled = engines.getOrPut(handle.id) {
            PooledEngine(QuickJs.create(Dispatchers.IO))
        }
        pooled.lock.withLock {
            try {
                if (!pooled.loaded) {
                    val key = crypto.appKey() ?: error("Module key not provisioned")
                    val expected = handle.manifest.enc?.keyId
                    require(!expected.isNullOrBlank() && expected == crypto.appKeyId()) {
                        "Module key mismatch"
                    }
                    val source = String(
                        crypto.decrypt(manager.readEntryBytes(handle, handle.manifest.entryPoint), key),
                        Charsets.UTF_8,
                    )
                    bindBridge(pooled.js, handle)
                    pooled.js.evaluate<String>(namespaceScript(), "bridge.js")
                    pooled.js.evaluate<String>("$source\n\"__module_loaded__\";", "module.js")
                    val g = globalOf(handle)
                    pooled.js.evaluate<String>(
                        """
                        if (typeof globalThis.LastWave === 'undefined') globalThis.LastWave = {};
                        if (typeof globalThis['$g'] !== 'undefined') globalThis.LastWave['$g'] = globalThis['$g'];
                        if (typeof globalThis.LastWaveProvider !== 'undefined') {
                            globalThis.LastWave.LastWaveProvider = globalThis.LastWaveProvider;
                            if (typeof globalThis['$g'] === 'undefined') globalThis['$g'] = globalThis.LastWaveProvider;
                        }
                        "__globals_ready__";
                        """.trimIndent(),
                        "globals.js",
                    )
                    pooled.loaded = true
                }
                block(pooled.js)
            } catch (e: Exception) {
                Log.e(TAG, "Engine error in ${handle.id}: ${e.message}", e)
                engines.remove(handle.id, pooled)
                runCatching { pooled.js.close() }
                throw e
            }
        }
    }

    private fun namespaceScript(): String =
        "globalThis.LastWave=Object.assign(globalThis.LastWave||{},{" +
            "httpRequest:a=>globalThis.__lw_httpRequest(a)," +
            "rsaSign:a=>globalThis.__lw_rsaSign(a)," +
            "b64decode:a=>globalThis.__lw_b64decode(a)," +
            "b64encode:a=>globalThis.__lw_b64encode(a)," +
            "storeGet:a=>globalThis.__lw_storeGet(a)," +
            "storeSet:a=>globalThis.__lw_storeSet(a)," +
            "logWrite:a=>globalThis.__lw_logWrite(a)," +
            "uuid:()=>globalThis.__lw_uuid(\"\")," +
            "sleepMs:a=>globalThis.__lw_sleepMs(a)});" +
            "\"__bridge_ready__\";"

    private fun bindBridge(engine: QuickJs, handle: ProviderHandle) {
        fun strArg(args: Array<Any?>): String = args.firstOrNull() as? String ?: ""
        engine.bindGlobalFn<String>("__lw_httpRequest") { args: Array<Any?> -> httpRequest(strArg(args)) }
        engine.bindGlobalFn<String>("__lw_rsaSign") { args: Array<Any?> -> rsaSign(strArg(args)) }
        engine.bindGlobalFn<String>("__lw_b64decode") { args: Array<Any?> ->
            String(Base64.decode(strArg(args), Base64.DEFAULT), Charsets.UTF_8)
        }
        engine.bindGlobalFn<String>("__lw_b64encode") { args: Array<Any?> ->
            Base64.encodeToString(strArg(args).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
        engine.bindGlobalFn<String>("__lw_storeGet") { args: Array<Any?> ->
            manager.storeGet(handle, strArg(args)) ?: ""
        }
        engine.bindGlobalFn<String>("__lw_storeSet") { args: Array<Any?> -> storeSet(handle, strArg(args)) }
        engine.bindGlobalFn<String>("__lw_logWrite") { args: Array<Any?> -> logWrite(handle, strArg(args)) }
        engine.bindGlobalFn<String>("__lw_uuid") { _: Array<Any?> -> UUID.randomUUID().toString() }
        engine.bindGlobalFn<String>("__lw_sleepMs") { args: Array<Any?> ->
            val ms = runCatching { JSONObject(strArg(args)).optLong("ms", 0L) }.getOrDefault(0L)
            if (ms > 0) Thread.sleep(ms.coerceAtMost(10_000L))
            "ok"
        }
    }

    private fun httpRequest(reqJson: String): String {
        val req = JSONObject(reqJson)
        val timeoutMs = req.optLong("timeoutMs", 25_000L).coerceIn(1_000L, 60_000L)
        val builder = Request.Builder().url(req.getString("url"))
        val headers = req.optJSONObject("headers")
        headers?.keys()?.forEach { k -> builder.addHeader(k, headers.optString(k)) }
        val bodyStr = if (req.isNull("body")) null else req.optString("body", null)
        val contentType = (headers?.optString("content-type") ?: headers?.optString("Content-Type")
            ?: "application/json; charset=UTF-8").toMediaTypeOrNull()
        when (req.optString("method", "GET").uppercase()) {
            "POST" -> builder.post((bodyStr ?: "").toRequestBody(contentType))
            "PUT" -> builder.put((bodyStr ?: "").toRequestBody(contentType))
            "DELETE" -> if (bodyStr != null) builder.delete(bodyStr.toRequestBody(contentType)) else builder.delete()
            else -> builder.get()
        }
        val client = okHttp.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs + 5_000L, TimeUnit.MILLISECONDS)
            .build()
        client.newCall(builder.build()).execute().use { res ->
            val outHeaders = JSONObject()
            res.headers.forEach { (k, v) -> outHeaders.put(k.lowercase(), v) }
            return JSONObject()
                .put("status", res.code)
                .put("headers", outHeaders)
                .put("body", res.body?.string() ?: "")
                .toString()
        }
    }

    private fun rsaSign(reqJson: String): String {
        val req = JSONObject(reqJson)
        val data = req.getString("data")
        val pem = req.getString("pem")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        var keyBytes = Base64.decode(pem, Base64.DEFAULT)
        val privateKey = try {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        } catch (_: Exception) {
            keyBytes = pkcs1ToPkcs8(keyBytes)
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        }
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(data.toByteArray(Charsets.UTF_8))
        }
        return Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
    }

    private fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
        val header = byteArrayOf(
            0x30, 0x82.toByte(), 0x00, 0x00, 0x02, 0x01, 0x00, 0x30, 0x0D, 0x06, 0x09,
            0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01,
            0x01, 0x05, 0x00, 0x04, 0x82.toByte(), 0x00, 0x00,
        )
        val total = pkcs1.size + 22
        val out = header.copyOf()
        out[2] = ((total shr 8) and 0xFF).toByte()
        out[3] = (total and 0xFF).toByte()
        out[24] = ((pkcs1.size shr 8) and 0xFF).toByte()
        out[25] = (pkcs1.size and 0xFF).toByte()
        return out + pkcs1
    }

    private fun storeSet(handle: ProviderHandle, argJson: String): String {
        runCatching {
            val o = JSONObject(argJson)
            manager.storeSet(handle, o.getString("key"), o.optString("value", ""))
        }
        return "ok"
    }

    private fun logWrite(handle: ProviderHandle, argJson: String): String {
        runCatching {
            val o = JSONObject(argJson)
            Log.d(TAG, "[${handle.id}] ${o.optString("level", "info")}: ${o.optString("msg", "").take(500)}")
        }
        return "ok"
    }

    private fun q(raw: String): String = JSONObject.quote(raw)
}

/** Extension-owned download policy: the module decides, the host executes. */
data class ModulePolicy(
    val transcode: String = "",
    val labels: Map<String, String> = emptyMap(),
)
