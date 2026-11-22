package com.lastwave.app.data.backup

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.local.db.RecommendationExclusionDao
import com.lastwave.app.data.local.db.SavedPlaylistDao
import com.lastwave.app.data.playlist.PlaylistPublicMirror
import com.lastwave.app.data.scrobbler.ExtraScrobblerPreferences
import com.lastwave.app.data.scrobbler.ExtraService
import com.lastwave.app.data.scrobbler.ScrobblerAccount
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class BackupScrobblerCredentialsTest {
    private val accounts = mapOf(
        ExtraService.LISTENBRAINZ to ScrobblerAccount("listenbrainz-token", "lb-user", "https://brainz.example/api"),
        ExtraService.LIBREFM to ScrobblerAccount("librefm-session", "libre-user", "https://libre.example"),
    )

    @Test
    fun backupExportsAndRestoresEveryScrobblerCredential() = runBlocking {
        val source = MemoryPreferences()
        val sourceAccounts = ExtraScrobblerPreferences(source)
        accounts.forEach { (service, account) -> sourceAccounts.save(service, account) }
        val lastFm = mapOf(
            "lw_apikey" to "lastfm-api-key",
            "lw_apisecret" to "lastfm-api-secret",
            "lw_sessionkey" to "lastfm-session",
            "lw_username" to "lastfm-user",
        )
        source.edit { prefs -> lastFm.forEach { (name, value) -> prefs[stringPreferencesKey(name)] = value } }

        val content = repository(source).buildBackup("test")
        val backup = Json.decodeFromString<BackupFile>(content)
        val expectedProviderStrings = mapOf(
            "lw_lb_token" to accounts.getValue(ExtraService.LISTENBRAINZ).credential,
            "lw_lb_user" to accounts.getValue(ExtraService.LISTENBRAINZ).user,
            "lw_lb_server" to accounts.getValue(ExtraService.LISTENBRAINZ).server,
            "lw_librefm_session" to accounts.getValue(ExtraService.LIBREFM).credential,
            "lw_librefm_user" to accounts.getValue(ExtraService.LIBREFM).user,
            "lw_librefm_server" to accounts.getValue(ExtraService.LIBREFM).server,
        )
        assertThat(backup.prefs.strings).containsExactlyEntriesIn(expectedProviderStrings + lastFm)

        for (preserveSignedInSession in listOf(false, true)) {
            val destination = MemoryPreferences()
            destination.edit { it[stringPreferencesKey("lw_sessionkey")] = "current-lastfm-session" }
            assertThat(repository(destination).restore(content, preserveSignedInSession))
                .isEqualTo(RestoreResult.Success(0, 0))
            val restoredAccounts = ExtraScrobblerPreferences(destination)
            accounts.forEach { (service, account) -> assertThat(restoredAccounts.current(service)).isEqualTo(account) }
            assertThat(destination.data.first()[stringPreferencesKey("lw_sessionkey")])
                .isEqualTo(if (preserveSignedInSession) "current-lastfm-session" else "lastfm-session")
        }
    }

    private fun repository(store: DataStore<Preferences>) = BackupRepository(
        context = RuntimeEnvironment.getApplication(),
        dataStore = store,
        playlistDao = mockk<SavedPlaylistDao>(relaxUnitFun = true) { coEvery { getAll() } returns emptyList() },
        recommendationExclusionDao = mockk<RecommendationExclusionDao>(relaxUnitFun = true) {
            coEvery { getAll() } returns emptyList()
        },
        playlistPublicMirror = mockk<PlaylistPublicMirror> { coEvery { writeFromDatabase() } returns Result.success(Unit) },
    )

    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
