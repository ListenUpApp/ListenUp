package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Typing a chapter's start. The field takes back exactly what the row shows, and refuses anything
 * that is not a time rather than guessing — a guessed boundary lands somewhere nobody asked for.
 */
@RunWith(RobolectricTestRunner::class)
class ChapterTimeDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun render(onConfirm: (Long) -> Unit) {
        composeRule.setContent {
            MaterialTheme {
                ChapterTimeDialog(initialMs = 148_328_400L, onConfirm = onConfirm, onDismiss = {})
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `the field opens on the start as the row shows it`() {
        render(onConfirm = {})

        composeRule.onNodeWithText("41:12:08.4").assertIsDisplayed()
    }

    @Test
    fun `a typed time is applied to the millisecond`() {
        val applied = mutableListOf<Long>()
        render(onConfirm = { applied += it })

        composeRule.onNode(hasSetTextAction()).performTextReplacement("1:02:03.456")
        composeRule.onNodeWithText("Save").performClick()

        assert(applied == listOf(3_723_456L)) { "expected 1:02:03.456 as 3723456 ms, got $applied" }
    }

    @Test
    fun `something that is not a time is refused where it was typed`() {
        val applied = mutableListOf<Long>()
        render(onConfirm = { applied += it })

        composeRule.onNode(hasSetTextAction()).performTextReplacement("soon")

        composeRule.onNodeWithText("Type a time like 1:02:03.4.").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("3:05")
        composeRule.onNodeWithText("Save").assertIsEnabled()
        assert(applied.isEmpty()) { "nothing should have been applied yet, got $applied" }
    }
}
