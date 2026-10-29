package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.presentation.home.HomeSlot
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Spec §2.3 FirstSync, H5 and H14 on Android: the strip, and skeletons the size of what they stand for. */
@RunWith(RobolectricTestRunner::class)
class HomeFirstSyncTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun scanning(
        books: Int,
        booksTotal: Int,
    ) = ScanProgressState(
        phase = "analyzing",
        current = books,
        total = booksTotal,
        added = 0,
        updated = 0,
        removed = 0,
        books = books,
        booksTotal = booksTotal,
    )

    @Test
    fun `the strip announces politely and shows a count only when the scan knows both numbers`() {
        composeRule.setContent { MaterialTheme { FirstSyncStrip(scan = null) } }
        composeRule.onNodeWithText("Building your library", substring = true).assertIsDisplayed()
        composeRule
            .onNodeWithText("Your place in each book is already here; covers and titles are arriving.")
            .assertIsDisplayed()
        composeRule.onAllNodesWithText(" books", substring = true).assertCountEquals(0)
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)).assertExists()
    }

    @Test
    fun `a scan's counts appear in the strip`() {
        composeRule.setContent { MaterialTheme { FirstSyncStrip(scan = scanning(books = 412, booksTotal = 1280)) } }
        composeRule.onNodeWithText("Building your library · 412 of 1,280 books").assertIsDisplayed()
    }

    @Test
    fun `first sync draws a skeleton slot with no Resume, as Home has no Resume hero`() {
        composeRule.setContent { MaterialTheme { HomeSkeletonSlot() } }
        composeRule.onNodeWithContentDescription("Up next, loading").assertIsDisplayed()
        composeRule.onAllNodesWithText("Resume").assertCountEquals(0)
    }

    @Test
    fun `page loading draws the skeletons and no strip, and no spinner`() {
        composeRule.setContent {
            MaterialTheme { HomeLoadingBody(width = HomeWidth.Compact, appHeader = testAppHeader, contentPadding = PaddingValues()) }
        }
        composeRule.onNodeWithContentDescription("Up next, loading").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Loading books").assertExists()
        composeRule.onAllNodesWithText("Building your library", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Resume").assertCountEquals(0)
        composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
    }

    @Test
    fun `Home in first sync puts the strip above the skeleton slot`() {
        composeRule.setContent {
            MaterialTheme {
                HomeBody(
                    state = homeReady(slot = HomeSlot.FirstSync, everythingInProgress = emptyList(), isLibraryArriving = true),
                    stats = HomeStatsUiState.Loading,
                    width = HomeWidth.Compact,
                    actions = HomeActions.None,
                    selection = HomeSelection.None,
                    appHeader = testAppHeader,
                    contentPadding = PaddingValues(),
                )
            }
        }
        composeRule.onNodeWithText("Building your library", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Up next, loading").assertIsDisplayed()
    }

    @Test
    fun `a scan behind a slot still shows the strip, above it`() {
        composeRule.setContent {
            MaterialTheme {
                HomeBody(
                    state =
                        homeReady(
                            slot = finishedSlot(),
                            everythingInProgress = emptyList(),
                            scanProgress = scanning(books = 40, booksTotal = 100),
                        ),
                    stats = HomeStatsUiState.Loading,
                    width = HomeWidth.Compact,
                    actions = HomeActions.None,
                    selection = HomeSelection.None,
                    appHeader = testAppHeader,
                    contentPadding = PaddingValues(),
                )
            }
        }
        val strip = composeRule.onNodeWithText("Building your library · 40 of 100 books").getUnclippedBoundsInRoot()
        val slot = composeRule.onNodeWithText("Maya and Tom finished it too").getUnclippedBoundsInRoot()
        (strip.bottom <= slot.top) shouldBe true
    }

    @Test
    fun `while the library arrives, My shelves loads rather than inviting a first shelf`() {
        composeRule.setContent {
            MaterialTheme {
                HomeBody(
                    state = homeReady(slot = HomeSlot.FirstSync, everythingInProgress = emptyList(), isLibraryArriving = true),
                    stats = HomeStatsUiState.Loading,
                    width = HomeWidth.Compact,
                    actions = HomeActions.None,
                    selection = HomeSelection.None,
                    appHeader = testAppHeader,
                    contentPadding = PaddingValues(),
                )
            }
        }
        composeRule.onNodeWithContentDescription("Loading shelves").assertExists()
        composeRule.onAllNodesWithText("A shelf is your own list", substring = true).assertCountEquals(0)
    }
}
