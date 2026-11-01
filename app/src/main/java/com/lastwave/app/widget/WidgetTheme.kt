package com.lastwave.app.widget

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.lastwave.app.R
import java.io.File

/**
 * Shared artwork + dynamic-colour pipeline for both widgets.
 *
 * The expensive work (file decode, squircle rounding, Palette) used to run
 * once per size layout per widget type on every push (8x per update). It now
 * runs once per distinct cover and is cached, so a song change only costs one
 * cheap RemoteViews bind per layout.
 */
internal object WidgetTheme {

    private const val ART_MAX_PX = 256
    private const val PALETTE_PX = 48

    internal class Colors(
        val surface: Int,
        val primary: Int,
        val onPrimary: Int,
        val text: Int,
        val subText: Int,
        val track: Int,
    )

    internal class Themed(val art: Bitmap?, val colors: Colors?)

    private val NONE = Themed(null, null)

    private var cacheKey: String? = null
    private var cacheValue: Themed = NONE

    /** Resolves rounded art + colours for the current cover, using the cache when unchanged. */
    @Synchronized
    fun resolve(artPath: String?, memoryArt: Bitmap?): Themed {
        val memory = memoryArt?.takeIf { !it.isRecycled }
        val key = when {
            memory != null -> "m${System.identityHashCode(memory)}:${memory.width}x${memory.height}"
            !artPath.isNullOrBlank() -> {
                val file = File(artPath)
                if (!file.exists()) return NONE
                "f$artPath:${file.lastModified()}:${file.length()}"
            }
            else -> return NONE
        }
        if (key == cacheKey) return cacheValue

        val raw = memory ?: runCatching { BitmapFactory.decodeFile(artPath) }.getOrNull()
        if (raw == null) return NONE
        val source = if (raw.config == Bitmap.Config.HARDWARE) {
            raw.copy(Bitmap.Config.ARGB_8888, false) ?: return NONE
        } else raw
        val sourceIsTemp = source !== memory
        val value = runCatching {
            val scaled = downscale(source, ART_MAX_PX)
            val colors = extractColors(scaled)
            val rounded = roundedCorners(scaled, 0.22f)
            if (scaled !== source && scaled !== rounded) scaled.recycle()
            Themed(rounded, colors)
        }.getOrDefault(NONE)
        if (sourceIsTemp && source !== value.art) runCatching { source.recycle() }
        cacheKey = key
        cacheValue = value
        return value
    }

    private fun downscale(src: Bitmap, maxPx: Int): Bitmap {
        val largest = maxOf(src.width, src.height)
        if (largest <= maxPx) return src
        val scale = maxPx.toFloat() / largest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun extractColors(art: Bitmap): Colors? {
        // Software copy: hardware bitmaps (Coil) cannot be sampled by Palette.
        val small = Bitmap.createScaledBitmap(
            if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) ?: return null else art,
            PALETTE_PX, PALETTE_PX, true,
        )
        val palette = Palette.from(small).maximumColorCount(12).generate()
        small.recycle()
        val swatch = palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.dominantSwatch
            ?: palette.mutedSwatch
            ?: return null

        // Keep the accent punchy but never so light/dark that the UI vanishes.
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(swatch.rgb, hsl)
        hsl[1] = hsl[1].coerceAtLeast(0.35f)
        hsl[2] = hsl[2].coerceIn(0.45f, 0.72f)
        val primary = ColorUtils.HSLToColor(hsl)

        val base = Color.rgb(0x14, 0x14, 0x18)
        val surface = ColorUtils.blendARGB(base, primary, 0.40f)
        val text = readableOn(surface)
        return Colors(
            surface = surface,
            primary = primary,
            onPrimary = readableOn(primary),
            text = text,
            subText = ColorUtils.setAlphaComponent(text, 0xB8),
            track = ColorUtils.setAlphaComponent(text, 0x33),
        )
    }

    private fun readableOn(bg: Int): Int {
        val light = Color.WHITE
        val dark = Color.rgb(0x16, 0x10, 0x12)
        return if (ColorUtils.calculateContrast(light, bg) >= ColorUtils.calculateContrast(dark, bg)) light else dark
    }

    /**
     * Tints the whole card from the cover: background, text, buttons,
     * icons and progress. Works for both widget families; ids absent from a
     * given layout are ignored by RemoteViews.
     */
    fun apply(views: RemoteViews, colors: Colors?) {
        if (colors == null) return
        // Text + icons work on every API level.
        views.setTextColor(R.id.widget_title, colors.text)
        views.setTextColor(R.id.widget_subtitle, colors.subText)
        views.setTextColor(R.id.widget_state, colors.onPrimary)
        views.setTextColor(R.id.widget_play_pause_text, colors.onPrimary)
        views.setInt(R.id.widget_play_pause, "setColorFilter", colors.onPrimary)
        views.setInt(R.id.widget_play_pause_icon, "setColorFilter", colors.onPrimary)
        views.setInt(R.id.widget_next, "setColorFilter", colors.onPrimary)
        views.setInt(R.id.widget_prev, "setColorFilter", colors.onPrimary)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val surface = ColorStateList.valueOf(colors.surface)
            val primary = ColorStateList.valueOf(colors.primary)
            views.setColorStateList(R.id.widget_root, "setBackgroundTintList", surface)
            views.setColorStateList(R.id.widget_play_pause_container, "setBackgroundTintList", primary)
            views.setColorStateList(R.id.widget_next_container, "setBackgroundTintList", primary)
            // Obsidian family: the pill/button themselves carry the background.
            views.setColorStateList(R.id.widget_play_pause, "setBackgroundTintList", primary)
            views.setColorStateList(R.id.widget_next, "setBackgroundTintList", primary)
            views.setColorStateList(R.id.widget_progress, "setProgressTintList", primary)
            views.setColorStateList(R.id.widget_progress, "setProgressBackgroundTintList", ColorStateList.valueOf(colors.track))
        }
    }

    private fun roundedCorners(src: Bitmap, radiusFraction: Float): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = minOf(w, h) * radiusFraction
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val drawable = if (src.config == Bitmap.Config.HARDWARE) {
            src.copy(Bitmap.Config.ARGB_8888, false) ?: return src
        } else src
        canvas.drawBitmap(drawable, 0f, 0f, paint)
        if (drawable !== src) drawable.recycle()
        return out
    }
}
