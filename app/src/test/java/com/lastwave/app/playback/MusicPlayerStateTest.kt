package com.lastwave.app.playback

import org.junit.Assert.assertSame
import org.junit.Test

class MusicPlayerStateTest {
    @Test
    fun `a state rebuild keeps the quality switch indicator`() {
        val switch = QualitySwitch(upgrade = true, title = "Lossless")
        val live = MusicPlayerState(qualitySwitch = switch)

        assertSame(switch, MusicPlayerState().withSubsystemStateFrom(live).qualitySwitch)
    }
}
