package com.calypsan.listenup.client.design.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import com.calypsan.listenup.client.design.haptics.Haptics
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins what the one top bar grew so every feature screen could use it: a subtitle that is not a
 * second heading, a navigation button that can be a named Close instead of Back, and a felt press.
 */
@RunWith(RobolectricTestRunner::class)
class ListenUpTopAppBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the title is the one heading and the subtitle sits under it`() {
        composeRule.setContent {
            MaterialTheme {
                ListenUpTopAppBar(title = TITLE, subtitle = SUBTITLE, onBack = {})
            }
        }

        composeRule.onNodeWithText(TITLE).assert(isHeading())
        composeRule.onNodeWithText(SUBTITLE).assertIsDisplayed()
        composeRule.onAllNodes(isHeading()).assertCountEquals(1)
    }

    @Test
    fun `back is named Back and is felt`() {
        val haptics = RecordingHaptics()
        var backs = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalHaptics provides haptics) {
                MaterialTheme {
                    ListenUpTopAppBar(title = TITLE, onBack = { backs++ })
                }
            }
        }

        composeRule.onNodeWithContentDescription("Back").performClick()

        backs shouldBe 1
        haptics.presses shouldBe 1
    }

    @Test
    fun `a bar that dismisses a pane names its Close`() {
        composeRule.setContent {
            MaterialTheme {
                ListenUpTopAppBar(
                    title = TITLE,
                    onBack = {},
                    navigationIcon = Icons.Outlined.Close,
                    navigationContentDescription = CLOSE,
                )
            }
        }

        composeRule.onNodeWithContentDescription(CLOSE).assert(hasClickAction())
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun `no navigation button when there is nowhere to go back to`() {
        composeRule.setContent {
            MaterialTheme {
                ListenUpTopAppBar(title = TITLE, onBack = null)
            }
        }

        composeRule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    private class RecordingHaptics : Haptics {
        var presses = 0

        override fun selectionTick() = Unit

        override fun press() {
            presses++
        }

        override fun toggle(on: Boolean) = Unit

        override fun longPress() = Unit

        override fun thresholdActivate() = Unit

        override fun commit() = Unit
    }

    private companion object {
        const val TITLE = "Chapters"
        const val SUBTITLE = "The Way of Kings · 42 chapters"
        const val CLOSE = "Close book details"
    }
}
