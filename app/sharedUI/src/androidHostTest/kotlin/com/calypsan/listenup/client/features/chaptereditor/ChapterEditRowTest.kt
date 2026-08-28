package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.Chapter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The chapter row's affordances.
 *
 * The spec's claim is that the whole correction can be done from the list alone — the timeline is
 * "optional spatial sugar". That only holds if every precision tool has a tap equivalent here, so
 * these assert the controls exist and are wired, not how they look. A user who cannot drag is not
 * a second-class user of this screen, and this is where that stops being a good intention.
 */
@RunWith(RobolectricTestRunner::class)
class ChapterEditRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chapter =
        Chapter(id = "c1", title = "The Spear That Would Not Break", duration = 806_000L, startTime = 148_328_400L)

    private fun render(
        isLocked: Boolean = false,
        isPlaying: Boolean = false,
        onNudge: (Long) -> Unit = {},
        onSnap: () -> Unit = {},
        onToggleLock: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                ChapterEditRow(
                    chapter = chapter,
                    number = 213,
                    isSelected = false,
                    isPlaying = isPlaying,
                    onSelect = {},
                    onNudge = onNudge,
                    onSnapToPlayhead = onSnap,
                    onToggleLock = onToggleLock,
                    onMore = {},
                    isLocked = isLocked,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `the row shows its number, title and precise start`() {
        render()

        composeRule.onNodeWithText("213").assertIsDisplayed()
        composeRule.onNodeWithText("The Spear That Would Not Break").assertIsDisplayed()
        // Precise, not a rounded clock: this is the number being edited, and a start that reads
        // the same before and after a nudge makes the nudge look broken.
        composeRule.onNodeWithText("41:12:08.4").assertIsDisplayed()
    }

    @Test
    fun `nudging back and forward sends opposite signed steps`() {
        val steps = mutableListOf<Long>()
        render(onNudge = { steps += it })

        composeRule.onNodeWithContentDescription("Nudge back").performClick()
        composeRule.onNodeWithContentDescription("Nudge forward").performClick()

        assert(steps == listOf(-1_000L, 1_000L)) { "expected a second back then forward, got $steps" }
    }

    @Test
    fun `snap to playhead is reachable without dragging anything`() {
        var snapped = false
        render(onSnap = { snapped = true })

        composeRule.onNodeWithContentDescription("Set start at playhead").performClick()

        assert(snapped) { "the exact-millisecond tool must be usable from the list alone" }
    }

    @Test
    fun `the lock control says which way it will go`() {
        // An icon that means both "locked" and "press to lock" is unreadable; the description
        // names the action, so a screen reader user knows what pressing it does.
        render(isLocked = false)
        composeRule.onNodeWithContentDescription("Lock chapter").assertIsDisplayed()
    }

    @Test
    fun `a locked chapter offers to unlock`() {
        render(isLocked = true)

        composeRule.onNodeWithContentDescription("Unlock chapter").assertIsDisplayed()
    }

    @Test
    fun `toggling the lock reports it`() {
        var toggled = false
        render(onToggleLock = { toggled = true })

        composeRule.onNodeWithContentDescription("Lock chapter").performClick()

        assert(toggled)
    }

    @Test
    fun `the NOW badge appears only for the chapter being played`() {
        render(isPlaying = true)
        composeRule.onNodeWithText("NOW").assertIsDisplayed()
    }

    @Test
    fun `a chapter that is not playing has no badge`() {
        render(isPlaying = false)

        composeRule.onNodeWithText("NOW").assertDoesNotExist()
    }
}
