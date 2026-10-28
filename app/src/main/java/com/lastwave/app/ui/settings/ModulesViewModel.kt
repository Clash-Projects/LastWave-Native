package com.lastwave.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.plugin.InstalledProviderModule
import com.lastwave.app.data.plugin.ModuleInstallResult
import com.lastwave.app.data.plugin.ModuleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.lastwave.app.data.lossless.LosslessMusicApi
import com.lastwave.app.data.lossless.NativeSecrets
import javax.inject.Inject

/**
 * Settings -> Provider Modules: install (.lwp), enable/disable, remove.
 * Resolution itself runs in the player; this screen only manages packages.
 */
@HiltViewModel
class ModulesViewModel @Inject constructor(
    private val moduleManager: ModuleManager,
    private val settingsPreferences: SettingsPreferences,
    private val nativeSecrets: NativeSecrets,
    private val losslessMusicApi: LosslessMusicApi,
) : ViewModel() {

    private val refreshTick = MutableStateFlow(0)

    val modules: StateFlow<List<InstalledProviderModule>> = refreshTick
        .flatMapLatest { flow { emit(moduleManager.list()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    val preferModules: StateFlow<Boolean> = settingsPreferences.settings
        .map { it.preferProviderModules }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val addonUrl: StateFlow<String?> = settingsPreferences.addonUrl
    val addonName: StateFlow<String?> = settingsPreferences.addonName
    val addonEnabled: StateFlow<Boolean> = settingsPreferences.addonEnabled
    val httpAddons: StateFlow<List<com.lastwave.app.data.local.HttpAddonConfig>> = settingsPreferences.httpAddons

    private val _addonHealth = MutableStateFlow<com.lastwave.app.data.addon.AddonHealth?>(null)
    val addonHealth: StateFlow<com.lastwave.app.data.addon.AddonHealth?> = _addonHealth.asStateFlow()

    private val _addonHealthMap = MutableStateFlow<Map<String, com.lastwave.app.data.addon.AddonHealth>>(emptyMap())
    val addonHealthMap: StateFlow<Map<String, com.lastwave.app.data.addon.AddonHealth>> = _addonHealthMap.asStateFlow()

    fun setPreferModules(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { settingsPreferences.setPreferProviderModules(enabled) }
        }
    }

    fun setAddonEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsPreferences.setAddonEnabled(enabled)
        }
    }

    fun toggleAddon(id: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsPreferences.toggleHttpAddon(id, enabled)
            losslessMusicApi.invalidateCredentialsCache()
        }
    }

    fun saveAddon(rawUrl: String) {
        saveAddon(null, rawUrl)
    }

    fun saveAddon(id: String?, rawUrl: String) {
        val trimmed = com.lastwave.app.data.addon.AddonClient.normalizeBase(rawUrl)
        if (trimmed.isBlank()) {
            _notice.value = "Addon URL cannot be empty"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            try {
                losslessMusicApi.invalidateCredentialsCache()
                val client = com.lastwave.app.data.addon.AddonClient(trimmed, nativeSecrets = nativeSecrets)
                val health = client.health()
                val manifestRes = client.manifest()
                val manifestName = manifestRes.getOrNull()?.displayName ?: "HTTP Addon"

                val current = httpAddons.value
                val targetId = if (id.isNullOrBlank()) {
                    val existing = if (current.size == 1) current.first() else null
                    if (existing != null) {
                        settingsPreferences.updateHttpAddon(existing.id, trimmed, manifestName)
                        existing.id
                    } else {
                        val added = settingsPreferences.addHttpAddon(trimmed, manifestName)
                        added.id
                    }
                } else {
                    settingsPreferences.updateHttpAddon(id, trimmed, manifestName)
                    id
                }

                _addonHealth.value = health
                _addonHealthMap.value = _addonHealthMap.value + (targetId to health)

                when (health) {
                    is com.lastwave.app.data.addon.AddonHealth.Ok -> _notice.value = "Connected: $manifestName"
                    is com.lastwave.app.data.addon.AddonHealth.Unreachable -> _notice.value = "Saved, but connection failed: ${health.reason}"
                    is com.lastwave.app.data.addon.AddonHealth.Rejected -> _notice.value = "Rejected: ${health.reason}"
                }
            } catch (e: Exception) {
                _notice.value = "Error connecting: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun removeAddon(id: String) {
        viewModelScope.launch {
            settingsPreferences.removeHttpAddon(id)
            _addonHealthMap.value = _addonHealthMap.value - id
            losslessMusicApi.invalidateCredentialsCache()
            _notice.value = "Addon removed"
        }
    }

    fun removeAddon() {
        val first = httpAddons.value.firstOrNull()
        if (first != null) {
            removeAddon(first.id)
        } else {
            viewModelScope.launch {
                settingsPreferences.setAddonUrl(null)
                settingsPreferences.setAddonName(null)
                settingsPreferences.setAddonEnabled(false)
                _addonHealth.value = null
                losslessMusicApi.invalidateCredentialsCache()
                _notice.value = "Addon removed"
            }
        }
    }

    fun testAddon(id: String) {
        val addon = httpAddons.value.firstOrNull { it.id == id } ?: return
        if (addon.url.isBlank()) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val health = com.lastwave.app.data.addon.AddonClient(addon.url, nativeSecrets = nativeSecrets).health()
                _addonHealthMap.value = _addonHealthMap.value + (id to health)
                _addonHealth.value = health
                _notice.value = when (health) {
                    is com.lastwave.app.data.addon.AddonHealth.Ok -> "${addon.name}: Active and reachable"
                    is com.lastwave.app.data.addon.AddonHealth.Unreachable -> "${addon.name}: Unreachable (${health.reason})"
                    is com.lastwave.app.data.addon.AddonHealth.Rejected -> "${addon.name}: Rejected (${health.reason})"
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun testAddon() {
        val first = httpAddons.value.firstOrNull()
        if (first != null) {
            testAddon(first.id)
        } else {
            val current = addonUrl.value
            if (current.isNullOrBlank()) return
            viewModelScope.launch {
                _busy.value = true
                try {
                    val health = com.lastwave.app.data.addon.AddonClient(current, nativeSecrets = nativeSecrets).health()
                    _addonHealth.value = health
                    _notice.value = when (health) {
                        is com.lastwave.app.data.addon.AddonHealth.Ok -> "Addon is active and reachable"
                        is com.lastwave.app.data.addon.AddonHealth.Unreachable -> "Addon unreachable: ${health.reason}"
                        is com.lastwave.app.data.addon.AddonHealth.Rejected -> "Addon rejected: ${health.reason}"
                    }
                } finally {
                    _busy.value = false
                }
            }
        }
    }

    fun install(uri: Uri) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _notice.value = null
            try {
                when (val result = moduleManager.install(uri)) {
                    is ModuleInstallResult.Installed ->
                        _notice.value = "Installed ${result.module.manifest.name}"
                    is ModuleInstallResult.Rejected ->
                        _notice.value = result.reason
                }
            } finally {
                _busy.value = false
                refreshTick.value += 1
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { moduleManager.setEnabled(id, enabled) }
            refreshTick.value += 1
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            val ok = runCatching { moduleManager.remove(id) }.getOrDefault(false)
            _notice.value = if (ok) "Module removed" else "Remove failed"
            refreshTick.value += 1
        }
    }

    fun clearNotice() {
        _notice.value = null
    }
}
