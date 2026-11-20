package com.lastwave.app.playback

import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class ZeroVolumePlaybackObserverTest {

    @Test
    fun `volume transition from positive to zero while playing pauses playback and sets pausedByZeroVolume`() {
        var pauseCalled = false
        var isPlaying = true
        var enabled = true

        val observer = ZeroVolumePlaybackObserver(
            context = null,
            audioManager = null,
            isPauseOnZeroVolumeEnabled = { enabled },
            isPlaying = { isPlaying },
            onPausePlayback = {
                pauseCalled = true
                isPlaying = false
            },
            onResumePlayback = {
                isPlaying = true
            },
            initialVolume = 10,
        )

        assertThat(observer.lastKnownVolume).isEqualTo(10)
        assertThat(observer.pausedByZeroVolume).isFalse()

        // Transition volume down to 0
        observer.onVolumeChanged(currentVolume = 0, isMuted = false)

        assertThat(pauseCalled).isTrue()
        assertThat(observer.pausedByZeroVolume).isTrue()
        assertThat(observer.lastKnownVolume).isEqualTo(0)
    }

    @Test
    fun `attempting to press Play while volume is zero and pauseOnZeroVolume enabled blocks playback and emits snackbar event`() {
        var enabled = true
        val currentVolume = 0

        val observer = ZeroVolumePlaybackObserver(
            context = null,
            audioManager = null,
            isPauseOnZeroVolumeEnabled = { enabled },
            isPlaying = { false },
            onPausePlayback = {},
            onResumePlayback = {},
            initialVolume = currentVolume,
        )

        val shouldBlock = observer.shouldBlockPlayAction()

        assertThat(shouldBlock).isTrue()
        val emitted = observer.snackbarEvent.replayCache.firstOrNull()
        assertThat(emitted).isEqualTo("Increase volume idiot !")
    }

    @Test
    fun `volume transition from zero to positive resumes playback only when pausedByZeroVolume is true`() {
        var resumeCalled = false
        var isPlaying = true
        var enabled = true

        val observer = ZeroVolumePlaybackObserver(
            context = null,
            audioManager = null,
            isPauseOnZeroVolumeEnabled = { enabled },
            isPlaying = { isPlaying },
            onPausePlayback = { isPlaying = false },
            onResumePlayback = {
                resumeCalled = true
                isPlaying = true
            },
            initialVolume = 8,
        )

        // Drop to 0 volume -> auto-pause
        observer.onVolumeChanged(currentVolume = 0, isMuted = false)
        assertThat(observer.pausedByZeroVolume).isTrue()

        // Raise volume from 0 to 5 -> auto-resume
        observer.onVolumeChanged(currentVolume = 5, isMuted = false)
        assertThat(resumeCalled).isTrue()
        assertThat(observer.pausedByZeroVolume).isFalse()
        assertThat(observer.lastKnownVolume).isEqualTo(5)
    }

    @Test
    fun `pausedByZeroVolume resets on manual action and does not auto-resume on volume raise`() {
        var resumeCalled = false
        var isPlaying = true
        var enabled = true

        val observer = ZeroVolumePlaybackObserver(
            context = null,
            audioManager = null,
            isPauseOnZeroVolumeEnabled = { enabled },
            isPlaying = { isPlaying },
            onPausePlayback = { isPlaying = false },
            onResumePlayback = {
                resumeCalled = true
                isPlaying = true
            },
            initialVolume = 12,
        )

        // Drop to 0 volume -> auto-pause
        observer.onVolumeChanged(currentVolume = 0, isMuted = false)
        assertThat(observer.pausedByZeroVolume).isTrue()

        // Manual action (e.g. user manually pauses or skips track)
        observer.onManualAction()
        assertThat(observer.pausedByZeroVolume).isFalse()

        // Now volume increases from 0 to 7 -> should NOT resume
        observer.onVolumeChanged(currentVolume = 7, isMuted = false)
        assertThat(resumeCalled).isFalse()
        assertThat(observer.pausedByZeroVolume).isFalse()
        assertThat(observer.lastKnownVolume).isEqualTo(7)
    }

    @Test
    fun `when disabled pressing Play at zero volume works normally without blocking or snackbar`() {
        var enabled = false

        val observer = ZeroVolumePlaybackObserver(
            context = null,
            audioManager = null,
            isPauseOnZeroVolumeEnabled = { enabled },
            isPlaying = { false },
            onPausePlayback = {},
            onResumePlayback = {},
            initialVolume = 0,
        )

        val shouldBlock = observer.shouldBlockPlayAction()

        assertThat(shouldBlock).isFalse()
        assertThat(observer.snackbarEvent.replayCache).isEmpty()
    }

    @Test
    fun `when disabled volume dropping to zero does not pause or set pausedByZeroVolume`() {
        var pauseCalled = false
        var isPlaying = true
        var enabled = false

        val observer = ZeroVolumePlaybackObserver(
            context = null,
            audioManager = null,
            isPauseOnZeroVolumeEnabled = { enabled },
            isPlaying = { isPlaying },
            onPausePlayback = { pauseCalled = true },
            onResumePlayback = {},
            initialVolume = 10,
        )

        observer.onVolumeChanged(currentVolume = 0, isMuted = false)

        assertThat(pauseCalled).isFalse()
        assertThat(observer.pausedByZeroVolume).isFalse()
        assertThat(observer.lastKnownVolume).isEqualTo(0)
    }

    @Test
    fun `stream mute counts as zero volume for auto-pause and blocking`() {
        var pauseCalled = false
        var isPlaying = true
        var enabled = true

        val observer = ZeroVolumePlaybackObserver(
            context = null,
            audioManager = null,
            isPauseOnZeroVolumeEnabled = { enabled },
            isPlaying = { isPlaying },
            onPausePlayback = { pauseCalled = true },
            onResumePlayback = {},
            initialVolume = 10,
        )

        // Volume is reported as 10 but stream is muted
        observer.onVolumeChanged(currentVolume = 10, isMuted = true)

        assertThat(pauseCalled).isTrue()
        assertThat(observer.pausedByZeroVolume).isTrue()
        assertThat(observer.lastKnownVolume).isEqualTo(0)
    }

    @Test
    fun `volume increase from zero jumps to 10 percent floor and resumes when pausedByZeroVolume is true`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 100

        var resumeCalled = false
        var isPlaying = true

        val observer = ZeroVolumePlaybackObserver(
            context = null,
            audioManager = audioManager,
            isPauseOnZeroVolumeEnabled = { true },
            isPlaying = { isPlaying },
            onPausePlayback = { isPlaying = false },
            onResumePlayback = {
                resumeCalled = true
                isPlaying = true
            },
            initialVolume = 15,
        )

        // Drop to 0 -> auto-pause
        observer.onVolumeChanged(currentVolume = 0, isMuted = false)
        assertThat(observer.pausedByZeroVolume).isTrue()

        // Volume increases from 0 to 1 (below 10% floor of 10)
        observer.onVolumeChanged(currentVolume = 1, isMuted = false)

        verify(exactly = 1) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0) }
        assertThat(resumeCalled).isTrue()
        assertThat(observer.pausedByZeroVolume).isFalse()
        assertThat(observer.lastKnownVolume).isEqualTo(10)
    }

    @Test
    fun `volume increase from zero at or above 10 percent preserves volume and resumes without setStreamVolume`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 100

        var resumeCalled = false
        var isPlaying = true

        val observer = ZeroVolumePlaybackObserver(
            context = null,
            audioManager = audioManager,
            isPauseOnZeroVolumeEnabled = { true },
            isPlaying = { isPlaying },
            onPausePlayback = { isPlaying = false },
            onResumePlayback = {
                resumeCalled = true
                isPlaying = true
            },
            initialVolume = 15,
        )

        // Drop to 0 -> auto-pause
        observer.onVolumeChanged(currentVolume = 0, isMuted = false)
        assertThat(observer.pausedByZeroVolume).isTrue()

        // Volume increases from 0 to 25 (above 10% floor)
        observer.onVolumeChanged(currentVolume = 25, isMuted = false)

        verify(exactly = 0) { audioManager.setStreamVolume(any(), any(), any()) }
        assertThat(resumeCalled).isTrue()
        assertThat(observer.pausedByZeroVolume).isFalse()
        assertThat(observer.lastKnownVolume).isEqualTo(25)
    }

    @Test
    fun `volume increase from zero when not pausedByZeroVolume does not jump to 10 percent or resume`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 100

        var resumeCalled = false

        val observer = ZeroVolumePlaybackObserver(
            context = null,
            audioManager = audioManager,
            isPauseOnZeroVolumeEnabled = { true },
            isPlaying = { false },
            onPausePlayback = {},
            onResumePlayback = { resumeCalled = true },
            initialVolume = 0,
        )

        assertThat(observer.pausedByZeroVolume).isFalse()

        // Volume increases from 0 to 1 without pausedByZeroVolume
        observer.onVolumeChanged(currentVolume = 1, isMuted = false)

        verify(exactly = 0) { audioManager.setStreamVolume(any(), any(), any()) }
        assertThat(resumeCalled).isFalse()
        assertThat(observer.pausedByZeroVolume).isFalse()
        assertThat(observer.lastKnownVolume).isEqualTo(1)
    }
}
