package com.lastwave.app.ui.navigation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import com.lastwave.app.data.local.ThemeMode
import com.lastwave.app.data.local.ThemePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAppearancePreferencesTest {
    @Test
    fun material3IsOptInAndUsesThePersistedNavigationKey() = runTest {
        val store = MemoryPreferences()
        val preferences = ThemePreferences(store)
        assertFalse(preferences.prefs.first().md3NavBar)

        preferences.setMd3NavBar(true)

        assertEquals(true, store.data.first()[booleanPreferencesKey("lw_md3NavBar")])
        // A fresh consumer must read the saved choice, not a local switch state.
        assertTrue(ThemePreferences(store).prefs.first().md3NavBar)
        preferences.setMd3NavBar(false)
        assertFalse(ThemePreferences(store).prefs.first().md3NavBar)
    }

    @Test
    fun glassAndThemeChangesDoNotResetTheNavigationStyle() = runTest {
        val preferences = ThemePreferences(MemoryPreferences())
        preferences.setMd3NavBar(true)
        preferences.setLiquidGlass(false)
        preferences.setThemeMode(ThemeMode.DARK)
        preferences.setManualAccent("#2196C6", "#60C6FF")

        val saved = preferences.prefs.first()
        assertTrue(saved.md3NavBar)
        assertFalse(saved.liquidGlass)
        assertEquals(ThemeMode.DARK, saved.themeMode)
    }

    @Test
    fun hidingNavigationPreservesItsSelectedStyle() = runTest {
        val preferences = ThemePreferences(MemoryPreferences())
        preferences.setMd3NavBar(true)
        preferences.setHideNavTabs(true)
        assertTrue(preferences.prefs.first().md3NavBar)
        assertTrue(preferences.prefs.first().hideNavTabs)

        preferences.setHideNavTabs(false)
        assertTrue(preferences.prefs.first().md3NavBar)
        assertFalse(preferences.prefs.first().hideNavTabs)
    }

    /** Exercises the real ThemePreferences mapping/edit code without Android I/O. */
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
