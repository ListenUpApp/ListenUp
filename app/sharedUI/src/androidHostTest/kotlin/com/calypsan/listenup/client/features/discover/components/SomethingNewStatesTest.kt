package com.calypsan.listenup.client.features.discover.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.client.features.discover.NOW_MS
import com.calypsan.listenup.client.features.discover.WithAvatarDependencies
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec §3.8 for Something new and Recently added: both keep their heading while loading or failed,
 * each Try again restarts its own section, and "started everything" offers the Library instead of a
 * Shuffle that would draw nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w412dp-h3000dp")
class SomethingNewStatesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private fun show(
        state: DiscoverBooksUiState,
        onRetry: () -> Unit = {},
        onBrowseLibrary: () -> Unit = {},
    ) = composeRule.setContent {
        WithAvatarDependencies {
            SomethingNewSection(
                state = state,
                onShuffle = {},
                onRetry = onRetry,
                onBrowseLibrary = onBrowseLibrary,
                onBookClick = {},
                isInSelectionMode = false,
                selectedBookIds = emptySet(),
                onBookLongPress = null,
            )
        }
    }

    private fun showRecent(
        state: RecentlyAddedUiState,
        onRetry: () -> Unit = {},
    ) = composeRule.setContent {
        WithAvatarDependencies {
            RecentlyAddedSection(
                state = state,
                onBookClick = {},
                onOpenLibraryNewest = {},
                onRetry = onRetry,
                nowMs = NOW_MS,
            )
        }
    }

    @Test
    fun `loading keeps the heading, the subtitle and a skeleton`() {
        show(DiscoverBooksUiState.Loading)
        composeRule.onNode(hasText("Something new") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("Books you haven't started", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("picks-skeleton").assertIsDisplayed()
    }

    @Test
    fun `started everything says so and offers the Library`() {
        var browsed = 0
        show(DiscoverBooksUiState.AllStarted, onBrowseLibrary = { browsed++ })
        composeRule.onNodeWithText("You've started every book here.").assertIsDisplayed()
        composeRule.onNode(hasText("Browse the Library") and hasClickAction()).performClick()
        assertEquals(1, browsed)
    }

    @Test
    fun `started everything hides Shuffle, which would draw nothing`() {
        show(DiscoverBooksUiState.AllStarted)
        composeRule.onNodeWithContentDescription("Shuffle: draw ten different books").assertDoesNotExist()
    }

    @Test
    fun `an error's Try again draws again, and Shuffle steps aside`() {
        var draws = 0
        show(DiscoverBooksUiState.Error(UnexpectedClientError()), onRetry = { draws++ })
        composeRule.onNodeWithText("Couldn't load new books").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Shuffle: draw ten different books").assertDoesNotExist()
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, draws)
    }

    @Test
    fun `an empty library is said plainly, never 'you started everything'`() {
        show(DiscoverBooksUiState.Ready(emptyList()))
        composeRule.onNodeWithText("Nothing new just yet.").assertIsDisplayed()
        composeRule.onNodeWithText("You've started every book here.").assertDoesNotExist()
    }

    @Test
    fun `recently added keeps its heading while loading`() {
        showRecent(RecentlyAddedUiState.Loading)
        composeRule.onNode(hasText("Recently added") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithTag("recent-skeleton").assertIsDisplayed()
    }

    @Test
    fun `recently added owns up to a failure with its own Try again`() {
        var retried = 0
        showRecent(RecentlyAddedUiState.Error(UnexpectedClientError()), onRetry = { retried++ })
        composeRule.onNodeWithText("Couldn't load recently added").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `recently added with nothing new says so`() {
        showRecent(RecentlyAddedUiState.Ready(emptyList()))
        composeRule.onNode(hasText("Recently added") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("No books added recently.").assertIsDisplayed()
    }
}
