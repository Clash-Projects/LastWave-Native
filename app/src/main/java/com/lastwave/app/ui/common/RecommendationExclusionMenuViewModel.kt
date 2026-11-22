package com.lastwave.app.ui.common

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import com.lastwave.app.data.discover.DiscoverRepository
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.markNotInterested
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class RecommendationExclusionMenuViewModel @Inject constructor(
    private val discoverRepository: DiscoverRepository,
    private val innerTube: InnerTubeMusicApi,
    private val auth: YtMusicAuthManager,
    private val applicationScope: CoroutineScope,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    fun exclude(track: PlayableTrack) {
        val account = auth.connection.value
        applicationScope.launch(Dispatchers.IO) {
            var saved = false
            val message = try {
                saved = discoverRepository.excludeFromRecommendations(track.title, track.artist)
                when {
                    !saved -> "Could not exclude this song. Please try again."
                    !account.isConnected || track.videoId.isNullOrBlank() -> "Song excluded from recommendations"
                    innerTube.markNotInterested(track.videoId, account) -> "Song excluded here and on YouTube Music"
                    else -> "Song excluded here. YouTube Music could not confirm this change."
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("RecommendationExclusion", "Recommendation feedback failed", error)
                if (saved) "Song excluded here. Could not update YouTube Music; please try again."
                else "Could not exclude this song. Please try again."
            }
            withContext(Dispatchers.Main) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        }
    }
}
