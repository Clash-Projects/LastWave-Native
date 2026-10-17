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
import kotlin.math.roundToInt

object ShareCardRenderer {

    const val BASE_WIDTH: Int = 1080
    const val MIN_HEIGHT: Int = 1080 // 1:1 square aspect ratio minimum (no big empty areas)
    const val MAX_HEIGHT: Int = 1920 // 9:16 vertical story aspect ratio maximum

    const val LYRIC_LINE_SPACING_MULTIPLIER: Float = 1.18f
    const val LYRIC_BLOCK_GAP_FACTOR: Float = 0.45f

    const val BASE_LYRIC_FONT_SIZE: Float = 84f
    const val MIN_LYRIC_FONT_SIZE: Float = 52f

    const val BASE_TITLE_FONT_SIZE: Float = 46f
    const val BASE_ARTIST_FONT_SIZE: Float = 40f

    const val BASE_SONG_LYRIC_FONT_SIZE: Float = 64f
    const val MIN_SONG_LYRIC_FONT_SIZE: Float = 44f

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
        val minH = (width * 1.0f).roundToInt() // 1:1 minimum
        val maxH = (width * 16.0f / 9.0f).roundToInt() // 9:16 maximum

        val hPadding = (60 * scale).roundToInt()
        val contentWidth = width - (hPadding * 2)

        // Measure required height and calculate fitted font size
        val layoutPlan = measureLayoutPlan(context, spec, contentWidth, scale, maxH)
        val height = layoutPlan.requiredHeight.coerceIn(minH, maxH)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (spec.mode == CardMode.Song) {
            renderSongCard(canvas, context, spec, width, height, hPadding, contentWidth, scale, layoutPlan)
        } else {
            renderLyricsCard(canvas, context, spec, width, height, hPadding, contentWidth, scale, layoutPlan)
        }

