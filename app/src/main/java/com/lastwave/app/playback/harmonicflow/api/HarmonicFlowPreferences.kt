package com.lastwave.app.playback.harmonicflow.api

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class TransitionDurationMode {
    AUTO,
    MANUAL,
}

enum class ThermalProfile {
    ECO,
    BALANCED,
    PERFORMANCE,
}

data class HarmonicFlowSettings(
    val harmonicFlowEnabled: Boolean = false,
    val durationMode: TransitionDurationMode = TransitionDurationMode.AUTO,
    val targetDurationSeconds: Int = 12, // 12s..30s, default 12s
    val spectralFilterSweepsEnabled: Boolean = true,
    val keepAlbumsContinuous: Boolean = true,
    val thermalProfile: ThermalProfile = ThermalProfile.BALANCED,
)

private val Context.harmonicFlowDataStore by preferencesDataStore(name = "harmonic_flow_prefs")

@Singleton
class HarmonicFlowPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("harmonic_flow_enabled")
        val DURATION_MODE = stringPreferencesKey("harmonic_flow_duration_mode")
        val TARGET_SECONDS = intPreferencesKey("harmonic_flow_target_seconds")
        val SPECTRAL_SWEEPS = booleanPreferencesKey("harmonic_flow_spectral_sweeps")
        val KEEP_ALBUMS = booleanPreferencesKey("harmonic_flow_keep_albums")
        val THERMAL_PROFILE = stringPreferencesKey("harmonic_flow_thermal_profile")
    }

    val settings: Flow<HarmonicFlowSettings> = context.harmonicFlowDataStore.data.map { prefs ->
        val mode = runCatching {
            TransitionDurationMode.valueOf(prefs[Keys.DURATION_MODE] ?: TransitionDurationMode.AUTO.name)
        }.getOrDefault(TransitionDurationMode.AUTO)

        val profile = runCatching {
            ThermalProfile.valueOf(prefs[Keys.THERMAL_PROFILE] ?: ThermalProfile.BALANCED.name)
        }.getOrDefault(ThermalProfile.BALANCED)

        HarmonicFlowSettings(
            harmonicFlowEnabled = prefs[Keys.ENABLED] ?: false,
            durationMode = mode,
            targetDurationSeconds = (prefs[Keys.TARGET_SECONDS] ?: 12).coerceIn(12, 30),
            spectralFilterSweepsEnabled = prefs[Keys.SPECTRAL_SWEEPS] ?: true,
            keepAlbumsContinuous = prefs[Keys.KEEP_ALBUMS] ?: true,
            thermalProfile = profile,
        )
    }

    suspend fun setHarmonicFlowEnabled(enabled: Boolean) {
        context.harmonicFlowDataStore.edit { it[Keys.ENABLED] = enabled }
    }

    suspend fun setDurationMode(mode: TransitionDurationMode) {
        context.harmonicFlowDataStore.edit { it[Keys.DURATION_MODE] = mode.name }
    }

    suspend fun setTargetDurationSeconds(seconds: Int) {
        context.harmonicFlowDataStore.edit { it[Keys.TARGET_SECONDS] = seconds.coerceIn(12, 30) }
    }

    suspend fun setSpectralFilterSweepsEnabled(enabled: Boolean) {
        context.harmonicFlowDataStore.edit { it[Keys.SPECTRAL_SWEEPS] = enabled }
    }

    suspend fun setKeepAlbumsContinuous(enabled: Boolean) {
        context.harmonicFlowDataStore.edit { it[Keys.KEEP_ALBUMS] = enabled }
    }

    suspend fun setThermalProfile(profile: ThermalProfile) {
        context.harmonicFlowDataStore.edit { it[Keys.THERMAL_PROFILE] = profile.name }
    }
}
