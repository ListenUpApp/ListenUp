package com.calypsan.listenup.client.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBook
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksUiState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A local cover path takes BookCoverImage's synchronous path; a null one resolves through Koin, absent here.
private val GATE = KeptOffBook("b1", "The Gate of the Feral Gods", "Matt Dinniman", "/tmp/cover-b1.webp", null)
private val EDUCATED = KeptOffBook("b2", "Educated", "Tara Westover", "/tmp/cover-b2.webp", null)

/** #1541's list on Android: the books kept off Hardcover, each with Sync again. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class HardcoverKeptOffContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val synced = mutableListOf<String>()

    private fun render(state: KeptOffBooksUiState) {
        composeRule.setContent {
            MaterialTheme { HardcoverKeptOffContent(state = state, onSyncAgain = { synced += it }) }
        }
    }

    @Test
    fun `the list says what keeping off means, and each book offers Sync again`() {
        render(KeptOffBooksUiState.Loaded(listOf(GATE, EDUCATED)))
        composeRule
            .onNodeWithText("Nothing about these books is shared with Hardcover or brought in from it.")
            .assertIsDisplayed()
        // The cover's text fallback names the book too, so the row's title and author are the first of two.
        composeRule.onAllNodesWithText("The Gate of the Feral Gods")[0].assertIsDisplayed()
        composeRule.onAllNodesWithText("Matt Dinniman")[0].assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Sync The Gate of the Feral Gods with Hardcover again")
            .assertHeightIsAtLeast(44.dp)
            .performClick()
        synced shouldBe listOf("b1")
    }

    @Test
    fun `a list the server couldn't give says so, and claims no books`() {
        render(KeptOffBooksUiState.Unavailable)
        composeRule.onNodeWithText("Couldn't load these books. Try again in a moment.").assertIsDisplayed()
        composeRule.onNodeWithText("Sync again").assertDoesNotExist()
    }
}
