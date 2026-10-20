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

    fun saveSpdc(value: String, onDone: () -> Unit) {
        viewModelScope.launch {
            spotifyPreferences.saveSpdc(value)
            onDone()
        }
    }

    fun clearConnection() {
        viewModelScope.launch {
            spotifyPreferences.clearConnection()
        }
    }
}
