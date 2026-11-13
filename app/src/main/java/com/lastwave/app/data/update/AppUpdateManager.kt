package com.lastwave.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.lastwave.app.BuildConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val isChecking: Boolean = false,
    val isUpdateAvailable: Boolean = false,
    val latestVersion: String = "",
    val currentVersion: String = "",
    val releaseNotes: String = "",
    val releaseUrl: String = "",
    val downloadUrl: String? = null,
    val downloadFileName: String? = null,
    val isDismissed: Boolean = false,
    val message: String? = null,
    /** Release title and publish date, shown in the update dialog. */
    val releaseTitle: String = "",
    val publishedAt: String = "",
    val downloadSizeBytes: Long = 0L,
    /** The in-app update dialog is open (on launch, or from Settings / the banner). */
    val showDialog: Boolean = false,
    val download: UpdateDownload = UpdateDownload.Idle,
)

/** The in-app APK download, from the first byte to the system installer. */
sealed interface UpdateDownload {
    data object Idle : UpdateDownload
    data class Downloading(val receivedBytes: Long, val totalBytes: Long) : UpdateDownload
    /** Downloaded and verified; the installer prompt was (or can be) opened. */
    data class Ready(val filePath: String) : UpdateDownload
    data class Failed(val message: String) : UpdateDownload
}

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long = 0L,
)

enum class ApkVariant {
    UNCOMPRESSED,
    ANDROID7,
    UNIVERSAL,
    /** vivo Origin Island build (separate package); never mixed with the others. */
    VIVO_ORIGIN,
}

