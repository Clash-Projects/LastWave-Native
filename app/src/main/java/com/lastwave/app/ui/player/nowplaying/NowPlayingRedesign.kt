package com.lastwave.app.ui.player.nowplaying

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lastwave.app.R
import com.lastwave.app.data.local.NowPlayingLayoutPreferences
import com.lastwave.app.ui.settings.SettingsGroup
import com.lastwave.app.ui.settings.SettingsToggleCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class NowPlayingLayoutViewModel @Inject constructor(
    private val prefs: NowPlayingLayoutPreferences,
) : ViewModel() {
    val redesignEnabled: StateFlow<Boolean> = prefs.redesignEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setRedesignEnabled(enabled: Boolean) {
        // Settings can pop right after the tap; the write must still land.
        viewModelScope.launch { withContext(NonCancellable) { prefs.setRedesignEnabled(enabled) } }
    }
}

/** Whether the full player should draw [NowPlayingPanel] instead of the classic controls. */
@Composable
internal fun rememberNowPlayingRedesignEnabled(): Boolean {
    val viewModel: NowPlayingLayoutViewModel = hiltViewModel()
    val enabled by viewModel.redesignEnabled.collectAsStateWithLifecycle()
    return enabled
}

/**
 * The preview switch for Settings › Experimental. Its own one-row group, so the
 * section's existing row indices stay untouched.
 */
@Composable
internal fun NowPlayingRedesignSettingsGroup() {
    val viewModel: NowPlayingLayoutViewModel = hiltViewModel()
    val enabled by viewModel.redesignEnabled.collectAsStateWithLifecycle()
    SettingsGroup(rowCount = 1) { _, position ->
        SettingsToggleCard(
            icon = Icons.Filled.PlayCircle,
            iconContainer = MaterialTheme.colorScheme.secondaryContainer,
            iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
            title = stringResource(R.string.now_playing_redesign_title),
            subtitle = stringResource(R.string.now_playing_redesign_sub),
            checked = enabled,
            onCheckedChange = viewModel::setRedesignEnabled,
            position = position,
        )
    }
}
