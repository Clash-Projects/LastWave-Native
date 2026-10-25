package com.lastwave.app.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.PlaylistPreviewLoader
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Keeps card previews separate from Home fetching, pagination and playback. */
@HiltViewModel
class PlaylistPreviewViewModel @Inject constructor(
    private val api: InnerTubeMusicApi,
    auth: YtMusicAuthManager,
) : ViewModel() {
    private var previewScope = newScope()
    private val current = MutableStateFlow(newLoader())
    val loader = current.asStateFlow()

    init {
        viewModelScope.launch {
            var account = auth.connection.value
            auth.connection.collect { connection ->
                if (connection != account) {
                    account = connection
                    previewScope.cancel()
                    previewScope = newScope()
                    current.value = newLoader()
                }
            }
        }
    }

    private fun newScope() = CoroutineScope(
        viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job])
    )

    private fun newLoader() = PlaylistPreviewLoader(previewScope, api::fetchPlaylistPreview)
}
