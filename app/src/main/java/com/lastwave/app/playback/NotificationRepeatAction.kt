package com.lastwave.app.playback

import androidx.annotation.DrawableRes
import androidx.media3.common.Player
import com.lastwave.app.R

/**
 * Handles the stateful Repeat button in the Android media notification bar
 * (GitHub issue #685), replacing the Shuffle button in the rightmost custom layout slot.
 */
object NotificationRepeatAction {
    const val ACTION_TOGGLE_REPEAT = "com.lastwave.app.playback.TOGGLE_REPEAT"
    const val ACTION_REPEAT = "com.lastwave.app.playback.REPEAT"
    const val ACTION_LIKE = "com.lastwave.app.playback.LIKE"

    data class RepeatState(
        @DrawableRes val iconRes: Int,
        val contentDescription: String,
    )

    data class NotificationCustomAction(
        val action: String,
        val name: String,
        @DrawableRes val iconRes: Int,
    )

    /**
     * Maps the player's current [repeatMode] to its dynamic icon and content description:
     * - REPEAT_MODE_OFF -> ic_repeat / "Repeat off"
     * - REPEAT_MODE_ALL -> ic_repeat_on / "Repeat all"
     * - REPEAT_MODE_ONE -> ic_repeat_one_on / "Repeat one"
     */
    fun resolveRepeatState(repeatMode: Int): RepeatState = when (repeatMode) {
        Player.REPEAT_MODE_ONE -> RepeatState(
            iconRes = R.drawable.ic_repeat_one_on,
            contentDescription = "Repeat one",
        )
        Player.REPEAT_MODE_ALL -> RepeatState(
            iconRes = R.drawable.ic_repeat_on,
            contentDescription = "Repeat all",
        )
        else -> RepeatState(
            iconRes = R.drawable.ic_repeat,
            contentDescription = "Repeat off",
        )
    }

    /**
     * Cycles repeat modes in order: OFF -> ALL -> ONE -> OFF.
     */
    fun cycleRepeatMode(currentMode: Int): Int = when (currentMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }

    /**
     * Builds the MediaSession notification custom layout actions.
     * Places Favorite on the far left (slot 0) and Repeat on the far right (slot 1),
     * with no Shuffle button remaining in the notification layout.
     */
    fun buildCustomLayout(isLiked: Boolean, repeatMode: Int): List<NotificationCustomAction> {
        val likeAction = NotificationCustomAction(
            action = ACTION_LIKE,
            name = if (isLiked) "Unlike song" else "Like song",
            iconRes = if (isLiked) R.drawable.ic_wave_widget_favorite_filled else R.drawable.ic_wave_widget_favorite,
        )
        val repeatState = resolveRepeatState(repeatMode)
        val repeatAction = NotificationCustomAction(
            action = ACTION_TOGGLE_REPEAT,
            name = repeatState.contentDescription,
            iconRes = repeatState.iconRes,
        )
        return listOf(likeAction, repeatAction)
    }
}
