package com.lastwave.app.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StableFeedSectionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun removedSectionKeepsItsHeightAndHidesOldContent() {
        var unavailable by mutableStateOf(false)
        var taps by mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme {
                StableFeedSection(unavailable, Modifier.testTag("section")) {
                    Box(Modifier.fillMaxWidth().height(160.dp).clickable { taps++ }) {
                        Text("Old recommendation")
                    }
                }
            }
        }
        compose.onNodeWithText("Old recommendation").assertExists()
        val before = compose.onNodeWithTag("section").fetchSemanticsNode().boundsInRoot.height

        compose.runOnIdle { unavailable = true }

        assertEquals(before, compose.onNodeWithTag("section").fetchSemanticsNode().boundsInRoot.height, 0f)
        compose.onNodeWithText("Old recommendation").assertDoesNotExist()
        compose.onNodeWithText("Not in the latest refresh").assertExists().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, taps) }
    }
}
