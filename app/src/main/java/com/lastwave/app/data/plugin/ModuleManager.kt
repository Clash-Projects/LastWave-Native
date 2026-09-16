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
 * Installs / lists / removes provider modules (.lwp packages).
 *
 * Layout: filesDir/provider_modules/<moduleId>/module.lwp + store/ (per-module
 * JS storage). Registry at provider_modules/registry.json. A package is
 * accepted only if: valid zip, manifest.json parses, id/entryPoint present,
 * encrypted==true with enc.keyId matching this build's module key, and the
 * entry point exists inside the zip. Plaintext (unencrypted) code is refused.
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
        readRegistry().modules
    }

    suspend fun enabledHandles(): List<ProviderHandle> = withContext(Dispatchers.IO) {        readRegistry().modules
            .filter { it.enabled && it.manifest.isPlaybackEligible() }
            .mapNotNull { entry ->
                val dir = modulesDirFor(entry.manifest.id)
                if (File(dir, "module.lwp").isFile) ProviderHandle(entry.manifest.id, entry.manifest, dir)
                else null
            }
    }

    /** Handle for a manifest id regardless of enabled flag (license path). */
    suspend fun findHandleById(id: String): ProviderHandle? = withContext(Dispatchers.IO) {
        val entry = readRegistry().modules.firstOrNull { it.manifest.id == id } ?: return@withContext null
        val dir = modulesDirFor(entry.manifest.id)
        if (File(dir, "module.lwp").isFile) ProviderHandle(entry.manifest.id, entry.manifest, dir)
        else null
    }

    suspend fun install(uri: Uri): ModuleInstallResult = withContext(Dispatchers.IO) {
        val key = crypto.appKey()
            ?: return@withContext ModuleInstallResult.Rejected("Module key not provisioned in this build")
        val expectedKeyId = crypto.appKeyId()
        val tmp = File(context.cacheDir, "module_import_${System.currentTimeMillis()}.lwp")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return@withContext ModuleInstallResult.Rejected("Cannot read selected file")
            val manifest = readManifest(tmp)
                ?: return@withContext ModuleInstallResult.Rejected("Not a valid module: manifest.json missing or broken")
            if (manifest.id.isBlank() || manifest.entryPoint.isBlank()) {
                return@withContext ModuleInstallResult.Rejected("Module manifest missing id/entryPoint")
            }
            if (!manifest.encrypted || manifest.enc?.keyId.isNullOrBlank()) {
                return@withContext ModuleInstallResult.Rejected("Refused: module code is not encrypted")
            }
            if (manifest.enc?.keyId != expectedKeyId) {
                return@withContext ModuleInstallResult.Rejected("Refused: module key does not match this build")
            }
            if (!zipHasEntry(tmp, manifest.entryPoint)) {
                return@withContext ModuleInstallResult.Rejected("Entry point ${manifest.entryPoint} missing in package")
            }
            // Code must actually decrypt before we trust the package.
            if (!canDecryptEntry(tmp, manifest.entryPoint, key)) {
                return@withContext ModuleInstallResult.Rejected("Refused: code cannot be decrypted with this build's key")
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

    fun readEntryBytes(handle: ProviderHandle, name: String): ByteArray {        ZipFile(File(handle.dir, "module.lwp")).use { zip ->
            val entry = zip.getEntry(name) ?: error("Missing $name in module")
            return zip.getInputStream(entry).use { it.readBytes() }
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

    fun deleteOfflineSidecar(title: String, artist: String) {
        runCatching { offlineDirFor(title, artist).deleteRecursively() }
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

    private fun canDecryptEntry(lwp: File, name: String, key: ByteArray): Boolean {
        return try {
            ZipFile(lwp).use { zip ->
                val entry = zip.getEntry(name) ?: return false
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                crypto.decrypt(bytes, key).isNotEmpty()
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
