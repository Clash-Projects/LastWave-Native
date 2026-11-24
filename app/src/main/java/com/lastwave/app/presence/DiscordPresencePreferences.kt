/*
 * Copyright (C) 2026 LastWave Authors (https://github.com/Clash-Projects/LastWave-Native)
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This file is part of LastWave.
 *
 * LastWave is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * LastWave is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LastWave. If not, see <https://www.gnu.org/licenses/>.
 */

package com.lastwave.app.presence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.lastwave.app.data.local.readSafely
import com.lastwave.app.data.local.recoverPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * "Show what you're playing on your Discord profile."
 *
 * Default ON, like the desktop client: presence is the feature people install a
 * music app's Discord integration for, and a missing key reads as `true` so
 * existing users start seeing their card without touching a switch. An
 * explicit OFF is stored durably and is never overwritten by anything else —
 * only [setEnabled] writes this key.
 *
 * The key name matches the desktop client's pref (`lw_discord_rich_presence`) so
 * the same human decision has the same identity in both apps.
 */
@Singleton
class DiscordPresencePreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("lw_discord_rich_presence")
    }

    val enabled: Flow<Boolean> = dataStore.data
        .recoverPreferences("DiscordPresencePreferences")
        .map { prefs -> prefs.readSafely(Keys.ENABLED) ?: true }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ENABLED] = enabled }
    }
}