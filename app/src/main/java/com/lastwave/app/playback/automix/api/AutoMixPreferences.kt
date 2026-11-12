package com.lastwave.app.playback.automix.api

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

data class AutoMixSettings(
    val autoMixEnabled: Boolean = false,
    val durationMode: TransitionDurationMode = TransitionDurationMode.AUTO,
    val targetDurationSeconds: Int = 12, // 12s..30s, default 12s
    val spectralFilterSweepsEnabled: Boolean = true,
    val thermalProfile: ThermalProfile = ThermalProfile.BALANCED,
)

private val Context.autoMixDataStore by preferencesDataStore(name = "automix_prefs")

@Singleton
class AutoMixPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("automix_enabled")
        val LEGACY_ENABLED = booleanPreferencesKey("harmonic_flow_enabled")
        val DURATION_MODE = stringPreferencesKey("automix_duration_mode")
        val TARGET_SECONDS = intPreferencesKey("automix_target_seconds")
        val SPECTRAL_SWEEPS = booleanPreferencesKey("automix_spectral_sweeps")
        val THERMAL_PROFILE = stringPreferencesKey("automix_thermal_profile")
    }

    val settings: Flow<AutoMixSettings> = context.autoMixDataStore.data.map { prefs ->
        val mode = runCatching {
            TransitionDurationMode.valueOf(prefs[Keys.DURATION_MODE] ?: TransitionDurationMode.AUTO.name)
        }.getOrDefault(TransitionDurationMode.AUTO)

        val profile = runCatching {
            ThermalProfile.valueOf(prefs[Keys.THERMAL_PROFILE] ?: ThermalProfile.BALANCED.name)
        }.getOrDefault(ThermalProfile.BALANCED)

        val isEnabled = prefs[Keys.ENABLED] ?: prefs[Keys.LEGACY_ENABLED] ?: false

        AutoMixSettings(
            autoMixEnabled = isEnabled,
            durationMode = mode,
            targetDurationSeconds = (prefs[Keys.TARGET_SECONDS] ?: 12).coerceIn(12, 30),
            spectralFilterSweepsEnabled = prefs[Keys.SPECTRAL_SWEEPS] ?: true,
            thermalProfile = profile,
        )
    }

    suspend fun setAutoMixEnabled(enabled: Boolean) {
        context.autoMixDataStore.edit {
            it[Keys.ENABLED] = enabled
            it[Keys.LEGACY_ENABLED] = enabled
        }
    }

    suspend fun setDurationMode(mode: TransitionDurationMode) {
        context.autoMixDataStore.edit { it[Keys.DURATION_MODE] = mode.name }
    }

    suspend fun setTargetDurationSeconds(seconds: Int) {
        context.autoMixDataStore.edit { it[Keys.TARGET_SECONDS] = seconds.coerceIn(12, 30) }
    }

    suspend fun setSpectralFilterSweepsEnabled(enabled: Boolean) {
        context.autoMixDataStore.edit { it[Keys.SPECTRAL_SWEEPS] = enabled }
    }

    suspend fun setThermalProfile(profile: ThermalProfile) {
        context.autoMixDataStore.edit { it[Keys.THERMAL_PROFILE] = profile.name }
    }
}
