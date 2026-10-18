package com.lastwave.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
)

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
)

enum class ApkVariant {
    UNCOMPRESSED,
    ANDROID7,
    UNIVERSAL,
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
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "4.3.1"
    } catch (_: Exception) {
        "4.3.1"
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

                val apkAssets = mutableListOf<ReleaseAsset>()
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "").trim()
                        val download = asset.optString("browser_download_url", "").trim()
                        if (name.endsWith(".apk", ignoreCase = true) && download.isNotBlank()) {
                            apkAssets.add(ReleaseAsset(name = name, downloadUrl = download))
                        }
                    }
                }

                val bestAsset = selectBestAsset(
                    assets = apkAssets,
                    isUncompressed = isUncompressedBuild(),
                    sdkInt = Build.VERSION.SDK_INT,
                )
                val downloadUrl = bestAsset?.downloadUrl ?: releaseUrl
                val downloadFileName = bestAsset?.name

                val currentVersion = getCurrentVersion()
                val hasNewer = isNewerVersion(tagName, currentVersion)
                val cleanTag = tagName.removePrefix("v").removePrefix("V")
                val dismissedVersion = prefs.getString("dismissed_version", null)
                val isDismissed = dismissedVersion == cleanTag

                _updateInfo.update {
                    it.copy(
                        isChecking = false,
                        isUpdateAvailable = hasNewer,
                        latestVersion = cleanTag,
                        currentVersion = currentVersion,
                        releaseNotes = releaseNotes,
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

    fun openUpdate(context: Context) {
        val targetUrl = _updateInfo.value.downloadUrl ?: _updateInfo.value.releaseUrl.ifBlank {
            "https://github.com/Clash-Projects/LastWave-native/releases"
        }
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
        fun classifyAsset(name: String): ApkVariant {
            val lower = name.lowercase()
            return when {
                lower.contains("uncompressed") || lower.contains("-raw") || lower.contains("_raw") -> ApkVariant.UNCOMPRESSED
                lower.contains("android7") || lower.contains("legacy") -> ApkVariant.ANDROID7
                else -> ApkVariant.UNIVERSAL
            }
        }

        fun selectBestAsset(
            assets: List<ReleaseAsset>,
            isUncompressed: Boolean,
            sdkInt: Int,
        ): ReleaseAsset? {
            val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && it.downloadUrl.isNotBlank() }
            if (apkAssets.isEmpty()) return null

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
            }
        }
    }
}
