package com.lastwave.app.data.plugin

import kotlinx.serialization.Serializable

/**
 * Manifest of an installed provider module (.lwp package).
 * Field names mirror the module-spec schema.json; providers stay anonymous
 * to the host — only id/name/version/capabilities are surfaced in UI.
 */
@Serializable
data class ProviderManifest(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val versionCode: Int = 0,
    val description: String = "",
    val author: String = "",
    val icon: String = "",
    val entryPoint: String = "provider.js",
    /** JS global the host calls (e.g. "LastWaveProvider"); never provider-named. */
    val global: String = "LastWaveProvider",
    val targetApi: Int = 1,
    val capabilities: List<String> = emptyList(),
    val encrypted: Boolean = false,
    val enc: ProviderEncInfo? = null,
)

@Serializable
data class ProviderEncInfo(
    val alg: String = "",
    val format: String = "",
    val files: List<String> = emptyList(),
    val keyId: String = "",
    val bridge: Int = 0,
)

/** One installed module: manifest snapshot + user toggle. */
@Serializable
data class InstalledProviderModule(
    val manifest: ProviderManifest,
    val enabled: Boolean = true,
    val installedAt: Long = 0L,
)

/** Registry persisted next to the installed packages. */
@Serializable
data class ProviderRegistry(
    val modules: List<InstalledProviderModule> = emptyList(),
)

/** Live handle handed to the runner (dir holds module.lwp + store/). */
data class ProviderHandle(
    val id: String,
    val manifest: ProviderManifest,
    val dir: java.io.File,
)

/**
 * Offline record for a downloaded module track. Lives in app-private storage
 * keyed by track (no Room migration); the audio bytes live in MediaStore.
 */
@Serializable
data class OfflineSidecar(
    val descriptorJson: String = "",
    val keySetIdB64: String = "",
    val licenseUrl: String = "",
    val licenseExpiresAtMs: Long = 0L,
    val audioFilePath: String = "",
    val mediaStoreUri: String = "",
    val bytes: Long = 0L,
    val downloadedAtMs: Long = 0L,
)

fun ProviderManifest.isPlaybackEligible(): Boolean =
    id.isNotBlank() && entryPoint.isNotBlank() &&
        capabilities.contains("search") && capabilities.contains("playback")
