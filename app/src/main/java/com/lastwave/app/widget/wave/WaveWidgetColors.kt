package com.lastwave.app.widget.wave

import android.content.Context
import android.content.res.Configuration
import android.os.Build

data class WaveWidgetColors(
    val background: Int,
    val onBackground: Int,
    val accent: Int,
    val onAccent: Int,
    val container: Int,
    val muted: Int,
) {
    companion object {
        fun theme(context: Context): WaveWidgetColors {
            val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val accent = runCatching { context.getColor(android.R.color.system_accent1_500) }.getOrNull()
                val background = runCatching {
                    context.getColor(
                        if (dark) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_50,
                    )
                }.getOrNull()
                if (accent != null && background != null) return derive(accent, dark, background)
            }
            return derive(if (dark) 0xFFC9FB00.toInt() else 0xFF00666D.toInt(), dark)
        }

        fun derive(swatch: Int, dark: Boolean, preferredBackground: Int? = null): WaveWidgetColors {
            val accent = WaveColorMath.ensureOpaque(
                WaveColorMath.ensureSaturation(swatch, minimumSpread = 44),
            )
            val background = preferredBackground ?: if (dark) {
                WaveColorMath.mix(accent, 0xFF061416.toInt(), 0.82f)
            } else {
                WaveColorMath.mix(accent, 0xFFF7FAF7.toInt(), 0.90f)
            }
            val container = if (dark) {
                WaveColorMath.mix(accent, background, 0.70f)
            } else {
                WaveColorMath.mix(accent, background, 0.82f)
            }
            val onBackground = WaveColorMath.highContrastForeground(background)
            val onAccent = WaveColorMath.highContrastForeground(accent)
            var muted = WaveColorMath.mix(onBackground, background, if (dark) 0.28f else 0.34f)
            if (WaveColorMath.contrastRatio(muted, background) < 4.5) muted = onBackground
            return WaveWidgetColors(
                background = WaveColorMath.ensureOpaque(background),
                onBackground = onBackground,
                accent = accent,
                onAccent = onAccent,
                container = WaveColorMath.ensureOpaque(container),
                muted = muted,
            )
        }
    }
}

internal object WaveColorMath {
    fun ensureOpaque(color: Int): Int = color or (0xFF shl 24)

    /** Preserve the cover hue with a rich, dark surface that keeps white controls readable. */
    fun stripBackground(accent: Int): Int {
        val channels = intArrayOf(red(accent), green(accent), blue(accent))
        val maximum = channels.maxOrNull()!!.coerceAtLeast(1)
        val minimum = channels.minOrNull()!!
        val spread = (maximum - minimum).coerceAtLeast(1)
        val saturation = (spread.toFloat() / maximum).coerceIn(0.76f, 0.94f)
        val value = (maximum / 255f * 0.62f).coerceIn(0.34f, 0.55f)
        val floor = value * (1f - saturation)
        val chroma = value * saturation
        val vivid = channels.map { channel ->
            ((floor + (channel - minimum).toFloat() / spread * chroma) * 255f).toInt()
        }
        var background = argb(255, vivid[0], vivid[1], vivid[2])
        while (contrastRatio(0xFFFFFFFF.toInt(), background) < 4.5) {
            background = mix(background, 0xFF000000.toInt(), 0.06f)
        }
        return background
    }

    fun mix(first: Int, second: Int, secondWeight: Float): Int {
        val weight = secondWeight.coerceIn(0f, 1f)
        val inverse = 1f - weight
        return argb(
            255,
            (red(first) * inverse + red(second) * weight).toInt(),
            (green(first) * inverse + green(second) * weight).toInt(),
            (blue(first) * inverse + blue(second) * weight).toInt(),
        )
    }

    fun ensureSaturation(color: Int, minimumSpread: Int): Int {
        val r = red(color)
        val g = green(color)
        val b = blue(color)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max - min >= minimumSpread) return ensureOpaque(color)
        val average = (r + g + b) / 3
        val boosted = intArrayOf(r, g, b).mapIndexed { index, component ->
            val direction = when {
                index == ((r * 3 + g * 5 + b * 7) % 3) -> 1
                component < average -> -1
                else -> 0
            }
            (component + direction * minimumSpread / 2).coerceIn(0, 255)
        }
        return argb(255, boosted[0], boosted[1], boosted[2])
    }

    fun highContrastForeground(background: Int): Int {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        return if (contrastRatio(white, background) >= contrastRatio(black, background)) white else black
    }

    fun contrastRatio(foreground: Int, background: Int): Double {
        val lighter = maxOf(luminance(foreground), luminance(background))
        val darker = minOf(luminance(foreground), luminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) normalized / 12.92
            else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        return channel(red(color)) * 0.2126 + channel(green(color)) * 0.7152 + channel(blue(color)) * 0.0722
    }

    private fun red(color: Int) = color ushr 16 and 0xFF
    private fun green(color: Int) = color ushr 8 and 0xFF
    private fun blue(color: Int) = color and 0xFF
    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or
            (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or
            blue.coerceIn(0, 255)
}
