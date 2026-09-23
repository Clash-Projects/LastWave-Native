package com.lastwave.app.data.ytmusic

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lastwave.app.data.local.readSafely
import com.lastwave.app.data.local.recoverPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A YouTube Music playlist mirrored to (or imported from) the account. */
@Serializable
data class YtPlaylistMapping(
    val remotePlaylistId: String,
    val remoteTitle: String,
    val lastSyncAtMillis: Long = 0L,
    val lastSyncedVideoIds: List<String> = emptyList(),
    /** Imported account playlists stay on YouTube when their optional local copy is deleted. */
    val deleteRemoteWithLocal: Boolean = true,
)

@Serializable
data class YtCachedLibraryPlaylist(
    val id: String,
    val title: String,
    val author: String? = null,
    val trackCountText: String? = null,
    val artworkUrl: String? = null,
)

data class YtConnection(
    val cookies: Map<String, String> = emptyMap(),
    val accountName: String = "",
    val channelHandle: String? = null,
    val photoUrl: String? = null,
    val connectedAtMillis: Long = 0L,
    /**
     * Selected YouTube channel within the signed-in session. Cookies alone
     * always resolve to the default (first) channel — switching channels is
     * a per-request flag, never a cookie change:
     * [pageId] (brand-channel delegation token from
     * `account/get_account_switcher`'s `pageIdToken`, music.youtube.com's own
     * `pageid=` switcher parameter) goes out as the `X-Goog-PageId` header
     * (+`X-Goog-AuthUser: 0`); [onBehalfOfUser] (legacy UC/`onBehalfOfUser`
     * token from `account_menu`) goes into the InnerTube context `user`
     * block; [authUserIndex] (multi-login session index) goes out as the
     * `X-Goog-AuthUser` header. Blank [pageId] + null [onBehalfOfUser] means
     * "YouTube's default channel", exactly as before this feature.
     */
    val onBehalfOfUser: String? = null,
    val authUserIndex: Int? = null,
    val pageId: String = "",
) {
    val isConnected: Boolean
        get() = cookies.isNotEmpty() && accountName.isNotBlank()

    companion object {
        val DISCONNECTED = YtConnection()
    }
}

/**
 * DataStore-backed persistence for the connected YouTube Music account:
 * session cookies, display identity, sync settings and the local→remote
 * playlist mapping table used by [YtMusicSyncManager].
 *
 * Stored in the app's shared DataStore so it rides along with existing
 * backup/clear flows without touching Room schema.
 */
