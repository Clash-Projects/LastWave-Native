package com.lastwave.app.ui.player.lyricsshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.lastwave.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ShareCardRenderer {

    const val BASE_WIDTH: Int = 1080
    const val MIN_HEIGHT: Int = 1350 // 4:5
    const val MAX_HEIGHT: Int = 1920 // 9:16

    /**
     * Renders the card into an immutable software [Bitmap] with automatic OOM retry.
     */
    suspend fun renderCard(
        context: Context,
        spec: ShareCardSpec,
    ): Bitmap = withContext(Dispatchers.Default) {
        try {
            renderInternal(context, spec)
        } catch (oom: OutOfMemoryError) {
            System.gc()
            val retrySpec = spec.copy(scaleFactor = spec.scaleFactor * 0.75f)
            renderInternal(context, retrySpec)
        }
    }

    private fun renderInternal(
        context: Context,
        spec: ShareCardSpec,
    ): Bitmap {
        val scale = spec.scaleFactor
        val width = (spec.targetWidth * scale).roundToInt().coerceAtLeast(320)
        val minH = ((spec.targetWidth * 5 / 4) * scale).roundToInt()
        val maxH = ((spec.targetWidth * 16 / 9) * scale).roundToInt()

        // Measure content height requirements
        val measuredHeight = measureRequiredHeight(context, spec, width, scale)
        val height = measuredHeight.coerceIn(minH, maxH)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (spec.mode == CardMode.Song) {
            renderSongCard(canvas, context, spec, width, height, scale)
        } else {
            renderLyricsCard(canvas, context, spec, width, height, scale)
        }

        return bitmap
    }

    private fun getTypeface(context: Context, weight: Int): Typeface {
        return try {
            val base = ResourcesCompat.getFont(context, R.font.google_sans_flex)
            if (base != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(base, weight, false)
            } else {
                base ?: Typeface.create(Typeface.DEFAULT, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
            }
        } catch (_: Throwable) {
            Typeface.create(Typeface.DEFAULT, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun measureRequiredHeight(
        context: Context,
        spec: ShareCardSpec,
        width: Int,
        scale: Float,
    ): Int {
        val hPadding = (72 * scale).roundToInt()
        val contentWidth = width - (hPadding * 2)

        return if (spec.mode == CardMode.Song) {
            val artSize = (440 * scale).roundToInt()
            val topMargin = (72 * scale).roundToInt()
            val artToTitle = (28 * scale).roundToInt()
            val titleH = (44 * scale).roundToInt()
            val artistH = (32 * scale).roundToInt()
            val dividerSpace = (36 * scale).roundToInt()
            val lyricsHeight = measureLyricsBlockHeight(context, spec, contentWidth, scale, maxFontSize = 46f)
            val footerSpace = (100 * scale).roundToInt()

            topMargin + artSize + artToTitle + titleH + artistH + dividerSpace + lyricsHeight + footerSpace
        } else {
            val topMargin = (84 * scale).roundToInt()
            val attrHeight = (54 * scale).roundToInt()
            val lyricsHeight = measureLyricsBlockHeight(context, spec, contentWidth, scale, maxFontSize = 58f)
            val footerSpace = (120 * scale).roundToInt()

            topMargin + attrHeight + lyricsHeight + footerSpace
        }
    }

    private fun measureLyricsBlockHeight(
        context: Context,
        spec: ShareCardSpec,
        contentWidth: Int,
        scale: Float,
        maxFontSize: Float,
    ): Int {
        val boldTypeface = getTypeface(context, 700)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldTypeface
            textSize = maxFontSize * scale
        }

        var totalH = 0
        val lineSpacing = (18 * scale).roundToInt()
        for (line in spec.lines) {
            val layout = createStaticLayout(line.text, textPaint, contentWidth, line.isRtl)
            totalH += layout.height + lineSpacing
        }
        return totalH
    }

    private fun createStaticLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        isRtl: Boolean,
    ): StaticLayout {
        val alignment = if (isRtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
        val safeWidth = max(width, 10)
        return try {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)?.let { builder ->
                builder.setAlignment(alignment)
                    .setLineSpacing(0f, 1.25f)
                    .setIncludePad(true) // Crucial for Hindi/Devanagari matras and Arabic ascenders
                    .build()
            } ?: @Suppress("DEPRECATION") StaticLayout(text, 0, text.length, paint, safeWidth, alignment, 1.25f, 0f, true)
        } catch (_: Throwable) {
            @Suppress("DEPRECATION")
            StaticLayout(text, 0, text.length, paint, safeWidth, alignment, 1.25f, 0f, true)
        }
    }

    // ==========================================
    // SONG CARD RENDERING
    // ==========================================
    private fun renderSongCard(
        canvas: Canvas,
        context: Context,
        spec: ShareCardSpec,
        width: Int,
        height: Int,
        scale: Float,
    ) {
        // 1. Blurred background image
        val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val blurBitmap = spec.blurredBackgroundBitmap
        if (blurBitmap != null && !blurBitmap.isRecycled) {
            val src = Rect(0, 0, blurBitmap.width, blurBitmap.height)
            val dst = Rect(0, 0, width, height)
            canvas.drawBitmap(blurBitmap, src, dst, bgPaint)
        } else {
            val fallback = ArtworkResolver.createBrandFallbackGradient(spec.swatchColor, width, height)
            canvas.drawBitmap(fallback, 0f, 0f, bgPaint)
        }

        // 2. Color scrim overlay with high-contrast guarantee
        val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.swatchColor
            alpha = if (spec.derivedColors.isDarkBackground) 180 else 215
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        // Dark gradient scrim from top to bottom to ground the layout
        val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(0x44000000, 0x11000000, 0x88000000.toInt()),
                floatArrayOf(0.0f, 0.45f, 1.0f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignettePaint)

        val hPadding = (72 * scale).roundToInt()
        val contentWidth = width - (hPadding * 2)
        var currentY = (64 * scale)

        // 3. Prominent Cover Art (centered, rounded corners, soft shadow)
        val artSize = (440 * scale).roundToInt()
        val artX = (width - artSize) / 2f
        val artRect = RectF(artX, currentY, artX + artSize, currentY + artSize)
        val cornerRadius = 24f * scale

        // Soft shadow
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55000000
            maskFilter = null
        }
        val shadowRect = RectF(artRect.left, artRect.top + 6 * scale, artRect.right, artRect.bottom + 10 * scale)
        canvas.drawRoundRect(shadowRect, cornerRadius, cornerRadius, shadowPaint)

        // Draw cover bitmap or placeholder
        val coverBitmap = spec.coverBitmap
        if (coverBitmap != null && !coverBitmap.isRecycled) {
            val roundedCover = getRoundedBitmap(coverBitmap, cornerRadius, artSize, artSize)
            canvas.drawBitmap(roundedCover, artRect.left, artRect.top, null)
        } else {
            // Placeholder rounded card with brand logo
            val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    artRect.left, artRect.top, artRect.right, artRect.bottom,
                    intArrayOf(spec.swatchColor, ColorContrastHelper.DEFAULT_DARK),
                    null,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRoundRect(artRect, cornerRadius, cornerRadius, placeholderPaint)
            drawLogoMark(canvas, context, artRect.centerX(), artRect.centerY(), (64 * scale).roundToInt(), spec.derivedColors.lyricsTextColor)
        }

        currentY += artSize + (28 * scale)

        // 4. Song title & artist
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = getTypeface(context, 700)
            textSize = 38f * scale
            color = spec.derivedColors.lyricsTextColor
        }
        val titleLayout = createStaticLayout(spec.title, titlePaint, contentWidth, spec.isRtl)
        canvas.save()
        canvas.translate(hPadding.toFloat(), currentY)
        titleLayout.draw(canvas)
        canvas.restore()
        currentY += titleLayout.height + (6 * scale)

        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = getTypeface(context, 450)
            textSize = 28f * scale
            color = spec.derivedColors.secondaryTextColor
        }
        val artistLayout = createStaticLayout(spec.artist, artistPaint, contentWidth, spec.isRtl)
        canvas.save()
        canvas.translate(hPadding.toFloat(), currentY)
        artistLayout.draw(canvas)
        canvas.restore()
        currentY += artistLayout.height + (24 * scale)

        // 5. Thin divider
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.derivedColors.secondaryTextColor
            alpha = 50
            strokeWidth = 1.5f * scale
        }
        canvas.drawLine(hPadding.toFloat(), currentY, (width - hPadding).toFloat(), currentY, dividerPaint)
        currentY += (24 * scale)

        // 6. Selected Lyrics (auto-fitted)
        val footerAreaH = (88 * scale)
        val availableLyricsH = (height - currentY - footerAreaH).coerceAtLeast(100f)
        drawFittedLyrics(canvas, context, spec, hPadding, currentY, contentWidth, availableLyricsH, scale, startFontSize = 46f)

        // 7. Brand footer
        drawFooter(canvas, context, width, height, scale, spec.derivedColors.brandTextColor)
    }

    // ==========================================
    // LYRICS CARD RENDERING
    // ==========================================
    private fun renderLyricsCard(
        canvas: Canvas,
        context: Context,
        spec: ShareCardSpec,
        width: Int,
        height: Int,
        scale: Float,
    ) {
        // Flat / subtle gradient background
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val darkerBg = AndroidColor.rgb(
                (AndroidColor.red(spec.swatchColor) * 0.45f).toInt(),
                (AndroidColor.green(spec.swatchColor) * 0.45f).toInt(),
                (AndroidColor.blue(spec.swatchColor) * 0.45f).toInt(),
            )
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(spec.swatchColor, darkerBg, ColorContrastHelper.DEFAULT_DARK),
                floatArrayOf(0.0f, 0.6f, 1.0f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val hPadding = (80 * scale).roundToInt()
        val contentWidth = width - (hPadding * 2)
        var currentY = (72 * scale)

        // Small attribution: "Title • Artist"
        val attrPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = getTypeface(context, 600)
            textSize = 28f * scale
            color = spec.derivedColors.secondaryTextColor
        }
        val attrText = "${spec.title}  •  ${spec.artist}"
        val attrLayout = createStaticLayout(attrText, attrPaint, contentWidth, spec.isRtl)
        canvas.save()
        canvas.translate(hPadding.toFloat(), currentY)
        attrLayout.draw(canvas)
        canvas.restore()
        currentY += attrLayout.height + (36 * scale)

        // Hero Lyrics
        val footerAreaH = (100 * scale)
        val availableLyricsH = (height - currentY - footerAreaH).coerceAtLeast(100f)
        drawFittedLyrics(canvas, context, spec, hPadding, currentY, contentWidth, availableLyricsH, scale, startFontSize = 58f)

        // Brand footer
        drawFooter(canvas, context, width, height, scale, spec.derivedColors.brandTextColor)
    }

    /**
     * Auto-fits lyrics font size so 1 to 5 lines never overflow or clip.
     */
    private fun drawFittedLyrics(
        canvas: Canvas,
        context: Context,
        spec: ShareCardSpec,
        x: Int,
        startY: Float,
        contentWidth: Int,
        availableHeight: Float,
        scale: Float,
        startFontSize: Float,
    ) {
        val boldTypeface = getTypeface(context, 700)
        var fontSize = startFontSize
        val minFontSize = 24f
        val lineSpacing = (20 * scale)

        var layouts: List<StaticLayout>
        var totalHeight: Float

        do {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldTypeface
                textSize = fontSize * scale
                color = spec.derivedColors.lyricsTextColor
            }
            layouts = spec.lines.map { createStaticLayout(it.text, paint, contentWidth, it.isRtl) }
            totalHeight = layouts.sumOf { it.height.toDouble() }.toFloat() + ((layouts.size - 1) * lineSpacing)

            if (totalHeight <= availableHeight || fontSize <= minFontSize) {
                break
            }
            fontSize -= 2f
        } while (fontSize >= minFontSize)

        // Center vertically in available slot if there's spare room
        val offsetTop = if (availableHeight > totalHeight) {
            startY + ((availableHeight - totalHeight) * 0.35f)
        } else {
            startY
        }

        var y = offsetTop
        for (layout in layouts) {
            canvas.save()
            canvas.translate(x.toFloat(), y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + lineSpacing
        }
    }

    private fun drawFooter(
        canvas: Canvas,
        context: Context,
        width: Int,
        height: Int,
        scale: Float,
        brandColor: Int,
    ) {
        val footerY = height - (56 * scale)
        val logoSize = (32 * scale).roundToInt()
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = getTypeface(context, 750)
            textSize = 24f * scale
            color = brandColor
        }

        val brandName = "LastWave"
        val textWidth = textPaint.measureText(brandName)
        val gap = 12f * scale
        val totalWidth = logoSize + gap + textWidth
        val startX = (width - totalWidth) / 2f

        // Draw logo mark
        drawLogoMark(canvas, context, startX + (logoSize / 2f), footerY, logoSize, brandColor)

        // Draw brand name
        val textY = footerY + (textPaint.textSize * 0.35f)
        canvas.drawText(brandName, startX + logoSize + gap, textY, textPaint)
    }

    private fun drawLogoMark(
        canvas: Canvas,
        context: Context,
        centerX: Float,
        centerY: Float,
        size: Int,
        tintColor: Int,
    ) {
        val drawable = runCatching {
            ContextCompat.getDrawable(context, R.drawable.ic_lastwave_logo_mono)
        }.getOrNull() ?: return
        val half = size / 2
        drawable.setBounds((centerX - half).toInt(), (centerY - half).toInt(), (centerX + half).toInt(), (centerY + half).toInt())
        drawable.colorFilter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
        drawable.draw(canvas)
    }

    private fun getRoundedBitmap(source: Bitmap, cornerRadius: Float, targetW: Int, targetH: Int): Bitmap {
        val output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, targetW.toFloat(), targetH.toFloat())

        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

        val srcRect = Rect(0, 0, source.width, source.height)
        val dstRect = Rect(0, 0, targetW, targetH)
        canvas.drawBitmap(source, srcRect, dstRect, paint)
        return output
    }
}
