package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchedBook
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val MATCH = HardcoverMatchedBook(427_578L, "Project Hail Mary", listOf("Andy Weir"), 2021, chosenByYou = true)

/** Spec B5's Book Detail card, in each of its states. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BookHardcoverSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var opens = 0
    private var removes = 0

    private fun render(state: BookHardcoverUiState) {
        composeRule.setContent {
            MaterialTheme {
                BookHardcoverContent(state = state, onFindMatch = { opens++ }, onRemoveMatch = { removes++ })
            }
        }
    }

    @Test
    fun `needs a match explains itself and opens Find on Hardcover`() {
        render(BookHardcoverUiState.NeedsMatch)
        composeRule.onNodeWithText("Needs a match").assertIsDisplayed()
        composeRule.onNodeWithText("Pick the right book so your listening syncs.").assertIsDisplayed()
        composeRule.onNodeWithText("Find on Hardcover").performClick()
        opens shouldBe 1
    }

    @Test
    fun `a linked book shows what it is matched to, who chose it, and where it stands`() {
        render(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.WAITING))
        composeRule.onNodeWithText("On Hardcover").assertIsDisplayed()
        composeRule.onNodeWithText("Project Hail Mary").assertIsDisplayed()
        composeRule.onNodeWithText("Andy Weir · 2021").assertIsDisplayed()
        composeRule.onNodeWithText("Matched by you").assertIsDisplayed()
        composeRule.onNodeWithText("Waiting to sync").assertIsDisplayed()
        composeRule.onNodeWithText("Change match").performClick()
        opens shouldBe 1
    }

    @Test
    fun `Remove match removes it`() {
        render(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.UP_TO_DATE))
        composeRule.onNodeWithText("Remove match").performClick()
        removes shouldBe 1
    }

    @Test
    fun `up to date says so`() {
        render(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.UP_TO_DATE))
        composeRule.onNodeWithText("Up to date on Hardcover").assertIsDisplayed()
    }

    @Test
    fun `nothing sent yet says so`() {
        render(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.NOTHING_SENT_YET))
        composeRule.onNodeWithText("Nothing sent yet").assertIsDisplayed()
    }

    @Test
    fun `removed on Hardcover explains the pause and the way out`() {
        render(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.REMOVED_ON_HARDCOVER))
        composeRule
            .onNodeWithText("Paused: you removed it on Hardcover. Listening to it again starts a new read.")
            .assertIsDisplayed()
    }

    @Test
    fun `a fresh match reads Matched just now in place of where it stands`() {
        render(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.NOTHING_SENT_YET, justMatched = true))
        composeRule.onNodeWithText("Matched just now").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing sent yet").assertDoesNotExist()
    }

    @Test
    fun `a match Hardcover couldn't name still says it is matched`() {
        render(BookHardcoverUiState.Linked(MATCH.copy(title = null), HardcoverBookSync.UP_TO_DATE))
        composeRule.onNodeWithText("Matched on Hardcover").assertIsDisplayed()
    }

    @Test
    fun `hidden draws nothing`() {
        render(BookHardcoverUiState.Hidden)
        composeRule.onNodeWithText("On Hardcover").assertDoesNotExist()
        composeRule.onNodeWithText("Hardcover").assertDoesNotExist()
    }
}
