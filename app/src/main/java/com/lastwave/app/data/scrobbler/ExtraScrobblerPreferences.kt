package com.lastwave.app.data.scrobbler

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lastwave.app.data.local.readSafely
import com.lastwave.app.data.local.recoverPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Scrobble services besides Last.fm. Each has its own connection and offline queue. */
enum class ExtraService(
    val displayName: String,
    credentialName: String,
    userName: String,
    serverName: String,
) {
    LISTENBRAINZ("ListenBrainz", "lw_lb_token", "lw_lb_user", "lw_lb_server"),
    LIBREFM("Libre.fm", "lw_librefm_session", "lw_librefm_user", "lw_librefm_server");

    internal val credentialKey = stringPreferencesKey(credentialName)
    internal val userKey = stringPreferencesKey(userName)
    internal val serverKey = stringPreferencesKey(serverName)
}

/**
 * [credential] is the ListenBrainz user token or the Libre.fm session key.
 * Empty [server] means the public service.
 */
data class ScrobblerAccount(
    val credential: String = "",
    val user: String = "",
    val server: String = "",
) {
    val connected: Boolean get() = credential.isNotEmpty()
}

@Singleton
class ExtraScrobblerPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    fun account(service: ExtraService): Flow<ScrobblerAccount> = dataStore.data
        .recoverPreferences("ExtraScrobblerPreferences")
        .map { p ->
            ScrobblerAccount(
                credential = p.readSafely(service.credentialKey).orEmpty(),
                user = p.readSafely(service.userKey).orEmpty(),
                server = p.readSafely(service.serverKey).orEmpty(),
            )
        }

    suspend fun current(service: ExtraService): ScrobblerAccount = account(service).first()

    suspend fun save(service: ExtraService, account: ScrobblerAccount) {
        dataStore.edit {
            it[service.credentialKey] = account.credential
            it[service.userKey] = account.user
            it[service.serverKey] = account.server
        }
    }

    suspend fun clear(service: ExtraService) = save(service, ScrobblerAccount())
}
