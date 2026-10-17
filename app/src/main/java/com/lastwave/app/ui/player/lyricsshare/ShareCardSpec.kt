package com.lastwave.app.ui.player.lyricsshare

import android.graphics.Bitmap

enum class CardMode {
    Song,
    Lyrics,
}

/**
 * Pure data model defining all parameters needed to render a card.
 */
data class ShareCardSpec(
    val mode: CardMode = CardMode.Song,
    val lines: List<ShareLine>,
    val title: String,
    val artist: String,
    val coverBitmap: Bitmap? = null,
    val blurredBackgroundBitmap: Bitmap? = null,
    val swatchColor: Int = ColorContrastHelper.BRAND_LIME,
    val derivedColors: DerivedCardColors = ColorContrastHelper.deriveTextColors(swatchColor),
    val isCoverBackground: Boolean = false,
    val isRtl: Boolean = false,
    val targetWidth: Int = 1080,
    val scaleFactor: Float = 1.0f,
)
