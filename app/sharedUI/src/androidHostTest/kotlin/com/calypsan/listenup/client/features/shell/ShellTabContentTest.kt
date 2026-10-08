package com.calypsan.listenup.client.features.shell

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.design.motion.LocalReduceMotion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the shell's tab switch: a fade-through that keeps what each tab was showing, and an instant
 * cut when animations are removed.
 */
@RunWith(RobolectricTestRunner::class)
class ShellTabContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var destination by mutableStateOf<ShellDestination>(ShellDestination.Home)

    @Test
    fun `a tab keeps its state across a switch away and back`() {
        render(reduceMotion = false)
        composeRule.onNodeWithText("Home 0").performClick()
        composeRule.onNodeWithText("Home 1").performClick()

        switchTo(ShellDestination.Library)
        composeRule.onNodeWithText("Library 0").assertExists()
        composeRule.onNodeWithText("Home 2").assertDoesNotExist()

        switchTo(ShellDestination.Home)
        composeRule.onNodeWithText("Home 2").assertExists()
    }

    @Test
    fun `the tabs fade through each other`() {
        render(reduceMotion = false)
        composeRule.mainClock.autoAdvance = false
        switchTo(ShellDestination.Library)
        repeat(FRAMES) { composeRule.mainClock.advanceTimeByFrame() }

        composeRule.onNodeWithText("Home 0").assertExists()
        composeRule.onNodeWithText("Library 0").assertExists()
    }

    @Test
    fun `the switch is an instant cut when animations are removed`() {
        render(reduceMotion = true)
        composeRule.mainClock.autoAdvance = false
        switchTo(ShellDestination.Library)
        repeat(FRAMES) { composeRule.mainClock.advanceTimeByFrame() }

        composeRule.onNodeWithText("Home 0").assertDoesNotExist()
        composeRule.onNodeWithText("Library 0").assertExists()
    }

    private fun render(reduceMotion: Boolean) {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                    ShellTabContent(currentDestination = destination) { shown ->
                        Counter(label = shown.title)
                    }
                }
            }
        }
    }

    private fun switchTo(target: ShellDestination) {
        composeRule.runOnUiThread {
            destination = target
            Snapshot.sendApplyNotifications()
        }
        composeRule.waitForIdle()
    }

    private companion object {
        /** Enough frames for an instant cut to land, and far too few for a fade to finish. */
        const val FRAMES = 3
    }
}

@Composable
private fun Counter(label: String) {
    var taps by rememberSaveable { mutableIntStateOf(0) }
    Text(text = "$label $taps", modifier = Modifier.clickable { taps++ })
}
