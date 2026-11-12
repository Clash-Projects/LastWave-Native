package com.lastwave.app.data.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

sealed interface PlaylistPreviewState {
    data object Loading : PlaylistPreviewState
    data class Ready(val preview: PlaylistPreview) : PlaylistPreviewState
    data object Failed : PlaylistPreviewState
}

/** One request per playlist for this session, including failures and in-flight requests. */
class PlaylistPreviewLoader(
    private val scope: CoroutineScope,
    private val fetch: suspend (String) -> PlaylistPreview?,
) {
    private val permits = Semaphore(2)
    private val entries = mutableMapOf<String, StateFlow<PlaylistPreviewState>>()

    /** Call only after a card enters the viewport. Scrolling away does not cancel shared work. */
    fun load(playlistId: String): StateFlow<PlaylistPreviewState> = synchronized(entries) {
        val id = playlistId.trim().removePrefix("VL")
        entries.getOrPut(id) {
            val state = MutableStateFlow<PlaylistPreviewState>(PlaylistPreviewState.Loading)
            if (id.isBlank()) {
                state.value = PlaylistPreviewState.Failed
            } else {
                scope.launch {
                    state.value = try {
                        permits.withPermit {
                            fetch(id)?.let(PlaylistPreviewState::Ready) ?: PlaylistPreviewState.Failed
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        PlaylistPreviewState.Failed
                    }
                }
            }
            state.asStateFlow()
        }
    }
}
