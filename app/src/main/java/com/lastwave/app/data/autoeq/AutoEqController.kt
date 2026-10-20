package com.lastwave.app.data.autoeq

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lastwave.app.playback.eq.ParametricEqNative
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything that defines the active correction.
 *
 * [base] is the lab's published profile; [settings] is what actually runs.
 * They differ when a target / bass / tilt is applied (refitted, not custom)
 * or when the user edits filters by hand ([isCustom]). Choosing the headphone
 * again restores the AutoEQ profile (non-custom).
 */
@Serializable
data class AutoEqState(
    val entry: AutoEqEntry? = null,
    val base: PeqSettings? = null,
    val settings: PeqSettings = PeqSettings(),
    val targetId: String = AutoEqTargets.DEFAULT_TARGET_ID,
    val bassDb: Double = 0.0,
    val tiltDbPerOctave: Double = 0.0,
    val isCustom: Boolean = false,
)

/** Current audio output, as the per-device association sees it. */
data class OutputDevice(val key: String, val label: String)

private val Context.autoEqDataStore: DataStore<Preferences> by preferencesDataStore(name = "autoeq_prefs")

@Singleton
class AutoEqController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalog: AutoEqCatalog,
    private val targets: AutoEqTargets,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val store get() = context.applicationContext.autoEqDataStore
    private val deviceMapSerializer = MapSerializer(String.serializer(), AutoEqState.serializer())

    private val data = store.data.catch { emit(emptyPreferences()) }

    val enabled: StateFlow<Boolean> = data.map { it[KEY_ENABLED] == true }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val state: StateFlow<AutoEqState> = data.map { prefs ->
        prefs[KEY_STATE]?.let { raw -> runCatching { json.decodeFromString(AutoEqState.serializer(), raw) }.getOrNull() }
            ?: AutoEqState()
    }.stateIn(scope, SharingStarted.Eagerly, AutoEqState())

    val autoPerDevice: StateFlow<Boolean> = data.map { it[KEY_AUTO_DEVICE] != false }
        .stateIn(scope, SharingStarted.Eagerly, true)

    val deviceProfiles: StateFlow<Map<String, AutoEqState>> = data.map { prefs ->
        prefs[KEY_DEVICES]?.let { raw -> runCatching { json.decodeFromString(deviceMapSerializer, raw) }.getOrNull() }
            ?: emptyMap()
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val deviceLabels = MutableStateFlow<Map<String, String>>(emptyMap())
    val knownDeviceLabels: StateFlow<Map<String, String>> = deviceLabels.asStateFlow()

    private val _currentDevice = MutableStateFlow<OutputDevice?>(null)
    val currentDevice: StateFlow<OutputDevice?> = _currentDevice.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val audioManager = context.getSystemService(AudioManager::class.java)

    init {
        scope.launch {
            combine(enabled, state) { on, current -> on to current.settings }
                .distinctUntilChanged()
                .collect { (on, settings) -> publish(on, settings) }
        }
        scope.launch {
            deviceLabels.value = data.first()[KEY_DEVICE_LABELS]
                ?.let { raw -> runCatching { json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw) }.getOrNull() }
                ?: emptyMap()
        }
        runCatching {
            audioManager?.registerAudioDeviceCallback(
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshDevice()
                    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshDevice()
                },
                Handler(Looper.getMainLooper()),
            )
        }
        refreshDevice()
    }

    private fun publish(on: Boolean, settings: PeqSettings) {
        ParametricEqNative.publish(
            enabled = on && !settings.isFlat,
            preampDb = settings.preampDb.toFloat(),
            filters = settings.filters.take(PeqSettings.MAX_FILTERS).map {
                ParametricEqNative.NativeFilter(
                    type = it.type.nativeId,
                    frequencyHz = it.frequencyHz.toFloat(),
                    gainDb = it.gainDb.toFloat(),
                    q = it.q.toFloat(),
                    enabled = it.enabled,
                )
            },
        )
    }

    fun setEnabled(on: Boolean) = scope.launch { store.edit { it[KEY_ENABLED] = on } }

    fun setAutoPerDevice(on: Boolean) = scope.launch { store.edit { it[KEY_AUTO_DEVICE] = on } }

    /** Downloads (or reuses) the lab profile and makes it current, keeping target/bass/tilt choices. */
    suspend fun selectProfile(entry: AutoEqEntry): Result<Unit> = runBusy<Unit> {
        val base = catalog.profile(entry)
        val previous = state.value
        val next = previous.copy(entry = entry, base = base, isCustom = false)
        writeState(next.copy(settings = computeSettings(next)))
        store.edit { it[KEY_ENABLED] = true }
    }

    suspend fun setTarget(targetId: String) = updateComputed { it.copy(targetId = targetId) }
    suspend fun setBass(db: Double) = updateComputed { it.copy(bassDb = db.coerceIn(-12.0, 12.0)) }
    suspend fun setTilt(dbPerOctave: Double) = updateComputed { it.copy(tiltDbPerOctave = dbPerOctave.coerceIn(-1.5, 1.5)) }

    /** Back to the AutoEQ profile (with the chosen target/bass/tilt), dropping manual edits. */
    suspend fun resetToProfile() = updateComputed { it }

    /** Manual edit: whatever the user sets runs as-is and the state becomes "Custom". */
    suspend fun setCustomSettings(settings: PeqSettings) {
        val clean = settings.copy(filters = settings.filters.take(PeqSettings.MAX_FILTERS))
        writeState(state.value.copy(settings = clean, isCustom = true))
    }

    /** Manual EQ without any headphone profile. */
    suspend fun startManual() {
        val current = state.value
        val seed = if (current.settings.filters.isNotEmpty()) current.settings else PeqSettings(
            filters = listOf(
                PeqFilter(PeqFilterType.LOW_SHELF, 105.0, 0.0, 0.7),
                PeqFilter(PeqFilterType.PEAKING, 1000.0, 0.0, 1.0),
                PeqFilter(PeqFilterType.HIGH_SHELF, 10000.0, 0.0, 0.7),
            ),
        )
        writeState(current.copy(settings = seed, isCustom = true))
        store.edit { it[KEY_ENABLED] = true }
    }

    suspend fun clearProfile() {
        writeState(AutoEqState())
    }

    suspend fun measurement(entry: AutoEqEntry): AutoEqMeasurement? = runCatching { catalog.measurement(entry) }.getOrNull()

    /** Associates the current state with the current output device. */
    suspend fun saveForCurrentDevice() {
        val device = currentDevice.value ?: return
        val map = deviceProfiles.value.toMutableMap()
        map[device.key] = state.value
        val labels = deviceLabels.value.toMutableMap().apply { put(device.key, device.label) }
        deviceLabels.value = labels
        store.edit {
            it[KEY_DEVICES] = json.encodeToString(deviceMapSerializer, map)
            it[KEY_DEVICE_LABELS] = json.encodeToString(MapSerializer(String.serializer(), String.serializer()), labels)
        }
    }

    suspend fun forgetDevice(key: String) {
        val map = deviceProfiles.value - key
        store.edit { it[KEY_DEVICES] = json.encodeToString(deviceMapSerializer, map) }
    }

    /** Target curve on the bundled grid, for the chart. */
    fun targetDelta(state: AutoEqState): List<Double>? {
        val entry = state.entry ?: return null
        return targets.byId(state.targetId)?.delta(entry.formFactor)
    }

    fun availableTargets(): List<AutoEqTarget> = targets.file.targets

    private suspend fun updateComputed(change: (AutoEqState) -> AutoEqState) {
        runBusy {
            val next = change(state.value).copy(isCustom = false)
            writeState(next.copy(settings = computeSettings(next)))
        }
    }

    /** Lab profile when nothing is adjusted, else a refit of profile + target delta + bass + tilt. */
    private suspend fun computeSettings(s: AutoEqState): PeqSettings = withContext(Dispatchers.Default) {
        val base = s.base ?: return@withContext s.settings
        val entry = s.entry
        val delta = if (entry != null && s.targetId != AutoEqTargets.DEFAULT_TARGET_ID) {
            targets.byId(s.targetId)?.delta(entry.formFactor)
        } else {
            null
        }
        if (delta == null && s.bassDb == 0.0 && s.tiltDbPerOctave == 0.0) return@withContext base
        val grid = PeqMath.LOG_GRID
        val desired = DoubleArray(grid.size) { i ->
            val f = grid[i]
            val value = PeqMath.filtersDb(base.filters, f) +
                (delta?.getOrNull(i) ?: 0.0) +
                PeqMath.bassShelfDb(s.bassDb, f) +
                PeqMath.tiltDb(s.tiltDbPerOctave, f)
            value.coerceIn(-15.0, 12.0)
        }
        PeqFitter.fit(desired, grid)
    }

    private suspend fun writeState(next: AutoEqState) {
        store.edit { it[KEY_STATE] = json.encodeToString(AutoEqState.serializer(), next) }
    }

    private suspend fun <T> runBusy(block: suspend () -> T): Result<T> {
        _busy.value = true
        return try {
            Result.success(block())
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "AutoEQ action failed", error)
            Result.failure(error)
        } finally {
            _busy.value = false
        }
    }

    private fun refreshDevice() {
        val device = runCatching { pickOutput() }.getOrNull()
        val previous = _currentDevice.value
        _currentDevice.value = device
        if (device == null || device.key == previous?.key) return
        scope.launch {
            if (!autoPerDevice.value) return@launch
            val saved = deviceProfiles.value[device.key] ?: return@launch
            Log.i(TAG, "Output ${device.label}: applying its AutoEQ profile")
            writeState(saved)
        }
    }

    /** Same priority Android uses for media: Bluetooth > USB > wired > speaker. */
    private fun pickOutput(): OutputDevice? {
        val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return null
        fun rank(type: Int): Int = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> 0
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> 1
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> 2
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 3
            else -> 9
        }
        val best = devices.filter { rank(it.type) < 9 }.minByOrNull { rank(it.type) } ?: return null
        val name = best.productName?.toString()?.trim().orEmpty()
        val kind = when (rank(best.type)) {
            0 -> "bt"
            1 -> "usb"
            2 -> "wired"
            else -> "speaker"
        }
        val label = when (kind) {
            "wired" -> name.ifBlank { "Wired headphones" }
            "speaker" -> "Phone speaker"
            else -> name.ifBlank { kind.uppercase() }
        }
        // Wired jacks report the phone model as product name: one key for all.
        val key = when (kind) {
            "wired" -> "wired"
            "speaker" -> "speaker"
            else -> "$kind:${name.lowercase()}"
        }
        return OutputDevice(key, label)
    }

    companion object {
        private const val TAG = "AutoEqController"
        private val KEY_ENABLED = booleanPreferencesKey("enabled")
        private val KEY_STATE = stringPreferencesKey("state")
        private val KEY_AUTO_DEVICE = booleanPreferencesKey("auto_per_device")
        private val KEY_DEVICES = stringPreferencesKey("device_profiles")
        private val KEY_DEVICE_LABELS = stringPreferencesKey("device_labels")
    }
}
