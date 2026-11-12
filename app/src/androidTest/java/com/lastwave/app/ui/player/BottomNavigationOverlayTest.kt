package com.lastwave.app.ui.player

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lastwave.app.ui.theme.LocalMd3NavBar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BottomNavigationOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun leavingShellRemovesNavigationWhileTheOutgoingScreenIsStillComposed() {
        lateinit var controller: NavHostController
        compose.setContent {
            controller = rememberNavController()
            val entry by controller.currentBackStackEntryAsState()
            var slot by remember { mutableStateOf<BottomNavigationContent>({ _ -> }) }
            Box(Modifier.fillMaxSize()) {
                NavHost(
                    navController = controller,
                    startDestination = "shell",
                    enterTransition = { fadeIn(tween(1_000)) },
                    exitTransition = { fadeOut(tween(1_000)) },
                ) {
                    composable("shell") {
                        DisposableEffect(Unit) {
                            slot = { _ -> Text("Navigation", Modifier.testTag("navigation")) }
                            onDispose { slot = { _ -> } }
                        }
                        Text("Shell screen")
                    }
                    composable("settings") { Text("Settings screen") }
                }
                BottomNavigationOverlay(
                    hasBottomNavigation = entry?.destination?.route == "shell",
                    material3NavigationEnabled = false,
                    playerExpanded = false,
                    content = slot,
                )
            }
        }
        compose.onNodeWithTag("navigation").assertExists()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { controller.navigate("settings") }
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("Shell screen").assertExists()
        compose.onNodeWithTag("navigation").assertDoesNotExist()
    }

    @Test
    fun leavingDuringPlayerFadeRemovesNavigationWithoutWaitingForFadeCompletion() {
        val hasNavigation = mutableStateOf(true)
        val playerExpanded = mutableStateOf(false)
        compose.setContent {
            BottomNavigationOverlay(hasNavigation.value, false, playerExpanded.value) {
                Text("Navigation", Modifier.testTag("navigation"))
            }
        }
        compose.onNodeWithTag("navigation").assertExists()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { playerExpanded.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("navigation").assertExists()

        compose.runOnIdle { hasNavigation.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("navigation").assertDoesNotExist()
    }

    @Test
    fun retainedSlotReceivesTheCurrentSettingAndLegacyCompositionLocal() {
        val hasNavigation = mutableStateOf(false)
        val material3Enabled = mutableStateOf(false)
        // Register once, as MainShell does. Updating the setting must not require
        // replacing the retained lambda or recreating the activity.
        val slot: BottomNavigationContent = { enabled ->
            Text("${if (enabled) "compact" else "tabs"}:${LocalMd3NavBar.current}")
        }
        compose.setContent {
            BottomNavigationOverlay(hasNavigation.value, material3Enabled.value, false, slot)
        }
        compose.onNodeWithText("tabs:false").assertDoesNotExist()
        compose.runOnIdle {
            material3Enabled.value = true
            hasNavigation.value = true
        }
        compose.onNodeWithText("compact:true").assertExists()
        compose.runOnIdle { material3Enabled.value = false }
        compose.onNodeWithText("tabs:false").assertExists()
        compose.onNodeWithText("compact:true").assertDoesNotExist()
    }
}
