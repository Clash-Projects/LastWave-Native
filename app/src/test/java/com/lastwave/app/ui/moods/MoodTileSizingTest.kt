package com.lastwave.app.ui.moods

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.ui.feed.cards.CardTokens
import com.lastwave.app.ui.theme.SystemTypography
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoodTileSizingTest {
    private val labelStyle = SystemTypography.titleSmall.copy(
        fontSize = CardTokens.titleFontSize,
        lineHeight = CardTokens.titleLineHeight,
        fontWeight = FontWeight.Medium,
        color = Color.White,
    )

    @Test
    fun ordinaryCategoryLabelsFitTheMinimumSquareWithoutEllipsis() {
        val labels = listOf("Chill", "Workout", "Dance & Electronic", "Moods & moments")
        val density = Density(2f)
        val measurer = textMeasurer(density)
        val cellSize = moodTileMinimumSize(labels, measurer, labelStyle, density)

        assertThat(cellSize).isEqualTo(88)
        assertLabelsFit(labels, measurer, density, cellSize)
    }

    @Test
    fun longCategoryNamesGrowTheSquareUntilEveryCharacterFits() {
        val labels = listOf(
            "Music for a peaceful late evening and quiet moments",
            "Dance, electronic and international favourites",
            "Chill",
        )
        val density = Density(1f)
        val measurer = textMeasurer(density)
        val cellSize = moodTileMinimumSize(labels, measurer, labelStyle, density)

        assertThat(cellSize).isGreaterThan(88)
        assertLabelsFit(labels, measurer, density, cellSize)
    }

    @Test
    fun largeFontScalesAndFractionalDensitiesKeepLatinAndRtlLabelsComplete() {
        val labels = listOf(
            "Feel good music for every moment",
            "موسيقى هادئة للاسترخاء في المساء",
            "موسيقى عربية & Jazz",
        )
        for (pixelDensity in listOf(1f, 1.05f, 1.15f, 1.25f, 2.625f, 3.5f)) {
            for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                val regularDensity = Density(pixelDensity, fontScale = 1f)
                val enlargedDensity = Density(pixelDensity, fontScale = 2f)
                val regularMeasurer = textMeasurer(regularDensity, direction)
                val enlargedMeasurer = textMeasurer(enlargedDensity, direction)
                val regularSize = moodTileMinimumSize(labels, regularMeasurer, labelStyle, regularDensity)
                val enlargedSize = moodTileMinimumSize(labels, enlargedMeasurer, labelStyle, enlargedDensity)

                assertThat(enlargedSize).isGreaterThan(regularSize)
                assertLabelsFit(labels, regularMeasurer, regularDensity, regularSize, direction)
                assertLabelsFit(labels, enlargedMeasurer, enlargedDensity, enlargedSize, direction)
            }
        }
    }

    @Test
    fun enlargedLabelsReducePageColumnsAndFitEveryTileOnNarrowPages() {
        val labels = listOf(
            "Dance and electronic music for a peaceful late evening",
            "موسيقى هادئة للاسترخاء في المساء",
            "Romance",
            "Feel good",
            "Workout",
            "Jazz",
            "Focus",
        )
        val density = Density(1.15f, fontScale = 1.6f)
        val measurer = textMeasurer(density, LayoutDirection.Rtl)
        val minimumSize = moodTileMinimumSize(labels, measurer, labelStyle, density)

        assertThat(moodMosaicColumnCount(328, minimumSize))
            .isLessThan(moodMosaicColumnCount(328))
        for (viewport in listOf(200, 328, 568, 968)) {
            val pattern = moodMosaicVerticalPattern(labels.size, viewport, minimumSize)
            pattern.forEach { tile ->
                assertThat(tile.startDp + tile.widthDp).isAtMost(viewport)
                assertLabelsFit(
                    labels = listOf(labels[tile.index]),
                    measurer = measurer,
                    density = density,
                    cellWidthDp = tile.widthDp,
                    direction = LayoutDirection.Rtl,
                    cellHeightDp = tile.heightDp,
                )
            }
        }
    }

    private fun textMeasurer(
        density: Density,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) = TextMeasurer(
        createFontFamilyResolver(RuntimeEnvironment.getApplication()),
        density,
        direction,
    )

    private fun assertLabelsFit(
        labels: List<String>,
        measurer: TextMeasurer,
        density: Density,
        cellWidthDp: Int,
        direction: LayoutDirection = LayoutDirection.Ltr,
        cellHeightDp: Int = cellWidthDp,
    ) {
        val paddingPx = with(density) { 8.dp.roundToPx() }
        val interiorWidthPx = with(density) { cellWidthDp.dp.roundToPx() } - 2 * paddingPx
        val interiorHeightPx = with(density) { cellHeightDp.dp.roundToPx() } - 2 * paddingPx
        labels.forEach { label ->
            val rendered = measurer.measure(
                text = label,
                style = labelStyle,
                overflow = TextOverflow.Clip,
                softWrap = true,
                constraints = Constraints(maxWidth = interiorWidthPx, maxHeight = interiorHeightPx),
                layoutDirection = direction,
                density = density,
            )

            assertThat(rendered.hasVisualOverflow).isFalse()
            assertThat(rendered.getLineEnd(rendered.lineCount - 1, visibleEnd = true)).isEqualTo(label.length)
            for (line in 0 until rendered.lineCount) {
                assertThat(rendered.isLineEllipsized(line)).isFalse()
            }
        }
    }
}
