package com.calypsan.listenup.client.features.match

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.design.motion.LocalTouchExplorationActive
import com.calypsan.listenup.client.presentation.match.MatchReceiptUiState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The receipt Book Detail shows after Apply: what changed, See what changed, Undo, and what Undo did. */
@RunWith(RobolectricTestRunner::class)
class MatchReceiptBannerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var undos = 0
    private var dismissals = 0

    private fun setBanner(
        state: MatchReceiptUiState,
        screenReader: Boolean = true,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalTouchExplorationActive provides screenReader) {
                MaterialTheme {
                    MatchReceiptBanner(state = state, onUndo = { undos++ }, onDismiss = { dismissals++ })
                }
            }
        }
    }

    private val shown = MatchReceiptUiState.Shown(MatchFixtures.receipt, undoing = false, undoError = null)

    @Test
    fun `the receipt says what changed and offers See what changed and Undo`() {
        setBanner(shown)

        composeRule.onNodeWithText("Changed 5 fields, cover from Beacon, 16 chapter names").assertIsDisplayed()
        composeRule.onNodeWithText("See what changed").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").performClick()

        undos shouldBe 1
    }

    @Test
    fun `See what changed lists every change with its source`() {
        setBanner(shown)

        composeRule.onNodeWithText("See what changed").performClick()

        composeRule.onNodeWithText("What changed").assertIsDisplayed()
        composeRule.onNodeWithText("Description · from Atlas").assertIsDisplayed()
        composeRule.onNodeWithText("Cover · from Beacon").assertIsDisplayed()
        composeRule.onNodeWithText("Genres added: Space Opera").assertIsDisplayed()
        composeRule.onNodeWithText("16 chapter names · from Atlas").assertIsDisplayed()
    }

    @Test
    fun `while undoing, Undo says so and can't be tapped again`() {
        setBanner(shown.copy(undoing = true))

        composeRule.onNodeWithText("Undoing…").assertIsNotEnabled()
    }

    @Test
    fun `a receipt that can't be undone offers no Undo`() {
        setBanner(shown.copy(receipt = MatchFixtures.receipt.copy(undoable = false)))

        composeRule.onAllNodesWithTextCount("Undo") shouldBe 0
    }

    @Test
    fun `after Undo it confirms everything is back`() {
        setBanner(MatchReceiptUiState.Undone)

        composeRule.onNodeWithText("Match undone. Everything it changed is back.").assertIsDisplayed()
    }

    @Test
    fun `an expired receipt explains why it can't be undone`() {
        setBanner(MatchReceiptUiState.Expired)

        composeRule.onNodeWithText("This book has changed since, so the match can't be undone.").assertIsDisplayed()
    }

    @Test
    fun `with a screen reader on, the receipt stays until dismissed`() {
        composeRule.mainClock.autoAdvance = false
        setBanner(shown, screenReader = true)

        composeRule.mainClock.advanceTimeBy(60_000)
        composeRule.onNodeWithTag(MATCH_RECEIPT_TAG).assertIsDisplayed()
        dismissals shouldBe 0
    }

    @Test
    fun `without a screen reader, the receipt dismisses itself after a long snackbar's time`() {
        composeRule.mainClock.autoAdvance = false
        setBanner(shown, screenReader = false)

        composeRule.mainClock.advanceTimeBy(11_000)
        dismissals shouldBe 1
    }

    @Test
    fun `nothing renders without a receipt`() {
        setBanner(MatchReceiptUiState.None)

        composeRule.onAllNodesWithTagCount(MATCH_RECEIPT_TAG) shouldBe 0
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(
            androidx.compose.ui.test
                .hasText(text),
        ).fetchSemanticsNodes().size

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(
            androidx.compose.ui.test
                .hasTestTag(tag),
        ).fetchSemanticsNodes().size
}
