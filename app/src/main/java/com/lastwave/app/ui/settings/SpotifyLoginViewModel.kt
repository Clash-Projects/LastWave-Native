package com.lastwave.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.spotify.SpotifyPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpotifyLoginViewModel @Inject constructor(
    private val spotifyPreferences: SpotifyPreferences,
) : ViewModel() {

    val isConnected = spotifyPreferences.isConnected
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val spdc = spotifyPreferences.spdc
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Persists the captured `sp_dc` cookie. [onDone] receives whether the
     * write actually landed: the screen must only latch "connected" on
     * success, otherwise a failed write would leave the WebView latched and
     * every later navigation blocked.
     */
    fun saveSpdc(value: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val saved = runCatching { spotifyPreferences.saveSpdc(value) }.isSuccess
            onDone(saved)
        }
    }

    fun clearConnection() {
        viewModelScope.launch {
            spotifyPreferences.clearConnection()
        }
    }
}
