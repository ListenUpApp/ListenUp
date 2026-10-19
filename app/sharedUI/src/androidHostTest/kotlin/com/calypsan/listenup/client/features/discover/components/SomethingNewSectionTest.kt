package com.calypsan.listenup.client.features.discover.components

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.discover.WithAvatarDependencies
import com.calypsan.listenup.client.features.discover.pick
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverUiBook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Something new" names what it draws (books you have not started, first in their series), says
 * which book of its series each pick is and how long it runs, and its Shuffle says what it does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SomethingNewSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private fun show(
        books: List<DiscoverUiBook>,
        onShuffle: () -> Unit = {},
    ) {
        composeRule.setContent {
            WithAvatarDependencies {
                SomethingNewSection(
                    state = DiscoverBooksUiState.Ready(books),
                    onShuffle = onShuffle,
                    onRetry = {},
                    onBrowseLibrary = null,
                    onBookClick = {},
                    isInSelectionMode = false,
                    selectedBookIds = emptySet(),
                    onBookLongPress = null,
                )
            }
        }
    }

    @Test
    fun theHeadingIsOneWordingWithItsSubtitle() {
        show(listOf(pick("p1", "Mistborn", "Mistborn", "1")))
        composeRule.onNode(hasText("Something new") and isHeading()).assertExists()
        composeRule.onNodeWithText("Books you haven't started, first in their series").assertExists()
    }

    @Test
    fun aPickSaysWhichBookOfItsSeriesAndItsLength() {
        show(
            listOf(
                pick("p1", "Mistborn", "Mistborn", "1", durationMs = 88_740_000),
                pick("p2", "Klara and the Sun", null, null, durationMs = 36_960_000),
            ),
        )
        composeRule.onNodeWithText("Book 1 of Mistborn", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Standalone", useUnmergedTree = true).assertExists()
        composeRule.onAllNodesWithText("24h 39m", useUnmergedTree = true).onFirst().assertExists()
    }

    @Test
    fun shuffleIsNamedForWhatItDoesAndAsksOnce() {
        var shuffles = 0
        show(listOf(pick("p1", "Mistborn", "Mistborn", "1")), onShuffle = { shuffles++ })
        composeRule
            .onNodeWithContentDescription("Shuffle: draw ten different books")
            .assertHasClickAction()
            .performClick()
        assertEquals(1, shuffles)
    }

    @Test
    fun shuffleIsAtLeastFortyEightDp() {
        show(listOf(pick("p1", "Mistborn", "Mistborn", "1")))
        composeRule
            .onNodeWithContentDescription("Shuffle: draw ten different books")
            .assertTouchHeightIsEqualTo(48.dp)
    }
}
