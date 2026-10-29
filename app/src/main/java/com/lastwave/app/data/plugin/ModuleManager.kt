package com.lastwave.app.data.plugin

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ModuleInstallResult {
    data class Installed(val module: InstalledProviderModule) : ModuleInstallResult
    data class Rejected(val reason: String) : ModuleInstallResult
}

/**
 * Installs / lists / removes provider modules (.lwp packages) — JSON-only.
 *
 * Addon holds ONLY encrypted config.json (url+secret, LWP2); all logic in app.
 * No JS eval. Layout: filesDir/provider_modules/<moduleId>/module.lwp + store/.
 * Accepted only if: valid zip, manifest parses, id present, entryPoint ==
 * "config.json", encrypted==true with enc.keyId matching native module key,
 * config.json exists + decrypts (AAD-bound) to JSON with baseUrl. Plaintext
 * and legacy provider.js packages are refused.
 */
@Singleton
class ModuleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: ModuleCrypto,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun rootDir(): File = File(context.filesDir, "provider_modules").apply { mkdirs() }
    private fun registryFile(): File = File(rootDir(), "registry.json")

    fun modulesDirFor(id: String): File = File(rootDir(), sanitizeId(id))

    suspend fun list(): List<InstalledProviderModule> = withContext(Dispatchers.IO) {
        ensureBundledModulesInstalled()
        readRegistry().modules
    }

    suspend fun enabledHandles(): List<ProviderHandle> = withContext(Dispatchers.IO) {
        ensureBundledModulesInstalled()
        readRegistry().modules
            .filter { it.enabled && it.manifest.isPlaybackEligible() }
            .mapNotNull { entry ->
                val dir = modulesDirFor(entry.manifest.id)
                if (File(dir, "module.lwp").isFile) ProviderHandle(entry.manifest.id, entry.manifest, dir)
                else null
            }
    }

    /** Handle for a manifest id regardless of enabled flag (license path). */
    suspend fun findHandleById(id: String): ProviderHandle? = withContext(Dispatchers.IO) {
        ensureBundledModulesInstalled()
        val entry = readRegistry().modules.firstOrNull { it.manifest.id == id } ?: return@withContext null
        val dir = modulesDirFor(entry.manifest.id)
        if (File(dir, "module.lwp").isFile) ProviderHandle(entry.manifest.id, entry.manifest, dir)
        else null
    }

    private fun ensureBundledModulesInstalled() {
        val assetFiles = runCatching { context.assets.list("modules") }.getOrNull() ?: return
        for (filename in assetFiles) {
            if (!filename.endsWith(".lwp", ignoreCase = true)) continue
            val tmp = File(context.cacheDir, "bundled_$filename")
            try {
                context.assets.open("modules/$filename").use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                val manifest = readManifest(tmp) ?: continue
                val dir = modulesDirFor(manifest.id)
                val moduleFile = File(dir, "module.lwp")
                val reg = readRegistry()
                val existing = reg.modules.firstOrNull { it.manifest.id == manifest.id }
                if (!moduleFile.isFile || existing == null || existing.manifest.versionCode < manifest.versionCode || moduleFile.length() != tmp.length()) {
                    dir.mkdirs()
                    File(dir, "store").mkdirs()
                    tmp.copyTo(moduleFile, overwrite = true)
                    val entry = InstalledProviderModule(manifest, enabled = existing?.enabled ?: true, installedAt = System.currentTimeMillis())
                    writeRegistry(reg.modules.filterNot { it.manifest.id == manifest.id } + entry)
                }
            } catch (e: Exception) {
                android.util.Log.w("ModuleManager", "Failed to auto-install bundled module $filename: ${e.message}")
            } finally {
                runCatching { tmp.delete() }
            }
        }
    }

    suspend fun install(uri: Uri): ModuleInstallResult = withContext(Dispatchers.IO) {
        // Key gated by native signature check; public forks get null -> reject.
        val tmp = File(context.cacheDir, "module_import_${System.currentTimeMillis()}.lwp")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return@withContext ModuleInstallResult.Rejected("Cannot read selected file")
            if (tmp.length() > 12 * 1024 * 1024) {
                return@withContext ModuleInstallResult.Rejected("Refused: package too large")
            }
            val manifest = readManifest(tmp)
                ?: return@withContext ModuleInstallResult.Rejected("Not a valid module: manifest.json missing or broken")
            if (manifest.id.isBlank() || manifest.entryPoint.isBlank()) {
                return@withContext ModuleInstallResult.Rejected("Module manifest missing id/entryPoint")
            }
            // JSON-only: legacy JS addons refused.
            if (manifest.entryPoint != "config.json") {
                return@withContext ModuleInstallResult.Rejected("Refused: legacy JS addon, need config.json")
            }
            if (!manifest.encrypted || manifest.enc?.keyId.isNullOrBlank()) {
                return@withContext ModuleInstallResult.Rejected("Refused: config is not encrypted")
            }
            if (manifest.enc?.alg != "AES-256-GCM" || manifest.enc?.format != "LWP2") {
                return@withContext ModuleInstallResult.Rejected("Refused: need LWP2 config")
            }
            if (manifest.enc?.files?.contains("config.json") != true) {
                return@withContext ModuleInstallResult.Rejected("Refused: config.json not in enc.files")
            }
            // loadKey constant-time binds keyId; null = wrong build / repack.
            val keyId = manifest.enc?.keyId
            val key = crypto.loadKey(keyId)
                ?: return@withContext ModuleInstallResult.Rejected("Refused: module key does not match this build")
            try {
                if (!zipHasEntry(tmp, "config.json")) {
                    key.fill(0)
                    return@withContext ModuleInstallResult.Rejected("config.json missing in package")
                }
                // Config must decrypt (LWP2 AAD-bound) to JSON with baseUrl.
                if (!canDecryptConfig(tmp, key)) {
                    key.fill(0)
                    return@withContext ModuleInstallResult.Rejected("Refused: config cannot be decrypted")
                }
                key.fill(0)
            } catch (error: Throwable) {
                key.fill(0)
                throw error
            }
            val dir = modulesDirFor(manifest.id).apply { mkdirs() }
            File(dir, "module.lwp").apply {
                if (exists()) delete()
                tmp.copyTo(this, overwrite = true)
            }
            File(dir, "store").apply { mkdirs() }
            val entry = InstalledProviderModule(manifest, enabled = true, installedAt = System.currentTimeMillis())
            writeRegistry(readRegistry().modules.filterNot { it.manifest.id == manifest.id } + entry)
            ModuleInstallResult.Installed(entry)
        } catch (e: Exception) {
            ModuleInstallResult.Rejected("Install failed: ${e.message?.take(120)}")
        } finally {
            runCatching { tmp.delete() }
        }
    }

    suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) {
        val ok = modulesDirFor(id).deleteRecursively()
        writeRegistry(readRegistry().modules.filterNot { it.manifest.id == id })
        ok
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        writeRegistry(readRegistry().modules.map {
            if (it.manifest.id == id) it.copy(enabled = enabled) else it
        })
    }

    fun readEntryBytes(handle: ProviderHandle, name: String): ByteArray {
        require(!name.contains("..") && !name.startsWith("/") && !name.startsWith("\\")) { "Bad entry" }
        ZipFile(File(handle.dir, "module.lwp")).use { zip ->
            val entry = zip.getEntry(name) ?: error("Missing $name in module")
            require(!entry.isDirectory && entry.size <= 8 * 1024 * 1024) { "Bad entry size" }
            return zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    fun readDecryptedEntry(handle: ProviderHandle, name: String): ByteArray? {
        if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) return null
        val keyId = handle.manifest.enc?.keyId ?: return null
        val key = crypto.loadKey(keyId) ?: return null
        return try {
            val raw = runCatching { readEntryBytes(handle, name) }.getOrNull()
                ?: run { key.fill(0); return null }
            if (raw.size > 8 * 1024 * 1024 + 64) { key.fill(0); return null }
            val pt = crypto.decryptEntry(raw, key, name)
            key.fill(0)
            pt
        } catch (_: Exception) {
            key.fill(0)
            null
        }
    }

    

    // -- store backing the JS storeGet/storeSet bridge -------------------------

    fun storeGet(handle: ProviderHandle, key: String): String? {
        val f = File(File(handle.dir, "store"), sanitizeKey(key))
        return if (f.isFile) runCatching { f.readText(Charsets.UTF_8) }.getOrNull() else null
    }

    fun storeSet(handle: ProviderHandle, key: String, value: String) {
        runCatching {
            File(File(handle.dir, "store"), sanitizeKey(key)).writeText(value, Charsets.UTF_8)
        }
    }

    // -- offline download sidecars (app-private, keyed by track) -----------------

    private fun offlineDirFor(title: String, artist: String): File =
        File(File(rootDir(), "offline"), sanitizeKey("${artist.trim().lowercase()}_${title.trim().lowercase()}"))

    fun writeOfflineSidecar(title: String, artist: String, sidecar: OfflineSidecar) {
        runCatching {
            val dir = offlineDirFor(title, artist).apply { mkdirs() }
            File(dir, "source.json").writeText(json.encodeToString(OfflineSidecar.serializer(), sidecar))
        }
    }

    fun readOfflineSidecar(title: String, artist: String): OfflineSidecar? {
        val f = File(offlineDirFor(title, artist), "source.json")
        if (!f.isFile) return null
        return runCatching { json.decodeFromString<OfflineSidecar>(f.readText()) }.getOrNull()
    }

    

    fun encodeDescriptor(descriptor: SegmentedStreamDescriptor): String =
        json.encodeToString(SegmentedStreamDescriptor.serializer(), descriptor)

    // -- internals ---------------------------------------------------------------

    private fun readRegistry(): ProviderRegistry {
        val f = registryFile()
        if (!f.isFile) return ProviderRegistry()
        return runCatching { json.decodeFromString<ProviderRegistry>(f.readText()) }
            .getOrDefault(ProviderRegistry())
    }

    private fun writeRegistry(modules: List<InstalledProviderModule>) {
        runCatching {
            registryFile().writeText(json.encodeToString(ProviderRegistry.serializer(), ProviderRegistry(modules)))
        }
    }

    private fun readManifest(lwp: File): ProviderManifest? {
        ZipFile(lwp).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: return null
            val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            return runCatching { json.decodeFromString<ProviderManifest>(text) }.getOrNull()
        }
    }

    private fun zipHasEntry(lwp: File, name: String): Boolean {
        ZipFile(lwp).use { zip -> return zip.getEntry(name) != null }
    }

    

    /** JSON-only gate: config.json must LWP2-decrypt to JSON with baseUrl. */
    private fun canDecryptConfig(lwp: File, key: ByteArray): Boolean {
        return try {
            ZipFile(lwp).use { zip ->
                val entry = zip.getEntry("config.json") ?: return false
                if (entry.isDirectory || entry.size > 64 * 1024 + 64) return false
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val pt = crypto.decryptEntry(bytes, key, "config.json")
                if (pt.isEmpty() || pt.size > 64 * 1024) return false
                val text = pt.toString(Charsets.UTF_8).trim()
                if (!text.startsWith("{")) return false
                val o = org.json.JSONObject(text)
                val url = o.optString("baseUrl").ifBlank {
                    o.optJSONObject("tidal")?.optString("baseUrl").orEmpty()
                }
                url.isNotBlank()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun sanitizeId(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "module" }

    private fun sanitizeKey(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifBlank { "k" }
}
