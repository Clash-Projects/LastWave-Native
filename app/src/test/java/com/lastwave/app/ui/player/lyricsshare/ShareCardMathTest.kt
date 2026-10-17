package com.lastwave.app.ui.player.lyricsshare

import com.google.common.truth.Truth.assertThat
import kotlin.math.roundToInt
import org.junit.Test

class ShareCardMathTest {

    @Test
    fun cardDimensions_target1080_clampsBetween4to5And9to16() {
        val targetWidth = 1080
        val scale = 1.0f

        val width = (targetWidth * scale).roundToInt().coerceAtLeast(320)
        val minH = ((targetWidth * 5 / 4) * scale).roundToInt()
        val maxH = ((targetWidth * 16 / 9) * scale).roundToInt()

        assertThat(width).isEqualTo(1080)
        // 4:5 ratio -> 1080 x 1350
        assertThat(minH).isEqualTo(1350)
        // 9:16 ratio -> 1080 x 1920
        assertThat(maxH).isEqualTo(1920)

        // Short lyrics should clamp to 1350 (4:5)
        val measuredShort = 900
        val clampedShort = measuredShort.coerceIn(minH, maxH)
        assertThat(clampedShort).isEqualTo(1350)

        // Long lyrics should clamp to 1920 (9:16)
        val measuredLong = 2400
        val clampedLong = measuredLong.coerceIn(minH, maxH)
        assertThat(clampedLong).isEqualTo(1920)

        // Intermediate lyrics stays within bounds
        val measuredMid = 1600
        val clampedMid = measuredMid.coerceIn(minH, maxH)
        assertThat(clampedMid).isEqualTo(1600)
    }

    @Test
    fun cardDimensions_scaled075_preservesAspectBounds() {
        val targetWidth = 1080
        val scale = 0.75f

        val width = (targetWidth * scale).roundToInt().coerceAtLeast(320)
        val minH = ((targetWidth * 5 / 4) * scale).roundToInt()
        val maxH = ((targetWidth * 16 / 9) * scale).roundToInt()

        assertThat(width).isEqualTo(810)
        assertThat(minH).isEqualTo(1013)
        assertThat(maxH).isEqualTo(1440)

        val ratioMin = width.toDouble() / minH
        assertThat(ratioMin).isWithin(0.01).of(4.0 / 5.0)

        val ratioMax = width.toDouble() / maxH
        assertThat(ratioMax).isWithin(0.01).of(9.0 / 16.0)
    }

    @Test
    fun truncateEllipsis_keepsTextUnderLimit() {
        val longTitle = "A".repeat(100)
        val maxChars = 40
        val truncated = if (longTitle.length > maxChars) {
            longTitle.take(maxChars - 1) + "…"
        } else {
            longTitle
        }

        assertThat(truncated).hasLength(40)
        assertThat(truncated).endsWith("…")
    }
}
