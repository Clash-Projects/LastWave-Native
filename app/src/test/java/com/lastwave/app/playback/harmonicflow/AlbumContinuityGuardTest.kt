package com.lastwave.app.playback.harmonicflow

import com.lastwave.app.data.model.PlayableTrack
import com.lastwave.app.playback.harmonicflow.api.HarmonicFlowSettings
import com.lastwave.app.playback.harmonicflow.engine.SpliceTrajectorySynthesizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumContinuityGuardTest {

    private fun createTrack(album: String?, trackNumber: Int?, durationMs: Long = 180_000L) = PlayableTrack(
        videoId = "track_${album}_$trackNumber",
        title = "Track $trackNumber",
        artist = "The Band",
        album = album,
        trackNumber = trackNumber,
        durationMs = durationMs,
    )

    @Test
    fun sameAlbumSequentialTracksBypassedWhenShuffleOff() {
        val outTrack = createTrack("Dark Side of the Moon", 1)
        val inTrack = createTrack("Dark Side of the Moon", 2)
        val settings = HarmonicFlowSettings(keepAlbumsContinuous = true)

        val plan = SpliceTrajectorySynthesizer.synthesizePlan(
            outgoingTrack = outTrack,
            outgoingSignature = null,
            incomingTrack = inTrack,
            incomingSignature = null,
            settings = settings,
            isShuffleActive = false,
        )

        assertTrue("Should bypass transition for sequential album tracks", plan.isBypass)
        assertEquals("Same-album sequential continuity", plan.bypassReason)
        assertEquals(0L, plan.durationMs)
    }

    @Test
    fun sameAlbumNonSequentialTracksDoNotBypass() {
        val outTrack = createTrack("Dark Side of the Moon", 2)
        val inTrack = createTrack("Dark Side of the Moon", 5) // Skipped tracks 3 and 4
        val settings = HarmonicFlowSettings(keepAlbumsContinuous = true)

        val plan = SpliceTrajectorySynthesizer.synthesizePlan(
            outgoingTrack = outTrack,
            outgoingSignature = null,
            incomingTrack = inTrack,
            incomingSignature = null,
            settings = settings,
            isShuffleActive = false,
        )

        assertFalse("Non-sequential album tracks should not bypass transitions", plan.isBypass)
    }

    @Test
    fun differentAlbumsDoNotBypass() {
        val outTrack = createTrack("Album Alpha", 1)
        val inTrack = createTrack("Album Beta", 2)
        val settings = HarmonicFlowSettings(keepAlbumsContinuous = true)

        val plan = SpliceTrajectorySynthesizer.synthesizePlan(
            outgoingTrack = outTrack,
            outgoingSignature = null,
            incomingTrack = inTrack,
            incomingSignature = null,
            settings = settings,
            isShuffleActive = false,
        )

        assertFalse("Cross-album tracks should not bypass transitions", plan.isBypass)
    }

    @Test
    fun shuffleActiveDisablesAlbumBypass() {
        val outTrack = createTrack("Dark Side of the Moon", 1)
        val inTrack = createTrack("Dark Side of the Moon", 2)
        val settings = HarmonicFlowSettings(keepAlbumsContinuous = true)

        val plan = SpliceTrajectorySynthesizer.synthesizePlan(
            outgoingTrack = outTrack,
            outgoingSignature = null,
            incomingTrack = inTrack,
            incomingSignature = null,
            settings = settings,
            isShuffleActive = true, // Shuffle mode active
        )

        assertFalse("Shuffle mode should disable album continuity bypass", plan.isBypass)
    }

    @Test
    fun settingDisabledAllowsTransitionsBetweenAlbumTracks() {
        val outTrack = createTrack("Dark Side of the Moon", 1)
        val inTrack = createTrack("Dark Side of the Moon", 2)
        val settings = HarmonicFlowSettings(keepAlbumsContinuous = false) // Guard disabled

        val plan = SpliceTrajectorySynthesizer.synthesizePlan(
            outgoingTrack = outTrack,
            outgoingSignature = null,
            incomingTrack = inTrack,
            incomingSignature = null,
            settings = settings,
            isShuffleActive = false,
        )

        assertFalse("When setting is false, transitions should be permitted", plan.isBypass)
    }
}