@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("lastwave_updates", Context.MODE_PRIVATE)

    private val _updateInfo = MutableStateFlow(
        UpdateInfo(
            currentVersion = getCurrentVersion(),
        )
    )
    val updateInfo: StateFlow<UpdateInfo> = _updateInfo.asStateFlow()

    init {
        checkForUpdate(isSilent = true)
    }

    fun isUncompressedBuild(): Boolean {
        val pref = prefs.getString("preferred_variant", null)
        if (pref != null) {
            return pref.equals("uncompressed", ignoreCase = true) || pref.equals("raw", ignoreCase = true)
        }
        val buildType = runCatching { BuildConfig.BUILD_TYPE }.getOrDefault("")
        return buildType.equals("rawRelease", ignoreCase = true) ||
               buildType.contains("raw", ignoreCase = true) ||
               buildType.contains("uncompressed", ignoreCase = true)
    }

    fun getPreferredVariant(): String? = prefs.getString("preferred_variant", null)

    fun setPreferredVariant(variant: String?) {
        if (variant == null) {
            prefs.edit().remove("preferred_variant").apply()
        } else {
            prefs.edit().putString("preferred_variant", variant).apply()
        }
        checkForUpdate(isSilent = true)
    }

    fun getCurrentVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "5.0.0"
    } catch (_: Exception) {
        "5.0.0"
    }

    fun checkForUpdate(isSilent: Boolean = false) {
        scope.launch {
            _updateInfo.update {
                it.copy(
                    isChecking = true,
                    message = if (!isSilent) "Checking for updates..." else it.message,
                )
            }
            try {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/Clash-Projects/LastWave-native/releases/latest")
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "LastWave-Android")
                    .build()

                val response = withContext(Dispatchers.IO) {
                    okHttpClient.newCall(request).execute()
                }

                if (!response.isSuccessful) {
                    val code = response.code
                    _updateInfo.update {
                        it.copy(
                            isChecking = false,
                            message = if (!isSilent) "Could not check updates (HTTP $code)" else null,
                        )
                    }
                    return@launch
                }

                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "")
                val releaseUrl = json.optString("html_url", "https://github.com/Clash-Projects/LastWave-native/releases")
                val releaseNotes = json.optString("body", "")
                val releaseTitle = json.optString("name", "")
                val publishedAt = json.optString("published_at", "")

                val apkAssets = mutableListOf<ReleaseAsset>()
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "").trim()
                        val download = asset.optString("browser_download_url", "").trim()
                        if (name.endsWith(".apk", ignoreCase = true) && download.isNotBlank()) {
                            apkAssets.add(
                                ReleaseAsset(
                                    name = name,
                                    downloadUrl = download,
                                    sizeBytes = asset.optLong("size", 0L),
                                ),
                            )
                        }
                    }
                }

                val bestAsset = selectBestAsset(
                    assets = apkAssets,
                    isUncompressed = isUncompressedBuild(),
                    sdkInt = Build.VERSION.SDK_INT,
                    isVivoOrigin = BuildConfig.VIVO_ORIGIN,
                )
                val downloadUrl = bestAsset?.downloadUrl ?: releaseUrl
                val downloadFileName = bestAsset?.name

                val currentVersion = getCurrentVersion()
                val hasNewer = isNewerVersion(tagName, currentVersion)
                val cleanTag = tagName.removePrefix("v").removePrefix("V")
                val dismissedVersion = prefs.getString("dismissed_version", null)
                val isDismissed = dismissedVersion == cleanTag

                // Like the desktop app: a new, not-dismissed release opens the
                // update dialog by itself once per launch.
                val autoOpen = isSilent && hasNewer && !isDismissed && !autoDialogShown
                if (autoOpen) autoDialogShown = true
                _updateInfo.update {
                    it.copy(
                        isChecking = false,
                        isUpdateAvailable = hasNewer,
                        latestVersion = cleanTag,
                        currentVersion = currentVersion,
                        releaseNotes = releaseNotes,
                        releaseTitle = releaseTitle,
                        publishedAt = publishedAt,
                        downloadSizeBytes = bestAsset?.sizeBytes ?: 0L,
                        showDialog = it.showDialog || autoOpen,
                        releaseUrl = releaseUrl,
                        downloadUrl = downloadUrl,
                        downloadFileName = downloadFileName,
                        isDismissed = isDismissed,
                        message = if (!isSilent) {
                            if (hasNewer) "New version $cleanTag available!" else "You're on the latest version ($currentVersion)"
                        } else null,
                    )
                }
            } catch (e: Exception) {
                _updateInfo.update {
                    it.copy(
                        isChecking = false,
                        message = if (!isSilent) "Check failed: ${e.message ?: "Network error"}" else null,
                    )
                }
            }
        }
    }

    fun dismissUpdate(version: String) {
        val clean = version.removePrefix("v").removePrefix("V")
        prefs.edit().putString("dismissed_version", clean).apply()
        _updateInfo.update { it.copy(isDismissed = true) }
    }

    /** Settings' "Update Ready" and the banner open the in-app dialog, not the browser. */
    fun openUpdate(context: Context) = showDialog()

    fun showDialog() = _updateInfo.update { it.copy(showDialog = true) }

    fun hideDialog() = _updateInfo.update { it.copy(showDialog = false) }

    /** The release page, for the dialog's GitHub button. */
    fun openReleasePage(context: Context) {
        val targetUrl = _updateInfo.value.releaseUrl.ifBlank {
            "https://github.com/Clash-Projects/LastWave-native/releases"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) { }
    }

    @Volatile private var autoDialogShown = false
    private var downloadJob: Job? = null

    /**
     * Downloads this build's APK from the release, checks it really is a newer
     * build of this app, then hands it to the system installer. The installer
     * itself refuses an APK signed with a different key.
     */
    fun startDownload() {
        val info = _updateInfo.value
        val url = info.downloadUrl
        val fileName = info.downloadFileName
        if (url.isNullOrBlank() || fileName.isNullOrBlank()) {
            // The release has no APK for this build: the release page is all there is.
            openReleasePage(context)
            return
        }
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            val dir = File(context.cacheDir, UPDATE_DIR).apply {
                mkdirs()
                listFiles()?.forEach { runCatching { it.delete() } }
            }
            val part = File(dir, "$fileName.part")
            val target = File(dir, fileName)
            setDownload(UpdateDownload.Downloading(0L, info.downloadSizeBytes))
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "LastWave-Android")
                    .build()
                // A ~30 MB file: no overall call deadline, only a stalled-read one.
                val client = okHttpClient.newBuilder()
                    .callTimeout(0, TimeUnit.MILLISECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code})")
                    val body = response.body ?: throw IOException("Download was empty")
                    val total = body.contentLength().takeIf { it > 0L } ?: info.downloadSizeBytes
                    body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var received = 0L
                            var reported = 0L
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                received += read
                                if (received - reported >= PROGRESS_STEP_BYTES) {
                                    reported = received
                                    setDownload(UpdateDownload.Downloading(received, total))
                                }
                            }
                            if (total > 0L && received != total) {
                                throw IOException("Download incomplete ($received of $total bytes)")
                            }
                        }
                    }
                }
                verifyDownloadedApk(part)
                if (!part.renameTo(target)) throw IOException("Could not save the update")
                setDownload(UpdateDownload.Ready(target.path))
                installDownloaded()
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                runCatching { part.delete() }
                setDownload(UpdateDownload.Idle)
                throw cancellation
            } catch (error: Exception) {
                runCatching { part.delete() }
                setDownload(UpdateDownload.Failed(error.message ?: "Download failed"))
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    /**
     * Opens the system installer for the downloaded APK. Without the "install
     * unknown apps" grant it opens that setting first; tapping Install again
     * after allowing it continues.
     */
    fun installDownloaded() {
        val ready = _updateInfo.value.download as? UpdateDownload.Ready ?: return
        val file = File(ready.filePath)
        if (!file.exists()) {
            setDownload(UpdateDownload.Failed("The downloaded update is gone; download it again"))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            _updateInfo.update { it.copy(message = "Allow LastWave to install apps, then tap Install") }
            runCatching {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { error ->
            setDownload(UpdateDownload.Failed("Could not open the installer: ${error.message ?: "unknown error"}"))
        }
    }

    /** Only ever offer the installer a newer build of this very app. */
    private fun verifyDownloadedApk(apk: File) {
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: throw IOException("The download is not a valid APK")
        if (archive.packageName != context.packageName) {
            throw IOException("The download is a different app (${archive.packageName})")
        }
        val version = archive.versionName.orEmpty()
        if (!isNewerVersion(version, getCurrentVersion())) {
            throw IOException("The download ($version) is not newer than this version")
        }
    }

    private fun setDownload(state: UpdateDownload) = _updateInfo.update { it.copy(download = state) }

    fun isNewerVersion(remote: String, local: String): Boolean {
        if (remote.isBlank() || local.isBlank()) return false
        val cleanRemote = remote.removePrefix("v").removePrefix("V").substringBefore("-")
        val cleanLocal = local.removePrefix("v").removePrefix("V").substringBefore("-")
        if (cleanRemote == cleanLocal) return false

        val rParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val cParts = cleanLocal.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    companion object {
        /** Under the FileProvider's cache-path "shared/", so the installer can read it. */
        private const val UPDATE_DIR = "shared/updates"
        private const val PROGRESS_STEP_BYTES = 256L * 1024

        fun classifyAsset(name: String): ApkVariant {
            val lower = name.lowercase()
            return when {
                lower.contains("vivo") -> ApkVariant.VIVO_ORIGIN
                lower.contains("uncompressed") || lower.contains("-raw") || lower.contains("_raw") -> ApkVariant.UNCOMPRESSED
                lower.contains("android7") || lower.contains("legacy") -> ApkVariant.ANDROID7
                else -> ApkVariant.UNIVERSAL
            }
        }

        fun selectBestAsset(
            assets: List<ReleaseAsset>,
            isUncompressed: Boolean,
            sdkInt: Int,
            isVivoOrigin: Boolean = false,
        ): ReleaseAsset? {
            // The vivo build is a different package: installing the other APK
            // would add a second app instead of updating, so each build only
            // ever picks from its own pool.
            val apkAssets = assets.filter {
                it.name.endsWith(".apk", ignoreCase = true) && it.downloadUrl.isNotBlank() &&
                    (classifyAsset(it.name) == ApkVariant.VIVO_ORIGIN) == isVivoOrigin
            }
            if (apkAssets.isEmpty()) return null
            if (isVivoOrigin) return apkAssets.first()

            val target = when {
                sdkInt < 29 -> ApkVariant.ANDROID7
                isUncompressed -> ApkVariant.UNCOMPRESSED
                else -> ApkVariant.UNIVERSAL
            }

            // Direct match for target variant, preferring "universal" naming if present
            val matches = apkAssets.filter { classifyAsset(it.name) == target }
            if (matches.isNotEmpty()) {
                return matches.maxByOrNull { asset ->
                    val lower = asset.name.lowercase()
                    when (target) {
                        ApkVariant.UNIVERSAL -> if (lower.contains("universal")) 2 else 1
                        ApkVariant.UNCOMPRESSED -> if (lower.contains("universal")) 2 else 1
                        ApkVariant.ANDROID7 -> if (lower.contains("android7")) 2 else 1
                        ApkVariant.VIVO_ORIGIN -> 1
                    }
                }
            }

            // Fallback hierarchy if target variant was not present in this release:
            return when (target) {
                ApkVariant.UNCOMPRESSED -> {
                    apkAssets.firstOrNull { classifyAsset(it.name) == ApkVariant.UNIVERSAL }
                        ?: apkAssets.firstOrNull { classifyAsset(it.name) == ApkVariant.ANDROID7 }
                        ?: apkAssets.firstOrNull()
                }
                ApkVariant.UNIVERSAL -> {
                    apkAssets.firstOrNull { classifyAsset(it.name) == ApkVariant.UNCOMPRESSED }
                        ?: apkAssets.firstOrNull { classifyAsset(it.name) == ApkVariant.ANDROID7 }
                        ?: apkAssets.firstOrNull()
                }
                ApkVariant.ANDROID7 -> {
                    apkAssets.firstOrNull { classifyAsset(it.name) == ApkVariant.UNIVERSAL }
                        ?: apkAssets.firstOrNull()
                }
                ApkVariant.VIVO_ORIGIN -> apkAssets.firstOrNull()
            }
        }
    }
}
