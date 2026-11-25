package com.lastwave.app.data.download.smart

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lastwave.app.data.local.readSafely
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.local.recoverPreferences
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

data class SmartDownloadsSettings(
    val enabled: Boolean = false,
    val songLimit: Int = DEFAULT_SONG_LIMIT,
    val wifiOnly: Boolean = true,
    val storageLimitGiB: Int = DEFAULT_STORAGE_LIMIT_GIB,
) {
    val storageLimitBytes: Long get() = storageLimitGiB * 1024L * 1024 * 1024

    companion object {
        const val MIN_SONG_LIMIT = 10
        const val MAX_SONG_LIMIT = 500
        const val DEFAULT_SONG_LIMIT = 250
        const val MIN_STORAGE_LIMIT_GIB = 1
        const val MAX_STORAGE_LIMIT_GIB = 64
        const val DEFAULT_STORAGE_LIMIT_GIB = 2
    }
}

@Serializable
internal data class SmartDownloadsPlan(
    val account: String,
    val songLimit: Int,
    val checkInAt: Long,
    val runUntil: Long,
    val playlists: Map<String, List<String>>,
    // Full tracks, so a download needs no extra /player lookup for its title and artist.
    val pending: List<YouTubeMusicTrack>,
    val storageLimitGiB: Int = SmartDownloadsSettings.DEFAULT_STORAGE_LIMIT_GIB,
    val obsoleteVideoIds: Set<String> = emptySet(),
)

@Singleton
class SmartDownloadsPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val values = dataStore.data.recoverPreferences("SmartDownloads")
    val settings = values.map { it.smartSettings() }.distinctUntilChanged()

    private fun Preferences.smartSettings() = SmartDownloadsSettings(
        enabled = readSafely(enabledKey) ?: false,
        songLimit = (readSafely(limitKey) ?: SmartDownloadsSettings.DEFAULT_SONG_LIMIT)
            .coerceIn(SmartDownloadsSettings.MIN_SONG_LIMIT, SmartDownloadsSettings.MAX_SONG_LIMIT),
        wifiOnly = readSafely(wifiKey) ?: true,
        storageLimitGiB = (readSafely(storageLimitKey) ?: SmartDownloadsSettings.DEFAULT_STORAGE_LIMIT_GIB)
            .coerceIn(SmartDownloadsSettings.MIN_STORAGE_LIMIT_GIB, SmartDownloadsSettings.MAX_STORAGE_LIMIT_GIB),
    )

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit {
            it[enabledKey] = value
            if (!value) it.remove(planKey)
        }
    }

    suspend fun setSongLimit(value: Int) {
        dataStore.edit {
            it[limitKey] = value.coerceIn(SmartDownloadsSettings.MIN_SONG_LIMIT, SmartDownloadsSettings.MAX_SONG_LIMIT)
        }
    }

    suspend fun setWifiOnly(value: Boolean) {
        dataStore.edit { it[wifiKey] = value }
    }

    suspend fun setStorageLimitGiB(value: Int) {
        dataStore.edit {
            it[storageLimitKey] = value.coerceIn(
                SmartDownloadsSettings.MIN_STORAGE_LIMIT_GIB, SmartDownloadsSettings.MAX_STORAGE_LIMIT_GIB,
            )
        }
    }

    internal suspend fun readPlan(): SmartDownloadsPlan? {
        val value = values.first().readSafely(planKey) ?: return null
        return try {
            Json.decodeFromString(SmartDownloadsPlan.serializer(), value)
        } catch (_: SerializationException) {
            null
        }
    }

    internal suspend fun savePlan(plan: SmartDownloadsPlan, expected: SmartDownloadsSettings) {
        dataStore.edit {
            if (it.smartSettings() == expected) {
                it[planKey] = Json.encodeToString(SmartDownloadsPlan.serializer(), plan)
            }
        }
    }

    private companion object {
        val enabledKey = booleanPreferencesKey("lw_smart_downloads")
        val limitKey = intPreferencesKey("lw_smart_download_song_limit")
        val wifiKey = booleanPreferencesKey("lw_smart_downloads_wifi_only")
        val storageLimitKey = intPreferencesKey("lw_smart_download_storage_limit_gib")
        val planKey = stringPreferencesKey("lw_smart_download_plan")
    }
}
