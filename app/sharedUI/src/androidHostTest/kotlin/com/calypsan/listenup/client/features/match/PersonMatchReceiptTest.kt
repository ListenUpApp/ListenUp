package com.calypsan.listenup.client.features.match

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
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

/** The receipt the contributor page shows after a person's Apply: one sentence naming them, and Undo. */
@RunWith(RobolectricTestRunner::class)
class PersonMatchReceiptTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var undos = 0
    private var dismissals = 0
    private val ray = MatchReceiptSubject.Person(name = "Ray Porter")

    private fun setBanner(
        state: MatchReceiptUiState,
        screenReader: Boolean = true,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalTouchExplorationActive provides screenReader) {
                MaterialTheme {
                    MatchReceiptBanner(state = state, subject = ray, onUndo = { undos++ }, onDismiss = { dismissals++ })
                }
            }
        }
    }

    private val shown = MatchReceiptUiState.Shown(PersonMatchFixtures.receipt, undoing = false, undoError = null)

    @Test
    fun `the receipt names the person and what changed, with Undo and no See what changed`() {
        setBanner(shown)

        composeRule.onNodeWithText("Changed photo and biography for Ray Porter").assertIsDisplayed()
        composeRule.onAllNodes(hasText("See what changed")).fetchSemanticsNodes().size shouldBe 0
        composeRule.onNodeWithText("Undo").performClick()

        undos shouldBe 1
    }

    @Test
    fun `a photo alone says photo`() {
        setBanner(shown.copy(receipt = PersonMatchFixtures.receipt.copy(biographySource = null)))

        composeRule.onNodeWithText("Changed photo for Ray Porter").assertIsDisplayed()
    }

    @Test
    fun `an expired person receipt names them`() {
        setBanner(MatchReceiptUiState.Expired)

        composeRule.onNodeWithText("Ray Porter has changed since, so the match can't be undone.").assertIsDisplayed()
    }

    @Test
    fun `with a screen reader on, the person receipt stays until dismissed`() {
        composeRule.mainClock.autoAdvance = false
        setBanner(shown, screenReader = true)

        composeRule.mainClock.advanceTimeBy(60_000)
        composeRule.onNodeWithTag(MATCH_RECEIPT_TAG).assertIsDisplayed()
        dismissals shouldBe 0
    }
}
