package com.lastwave.app.playback

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.R
import org.junit.Test

class NotificationRepeatActionTest {

    @Test
    fun `custom layout places Repeat button on the far right and no Shuffle action remains`() {
        val layoutNotLiked = NotificationRepeatAction.buildCustomLayout(isLiked = false, repeatMode = Player.REPEAT_MODE_OFF)
        assertThat(layoutNotLiked).hasSize(2)
        assertThat(layoutNotLiked[0].action).isEqualTo(NotificationRepeatAction.ACTION_LIKE)
        assertThat(layoutNotLiked[0].name).isEqualTo("Like song")
        assertThat(layoutNotLiked[0].iconRes).isEqualTo(R.drawable.ic_wave_widget_favorite)
        assertThat(layoutNotLiked[1].action).isEqualTo(NotificationRepeatAction.ACTION_TOGGLE_REPEAT)
        assertThat(layoutNotLiked[1].name).isEqualTo("Repeat off")
        assertThat(layoutNotLiked[1].iconRes).isEqualTo(R.drawable.ic_repeat)

        val layoutLiked = NotificationRepeatAction.buildCustomLayout(isLiked = true, repeatMode = Player.REPEAT_MODE_ALL)
        assertThat(layoutLiked).hasSize(2)
        assertThat(layoutLiked[0].action).isEqualTo(NotificationRepeatAction.ACTION_LIKE)
        assertThat(layoutLiked[0].name).isEqualTo("Unlike song")
        assertThat(layoutLiked[0].iconRes).isEqualTo(R.drawable.ic_wave_widget_favorite_filled)
        assertThat(layoutLiked[1].action).isEqualTo(NotificationRepeatAction.ACTION_TOGGLE_REPEAT)
        assertThat(layoutLiked[1].name).isEqualTo("Repeat all")
        assertThat(layoutLiked[1].iconRes).isEqualTo(R.drawable.ic_repeat_on)

        for (action in layoutLiked) {
            assertThat(action.action).isNotEqualTo("com.lastwave.app.playback.SHUFFLE")
            assertThat(action.iconRes).isNotEqualTo(R.drawable.ic_widget_shuffle)
        }
    }

    @Test
    fun `cycling repeat mode transitions OFF to ALL to ONE to OFF`() {
        val mode1 = NotificationRepeatAction.cycleRepeatMode(Player.REPEAT_MODE_OFF)
        assertThat(mode1).isEqualTo(Player.REPEAT_MODE_ALL)

        val mode2 = NotificationRepeatAction.cycleRepeatMode(mode1)
        assertThat(mode2).isEqualTo(Player.REPEAT_MODE_ONE)

        val mode3 = NotificationRepeatAction.cycleRepeatMode(mode2)
        assertThat(mode3).isEqualTo(Player.REPEAT_MODE_OFF)
    }

    @Test
    fun `repeatMode resolves to expected icon and content description`() {
        val offState = NotificationRepeatAction.resolveRepeatState(Player.REPEAT_MODE_OFF)
        assertThat(offState.iconRes).isEqualTo(R.drawable.ic_repeat)
        assertThat(offState.contentDescription).isEqualTo("Repeat off")

        val allState = NotificationRepeatAction.resolveRepeatState(Player.REPEAT_MODE_ALL)
        assertThat(allState.iconRes).isEqualTo(R.drawable.ic_repeat_on)
        assertThat(allState.contentDescription).isEqualTo("Repeat all")

        val oneState = NotificationRepeatAction.resolveRepeatState(Player.REPEAT_MODE_ONE)
        assertThat(oneState.iconRes).isEqualTo(R.drawable.ic_repeat_one_on)
        assertThat(oneState.contentDescription).isEqualTo("Repeat one")
    }
}
