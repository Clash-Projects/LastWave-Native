package com.lastwave.app.ui.player.lyricsshare

import com.google.common.truth.Truth.assertThat
import kotlin.math.roundToInt
import org.junit.Test

class ShareCardMathTest {

    @Test
    fun cardDimensions_target1080_clampsBetween1to1And9to16() {
        val targetWidth = 1080
        val scale = 1.0f

        val width = (targetWidth * scale).roundToInt().coerceAtLeast(320)
        val minH = (width * 1.0f).roundToInt()
        val maxH = (width * 16.0f / 9.0f).roundToInt()

        assertThat(width).isEqualTo(1080)
        // 1:1 ratio -> 1080 x 1080 (min square clamp)
        assertThat(minH).isEqualTo(1080)
        // 9:16 ratio -> 1080 x 1920 (max story clamp)
        assertThat(maxH).isEqualTo(1920)

        // Short lyrics should clamp to 1080 (1:1)
        val measuredShort = 900
        val clampedShort = measuredShort.coerceIn(minH, maxH)
        assertThat(clampedShort).isEqualTo(1080)

        // Long lyrics should clamp to 1920 (9:16)
        val measuredLong = 2400
        val clampedLong = measuredLong.coerceIn(minH, maxH)
        assertThat(clampedLong).isEqualTo(1920)

        // Intermediate lyrics stays within bounds
        val measuredMid = 1450
        val clampedMid = measuredMid.coerceIn(minH, maxH)
        assertThat(clampedMid).isEqualTo(1450)
    }

    @Test
    fun cardDimensions_scaled075_preservesAspectBounds() {
        val targetWidth = 1080
        val scale = 0.75f

        val width = (targetWidth * scale).roundToInt().coerceAtLeast(320)
        val minH = (width * 1.0f).roundToInt()
        val maxH = (width * 16.0f / 9.0f).roundToInt()

        assertThat(width).isEqualTo(810)
        assertThat(minH).isEqualTo(810)
        assertThat(maxH).isEqualTo(1440)

        val ratioMin = width.toDouble() / minH
        assertThat(ratioMin).isWithin(0.01).of(1.0)

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
