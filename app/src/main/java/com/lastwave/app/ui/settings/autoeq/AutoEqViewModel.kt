package com.lastwave.app.ui.settings.autoeq

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.R
import com.lastwave.app.data.autoeq.AutoEqCatalog
import com.lastwave.app.data.autoeq.AutoEqController
import com.lastwave.app.data.autoeq.AutoEqEntry
import com.lastwave.app.data.autoeq.AutoEqIndexParser
import com.lastwave.app.data.autoeq.AutoEqMeasurement
import com.lastwave.app.data.autoeq.AutoEqState
import com.lastwave.app.data.autoeq.AutoEqTarget
import com.lastwave.app.data.autoeq.OutputDevice
import com.lastwave.app.data.autoeq.PeqFilter
import com.lastwave.app.data.autoeq.PeqFilterType
import com.lastwave.app.data.autoeq.PeqMath
import com.lastwave.app.data.autoeq.PeqSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AutoEqViewModel @Inject constructor(
    private val controller: AutoEqController,
    private val catalog: AutoEqCatalog,
) : ViewModel() {
    val enabled: StateFlow<Boolean> = controller.enabled
    val state: StateFlow<AutoEqState> = controller.state
    val busy: StateFlow<Boolean> = controller.busy
    val currentDevice: StateFlow<OutputDevice?> = controller.currentDevice
    val deviceProfiles: StateFlow<Map<String, AutoEqState>> = controller.deviceProfiles
    val deviceLabels: StateFlow<Map<String, String>> = controller.knownDeviceLabels
    val autoPerDevice: StateFlow<Boolean> = controller.autoPerDevice
    val targets: List<AutoEqTarget> = controller.availableTargets()

    private val _measurement = MutableStateFlow<AutoEqMeasurement?>(null)
    val measurement: StateFlow<AutoEqMeasurement?> = _measurement.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _results = MutableStateFlow<List<AutoEqEntry>>(emptyList())
    val results: StateFlow<List<AutoEqEntry>> = _results.asStateFlow()
    private val _indexState = MutableStateFlow(IndexState.IDLE)
    val indexState: StateFlow<IndexState> = _indexState.asStateFlow()
    private var entries: List<AutoEqEntry> = emptyList()
    private var searchJob: Job? = null

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    enum class IndexState { IDLE, LOADING, READY, ERROR }

    init {
        viewModelScope.launch {
            state.map { it.entry }.distinctUntilChanged().collect { entry ->
                _measurement.value = null
                if (entry != null) _measurement.value = controller.measurement(entry)
            }
        }
    }

    fun loadIndex(force: Boolean = false) {
        if (_indexState.value == IndexState.LOADING) return
        if (_indexState.value == IndexState.READY && !force) return
        _indexState.value = IndexState.LOADING
        viewModelScope.launch {
            try {
                entries = catalog.entries(forceRefresh = force)
                _indexState.value = IndexState.READY
                runSearch(_query.value)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                _indexState.value = IndexState.ERROR
            }
        }
    }

    fun setQuery(text: String) {
        _query.value = text
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(150)
            runSearch(text)
        }
    }

    private fun runSearch(text: String) {
        _results.value = AutoEqIndexParser.search(entries, text)
    }

    fun select(entry: AutoEqEntry, onDone: () -> Unit) {
        viewModelScope.launch {
            val result = controller.selectProfile(entry)
            if (result.isSuccess) {
                onDone()
                _messages.tryEmit(R.string.autoeq_msg_loaded)
            } else {
                _messages.tryEmit(R.string.autoeq_msg_download_failed)
            }
        }
    }

    fun setEnabled(on: Boolean) {
        controller.setEnabled(on)
    }

    fun setAutoPerDevice(on: Boolean) {
        controller.setAutoPerDevice(on)
    }

    fun setTarget(id: String) = viewModelScope.launch { controller.setTarget(id) }
    fun setBass(db: Double) = viewModelScope.launch { controller.setBass(db) }
    fun setTilt(value: Double) = viewModelScope.launch { controller.setTilt(value) }
    fun resetToProfile() = viewModelScope.launch { controller.resetToProfile() }
    fun startManual() = viewModelScope.launch { controller.startManual() }
    fun clearProfile() = viewModelScope.launch { controller.clearProfile() }

    fun updateFilter(index: Int, filter: PeqFilter) = editSettings { settings ->
        if (index !in settings.filters.indices) return@editSettings settings
        settings.copy(filters = settings.filters.toMutableList().apply { set(index, filter) })
    }

    fun addFilter() = editSettings { settings ->
        if (settings.filters.size >= PeqSettings.MAX_FILTERS) return@editSettings settings
        settings.copy(filters = settings.filters + PeqFilter(PeqFilterType.PEAKING, 1000.0, 0.0, 1.0))
    }

    fun removeFilter(index: Int) = editSettings { settings ->
        if (index !in settings.filters.indices) return@editSettings settings
        settings.copy(filters = settings.filters.toMutableList().apply { removeAt(index) })
    }

    fun setPreamp(db: Double) = editSettings { it.copy(preampDb = PeqMath.roundTo(db, 0.1)) }

    fun autoPreamp() = editSettings { it.copy(preampDb = PeqMath.autoPreampDb(it.filters)) }

    fun saveForCurrentDevice() = viewModelScope.launch {
        controller.saveForCurrentDevice()
        _messages.tryEmit(R.string.autoeq_msg_device_saved)
    }

    fun forgetDevice(key: String) = viewModelScope.launch { controller.forgetDevice(key) }

    fun targetDelta(state: AutoEqState): List<Double>? = controller.targetDelta(state)

    private fun editSettings(change: (PeqSettings) -> PeqSettings) = viewModelScope.launch {
        val current = state.value.settings
        val next = change(current)
        if (next != current) controller.setCustomSettings(next)
    }
}
