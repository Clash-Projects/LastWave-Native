package com.lastwave.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsPreferencesTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private class FakeDataStore(
        initialPreferences: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val flow = MutableStateFlow(initialPreferences)
        override val data: Flow<Preferences> = flow

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(flow.value)
            flow.value = updated
            return updated
        }
    }

    @Test
    fun miscSettings_prefetchQueueOnCellular_defaultIsFalse() {
        val settings = MiscSettings()
        assertThat(settings.prefetchQueueOnCellular).isFalse()
    }

    @Test
    fun settingsPreferences_readsDefaultFalseWhenUnset() = testScope.runTest {
        val dataStore = FakeDataStore()
        val context = mockk<Context>(relaxed = true)
        val settingsPreferences = SettingsPreferences(dataStore, context)

        val settings = settingsPreferences.settings.first()
        assertThat(settings.prefetchQueueOnCellular).isFalse()
    }

    @Test
    fun settingsPreferences_setPrefetchQueueOnCellular_persistsAndUpdates() = testScope.runTest {
        val dataStore = FakeDataStore()
        val context = mockk<Context>(relaxed = true)
        val settingsPreferences = SettingsPreferences(dataStore, context)

        settingsPreferences.setPrefetchQueueOnCellular(true)
        val enabledSettings = settingsPreferences.settings.first()
        assertThat(enabledSettings.prefetchQueueOnCellular).isTrue()

        settingsPreferences.setPrefetchQueueOnCellular(false)
        val disabledSettings = settingsPreferences.settings.first()
        assertThat(disabledSettings.prefetchQueueOnCellular).isFalse()
    }

    @Test
    fun settingsPreferences_readsTrueWhenKeyAlreadyPresentInDataStore() = testScope.runTest {
        val dataStore = FakeDataStore()
        dataStore.edit { preferences ->
            preferences[booleanPreferencesKey("lw_prefetch_queue_on_cellular")] = true
        }
        val context = mockk<Context>(relaxed = true)
        val settingsPreferences = SettingsPreferences(dataStore, context)

        val settings = settingsPreferences.settings.first()
        assertThat(settings.prefetchQueueOnCellular).isTrue()
    }
}
