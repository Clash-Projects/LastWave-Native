package com.lastwave.app.data.backup

import androidx.datastore.core.DataStore
import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lastwave.app.data.autoeq.autoEqDataStore
import com.lastwave.app.playback.automix.api.autoMixDataStore
import com.lastwave.app.data.local.db.SavedPlaylistDao
import com.lastwave.app.data.local.db.SavedPlaylistEntity
import com.lastwave.app.data.local.db.RecommendationExclusionDao
import com.lastwave.app.data.local.db.RecommendationExclusionEntity
import com.lastwave.app.data.local.readSafely
import com.lastwave.app.data.playlist.PlaylistPublicMirror
import com.lastwave.app.playback.loudnessDataStore
import com.lastwave.app.playback.usb.internalDirectDataStore
import com.lastwave.app.playback.usb.usbExclusiveDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val SCHEMA_VERSION = 9
private const val BACKUP_TYPE = "lastwave-backup"

/**
 * Preference keys whose on-disk type is Long. Backup schema <= 7 incorrectly
 * narrowed every Long to Int and restored it with an Int key. Preferences keys
 * are name-based, so reading one of those values through a Long key then throws
 * ClassCastException. Keep the names here both to repair old backups and to
 * normalise already-damaged preferences when they are backed up again.
 */
private val LONG_PREFERENCE_NAMES = setOf(
    "ytm_connected_at",
    "ytm_last_sync_at",
)

/**
 * Credentials and tokens that must never be written into an exported backup
 * file. Backups are plaintext JSON the user shares/copies around, so a leaked
 * cookie, API secret, session token, or addon URL (which embeds an access
 * token) would be a full account compromise. These keys are excluded from
 * buildBackup(); restoring therefore clears them and the user re-authenticates
 * (the web-auth restore flow preserves the signed-in session separately via
 * AUTH_PREFERENCE_NAMES).
 */
private val SENSITIVE_PREFERENCE_NAMES = setOf(
    "ytm_cookies",
    "lw_apikey",
    "lw_apisecret",
    "lw_sessionkey",
    "lw_addon_url",
)


@Serializable
data class BackupPrefsSnapshot(
    val strings: Map<String, String> = emptyMap(),
    val booleans: Map<String, Boolean> = emptyMap(),
    val integers: Map<String, Int> = emptyMap(),
    val longs: Map<String, Long> = emptyMap(),
    val floats: Map<String, Float> = emptyMap(),
    val stringSets: Map<String, Set<String>> = emptyMap(),
)

@Serializable
data class BackupPlaylistSnapshot(
    val id: Long,
    val title: String,
    val subtitle: String,
    val mode: String,
    val tracksJson: String,
    val createdAtMillis: Long,
    val discoverSignature: String? = null,
    val customCoverUri: String? = null,
    val isPinned: Boolean = false,
)

/** Explicit recommendation exclusions were added to backup schema v7. */
@Serializable
data class BackupRecommendationExclusionSnapshot(
    val trackKey: String,
    val excludedAtMillis: Long,
    val trackName: String = "",
    val artistName: String = "",
)

@Serializable
data class BackupFile(
    val type: String = BACKUP_TYPE,
    val schemaVersion: Int = SCHEMA_VERSION,
    val createdAt: Long,
    val appVersion: String,
    val prefs: BackupPrefsSnapshot,
    val playlists: List<BackupPlaylistSnapshot>,
    val recommendationExclusions: List<BackupRecommendationExclusionSnapshot> = emptyList(),
    /** Separate DataStore files (EQ, loudness, automix, USB), added in schema v9. */
    val extraStores: Map<String, BackupPrefsSnapshot> = emptyMap(),
)

sealed interface RestoreResult {
    data class Success(val playlistCount: Int, val exclusionCount: Int) : RestoreResult
    data object UnsupportedSchema : RestoreResult
    data object InvalidFile : RestoreResult
    data class Failed(val message: String) : RestoreResult
}

sealed interface BackupCheck {
    data class Valid(val playlistCount: Int) : BackupCheck
    data object UnsupportedSchema : BackupCheck
    data object Invalid : BackupCheck
}

