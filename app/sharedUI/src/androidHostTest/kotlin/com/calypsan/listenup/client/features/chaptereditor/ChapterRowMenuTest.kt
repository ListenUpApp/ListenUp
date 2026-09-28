package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.Haptics
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.domain.model.Chapter
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A chapter row's overflow is an anchored menu, not a dialog of buttons: it opens from a named
 * 48dp overflow button, offers the spec's four actions (§7.4), and Delete leads to the delete path.
 */
@RunWith(RobolectricTestRunner::class)
class ChapterRowMenuTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chapter = Chapter(id = "c7", title = "The Long Road", duration = 60_000L, startTime = 0L)

    private val calls = mutableListOf<String>()
    private val haptics = CountingHaptics()

    private fun render(canPlayFromHere: Boolean = true) {
        val playFromHere: (String) -> Unit = { calls += "play:$it" }
        val menu =
            ChapterRowMenuActions(
                onRename = { calls += "rename:$it" },
                onInsertBelow = { calls += "insert:$it" },
                onPlayFromHere = playFromHere.takeIf { canPlayFromHere },
                onDelete = { calls += "delete:$it" },
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalHaptics provides haptics) {
                MaterialTheme {
                    ChapterEditRow(
                        chapter = chapter,
                        number = 7,
                        isSelected = false,
                        isPlaying = false,
                        onSelect = {},
                        onNudge = {},
                        onSnapToPlayhead = {},
                        onToggleLock = {},
                        menu = menu,
                        onEditTime = {},
                    )
                }
            }
        }
    }

    @Test
    fun `the overflow is named for its chapter and is a full touch target`() {
        render()

        composeRule
            .onNodeWithContentDescription("More actions for The Long Road")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `opening the overflow lists the four actions, with a press felt`() {
        render()

        composeRule.onNodeWithContentDescription("More actions for The Long Road").performClick()

        composeRule.onNodeWithText("Rename chapter").assertIsDisplayed()
        composeRule.onNodeWithText("Insert chapter below").assertIsDisplayed()
        composeRule.onNodeWithText("Play from here").assertIsDisplayed()
        composeRule.onNodeWithText("Delete chapter").assertIsDisplayed()
        haptics.presses shouldBe 1
    }

    @Test
    fun `Delete takes the delete path for this chapter and closes the menu`() {
        render()

        composeRule.onNodeWithContentDescription("More actions for The Long Road").performClick()
        composeRule.onNodeWithText("Delete chapter").performClick()

        calls shouldBe listOf("delete:c7")
        composeRule.onNodeWithText("Delete chapter").assertDoesNotExist()
    }

    @Test
    fun `Play from here is disabled while another book is loaded`() {
        render(canPlayFromHere = false)

        composeRule.onNodeWithContentDescription("More actions for The Long Road").performClick()

        composeRule.onNodeWithText("Play from here").assertIsNotEnabled()
    }

    private class CountingHaptics : Haptics {
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
}
