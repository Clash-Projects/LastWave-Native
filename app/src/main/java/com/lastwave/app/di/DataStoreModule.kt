package com.lastwave.app.di

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lastwave_prefs",
    // Recover from a torn/corrupt preferences file instead of entering a
    // permanent startup crash loop. User playlists/downloads live in Room and
    // public storage, so they are not touched by this settings-only reset.
    // This file also holds login, so the reset used to log users out silently;
    // flag it so Login can say why and point at Restore backup.
    corruptionHandler = ReplaceFileCorruptionHandler { error ->
        Log.e("DataStoreModule", "lastwave_prefs corrupt; reset to defaults", error)
        prefsReset = true
        emptyPreferences()
    },
)

@Volatile
private var prefsReset = false

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    /** True once after a corruption reset; Login shows the notice. */
    fun consumePrefsReset(): Boolean = prefsReset.also { prefsReset = false }

    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.dataStore
}
