package com.lastwave.app.ui.shell

import android.graphics.Bitmap
import android.view.ViewConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lastwave.app.ui.theme.LocalIsDarkTheme
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real pointer input and GPU-backed Liquid rendering on a supported Android device. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class LiquidNavigationBarTest {
    @get:Rule val compose = createComposeRule()

    private val selected = mutableIntStateOf(0)
    private val alternateBackground = mutableStateOf(false)
    private val darkTheme = mutableStateOf(false)
    private val navigationStyle = mutableStateOf(LiquidNavigationStyle.Tabs)
    private val taps = mutableListOf<Int>()
    private val scrubs = mutableListOf<Int>()

    @Test
    fun tapsSelectEachTabAndPreserveFeedReselection() {
        showBar()
        compose.onNodeWithText("Stats").performTouchInput { click() }
        compose.onNodeWithText("Stats").assertIsSelected()
        compose.onNodeWithText("Feed").performTouchInput { click() }
        compose.onNodeWithText("Feed").performTouchInput { click() }
        compose.onNodeWithText("Playlist").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf(1, 2, 2, 0), taps)
            assertTrue(scrubs.isEmpty())
        }
    }

    @Test
    fun holdingSelectedTabInflatesAndLiftsBubbleBeforeDraggingInBothThemes() {
        selected.intValue = 1
        showBar(plainBackground = true)
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { darkTheme.value = isDark }
            val themeName = if (isDark) "dark" else "light"
            val resting = lensBounds()
            saveCapture("liquid-bubble-$themeName-idle-screen.png", compose.onRoot().captureToImage())
            val stats = tabPosition("Stats")
            // No move event: the lens must bloom on touch-down, before crossing drag slop.
            bar().performTouchInput { down(stats) }
            val held = lensBounds()
            assertTrue("Holding must visibly increase the lens height", held.height >= resting.height * 1.12f)
            assertGentleUniformGrowth(resting, held)
            assertTrue("Holding must lift the lens above its resting centre", held.center.y < resting.center.y - 1f)
            assertEquals("Holding the selected tab must not move it horizontally", resting.center.x, held.center.x, 2f)
            compose.onNodeWithText("Stats").assertIsSelected()
            assertNoNavigation()
            // Capture the whole root so the lifted rim and shadow are not cropped to the rail.
            saveCapture("liquid-bubble-$themeName-held-screen.png", compose.onRoot().captureToImage())
            bar().performTouchInput { cancel() }
            assertNoNavigation()
            assertSameBounds(resting, lensBounds())
        }
    }

    @Test
    fun holdingUnselectedTabPreviewsBubbleButCancellationPreservesSelection() {
        showBar(opticalPattern = true)
        val resting = lensBounds()
        val stats = tabPosition("Stats")
        bar().performTouchInput { down(stats) }
        assertLensCentredOn("Stats")
        compose.onNodeWithText("Playlist").assertIsSelected()
        compose.onNodeWithText("Stats").assertIsNotSelected()
        assertNoNavigation()
        saveCapture("liquid-bubble-unselected-held-screen.png", compose.onRoot().captureToImage())
        bar().performTouchInput { cancel() }
        assertNoNavigation()
        compose.onNodeWithText("Playlist").assertIsSelected()
        assertSameBounds(resting, lensBounds())
    }

    @Test
    fun releasingHeldTabClicksExactlyOnceAndRelaxesBubble() {
        showBar()
        val resting = lensBounds()
        val stats = tabPosition("Stats")
        bar().performTouchInput { down(stats) }
        assertLensCentredOn("Stats")
        assertNoNavigation()
        bar().performTouchInput { up() }
        compose.onNodeWithText("Stats").assertIsSelected()
        compose.runOnIdle {
            assertEquals(listOf(1), taps)
            assertTrue(scrubs.isEmpty())
        }
        val released = lensBounds()
        assertEquals("Release must restore resting width", resting.width, released.width, 2f)
        assertEquals("Release must restore resting height", resting.height, released.height, 2f)
        assertEquals("Release must restore resting vertical position", resting.center.y, released.center.y, 2f)
        assertLensCentredOn("Stats")
    }

    @Test
    fun offCentreGrabPreservesOffsetAndTracksMovementBelowDragSlop() {
        selected.intValue = 1
        showBar(plainBackground = true)
        val resting = lensBounds()
        val railOrigin = bar().fetchSemanticsNode().boundsInRoot.topLeft
        val grabPoint = Offset(resting.center.x + resting.width * 0.22f, resting.center.y) - railOrigin
        bar().performTouchInput { down(grabPoint) }
        assertEquals("Grabbing away from the centre must not jump the bubble under the finger", resting.center.x, lensBounds().center.x, 2f)
        assertNoNavigation()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val microDistance = ViewConfiguration.get(context).scaledTouchSlop * 0.4f
        bar().performTouchInput { moveTo(grabPoint + Offset(microDistance, 0f), delayMillis = 32) }
        assertEquals("The bubble must track small movement before navigation drag slop", resting.center.x + microDistance, lensBounds().center.x, 2f)
        assertNoNavigation()
        tab(1).assertIsSelected()
        saveCapture("liquid-bubble-off-centre-micro-move-screen.png", compose.onRoot().captureToImage())
        bar().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals("A small motion remains an ordinary tap on the current tab", listOf(1), taps)
            assertTrue(scrubs.isEmpty())
            assertEquals(1, selected.intValue)
        }
        assertSameBounds(resting, lensBounds())
    }

    @Test
    fun fractionalPointerDownDoesNotMagnetizeBubbleToNearestTab() {
        showBar(opticalPattern = true)
        val resting = lensBounds()
        val stats = taggedTabPosition(1)
        val feed = taggedTabPosition(2)
        val fractionalPoint = stats + (feed - stats) * 0.4f
        val railLeft = bar().fetchSemanticsNode().boundsInRoot.left
        bar().performTouchInput { down(fractionalPoint) }
        assertEquals("An outside grab must place the bubble at the continuous finger position", railLeft + fractionalPoint.x, lensBounds().center.x, 2f)
        assertNoNavigation()
        tab(0).assertIsSelected()
        saveCapture("liquid-bubble-fractional-down-screen.png", compose.onRoot().captureToImage())
        bar().performTouchInput { cancel() }
        assertNoNavigation()
        assertSameBounds(resting, lensBounds())
    }

    @Test
    fun movingClearLensRefractsNeutralGlyphsInBothStylesAndThemes() {
        showBar(plainBackground = true)
        for (style in LiquidNavigationStyle.entries) {
            for (isDark in listOf(false, true)) {
                compose.runOnIdle {
                    navigationStyle.value = style
                    darkTheme.value = isDark
                }
                val variant = "${style.name.lowercase()}-${if (isDark) "dark" else "light"}"
                val before = compose.onRoot().captureToImage()
                saveCapture("liquid-neutral-glyphs-$variant-idle-screen.png", before)
                val stats = taggedTabPosition(1)
                val feed = taggedTabPosition(2)
                // Cross stationary Stats ink at a fractional position, away from the selected tab.
                val fractionalPoint = stats + (feed - stats) * 0.25f
                bar().performTouchInput { down(fractionalPoint) }
                val held = lensBounds()
                val after = compose.onRoot().captureToImage()
                saveCapture("liquid-neutral-glyphs-$variant-held-screen.png", after)
                saveCapture("liquid-neutral-glyphs-$variant-held-lens.png", compose.onNodeWithTag("liquid_navigation_lens").captureToImage())
                val inset = held.height * 0.12f
                val glyphArea = Rect(held.left + inset, held.top + inset, held.right - inset, held.bottom - inset)
                val ink = glyphInkMetrics(before, after, glyphArea, isDark)
                // Inspect contrasting ink on a plain fixture, not the bright rim or tinted wash.
                // Dispersion may color glyph fringes; the majority of the glyph core stays neutral.
                assertTrue("$variant must expose contrasting glyph ink inside the lens: $ink", ink.total >= 24)
                assertTrue("$variant must preserve neutral glyph cores: $ink", ink.neutral >= ink.total * 0.60f)
                assertTrue("$variant must not force the captured glyph plane blue: $ink", ink.blue <= ink.total * 0.30f)
                assertTrue("$variant must visibly displace glyph ink through refraction: $ink", ink.displaced >= 12)
                assertNoNavigation()
                tab(0).assertIsSelected()
                bar().performTouchInput { cancel() }
                assertNoNavigation()
                assertLensCentredOnTab(0)
            }
        }
    }

    @Test
    fun heldCapsulePreservesMorePageDetailThanOuterRailInBothStylesAndThemes() {
        // Empty glyphs isolate page optics: an icon edge must not make a frosted lens look sharp.
        showBar(opticalPattern = true, hideGlyphs = true)
        for (style in LiquidNavigationStyle.entries) {
            for (isDark in listOf(false, true)) {
                compose.runOnIdle {
                    navigationStyle.value = style
                    darkTheme.value = isDark
                }
                val variant = "${style.name.lowercase()}-${if (isDark) "dark" else "light"}"
                val outerRail = compose.onRoot().captureToImage()
                saveCapture("liquid-detail-$variant-outer-rail-screen.png", outerRail)
                val stats = taggedTabPosition(1)
                bar().performTouchInput { down(stats) }
                val held = lensBounds()
                val innerCapsule = compose.onRoot().captureToImage()
                saveCapture("liquid-detail-$variant-inner-capsule-screen.png", innerCapsule)
                saveCapture("liquid-detail-$variant-held-bar.png", bar().captureToImage())
                saveCapture("liquid-detail-$variant-held-lens.png", compose.onNodeWithTag("liquid_navigation_lens").captureToImage())
                // Compare the same central page area before/after the lens covers it. Stay well
                // inside the capsule so its shadow, rim and rounded ends cannot inflate detail.
                val sample = Rect(
                    held.left + held.width * 0.22f,
                    held.top + held.height * 0.25f,
                    held.right - held.width * 0.22f,
                    held.bottom - held.height * 0.25f,
                )
                val radius = (held.height / 40f).roundToInt().coerceAtLeast(1)
                val outerDetail = highFrequencyDetail(outerRail, sample, radius)
                val innerDetail = highFrequencyDetail(innerCapsule, sample, radius)
                assertTrue(
                    "$variant inner capsule must retain more grid detail than the outer rail " +
                        "(inner=$innerDetail, outer=$outerDetail)",
                    innerDetail > outerDetail * 1.20f + 0.0005f,
                )
                assertNoNavigation()
                bar().performTouchInput { cancel() }
                assertNoNavigation()
                assertLensCentredOnTab(0)
            }
        }
    }

    @Test
    fun clearCapsuleKeepsPageDetailAfterReleaseAndCancellationInBothStylesAndThemes() {
        showBar(opticalPattern = true, hideGlyphs = true)
        for (style in LiquidNavigationStyle.entries) {
            for (isDark in listOf(false, true)) {
                compose.runOnIdle {
                    selected.intValue = 0
                    navigationStyle.value = style
                    darkTheme.value = isDark
                }
                assertLensCentredOnTab(0)

                val variant = "${style.name.lowercase()}-${if (isDark) "dark" else "light"}"
                val stats = taggedTabPosition(1)

                // Stats initially contains only the frosted rail because the lens rests
                // over Playlist. All following captures sample this same screen region.
                val outerRail = compose.onRoot().captureToImage()

                bar().performTouchInput { down(stats) }
                lensBounds() // Let the press spring settle before measuring its optics.
                val held = compose.onRoot().captureToImage()

                bar().performTouchInput { up() }
                tab(1).assertIsSelected()
                assertLensCentredOnTab(1)
                val releasedBounds = lensBounds()
                val released = compose.onRoot().captureToImage()
                saveCapture("liquid-detail-$variant-released-screen.png", released)

                bar().performTouchInput { down(stats) }
                lensBounds()
                bar().performTouchInput { cancel() }
                assertLensCentredOnTab(1)
                val cancelledBounds = lensBounds()
                val cancelled = compose.onRoot().captureToImage()
                saveCapture("liquid-detail-$variant-cancelled-screen.png", cancelled)
                assertSameBounds(releasedBounds, cancelledBounds)

                // Stay away from the rim, shadow, and rounded ends. Reuse the existing
                // detail threshold that distinguishes the clear source from the outer rail.
                val sample = Rect(
                    releasedBounds.left + releasedBounds.width * 0.22f,
                    releasedBounds.top + releasedBounds.height * 0.25f,
                    releasedBounds.right - releasedBounds.width * 0.22f,
                    releasedBounds.bottom - releasedBounds.height * 0.25f,
                )
                val radius = (releasedBounds.height / 40f).roundToInt().coerceAtLeast(1)
                val outerDetail = highFrequencyDetail(outerRail, sample, radius)
                val heldDetail = highFrequencyDetail(held, sample, radius)
                val releasedDetail = highFrequencyDetail(released, sample, radius)
                val cancelledDetail = highFrequencyDetail(cancelled, sample, radius)
                val clearThreshold = outerDetail * 1.20f + 0.0005f

                assertTrue(
                    "$variant held capsule must be clearer than the outer rail " +
                        "(held=$heldDetail, outer=$outerDetail)",
                    heldDetail > clearThreshold,
                )
                assertTrue(
                    "$variant capsule must remain clear after release " +
                        "(released=$releasedDetail, outer=$outerDetail)",
                    releasedDetail > clearThreshold,
                )
                assertTrue(
                    "$variant capsule must remain clear after cancellation " +
                        "(cancelled=$cancelledDetail, outer=$outerDetail)",
                    cancelledDetail > clearThreshold,
                )
            }
        }
    }

    @Test
    fun material3KeepsEveryTabAccessibleAndShowsOnlyTheSelectedHorizontalCaption() {
        showBar(style = LiquidNavigationStyle.Material3, plainBackground = true)
        for ((index, label) in listOf("Playlist", "Stats", "Feed").withIndex()) {
            tab(index).assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(label)))
        }
        tab(0).assertIsSelected()
        compose.onNodeWithText("Playlist", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Stats", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Feed", useUnmergedTree = true).assertDoesNotExist()
        val caption = compose.onNodeWithText("Playlist", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val selectedTab = tab(0).fetchSemanticsNode().boundsInRoot
        assertEquals("Compact caption must sit beside the icon, not below it", selectedTab.center.y, caption.center.y, 3f)
        assertTrue("Horizontal caption must occupy the icon's trailing side", caption.center.x > selectedTab.center.x)

        // A semantics click remains available even while this destination has no visible caption.
        tab(1).performClick()
        tab(1).assertIsSelected()
        compose.onNodeWithText("Stats", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Playlist", useUnmergedTree = true).assertDoesNotExist()
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { darkTheme.value = isDark }
            val themeName = if (isDark) "dark" else "light"
            val resting = lensBounds()
            saveCapture("liquid-bubble-material3-$themeName-idle-screen.png", compose.onRoot().captureToImage())
            val stats = taggedTabPosition(1)
            bar().performTouchInput { down(stats) }
            assertLensCentredOnTab(1)
            assertTrue("Compact style must use the same raised bubble", lensBounds().height >= resting.height * 1.12f)
            assertGentleUniformGrowth(resting, lensBounds())
            saveCapture("liquid-bubble-material3-$themeName-held-screen.png", compose.onRoot().captureToImage())
            bar().performTouchInput { cancel() }
            assertSameBounds(resting, lensBounds())
            compose.runOnIdle {
                assertEquals(listOf(1), taps)
                assertTrue(scrubs.isEmpty())
            }
        }
    }

    @Test
    fun material3ScrubTracksCompactTabsAndCommitsOnceOnRelease() {
        showBar(style = LiquidNavigationStyle.Material3, opticalPattern = true)
        val playlist = taggedTabPosition(0)
        val stats = taggedTabPosition(1)
        val feed = taggedTabPosition(2)
        bar().performTouchInput {
            down(playlist)
            moveTo(stats, delayMillis = 48)
            moveTo(feed, delayMillis = 48)
        }
        assertNoNavigation()
        tab(0).assertIsSelected()
        assertLensCentredOnTab(2)
        saveCapture("liquid-bubble-material3-drag-screen.png", compose.onRoot().captureToImage())
        bar().performTouchInput { up() }
        tab(2).assertIsSelected()
        assertLensCentredOnTab(2)
        compose.runOnIdle {
            assertEquals(listOf(2), scrubs)
            assertTrue(taps.isEmpty())
        }
    }

    @Test
    fun changingStyleDuringHoldOrScrubCancelsInputAndResetsGeometry() {
        showBar(opticalPattern = true)
        val standardResting = lensBounds()
        val stats = taggedTabPosition(1)
        bar().performTouchInput { down(stats) }
        assertLensCentredOnTab(1)
        compose.runOnIdle { navigationStyle.value = LiquidNavigationStyle.Material3 }
        assertLensCentredOnTab(0)
        tab(0).assertIsSelected()
        // Lift the old pointer after style replacement. It must not leak a child tab click.
        bar().performTouchInput { up() }
        assertNoNavigation()
        val compactResting = lensBounds()
        assertTrue("Compact style must reset to its shorter resting lens", compactResting.height < standardResting.height)

        val playlist = taggedTabPosition(0)
        val feed = taggedTabPosition(2)
        bar().performTouchInput {
            down(playlist)
            moveTo(feed, delayMillis = 64)
        }
        assertLensCentredOnTab(2)
        compose.runOnIdle { navigationStyle.value = LiquidNavigationStyle.Tabs }
        assertLensCentredOnTab(0)
        bar().performTouchInput { up() }
        assertNoNavigation()
        tab(0).assertIsSelected()
        assertSameBounds(standardResting, lensBounds())
    }

    @Test
    fun scrubTracksFingerButCommitsOnlyOnceOnRelease() {
        showBar()
        val playlist = tabPosition("Playlist")
        val stats = tabPosition("Stats")
        val feed = tabPosition("Feed")
        bar().performTouchInput {
            down(playlist)
            moveTo(stats, delayMillis = 48)
            moveTo(feed, delayMillis = 48)
        }
        compose.runOnIdle {
            assertTrue(taps.isEmpty())
            assertTrue(scrubs.isEmpty())
            assertEquals(0, selected.intValue)
        }
        assertLensCentredOn("Feed")
        // Accessibility selection describes the active destination, not a temporary preview.
        compose.onNodeWithText("Playlist").assertIsSelected()
        bar().performTouchInput { up() }
        compose.onNodeWithText("Feed").assertIsSelected()
        compose.runOnIdle {
            assertEquals(listOf(2), scrubs)
            assertTrue(taps.isEmpty())
        }
        assertLensCentredOn("Feed")
    }

    @Test
    fun scrubReturningToCurrentFeedDoesNotRefreshOrNavigate() {
        selected.intValue = 2
        showBar()
        val feed = tabPosition("Feed")
        val playlist = tabPosition("Playlist")
        bar().performTouchInput {
            down(feed)
            moveTo(playlist, delayMillis = 64)
            moveTo(feed, delayMillis = 64)
            up()
        }
        assertNoNavigation()
        compose.onNodeWithText("Feed").assertIsSelected()
        assertLensCentredOn("Feed")
    }

    @Test
    fun parentSelectionChangeDuringScrubKeepsGestureAndUsesCurrentSelection() {
        showBar()
        val playlist = tabPosition("Playlist")
        val feed = tabPosition("Feed")
        bar().performTouchInput {
            down(playlist)
            moveTo(feed, delayMillis = 64)
        }
        // A content pager settling must not restart pointerInput or take the lens from the finger.
        compose.runOnIdle { selected.intValue = 1 }
        compose.onNodeWithText("Stats").assertIsSelected()
        assertLensCentredOn("Feed")
        bar().performTouchInput {
            moveTo(playlist, delayMillis = 64)
            up()
        }
        compose.runOnIdle {
            // Playlist was selected at pointer-down, but Stats is the current destination now.
            assertEquals(listOf(0), scrubs)
            assertTrue(taps.isEmpty())
        }
        compose.onNodeWithText("Playlist").assertIsSelected()
        assertLensCentredOn("Playlist")
    }

    @Test
    fun continuousLensRendersBetweenTabsOverOpticalInspectionPattern() {
        showBar(opticalPattern = true)
        val playlist = tabPosition("Playlist")
        val stats = tabPosition("Stats")
        val betweenTabs = (playlist + stats) * 0.5f
        bar().performTouchInput {
            down(playlist)
            moveTo(betweenTabs, delayMillis = 80)
        }
        compose.waitForIdle()
        val lens = compose.onNodeWithTag("liquid_navigation_lens").fetchSemanticsNode().boundsInRoot
        val expectedCentre = bar().fetchSemanticsNode().boundsInRoot.left + betweenTabs.x
        assertEquals("The lens must follow the finger between tab centres", expectedCentre, lens.center.x, 2f)
        assertNoNavigation()
        // These captures pair the unmodified grid with the actual shell/lens. Refraction, rim,
        // frosting and chromatic separation can be inspected without brittle GPU pixel goldens.
        saveCapture("liquid-navigation-optics-mid-drag-screen.png", compose.onRoot().captureToImage())
        saveCapture("liquid-navigation-optics-mid-drag-bar.png", bar().captureToImage())
        saveCapture("liquid-navigation-optics-mid-drag-lens.png", compose.onNodeWithTag("liquid_navigation_lens").captureToImage())
        bar().performTouchInput { cancel() }
        assertNoNavigation()
        assertLensCentredOn("Playlist")
    }

    @Test
    fun cancelledScrubRestoresCurrentTabWithoutNavigating() {
        showBar()
        val playlist = tabPosition("Playlist")
        val feed = tabPosition("Feed")
        bar().performTouchInput {
            down(playlist)
            moveTo(feed, delayMillis = 64)
            cancel()
        }
        assertNoNavigation()
        compose.onNodeWithText("Playlist").assertIsSelected()
        assertLensCentredOn("Playlist")
    }

    @Test
    fun secondFingerCancelsScrubWithoutLeakingATap() {
        showBar()
        val playlist = tabPosition("Playlist")
        val feed = tabPosition("Feed")
        val stats = tabPosition("Stats")
        bar().performTouchInput {
            down(pointerId = 0, position = playlist)
            moveTo(pointerId = 0, position = feed, delayMillis = 64)
            down(pointerId = 1, position = stats)
            up(pointerId = 1)
            up(pointerId = 0)
        }
        assertNoNavigation()
        assertLensCentredOn("Playlist")
    }

    @Test
    fun verticalExitCancelsScrubAndVerticalSwipeDoesNotTap() {
        showBar()
        val playlist = tabPosition("Playlist")
        val feed = tabPosition("Feed")
        bar().performTouchInput {
            down(playlist)
            moveTo(feed, delayMillis = 48)
            moveTo(feed.copy(y = -height.toFloat()), delayMillis = 48)
            up()
        }
        assertNoNavigation()
        assertLensCentredOn("Playlist")
        bar().performTouchInput {
            down(playlist)
            moveTo(playlist.copy(y = -height.toFloat()), delayMillis = 64)
            up()
        }
        assertNoNavigation()
    }

    @Test
    fun rtlScrubUsesLogicalDestinationAndCorrectLensPosition() {
        showBar(LayoutDirection.Rtl)
        assertLensCentredOn("Playlist")
        val playlist = tabPosition("Playlist")
        val feed = tabPosition("Feed")
        assertTrue("RTL places Playlist on the right", playlist.x > feed.x)
        bar().performTouchInput {
            down(playlist)
            moveTo(feed, delayMillis = 96)
            up()
        }
        compose.runOnIdle { assertEquals(listOf(2), scrubs) }
        compose.onNodeWithText("Feed").assertIsSelected()
        assertLensCentredOn("Feed")
    }

    @Test
    fun tabsExposeSelectedRolesAndAccessibleClickActions() {
        showBar()
        for (label in listOf("Playlist", "Stats", "Feed")) {
            compose.onNodeWithText(label)
                .assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        }
        compose.onNodeWithText("Playlist").assertIsSelected()
        compose.onNodeWithText("Stats").assertIsNotSelected()
        compose.onNodeWithText("Feed").assertIsNotSelected()
        // performClick invokes semantics, like TalkBack, without depending on a touch gesture.
        compose.onNodeWithText("Stats").performClick()
        compose.onNodeWithText("Stats").assertIsSelected()
        compose.onNodeWithText("Playlist").assertIsNotSelected()
        compose.runOnIdle {
            assertEquals(listOf(1), taps)
            assertTrue(scrubs.isEmpty())
        }
    }

    @Test
    fun realGlassRendersAndTracksChangedSiblingSourceInBothThemes() {
        showBar()
        val initial = bar().captureToImage()
        saveCapture("liquid-navigation-light.png", initial)
        compose.runOnIdle { alternateBackground.value = true }
        val changed = bar().captureToImage()
        saveCapture("liquid-navigation-light-source-changed.png", changed)
        assertTrue("Glass must update when its sampled source changes", changedPixelFraction(initial, changed) > 0.35f)
        compose.runOnIdle { darkTheme.value = true }
        val dark = bar().captureToImage()
        saveCapture("liquid-navigation-dark.png", dark)
        assertTrue("Glass tint must adapt to the dark theme", changedPixelFraction(changed, dark) > 0.25f)
        assertNoNavigation()
        compose.onNodeWithText("Playlist").assertIsSelected()
    }

    private fun showBar(
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        opticalPattern: Boolean = false,
        plainBackground: Boolean = false,
        hideGlyphs: Boolean = false,
        style: LiquidNavigationStyle = LiquidNavigationStyle.Tabs,
    ) {
        navigationStyle.value = style
        val emptyGlyph = ImageVector.Builder(
            name = "PageDetailFixture",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).build()
        compose.setContent {
            val isDark = darkTheme.value
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection, LocalIsDarkTheme provides isDark) {
                MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme()) {
                    val liquidState = rememberLiquidState()
                    Box(Modifier.fillMaxSize()) {
                        // Opaque source and effects must remain siblings to avoid recursive capture.
                        Canvas(Modifier.fillMaxSize().liquefiable(liquidState)) {
                            if (plainBackground) {
                                // Uniform app-like surfaces expose missing edge depth that a busy
                                // optical grid can hide. Keep these nearly white/black, not absolute.
                                drawRect(if (isDark) Color(0xFF111216) else Color(0xFFF7F7FA))
                            } else if (opticalPattern) {
                                drawRect(
                                    Brush.horizontalGradient(
                                        listOf(Color(0xFF03558E), Color(0xFF28C5B5), Color(0xFFE79032), Color(0xFFAE376C)),
                                    ),
                                )
                                val spacing = 16.dp.toPx()
                                val markerWidth = 1.2.dp.toPx()
                                for (column in 0..(size.width / spacing).toInt()) {
                                    val x = column * spacing
                                    drawLine(Color.White.copy(alpha = 0.9f), Offset(x, 0f), Offset(x, size.height), markerWidth)
                                }
                                for (row in 0..(size.height / spacing).toInt()) {
                                    val y = row * spacing
                                    drawLine(Color(0xFF14243E), Offset(0f, y), Offset(size.width, y), markerWidth)
                                }
                            } else {
                                val colors = if (alternateBackground.value) {
                                    listOf(Color(0xFFB21B5C), Color(0xFFFFB300), Color(0xFF4054B2))
                                } else {
                                    listOf(Color(0xFF12B8A6), Color(0xFF476DEF), Color(0xFFE87426))
                                }
                                drawRect(Color(0xFF253347))
                                val stripeWidth = size.width / 12f
                                for (stripe in 0..11) {
                                    drawRect(colors[stripe % colors.size], Offset(stripe * stripeWidth, 0f), Size(stripeWidth + 1f, size.height))
                                }
                            }
                        }
                        LiquidNavigationBar(
                            liquidState = liquidState,
                            items = if (hideGlyphs) List(3) { LiquidNavigationItem("", emptyGlyph) } else listOf(
                                LiquidNavigationItem("Playlist", Icons.Filled.LibraryMusic),
                                LiquidNavigationItem("Stats", Icons.Filled.BarChart),
                                LiquidNavigationItem("Feed", Icons.Filled.RssFeed),
                            ),
                            selectedIndex = selected.intValue,
                            onSelect = { taps += it; selected.intValue = it },
                            onScrubSelect = { scrubs += it; selected.intValue = it },
                            style = navigationStyle.value,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }

    private fun bar() = compose.onNodeWithTag("liquid_navigation_bar")

    private fun tab(index: Int) = compose.onNodeWithTag("liquid_navigation_tab_$index")

    private fun taggedTabPosition(index: Int): Offset =
        tab(index).fetchSemanticsNode().boundsInRoot.center - bar().fetchSemanticsNode().boundsInRoot.topLeft

    private fun assertLensCentredOnTab(index: Int) {
        val lens = lensBounds()
        val target = tab(index).fetchSemanticsNode().boundsInRoot
        assertEquals("Lens must align with tab $index", target.center.x, lens.center.x, 2f)
    }

    private fun lensBounds(): Rect {
        compose.waitForIdle()
        return compose.onNodeWithTag("liquid_navigation_lens").fetchSemanticsNode().boundsInRoot
    }

    private fun assertSameBounds(expected: Rect, actual: Rect) {
        assertEquals("Cancellation must restore the lens left edge", expected.left, actual.left, 2f)
        assertEquals("Cancellation must restore the lens top edge", expected.top, actual.top, 2f)
        assertEquals("Cancellation must restore the lens right edge", expected.right, actual.right, 2f)
        assertEquals("Cancellation must restore the lens bottom edge", expected.bottom, actual.bottom, 2f)
    }

    private fun assertGentleUniformGrowth(resting: Rect, held: Rect) {
        val horizontalGrowth = held.width / resting.width
        val verticalGrowth = held.height / resting.height
        assertTrue("Press growth must remain gentle", horizontalGrowth <= 1.20f && verticalGrowth <= 1.20f)
        assertEquals("Press growth must preserve circular capsule ends", horizontalGrowth, verticalGrowth, 0.025f)
    }

    private fun tabPosition(label: String): Offset =
        compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.center - bar().fetchSemanticsNode().boundsInRoot.topLeft

    private fun assertLensCentredOn(label: String) {
        compose.waitForIdle()
        val lens = compose.onNodeWithTag("liquid_navigation_lens").fetchSemanticsNode().boundsInRoot
        val tab = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        assertEquals("Lens must align with $label", tab.center.x, lens.center.x, 2f)
    }

    private fun assertNoNavigation() = compose.runOnIdle {
        assertTrue("Tap callback leaked from cancelled/same-tab scrub: $taps", taps.isEmpty())
        assertTrue("Cancelled/same-tab scrub navigated: $scrubs", scrubs.isEmpty())
    }

    private fun changedPixelFraction(first: ImageBitmap, second: ImageBitmap): Float {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        val before = first.toPixelMap()
        val after = second.toPixelMap()
        var changed = 0
        var sampled = 0
        // Exclude rounded ends. Sample the actual glass surface across all three tabs.
        for (x in first.width / 8 until first.width * 7 / 8 step 4) {
            for (y in first.height / 5 until first.height * 4 / 5 step 4) {
                val a = before[x, y]
                val b = after[x, y]
                if (abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue) > 0.15f) changed++
                sampled++
            }
        }
        return changed.toFloat() / sampled
    }

    private data class GlyphInkMetrics(val total: Int, val neutral: Int, val blue: Int, val displaced: Int)

    private fun glyphInkMetrics(
        before: ImageBitmap,
        after: ImageBitmap,
        region: Rect,
        isDark: Boolean,
    ): GlyphInkMetrics {
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        val initial = before.toPixelMap()
        val current = after.toPixelMap()
        fun isInk(color: Color): Boolean = if (isDark) {
            maxOf(color.red, color.green, color.blue) > 0.65f
        } else {
            minOf(color.red, color.green, color.blue) < 0.50f
        }
        var total = 0
        var neutral = 0
        var blue = 0
        var displaced = 0
        for (y in region.top.toInt().coerceIn(0, after.height) until region.bottom.toInt().coerceIn(0, after.height)) {
            for (x in region.left.toInt().coerceIn(0, after.width) until region.right.toInt().coerceIn(0, after.width)) {
                val color = current[x, y]
                if (!isInk(color)) continue
                total++
                if (maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) < 0.20f) neutral++
                if (color.red < 0.65f && color.blue - color.red > 0.20f && color.blue - color.green > 0.10f) blue++
                if (!isInk(initial[x, y])) displaced++
            }
        }
        return GlyphInkMetrics(total, neutral, blue, displaced)
    }

    private fun highFrequencyDetail(image: ImageBitmap, region: Rect, radius: Int): Float {
        val pixels = image.toPixelMap()
        fun luminance(x: Int, y: Int): Float {
            val color = pixels[x, y]
            return color.red * 0.2126f + color.green * 0.7152f + color.blue * 0.0722f
        }
        var detail = 0f
        var samples = 0
        for (y in region.top.toInt().coerceAtLeast(radius) until region.bottom.toInt().coerceAtMost(image.height - radius)) {
            for (x in region.left.toInt().coerceAtLeast(radius) until region.right.toInt().coerceAtMost(image.width - radius)) {
                val centre = luminance(x, y)
                // A second difference rejects the fixture's smooth gradient and measures grid edges.
                detail += abs(luminance(x - radius, y) - 2f * centre + luminance(x + radius, y))
                detail += abs(luminance(x, y - radius) - 2f * centre + luminance(x, y + radius))
                samples++
            }
        }
        assertTrue("The optics sample must contain pixels", samples > 0)
        return detail / (samples * 2f)
    }

    private fun saveCapture(name: String, image: ImageBitmap) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("liquid-navigation-tests")!!
        File(directory, name).outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
