package com.lastwave.app.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.lastwave.app.BuildConfig
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
import java.io.File
import java.security.MessageDigest
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
    /** GitHub's "sha256:<hex>" for the chosen APK; null means the in-app install is unavailable. */
    val downloadDigest: String? = null,
    /** 0..100 while the APK downloads inside the app, null otherwise. */
    val downloadProgress: Int? = null,
    val readyToInstall: Boolean = false,
    val isInstalling: Boolean = false,
    val installError: String? = null,
)

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long = 0L,
    val digest: String? = null,
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
        // A previous update's APK is no longer needed once the app restarts.
        scope.launch { File(context.cacheDir, "updates").deleteRecursively() }
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
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
    } catch (_: Exception) {
        "1.0.0"
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
                    .url("https://api.github.com/repos/1f81-official/SoundDrop/releases/latest")
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "SoundDrop-Android")
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
                val releaseUrl = json.optString("html_url", "https://github.com/1f81-official/SoundDrop/releases")
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
                                    digest = if (asset.isNull("digest")) null else asset.optString("digest"),
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
                        downloadDigest = bestAsset?.digest,
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
    @Volatile private var activeDownload: okhttp3.Call? = null
    @Volatile private var downloadedApk: File? = null

    /**
     * Update now, as in Metrolist: downloads this build's APK inside the app, checks it against
     * GitHub's SHA-256 and our package name, then hands it to the system installer.
     * A release without a digest (or without an APK for this build) falls back to the browser.
     */
    fun startUpdate(context: Context) {
        val info = _updateInfo.value
        val url = info.downloadUrl
        val fileName = info.downloadFileName
        val digest = info.downloadDigest
        if (fileName == null || url.isNullOrBlank() || digest == null) return openDownload(context)
        // Before Android 8 unknown sources is a global setting the installer prompt checks itself.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            _updateInfo.update { it.copy(installError = "Allow LastWave to install apps, then tap Update again") }
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        if (info.downloadProgress != null || info.isInstalling) return
        if (info.readyToInstall && downloadedApk?.isFile == true) return install()
        _updateInfo.update { it.copy(downloadProgress = 0, installError = null, readyToInstall = false) }
        scope.launch {
            val result = runCatching { download(url, fileName, info.downloadSizeBytes, digest) }
            val file = result.getOrNull()
            val canceled = activeDownload?.isCanceled() == true
            activeDownload = null
            if (file == null) {
                _updateInfo.update {
                    it.copy(
                        downloadProgress = null,
                        installError = if (canceled) null
                        else "Download failed: ${result.exceptionOrNull()?.message ?: "network error"}",
                    )
                }
            } else {
                downloadedApk = file
                _updateInfo.update { it.copy(downloadProgress = null, readyToInstall = true) }
                install()
            }
        }
    }

    fun cancelDownload() {
        activeDownload?.cancel()
    }

    private fun download(url: String, fileName: String, expectedSize: Long, digest: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach(File::delete)
        val partial = File(dir, "$fileName.part")
        val output = File(dir, fileName)
        val request = Request.Builder().url(url).header("User-Agent", "LastWave-Android").build()
        val call = okHttpClient.newCall(request).also { activeDownload = it }
        call.execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = checkNotNull(response.body)
            val total = expectedSize.takeIf { it > 0 } ?: body.contentLength()
            check(dir.usableSpace > total + MIN_FREE_SPACE_BYTES) { "not enough storage" }
            var downloaded = 0L
            body.byteStream().use { input ->
                partial.outputStream().buffered().use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        check(expectedSize <= 0 || downloaded <= expectedSize) { "file is larger than the release says" }
                        target.write(buffer, 0, count)
                        if (total > 0) {
                            val progress = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            _updateInfo.update { if (it.downloadProgress == progress) it else it.copy(downloadProgress = progress) }
                        }
                    }
                }
            }
            check(expectedSize <= 0 || downloaded == expectedSize) { "incomplete download" }
        }
        if (!digestMatches(partial, digest)) {
            partial.delete()
            error("checksum mismatch")
        }
        // A wrong-package APK would install as a second app instead of updating this one.
        // The system installer itself rejects an APK signed with a different key.
        val archive = context.packageManager.getPackageArchiveInfo(partial.absolutePath, 0)
        if (archive?.packageName != context.packageName) {
            partial.delete()
            error("APK is not for this app")
        }
        check(partial.renameTo(output)) { "could not save the update" }
        return output
    }

    private fun install() {
        val file = downloadedApk?.takeIf { it.isFile } ?: run {
            _updateInfo.update { it.copy(readyToInstall = false) }
            return
        }
        _updateInfo.update { it.copy(isInstalling = true, installError = null) }
        scope.launch {
            val installer = context.packageManager.packageInstaller
            var sessionId = -1
            runCatching {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setSize(file.length())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                }
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    file.inputStream().use { input ->
                        session.openWrite("base.apk", 0, file.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                    // FLAG_MUTABLE: the installer fills in the status extras.
                    val callback = PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        Intent(context, UpdateInstallReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(callback.intentSender)
                }
            }.onFailure { e ->
                if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
                _updateInfo.update { it.copy(isInstalling = false, installError = "Install failed: ${e.message}") }
            }
        }
    }

    /** Called by [UpdateInstallReceiver]; on success Android replaces and restarts the app. */
    internal fun onInstallResult(status: Int, message: String?) {
        _updateInfo.update {
            when (status) {
                PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> it.copy(isInstalling = false)
                else -> it.copy(isInstalling = false, installError = "Install failed" + (message?.let { m -> ": $m" } ?: ""))
            }
        }
    }

    /** Opens this build's APK download in the browser; used when the in-app install can't run. */
    fun openDownload(context: Context) {
        val targetUrl = _updateInfo.value.downloadUrl?.takeIf { it.isNotBlank() }
            ?: return openReleasePage(context)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) { }
    }

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
        private const val MIN_FREE_SPACE_BYTES = 16L * 1024 * 1024

        /** [digest] is GitHub's asset digest, "sha256:<64 hex>". Anything else never matches. */
        fun digestMatches(file: File, digest: String): Boolean {
            val expected = digest.takeIf { it.matches(Regex("sha256:[0-9a-fA-F]{64}")) }
                ?.removePrefix("sha256:") ?: return false
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    md.update(buffer, 0, count)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
        }

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
