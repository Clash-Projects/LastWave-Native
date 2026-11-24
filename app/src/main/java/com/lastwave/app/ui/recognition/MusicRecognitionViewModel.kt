package com.lastwave.app.ui.recognition

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.recognition.AudioPlaybackCaptureService
import com.lastwave.app.data.recognition.AudioSignature
import com.lastwave.app.data.recognition.AudioSourceDetector
import com.lastwave.app.data.recognition.MicCapture
import com.lastwave.app.data.recognition.PcmResampler
import com.lastwave.app.data.recognition.RecognitionClient
import com.lastwave.app.data.recognition.RecognitionFailure
import com.lastwave.app.data.recognition.RecognizedSong
import com.lastwave.app.ui.navigation.AppRouteNavigator
import com.lastwave.app.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class RecognitionError { PERMISSION, MICROPHONE, SILENCE, NO_MATCH, RATE_LIMITED, NETWORK }

sealed interface RecognitionState {
    data object Idle : RecognitionState
    data object Listening : RecognitionState
    data object Identifying : RecognitionState
    data class Found(val song: RecognizedSong) : RecognitionState
    data class Failed(val error: RecognitionError) : RecognitionState
}

@HiltViewModel
class MusicRecognitionViewModel @Inject constructor(
    private val client: RecognitionClient,
    private val routeNavigator: AppRouteNavigator,
) : ViewModel() {

    private val _state = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
    val state: StateFlow<RecognitionState> = _state.asStateFlow()

    private var job: Job? = null
    private var activeMediaProjection: MediaProjection? = null
    private var activeContext: Context? = null

    fun shouldUseInternalPlaybackCapture(context: Context): Boolean =
        AudioSourceDetector.shouldUseInternalPlaybackCapture(context)

    fun shouldUseInternalPlaybackCapture(audioManager: AudioManager): Boolean =
        AudioSourceDetector.shouldUseInternalPlaybackCapture(audioManager)

    /** Caller must already hold RECORD_AUDIO. */
    fun start(mediaProjection: MediaProjection? = null) {
        job?.cancel()
        activeMediaProjection?.stop()
        activeMediaProjection = mediaProjection
        val ctx = activeContext
        activeContext = null
        job = viewModelScope.launch {
            executeRecognition(ctx, mediaProjection)
        }
    }

    /**
     * Starts internal playback capture with the result from MediaProjectionManager.createScreenCaptureIntent().
     * Defers calling getMediaProjection() until AudioPlaybackCaptureService is running in the foreground.
     */
    fun startWithProjectionResult(context: Context, resultCode: Int, data: Intent) {
        job?.cancel()
        activeMediaProjection?.stop()
        activeMediaProjection = null
        val appContext = context.applicationContext
        activeContext = appContext
        AudioPlaybackCaptureService.stop(appContext)

        job = viewModelScope.launch {
            _state.value = RecognitionState.Listening
            var mediaProjection: MediaProjection? = null
            try {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, AudioPlaybackCaptureService::class.java),
                )

                val ready = withTimeoutOrNull(3000L) {
                    AudioPlaybackCaptureService.isForegroundReady.first { it }
                } == true

                if (ready) {
                    val mediaProjectionManager = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                    mediaProjection = runCatching {
                        mediaProjectionManager?.getMediaProjection(resultCode, data)
                    }.getOrNull()
                }
            } catch (e: Exception) {
                mediaProjection = null
            }

            if (mediaProjection == null) {
                AudioPlaybackCaptureService.stop(appContext)
            }

            activeMediaProjection = mediaProjection
            executeRecognition(appContext, mediaProjection)
        }
    }

    private suspend fun executeRecognition(appContext: Context?, mediaProjection: MediaProjection?) {
        _state.value = RecognitionState.Listening
        _state.value = try {
            val clip = MicCapture.record(LISTEN_MS, mediaProjection, appContext)
            if (MicCapture.isSilent(clip.samples)) {
                RecognitionState.Failed(RecognitionError.SILENCE)
            } else {
                _state.value = RecognitionState.Identifying
                val (signature, sampleMs) = withContext(Dispatchers.Default) {
                    val pcm = PcmResampler.resample(clip.samples, clip.sampleRate, AudioSignature.SAMPLE_RATE)
                    AudioSignature.toDataUri(pcm) to pcm.size * 1000L / AudioSignature.SAMPLE_RATE
                }
                RecognitionState.Found(client.recognize(signature, sampleMs))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MicCapture.CaptureException) {
            RecognitionState.Failed(RecognitionError.MICROPHONE)
        } catch (e: RecognitionFailure.NoMatch) {
            RecognitionState.Failed(RecognitionError.NO_MATCH)
        } catch (e: RecognitionFailure.RateLimited) {
            RecognitionState.Failed(RecognitionError.RATE_LIMITED)
        } catch (e: Exception) {
            RecognitionState.Failed(RecognitionError.NETWORK)
        } finally {
            activeMediaProjection = null
            if (appContext != null) {
                AudioPlaybackCaptureService.stop(appContext)
            }
        }
    }

    fun onPermissionDenied() {
        _state.value = RecognitionState.Failed(RecognitionError.PERMISSION)
    }

    fun searchFor(song: RecognizedSong) {
        dismiss()
        routeNavigator.navigateTo(Screen.Search.createRoute(listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" ")))
    }

    /** Stops listening or identifying and closes the sheet. */
    fun dismiss(context: Context? = null) {
        job?.cancel()
        activeMediaProjection?.stop()
        activeMediaProjection = null
        val ctx = context ?: activeContext
        activeContext = null
        if (ctx != null) {
            AudioPlaybackCaptureService.stop(ctx)
        }
        _state.value = RecognitionState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        job?.cancel()
        activeMediaProjection?.stop()
        activeMediaProjection = null
        activeContext?.let { AudioPlaybackCaptureService.stop(it) }
        activeContext = null
    }

    private companion object {
        const val LISTEN_MS = 10_000L
    }
}
