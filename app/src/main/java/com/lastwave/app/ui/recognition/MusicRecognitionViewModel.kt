package com.lastwave.app.ui.recognition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.recognition.AudioSignature
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** Caller must already hold RECORD_AUDIO. */
    fun start() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = RecognitionState.Listening
            _state.value = try {
                val clip = MicCapture.record(LISTEN_MS)
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
    fun dismiss() {
        job?.cancel()
        _state.value = RecognitionState.Idle
    }

    private companion object {
        const val LISTEN_MS = 10_000L
    }
}