@Singleton
class YtMusicPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }
    internal val playlistSyncMutex = Mutex()

    val connection: Flow<YtConnection> = dataStore.data
        .recoverPreferences("YtMusicPreferences")
        .map { prefs ->
            val cookies = prefs.readSafely(COOKIES_KEY)?.let { raw ->
                runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull()
            } ?: emptyMap()
            val name = prefs.readSafely(ACCOUNT_NAME_KEY) ?: ""
            if (cookies.isEmpty()) {
                YtConnection.DISCONNECTED
            } else {
                YtConnection(
                    cookies = cookies,
                    accountName = name,
                    channelHandle = prefs.readSafely(CHANNEL_HANDLE_KEY),
                    photoUrl = prefs.readSafely(PHOTO_URL_KEY),
                    connectedAtMillis = prefs.safeLong(CONNECTED_AT_KEY),
                    onBehalfOfUser = prefs.readSafely(ON_BEHALF_OF_USER_KEY)?.takeIf { it.isNotBlank() },
                    authUserIndex = prefs.readSafely(AUTH_USER_INDEX_KEY),
                    pageId = prefs.readSafely(PAGE_ID_KEY)?.takeIf { it.isNotBlank() }.orEmpty(),
                )
            }
        }

    /** True only while a connection exists AND the user left sync on — both
     *  must hold before any background write to the account happens. */
    suspend fun isSyncActive(): Boolean =
        connection.first().isConnected &&
            dataStore.data.recoverPreferences("YtMusicPreferences").first().let { prefs ->
                prefs.readSafely(SYNC_ENABLED_KEY)
                    ?: !prefs.readSafely(COOKIES_KEY).isNullOrBlank()
            }

    val syncEnabled: Flow<Boolean> = dataStore.data
        .recoverPreferences("YtMusicPreferences")
        .map { prefs ->
            prefs.readSafely(SYNC_ENABLED_KEY)
                ?: !prefs.readSafely(COOKIES_KEY).isNullOrBlank()
        }

    /**
     * "Sync playback to YouTube Music history": records songs actually
     * listened to in LastWave — including Qobuz/FLAC and downloaded-file
     * playback — in the connected account's YouTube Music history.
     *
     * Default ON: a missing key reads as `true`, so existing and new users
     * who never touched the switch start syncing automatically once an
     * account is connected. An explicit OFF (`false`) is stored durably and
     * is never overwritten by connects, disconnects, or updates — neither
     * [saveConnection] nor [clearConnection] touches this key.
     */
    val historySyncEnabled: Flow<Boolean> = dataStore.data
        .recoverPreferences("YtMusicPreferences")
        .map { prefs -> prefs.readSafely(HISTORY_SYNC_ENABLED_KEY) ?: true }

    val lastSyncAt: Flow<Long> = dataStore.data
        .recoverPreferences("YtMusicPreferences")
        .map { it.safeLong(LAST_SYNC_KEY) }

    /**
     * Local→remote sync mirrors, scoped per selected YouTube channel. Remote
     * playlist ids only exist under the channel that created them, so each
     * channel gets its own bucket — switching channels never reconciles one
     * channel's mirrors against another's library (which would fail every
     * playlist or, worse, duplicate mirrors). Legacy installs stored a flat
     * table; it is adopted verbatim as the default channel's bucket.
     */
    val playlistMappings: Flow<Map<Long, YtPlaylistMapping>> = dataStore.data
        .recoverPreferences("YtMusicPreferences")
        .map { prefs -> readBucketMappings(prefs) }

    suspend fun mappings(): Map<Long, YtPlaylistMapping> = playlistMappings.first()

    suspend fun setMappings(mappings: Map<Long, YtPlaylistMapping>) {
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs ->
                val buckets = readAllBuckets(prefs).toMutableMap()
                buckets[channelBucket(prefs)] =
                    mappings.mapKeys { (k, _) -> k.toString() }
                prefs[MAPPINGS_KEY] = json.encodeToString(
                    YtPlaylistMappingBucketSerializer,
                    buckets,
                )
            }
        }
    }

    private fun readBucketMappings(prefs: Preferences): Map<Long, YtPlaylistMapping> {
        val buckets = readAllBuckets(prefs)
        // Current bucket first, then pre-PageId (`auth|user`) and pre-channel
        // (`|`) layouts so existing mirrors survive the upgrade.
        val candidates = listOf(
            channelBucket(prefs),
            run {
                val user = prefs.readSafely(ON_BEHALF_OF_USER_KEY)?.takeIf { it.isNotBlank() }.orEmpty()
                val auth = prefs.readSafely(AUTH_USER_INDEX_KEY)?.toString().orEmpty()
                "$auth|$user"
            },
            DEFAULT_CHANNEL_BUCKET,
            LEGACY_DEFAULT_CHANNEL_BUCKET,
        ).distinct()
        return candidates.firstNotNullOfOrNull { buckets[it] }
            ?.mapNotNull { (key, mapping) -> key.toLongOrNull()?.let { it to mapping } }
            ?.toMap()
            .orEmpty()
    }

    private fun channelBucket(prefs: Preferences): String {
        val user = prefs.readSafely(ON_BEHALF_OF_USER_KEY)?.takeIf { it.isNotBlank() }.orEmpty()
        val auth = prefs.readSafely(AUTH_USER_INDEX_KEY)?.toString().orEmpty()
        val page = prefs.readSafely(PAGE_ID_KEY)?.takeIf { it.isNotBlank() }.orEmpty()
        return "$auth|$user|$page"
    }

    private fun readAllBuckets(prefs: Preferences): Map<String, Map<String, YtPlaylistMapping>> {
        val raw = prefs.readSafely(MAPPINGS_KEY) ?: return emptyMap()
        // Current nested schema first — a successful decode is authoritative,
        // even when the active bucket is simply empty.
        runCatching {
            json.decodeFromString<Map<String, Map<String, YtPlaylistMapping>>>(raw)
        }.getOrNull()?.let { return it }
        // Pre-channel flat table: it belongs to the default channel bucket.
        runCatching {
            json.decodeFromString<Map<String, YtPlaylistMapping>>(raw)
        }.getOrNull()?.let { flat ->
            return mapOf(DEFAULT_CHANNEL_BUCKET to flat)
        }
        return emptyMap()
    }

    suspend fun cachedLibraryPlaylists(): List<YtCachedLibraryPlaylist> =
        withContext(Dispatchers.IO) {
            dataStore.data.recoverPreferences("YtMusicPreferences").first()
                .readSafely(LIBRARY_CACHE_KEY)
                ?.let { raw ->
                    runCatching { json.decodeFromString<List<YtCachedLibraryPlaylist>>(raw) }.getOrNull()
                }
                .orEmpty()
        }

    suspend fun setCachedLibraryPlaylists(playlists: List<YtCachedLibraryPlaylist>) {
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs -> prefs[LIBRARY_CACHE_KEY] = json.encodeToString(playlists) }
        }
    }

    suspend fun saveConnection(
        cookies: Map<String, String>,
        accountName: String,
        channelHandle: String?,
        photoUrl: String?,
        onBehalfOfUser: String? = null,
        authUserIndex: Int? = null,
        pageId: String = "",
    ) {
        dataStore.edit { prefs ->
            prefs[COOKIES_KEY] = json.encodeToString(cookies)
            prefs[ACCOUNT_NAME_KEY] = accountName
            if (channelHandle != null) prefs[CHANNEL_HANDLE_KEY] = channelHandle else prefs.remove(CHANNEL_HANDLE_KEY)
            if (photoUrl != null) prefs[PHOTO_URL_KEY] = photoUrl else prefs.remove(PHOTO_URL_KEY)
            prefs[CONNECTED_AT_KEY] = System.currentTimeMillis()
            // A newly connected account is live by default; no separate auto-sync setup step.
            prefs[SYNC_ENABLED_KEY] = true
            // A fresh login starts on YouTube's default channel; an explicit
            // identity refresh passes the current selection back through.
            if (onBehalfOfUser.isNullOrBlank()) prefs.remove(ON_BEHALF_OF_USER_KEY)
            else prefs[ON_BEHALF_OF_USER_KEY] = onBehalfOfUser
            if (authUserIndex == null) prefs.remove(AUTH_USER_INDEX_KEY)
            else prefs[AUTH_USER_INDEX_KEY] = authUserIndex
            if (pageId.isBlank()) prefs.remove(PAGE_ID_KEY)
            else prefs[PAGE_ID_KEY] = pageId
        }
    }

    /** Switches the active YouTube channel inside the current session. */
    suspend fun setSelectedChannel(onBehalfOfUser: String?, authUserIndex: Int?, pageId: String = "") {
        dataStore.edit { prefs ->
            if (onBehalfOfUser.isNullOrBlank()) prefs.remove(ON_BEHALF_OF_USER_KEY)
            else prefs[ON_BEHALF_OF_USER_KEY] = onBehalfOfUser
            if (authUserIndex == null) prefs.remove(AUTH_USER_INDEX_KEY)
            else prefs[AUTH_USER_INDEX_KEY] = authUserIndex
            if (pageId.isBlank()) prefs.remove(PAGE_ID_KEY)
            else prefs[PAGE_ID_KEY] = pageId
        }
    }

    /**
     * Atomic channel switch + display-identity update. A single edit avoids
     * the race where a follow-up identity write would restore the previous
     * channel selection. Cookies, timestamps and sync settings are untouched.
     */
    suspend fun saveChannelSelection(
        onBehalfOfUser: String?,
        authUserIndex: Int?,
        accountName: String,
        channelHandle: String?,
        photoUrl: String?,
        pageId: String = "",
    ) {
        dataStore.edit { prefs ->
            if (onBehalfOfUser.isNullOrBlank()) prefs.remove(ON_BEHALF_OF_USER_KEY)
            else prefs[ON_BEHALF_OF_USER_KEY] = onBehalfOfUser
            if (authUserIndex == null) prefs.remove(AUTH_USER_INDEX_KEY)
            else prefs[AUTH_USER_INDEX_KEY] = authUserIndex
            if (pageId.isBlank()) prefs.remove(PAGE_ID_KEY)
            else prefs[PAGE_ID_KEY] = pageId
            prefs[ACCOUNT_NAME_KEY] = accountName
            if (channelHandle != null) prefs[CHANNEL_HANDLE_KEY] = channelHandle else prefs.remove(CHANNEL_HANDLE_KEY)
            if (photoUrl != null) prefs[PHOTO_URL_KEY] = photoUrl else prefs.remove(PHOTO_URL_KEY)
        }
    }

    suspend fun clearConnection() {
        dataStore.edit { prefs ->
            prefs.remove(COOKIES_KEY)
            prefs.remove(ACCOUNT_NAME_KEY)
            prefs.remove(CHANNEL_HANDLE_KEY)
            prefs.remove(PHOTO_URL_KEY)
            prefs.remove(CONNECTED_AT_KEY)
            prefs.remove(MAPPINGS_KEY)
            prefs.remove(LIBRARY_CACHE_KEY)
            prefs.remove(HIDDEN_LIBRARY_PLAYLIST_IDS_KEY)
            prefs.remove(ON_BEHALF_OF_USER_KEY)
            prefs.remove(AUTH_USER_INDEX_KEY)
            prefs.remove(PAGE_ID_KEY)
            prefs[SYNC_ENABLED_KEY] = false
        }
    }

    val syncedPlaylistIds: Flow<Set<Long>?> = dataStore.data
        .recoverPreferences("YtMusicPreferences")
        .map { prefs ->
            prefs.readSafely(SYNCED_PLAYLIST_IDS_KEY)?.let { raw ->
                runCatching { json.decodeFromString<Set<Long>>(raw) }.getOrNull()
            }
        }

    /** IDs explicitly hidden from LastWave. Empty means show every account playlist. */
    val hiddenLibraryPlaylistIds: Flow<Set<String>> = dataStore.data
        .recoverPreferences("YtMusicPreferences")
        .map { prefs ->
            prefs.readSafely(HIDDEN_LIBRARY_PLAYLIST_IDS_KEY)?.let { raw ->
                runCatching { json.decodeFromString<Set<String>>(raw) }.getOrNull()
            }.orEmpty()
        }

    suspend fun setLibraryPlaylistVisible(playlistId: String, visible: Boolean) {
        dataStore.edit { prefs ->
            val hidden = prefs.readSafely(HIDDEN_LIBRARY_PLAYLIST_IDS_KEY)?.let { raw ->
                runCatching { json.decodeFromString<Set<String>>(raw) }.getOrNull()
            }.orEmpty()
            val updated = if (visible) hidden - playlistId else hidden + playlistId
            if (updated.isEmpty()) {
                prefs.remove(HIDDEN_LIBRARY_PLAYLIST_IDS_KEY)
            } else {
                prefs[HIDDEN_LIBRARY_PLAYLIST_IDS_KEY] = json.encodeToString(updated)
            }
        }
    }

    suspend fun setAllLibraryPlaylistsVisible(playlistIds: Set<String>, visible: Boolean) {
        dataStore.edit { prefs ->
            if (visible) {
                prefs.remove(HIDDEN_LIBRARY_PLAYLIST_IDS_KEY)
            } else {
                prefs[HIDDEN_LIBRARY_PLAYLIST_IDS_KEY] = json.encodeToString(playlistIds)
            }
        }
    }

    suspend fun setSyncedPlaylistIds(ids: Set<Long>?) {
        dataStore.edit { prefs ->
            if (ids != null) {
                prefs[SYNCED_PLAYLIST_IDS_KEY] = json.encodeToString(ids)
            } else {
                prefs.remove(SYNCED_PLAYLIST_IDS_KEY)
            }
        }
    }

    suspend fun togglePlaylistSync(allPlaylistIds: List<Long>, playlistId: Long, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs.readSafely(SYNCED_PLAYLIST_IDS_KEY)?.let { raw ->
                runCatching { json.decodeFromString<Set<Long>>(raw) }.getOrNull()
            } ?: allPlaylistIds.toSet()
            val updated = if (enabled) current + playlistId else current - playlistId
            prefs[SYNCED_PLAYLIST_IDS_KEY] = json.encodeToString(updated)
        }
    }

    suspend fun setSyncEnabled(enabled: Boolean) {
        dataStore.edit { it[SYNC_ENABLED_KEY] = enabled }
    }

    suspend fun setHistorySyncEnabled(enabled: Boolean) {
        dataStore.edit { it[HISTORY_SYNC_ENABLED_KEY] = enabled }
    }

    suspend fun lastSyncAtMillis(): Long =
        dataStore.data.recoverPreferences("YtMusicPreferences").first().safeLong(LAST_SYNC_KEY)

    suspend fun setLastSyncAt(millis: Long) {
        dataStore.edit { it[LAST_SYNC_KEY] = millis }
    }

    val pinnedLibraryPlaylistIds: Flow<Set<String>> = dataStore.data
        .recoverPreferences("YtMusicPreferences")
        .map { prefs ->
            prefs.readSafely(PINNED_LIBRARY_PLAYLIST_IDS_KEY)?.let { raw ->
                runCatching { json.decodeFromString<Set<String>>(raw) }.getOrNull()
            }.orEmpty()
        }

    suspend fun setPinned(remotePlaylistId: String, isPinned: Boolean) {
        withContext(Dispatchers.IO) {
            dataStore.edit { prefs ->
                val current = prefs.readSafely(PINNED_LIBRARY_PLAYLIST_IDS_KEY)?.let { raw ->
                    runCatching { json.decodeFromString<Set<String>>(raw) }.getOrNull()
                }.orEmpty()
                val updated = if (isPinned) current + remotePlaylistId else current - remotePlaylistId
                if (updated.isEmpty()) {
                    prefs.remove(PINNED_LIBRARY_PLAYLIST_IDS_KEY)
                } else {
                    prefs[PINNED_LIBRARY_PLAYLIST_IDS_KEY] = json.encodeToString(updated)
                }
            }
        }
    }

    private companion object {
        const val TAG = "YtMusicPreferences"
        /** Bucket key for YouTube's default channel (no delegation flags). */
        const val DEFAULT_CHANNEL_BUCKET = "||"
        /** Pre-PageId default bucket (`auth|user`), kept for migration reads. */
        const val LEGACY_DEFAULT_CHANNEL_BUCKET = "|"
        val COOKIES_KEY = stringPreferencesKey("ytm_cookies")
        val ACCOUNT_NAME_KEY = stringPreferencesKey("ytm_account_name")
        val CHANNEL_HANDLE_KEY = stringPreferencesKey("ytm_channel_handle")
        val PHOTO_URL_KEY = stringPreferencesKey("ytm_photo_url")
        val CONNECTED_AT_KEY = longPreferencesKey("ytm_connected_at")
        val SYNC_ENABLED_KEY = booleanPreferencesKey("ytm_sync_enabled")
        val HISTORY_SYNC_ENABLED_KEY = booleanPreferencesKey("ytm_history_sync_enabled")
        val SYNCED_PLAYLIST_IDS_KEY = stringPreferencesKey("ytm_synced_playlist_ids")
        val MAPPINGS_KEY = stringPreferencesKey("ytm_playlist_mappings")
        val ON_BEHALF_OF_USER_KEY = stringPreferencesKey("ytm_on_behalf_of_user")
        val AUTH_USER_INDEX_KEY = intPreferencesKey("ytm_auth_user_index")
        val PAGE_ID_KEY = stringPreferencesKey("ytm_page_id")
        val LIBRARY_CACHE_KEY = stringPreferencesKey("ytm_library_playlist_cache")
        val HIDDEN_LIBRARY_PLAYLIST_IDS_KEY = stringPreferencesKey("ytm_hidden_library_playlist_ids")
        val PINNED_LIBRARY_PLAYLIST_IDS_KEY = stringPreferencesKey("ytm_pinned_library_playlist_ids")
        val LAST_SYNC_KEY = longPreferencesKey("ytm_last_sync_at")
    }
}

/** Backup schema <= 7 narrowed timestamps to Int. A typed lookup through the
 * raw map ignores that damaged representation and safely resets it to zero. */
private fun Preferences.safeLong(key: Preferences.Key<Long>): Long =
    readSafely(key) ?: 0L

private val YtPlaylistMappingBucketSerializer =
    MapSerializer(String.serializer(), MapSerializer(String.serializer(), YtPlaylistMapping.serializer()))