        return bitmap
    }

    fun getTypeface(context: Context, weight: Int): Typeface {
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

    fun wrapTextToWidth(
        text: CharSequence,
        paint: TextPaint,
        maxWidth: Int,
    ): CharSequence {
        if (maxWidth <= 0) return text
        val rawLines = text.split("\n")
        val result = mutableListOf<String>()

        for (rawLine in rawLines) {
            if (paint.measureText(rawLine) <= maxWidth) {
                result.add(rawLine)
            } else {
                val words = rawLine.split(" ")
                if (words.size <= 1) {
                    result.add(rawLine)
                } else {
                    var currentLine = StringBuilder()
                    for (word in words) {
                        val candidate = if (currentLine.isEmpty()) word else "$currentLine $word"
                        if (paint.measureText(candidate) <= maxWidth) {
                            currentLine = StringBuilder(candidate)
                        } else {
                            if (currentLine.isNotEmpty()) {
                                result.add(currentLine.toString())
                                currentLine = StringBuilder(word)
                            } else {
                                result.add(word)
                            }
                        }
                    }
                    if (currentLine.isNotEmpty()) {
                        result.add(currentLine.toString())
                    }
                }
            }
        }
        return result.joinToString("\n")
    }

    fun createStaticLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        isRtl: Boolean,
        spacingMult: Float = LYRIC_LINE_SPACING_MULTIPLIER,
    ): StaticLayout {
        val alignment = if (isRtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
        val safeWidth = max(width, 10)
        val wrappedText = wrapTextToWidth(text, paint, safeWidth)
        return try {
            StaticLayout.Builder.obtain(wrappedText, 0, wrappedText.length, paint, safeWidth).let { builder ->
                builder.setAlignment(alignment)
                    .setLineSpacing(0f, spacingMult)
                    .setIncludePad(true) // Crucial for Hindi/Devanagari matras, Arabic marks, and ascenders
                    .build()
            }
        } catch (_: Throwable) {
            @Suppress("DEPRECATION")
            StaticLayout(wrappedText, 0, wrappedText.length, paint, safeWidth, alignment, spacingMult, 0f, true)
        }
    }

    private data class LayoutPlan(
        val fontSize: Float,
        val lyricLayouts: List<StaticLayout>,
        val lyricsBlockHeight: Int,
        val requiredHeight: Int,
        val headerHeight: Int = 0,
        val titleLayout: StaticLayout? = null,
        val artistLayout: StaticLayout? = null,
    )

    private fun measureLayoutPlan(
        context: Context,
        spec: ShareCardSpec,
        contentWidth: Int,
        scale: Float,
        maxAllowedHeight: Int,
    ): LayoutPlan {
        val boldTypeface = getTypeface(context, 700)
        val regularTypeface = getTypeface(context, 450)

        val topPadding = (60 * scale).roundToInt()
        val bottomPadding = (60 * scale).roundToInt()
        val footerHeight = (64 * scale).roundToInt()

        if (spec.mode == CardMode.Lyrics) {
            // Lyrics Card: Header has small cover thumbnail (180px) on left + Title & Artist on right
            val thumbSize = (180 * scale).roundToInt()
            val textGap = (24 * scale).roundToInt()
            val textWidth = (contentWidth - thumbSize - textGap).coerceAtLeast(100)

            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldTypeface
                textSize = BASE_TITLE_FONT_SIZE * scale
                color = spec.derivedColors.lyricsTextColor
            }
            val titleLayout = createStaticLayout(spec.title, titlePaint, textWidth, spec.isRtl, 1.15f)

            val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = regularTypeface
                textSize = BASE_ARTIST_FONT_SIZE * scale
                color = spec.derivedColors.secondaryTextColor
            }
            val artistLayout = createStaticLayout(spec.artist, artistPaint, textWidth, spec.isRtl, 1.15f)

            val textBlockHeight = titleLayout.height + (8 * scale).roundToInt() + artistLayout.height
            val headerHeight = max(thumbSize, textBlockHeight)

            val dividerSpace = (26 * scale).roundToInt() + (32 * scale).roundToInt()
            val footerGap = (84 * scale).roundToInt()

            var fontSize = BASE_LYRIC_FONT_SIZE * scale
            val floor = MIN_LYRIC_FONT_SIZE * scale

            var bestPlan: LayoutPlan? = null

            while (fontSize >= floor) {
                val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = boldTypeface
                    textSize = fontSize
                    color = spec.derivedColors.lyricsTextColor
                }

                val layouts = spec.lines.map { createStaticLayout(it.text, lyricPaint, contentWidth, it.isRtl) }
                val blockGap = fontSize * LYRIC_BLOCK_GAP_FACTOR
                val lyricsHeight = (layouts.sumOf { it.height.toDouble() } + (layouts.size - 1).coerceAtLeast(0) * blockGap).roundToInt()

                val totalReq = topPadding + headerHeight + dividerSpace + lyricsHeight + footerGap + footerHeight + bottomPadding

                val plan = LayoutPlan(
                    fontSize = fontSize,
                    lyricLayouts = layouts,
                    lyricsBlockHeight = lyricsHeight,
                    requiredHeight = totalReq,
                    headerHeight = headerHeight,
                    titleLayout = titleLayout,
                    artistLayout = artistLayout,
                )

                if (bestPlan == null || totalReq <= maxAllowedHeight) {
                    bestPlan = plan
                }
                if (totalReq <= maxAllowedHeight) {
                    return plan
                }

                fontSize -= (2f * scale)
            }

            return bestPlan!!
        } else {
            // Song Card: Hero Cover Art (680px) on top left, title, artist, divider, lyrics, brand footer
            val heroSize = (680 * scale).roundToInt()
            val heroToTitle = (28 * scale).roundToInt()

            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldTypeface
                textSize = BASE_TITLE_FONT_SIZE * scale
                color = spec.derivedColors.lyricsTextColor
            }
            val titleLayout = createStaticLayout(spec.title, titlePaint, contentWidth, spec.isRtl, 1.15f)

            val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = regularTypeface
                textSize = BASE_ARTIST_FONT_SIZE * scale
                color = spec.derivedColors.secondaryTextColor
            }
            val artistLayout = createStaticLayout(spec.artist, artistPaint, contentWidth, spec.isRtl, 1.15f)

            val textBlockHeight = titleLayout.height + (8 * scale).roundToInt() + artistLayout.height
            val dividerSpace = (24 * scale).roundToInt() + (28 * scale).roundToInt()
            val footerGap = (72 * scale).roundToInt()

            var fontSize = BASE_SONG_LYRIC_FONT_SIZE * scale
            val floor = MIN_SONG_LYRIC_FONT_SIZE * scale

            var bestPlan: LayoutPlan? = null

            while (fontSize >= floor) {
                val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = boldTypeface
                    textSize = fontSize
                    color = spec.derivedColors.lyricsTextColor
                }

                val layouts = spec.lines.map { createStaticLayout(it.text, lyricPaint, contentWidth, it.isRtl) }
                val blockGap = fontSize * LYRIC_BLOCK_GAP_FACTOR
                val lyricsHeight = (layouts.sumOf { it.height.toDouble() } + (layouts.size - 1).coerceAtLeast(0) * blockGap).roundToInt()

                val totalReq = topPadding + heroSize + heroToTitle + textBlockHeight + dividerSpace + lyricsHeight + footerGap + footerHeight + bottomPadding

                val plan = LayoutPlan(
                    fontSize = fontSize,
                    lyricLayouts = layouts,
                    lyricsBlockHeight = lyricsHeight,
                    requiredHeight = totalReq,
                    headerHeight = heroSize,
                    titleLayout = titleLayout,
                    artistLayout = artistLayout,
                )

                if (bestPlan == null || totalReq <= maxAllowedHeight) {
                    bestPlan = plan
                }
                if (totalReq <= maxAllowedHeight) {
                    return plan
                }

                fontSize -= (2f * scale)
            }

            return bestPlan!!
        }
    }

    // ==========================================
    // LYRICS CARD RENDERING (YT Music & Spotify style)
    // ==========================================
    private fun renderLyricsCard(
        canvas: Canvas,
        context: Context,
        spec: ShareCardSpec,
        width: Int,
        height: Int,
        hPadding: Int,
        contentWidth: Int,
        scale: Float,
        plan: LayoutPlan,
    ) {
        // 1. Background
        if (spec.isCoverBackground && spec.coverBitmap != null && !spec.coverBitmap.isRecycled) {
            // Full-bleed center-cropped cover art with ~50% dark scrim + gradient protection
            drawCenterCropCover(canvas, spec.coverBitmap, width, height)
            canvas.drawColor(0x80000000.toInt()) // ~50% black scrim

            val scrimGrad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(0x66000000, 0x10000000, 0x88000000.toInt()),
                    floatArrayOf(0.0f, 0.40f, 1.0f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimGrad)
        } else {
            // Rich flat / subtle same-hue gradient (YT style - no muddy blur)
            val topColor = spec.derivedColors.backgroundColor or 0xFF000000.toInt()
            val hsv = FloatArray(3)
            AndroidColor.colorToHSV(topColor, hsv)
            hsv[2] = (hsv[2] * 0.82f).coerceAtLeast(0.08f) // slightly deeper shade at bottom
            val bottomColor = AndroidColor.HSVToColor(0xFF, hsv) or 0xFF000000.toInt()

            canvas.drawColor(topColor) // Explicit opaque fill
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(topColor, bottomColor),
                    null,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        }

        var currentY = (60 * scale)

        // 2. Header: Rounded Cover Thumbnail (left) + Title and Artist (right)
        val thumbSize = (180 * scale).roundToInt()
        val thumbRadius = 22f * scale
        val textGap = (24 * scale).roundToInt()
        val textX = (hPadding + thumbSize + textGap).toFloat()

        if (spec.coverBitmap != null && !spec.coverBitmap.isRecycled) {
            val roundedThumb = getRoundedBitmap(spec.coverBitmap, thumbRadius, thumbSize, thumbSize)
            canvas.drawBitmap(roundedThumb, hPadding.toFloat(), currentY, null)
        } else {
            // Fallback thumbnail with subtle gradient and brand mark
            val thumbRect = RectF(hPadding.toFloat(), currentY, (hPadding + thumbSize).toFloat(), currentY + thumbSize)
            val thumbBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.argb(40, 255, 255, 255)
            }
            canvas.drawRoundRect(thumbRect, thumbRadius, thumbRadius, thumbBgPaint)
            drawLogoMark(canvas, context, thumbRect.centerX(), thumbRect.centerY(), (48 * scale).roundToInt(), spec.derivedColors.lyricsTextColor)
        }

        // Title and Artist alongside thumbnail
        val titleLayout = plan.titleLayout
        val artistLayout = plan.artistLayout
        if (titleLayout != null && artistLayout != null) {
            val textBlockHeight = titleLayout.height + (8 * scale).roundToInt() + artistLayout.height
            val textOffsetY = if (thumbSize > textBlockHeight) (thumbSize - textBlockHeight) / 2f else 0f
            val textStartY = currentY + textOffsetY

            canvas.save()
            canvas.translate(textX, textStartY)
            titleLayout.draw(canvas)
            canvas.restore()

            canvas.save()
            canvas.translate(textX, textStartY + titleLayout.height + (8 * scale))
            artistLayout.draw(canvas)
            canvas.restore()
        }

        currentY += plan.headerHeight + (26 * scale)

        // 3. Thin divider line
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.derivedColors.dividerColor
            strokeWidth = 2f * scale
        }
        canvas.drawLine(hPadding.toFloat(), currentY, (width - hPadding).toFloat(), currentY, dividerPaint)
        currentY += (32 * scale)

        // 4. Large bold tinted lyrics (word wrapped naturally, tight line spacing, generous block gap)
        val blockGap = plan.fontSize * LYRIC_BLOCK_GAP_FACTOR

        // Calculate available vertical breathing room if card is clamped to 1:1 square
        val footerAreaH = (64 * scale) + (60 * scale)
        val remainingSpace = height - currentY - plan.lyricsBlockHeight - footerAreaH
        if (remainingSpace > (40 * scale)) {
            currentY += (remainingSpace * 0.35f) // visually center lyrics slightly if tall square
        }

        for (layout in plan.lyricLayouts) {
            canvas.save()
            canvas.translate(hPadding.toFloat(), currentY)
            layout.draw(canvas)
            canvas.restore()
            currentY += layout.height + blockGap
        }

        // 5. Brand footer: bottom-left aligned to hPadding!
        drawBrandFooterLeft(canvas, context, hPadding, height, scale, spec.derivedColors.brandTextColor)
    }

    // ==========================================
    // SONG CARD RENDERING (Hero Cover, left-aligned)
    // ==========================================
    private fun renderSongCard(
        canvas: Canvas,
        context: Context,
        spec: ShareCardSpec,
        width: Int,
        height: Int,
        hPadding: Int,
        contentWidth: Int,
        scale: Float,
        plan: LayoutPlan,
    ) {
        // 1. Background: rich flat / subtle gradient of selected swatch (NO muddy blur!)
        val topColor = spec.derivedColors.backgroundColor or 0xFF000000.toInt()
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(topColor, hsv)
        hsv[2] = (hsv[2] * 0.82f).coerceAtLeast(0.08f)
        val bottomColor = AndroidColor.HSVToColor(0xFF, hsv) or 0xFF000000.toInt()

        canvas.drawColor(topColor) // Explicit opaque fill
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(topColor, bottomColor),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        var currentY = (60 * scale)

        // 2. Hero Cover Art: large (~65% width), left-aligned to hPadding (no stray centering, no dark bottom strip!)
        val heroSize = plan.headerHeight
        val cornerRadius = 32f * scale
        val heroRect = RectF(hPadding.toFloat(), currentY, (hPadding + heroSize).toFloat(), currentY + heroSize)

        if (spec.coverBitmap != null && !spec.coverBitmap.isRecycled) {
            val roundedHero = getRoundedBitmap(spec.coverBitmap, cornerRadius, heroSize, heroSize)
            canvas.drawBitmap(roundedHero, heroRect.left, heroRect.top, null)
        } else {
            // Placeholder hero card
            val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.argb(40, 255, 255, 255)
            }
            canvas.drawRoundRect(heroRect, cornerRadius, cornerRadius, placeholderPaint)
            drawLogoMark(canvas, context, heroRect.centerX(), heroRect.centerY(), (80 * scale).roundToInt(), spec.derivedColors.lyricsTextColor)
        }

        currentY += heroSize + (28 * scale)

        // 3. Title and Artist
        val titleLayout = plan.titleLayout
        if (titleLayout != null) {
            canvas.save()
            canvas.translate(hPadding.toFloat(), currentY)
            titleLayout.draw(canvas)
            canvas.restore()
            currentY += titleLayout.height + (8 * scale)
        }

        val artistLayout = plan.artistLayout
        if (artistLayout != null) {
            canvas.save()
            canvas.translate(hPadding.toFloat(), currentY)
            artistLayout.draw(canvas)
            canvas.restore()
            currentY += artistLayout.height + (24 * scale)
        }

        // 4. Thin divider line
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.derivedColors.dividerColor
            strokeWidth = 2f * scale
        }
        canvas.drawLine(hPadding.toFloat(), currentY, (width - hPadding).toFloat(), currentY, dividerPaint)
        currentY += (28 * scale)

        // 5. Hero Lyrics (auto wrapped, generous block gap)
        val blockGap = plan.fontSize * LYRIC_BLOCK_GAP_FACTOR

        for (layout in plan.lyricLayouts) {
            canvas.save()
            canvas.translate(hPadding.toFloat(), currentY)
            layout.draw(canvas)
            canvas.restore()
            currentY += layout.height + blockGap
        }

        // 6. Brand footer: bottom-left aligned to hPadding!
        drawBrandFooterLeft(canvas, context, hPadding, height, scale, spec.derivedColors.brandTextColor)
    }

    /**
     * Draws the brand lockup on the BOTTOM-LEFT aligned with the same left margin as the lyrics and header.
     * Lockup occupies ~35-40% of card width.
     */
    private fun drawBrandFooterLeft(
        canvas: Canvas,
        context: Context,
        x: Int,
        height: Int,
        scale: Float,
        brandColor: Int,
    ) {
        val footerY = height - (60 * scale)
        val logoSize = (64 * scale).roundToInt()
        val gap = (14 * scale)

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = getTypeface(context, 750)
            textSize = 64f * scale
            color = brandColor
        }

        // Draw tintable logo mark
        drawLogoMark(canvas, context, x + (logoSize / 2f), footerY - (logoSize / 2f), logoSize, brandColor)

        // Draw "LastWave" text aligned horizontally with logo
        val brandName = "LastWave"
        val textX = x + logoSize + gap
        val textY = footerY - (logoSize * 0.18f)
        canvas.drawText(brandName, textX, textY, textPaint)
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

    private fun drawCenterCropCover(canvas: Canvas, source: Bitmap, targetW: Int, targetH: Int) {
        val srcW = source.width
        val srcH = source.height

        val srcRatio = srcW.toFloat() / srcH.toFloat()
        val targetRatio = targetW.toFloat() / targetH.toFloat()

        val cropW: Int
        val cropH: Int
        if (srcRatio > targetRatio) {
            cropH = srcH
            cropW = (srcH * targetRatio).roundToInt()
        } else {
            cropW = srcW
            cropH = (srcW / targetRatio).roundToInt()
        }

        val cropX = (srcW - cropW) / 2
        val cropY = (srcH - cropH) / 2

        val srcRect = Rect(cropX, cropY, cropX + cropW, cropY + cropH)
        val dstRect = Rect(0, 0, targetW, targetH)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(source, srcRect, dstRect, paint)
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