/**
 * Faithful port of settings.js's Backup & Restore (§8.6): serializes the
 * entire local storage (all DataStore prefs, saved playlists, and explicit
 * recommendation exclusions) into one JSON file, and restores it with a
 * pre-restore snapshot so any failure mid-apply rolls back automatically
 * rather than leaving a half-restored state.
 *
 * Older backups remain compatible; their former automatic history is
 * intentionally ignored and never converted into explicit dislikes.
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val playlistDao: SavedPlaylistDao,
    private val recommendationExclusionDao: RecommendationExclusionDao,
    private val playlistPublicMirror: PlaylistPublicMirror,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun checkBackup(content: String): BackupCheck {
        val backup = runCatching { json.decodeFromString<BackupFile>(content) }.getOrNull()
            ?: return BackupCheck.Invalid
        if (backup.type != BACKUP_TYPE) return BackupCheck.Invalid
        if (backup.schemaVersion > SCHEMA_VERSION) return BackupCheck.UnsupportedSchema
        return BackupCheck.Valid(backup.playlists.size)
    }

    suspend fun buildBackup(appVersionName: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val prefsSnapshot = dataStore.data.first().toSnapshot()
        val extraStores = extraStores().mapValues { (_, store) -> store.data.first().toSnapshot() }
        val playlists = playlistDao.getAll().map {
            BackupPlaylistSnapshot(
                id = it.id,
                title = it.title,
                subtitle = it.subtitle,
                mode = it.mode,
                tracksJson = it.tracksJson,
                createdAtMillis = it.createdAtMillis,
                discoverSignature = it.discoverSignature,
                customCoverUri = it.customCoverUri,
                isPinned = it.isPinned,
            )
        }
        val exclusions = recommendationExclusionDao.getAll().map {
            BackupRecommendationExclusionSnapshot(
                trackKey = it.trackKey,
                excludedAtMillis = it.excludedAtMillis,
                trackName = it.trackName,
                artistName = it.artistName,
            )
        }
        val backup = BackupFile(
            createdAt = System.currentTimeMillis(),
            appVersion = appVersionName,
            prefs = prefsSnapshot,
            playlists = playlists,
            recommendationExclusions = exclusions,
            extraStores = extraStores,
        )
        json.encodeToString(backup)
    }

    suspend fun restore(
        content: String,
        preserveSignedInSession: Boolean = false,
    ): RestoreResult {
        val backup = try {
            json.decodeFromString<BackupFile>(content)
        } catch (e: Exception) {
            return RestoreResult.InvalidFile
        }
        if (backup.type != BACKUP_TYPE) return RestoreResult.InvalidFile
        if (backup.schemaVersion > SCHEMA_VERSION) return RestoreResult.UnsupportedSchema

        val currentPrefs = try {
            dataStore.data.first()
        } catch (error: Exception) {
            return RestoreResult.Failed(error.message ?: "Could not read current settings")
        }
        val preservedAuthStrings = if (preserveSignedInSession) {
            AUTH_PREFERENCE_NAMES.mapNotNull { name ->
                currentPrefs.readSafely(stringPreferencesKey(name))?.let { value -> name to value }
            }.toMap()
        } else {
            emptyMap()
        }

        val previousPrefsSnapshot = try { buildBackup("rollback") } catch (e: Exception) { null }
        val previousPlaylists = try { playlistDao.getAll() } catch (e: Exception) { emptyList() }
        val previousExclusions = try { recommendationExclusionDao.getAll() } catch (e: Exception) { emptyList() }

        return try {
            dataStore.edit { mutablePrefs ->
                mutablePrefs.clear()
                mutablePrefs.applySnapshot(backup.prefs)
                if (preserveSignedInSession) {
                    preservedAuthStrings.forEach { (name, value) ->
                        mutablePrefs[stringPreferencesKey(name)] = value
                    }
                    mutablePrefs[booleanPreferencesKey(GUEST_MODE_PREFERENCE)] = false
                }
            }
            restoreExtraStores(backup.extraStores)
            playlistDao.replaceAll(backup.playlists.map { p ->
                SavedPlaylistEntity(
                    id = p.id,
                    title = p.title,
                    subtitle = p.subtitle,
                    mode = p.mode,
                    tracksJson = p.tracksJson,
                    createdAtMillis = p.createdAtMillis,
                    discoverSignature = p.discoverSignature,
                    customCoverUri = p.customCoverUri,
                    isPinned = p.isPinned,
                )
            })
            if (backup.schemaVersion >= 7) {
                recommendationExclusionDao.clear()
                recommendationExclusionDao.upsertAll(
                    backup.recommendationExclusions.map {
                        RecommendationExclusionEntity(
                            trackKey = it.trackKey,
                            excludedAtMillis = it.excludedAtMillis,
                            trackName = it.trackName,
                            artistName = it.artistName,
                        )
                    },
                )
            }
            playlistPublicMirror.writeFromDatabase()
            RestoreResult.Success(backup.playlists.size, backup.recommendationExclusions.size)
        } catch (e: Exception) {
            try {
                previousPrefsSnapshot?.let { rollback(it) }
                playlistDao.replaceAll(previousPlaylists)
                recommendationExclusionDao.clear()
                recommendationExclusionDao.upsertAll(previousExclusions)
            } catch (rollbackError: Exception) {
                // Nothing more we can safely do — surface the original failure.
            }
            RestoreResult.Failed(e.message ?: "Restore failed")
        }
    }

    private suspend fun rollback(snapshotJson: String) {
        val snapshot = json.decodeFromString<BackupFile>(snapshotJson)
        dataStore.edit { mutablePrefs ->
            mutablePrefs.clear()
            mutablePrefs.applySnapshot(snapshot.prefs)
        }
        restoreExtraStores(snapshot.extraStores)
    }

    private fun extraStores(): Map<String, DataStore<Preferences>> = mapOf(
        "autoeq_prefs" to context.autoEqDataStore,
        "loudness_prefs" to context.loudnessDataStore,
        "automix_prefs" to context.autoMixDataStore,
        "internal_direct_prefs" to context.internalDirectDataStore,
        "usb_exclusive_prefs" to context.usbExclusiveDataStore,
    )

    private suspend fun restoreExtraStores(snapshots: Map<String, BackupPrefsSnapshot>) {
        val stores = extraStores()
        snapshots.forEach { (name, snapshot) ->
            stores[name]?.edit { it.clear(); it.applySnapshot(snapshot) }
        }
    }

    private fun Preferences.toSnapshot(): BackupPrefsSnapshot {
        val strings = mutableMapOf<String, String>()
        val booleans = mutableMapOf<String, Boolean>()
        val integers = mutableMapOf<String, Int>()
        val longs = mutableMapOf<String, Long>()
        val floats = mutableMapOf<String, Float>()
        val stringSets = mutableMapOf<String, Set<String>>()
        for (entry in asMap()) {
            val key = entry.key.name
            if (key in SENSITIVE_PREFERENCE_NAMES) continue
            when (val value = entry.value) {
                is String -> strings[key] = value
                is Boolean -> booleans[key] = value
                // A timestamp found as Int has already been truncated by the
                // old schema and cannot be reconstructed; reset it safely.
                is Int -> if (key in LONG_PREFERENCE_NAMES) longs[key] = 0L else integers[key] = value
                is Long -> longs[key] = value
                // Float prefs (lyrics scale, app volume, loudness preamp) were silently dropped before v9.
                is Float -> floats[key] = value
                is Set<*> -> stringSets[key] = value.mapNotNull { it as? String }.toSet()
                else -> Unit
            }
        }
        return BackupPrefsSnapshot(strings, booleans, integers, longs, floats, stringSets)
    }

    private fun MutablePreferences.applySnapshot(snapshot: BackupPrefsSnapshot) {
        snapshot.strings.forEach { (k, v) -> this[stringPreferencesKey(k)] = v }
        snapshot.booleans.forEach { (k, v) -> this[booleanPreferencesKey(k)] = v }
        // Repair v7-and-older backups that wrote Long preferences into the integer bucket.
        snapshot.integers.forEach { (k, v) ->
            if (k in LONG_PREFERENCE_NAMES) this[longPreferencesKey(k)] = 0L else this[intPreferencesKey(k)] = v
        }
        snapshot.longs.forEach { (k, v) -> this[longPreferencesKey(k)] = v }
        snapshot.floats.forEach { (k, v) -> this[floatPreferencesKey(k)] = v }
        snapshot.stringSets.forEach { (k, v) -> this[stringSetPreferencesKey(k)] = v }
    }

    private companion object {
        const val GUEST_MODE_PREFERENCE = "lw_guest_mode"
        val AUTH_PREFERENCE_NAMES = listOf(
            "lw_apikey",
            "lw_apisecret",
            "lw_sessionkey",
            "lw_username",
        )
    }
}
