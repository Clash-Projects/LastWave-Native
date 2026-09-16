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
import javax.inject.Inject

/**
 * Settings -> Provider Modules: install (.lwp), enable/disable, remove.
 * Resolution itself runs in the player; this screen only manages packages.
 */
@HiltViewModel
class ModulesViewModel @Inject constructor(
    private val moduleManager: ModuleManager,
    private val settingsPreferences: SettingsPreferences,
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

    fun setPreferModules(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { settingsPreferences.setPreferProviderModules(enabled) }
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
