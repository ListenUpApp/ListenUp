package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.presentation.home.HomeConnectivity
import com.calypsan.listenup.client.presentation.home.HomeSlot
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.client.presentation.home.TimeOfDay
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Spec §2.5 Offline, §3.1 and §3.3 on Android: the pill, the dimmed cards and the failures. */
@RunWith(RobolectricTestRunner::class)
class HomeNoticesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun babel(downloaded: Boolean) =
        ContinueListeningItem.Ready(
            bookId = "babel",
            book =
                ContinueListeningBook(
                    bookId = "babel",
                    title = "Babel",
                    authorNames = "R. F. Kuang",
                    narratorNames = null,
                    coverPath = "/tmp/cover-babel.webp",
                    progress = 0.11f,
                    currentPositionMs = 8_000_000L,
                    totalDurationMs = 75_200_000L,
                    lastPlayedAtMs = 0L,
                    isDownloaded = downloaded,
                ),
        )

    @Test
    fun `the pill says offline quietly and politely`() {
        composeRule.setContent { MaterialTheme { OfflinePill() } }
        composeRule.onNodeWithText("Offline · downloaded books play as usual").assertIsDisplayed()
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)).assertExists()
    }

    @Test
    fun `offline, a card that is not downloaded reads Needs a connection and stays tappable`() {
        var opened: String? = null
        composeRule.setContent {
            MaterialTheme {
                HomeBookCard(
                    item = babel(downloaded = false),
                    isWide = false,
                    selection = HomeSelection.None,
                    onOpenBook = { opened = it },
                    cardWidth = 156.dp,
                    connectivity = HomeConnectivity.Offline,
                )
            }
        }
        composeRule.onNodeWithText("Needs a connection", useUnmergedTree = true).assertExists()
        composeRule
            .onNodeWithContentDescription("Babel, by R. F. Kuang, 11 percent listened, not downloaded, needs a connection")
            .assertHasClickAction()
            .performClick()
        opened shouldBe "babel"
    }

    @Test
    fun `a downloaded card offline reads as usual`() {
        composeRule.setContent {
            MaterialTheme {
                HomeBookCard(babel(downloaded = true), false, HomeSelection.None, {}, 156.dp, HomeConnectivity.Offline)
            }
        }
        composeRule.onAllNodesWithText("Needs a connection", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `Home offline shows the pill and dims the card that needs a connection`() {
        composeRule.setContent {
            MaterialTheme {
                HomeBody(
                    state =
                        homeReady(
                            slot = HomeSlot.Absent,
                            everythingInProgress = listOf(babel(downloaded = false)),
                            connectivity = HomeConnectivity.ServerUnreachable,
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
        composeRule.onNodeWithText("Offline · downloaded books play as usual").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Babel, by R. F. Kuang, 11 percent listened, not downloaded, needs a connection")
            .assertExists()
    }

    @Test
    fun `a failed Continue Listening is one line with Try again in the slot position, and no snackbar`() {
        var retried = false
        composeRule.setContent {
            MaterialTheme {
                HomeBody(
                    state =
                        homeReady(
                            slot = HomeSlot.Absent,
                            everythingInProgress = emptyList(),
                            continueListeningError = UnexpectedClientError(),
                        ),
                    stats = HomeStatsUiState.Loading,
                    width = HomeWidth.Compact,
                    actions = HomeActions.None.copy(onRetry = { retried = true }),
                    selection = HomeSelection.None,
                    appHeader = testAppHeader,
                    contentPadding = PaddingValues(),
                )
            }
        }
        composeRule.onNodeWithText("Couldn't load what you're listening to").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        retried shouldBe true
    }

    @Test
    fun `a page error keeps the greeting and puts the error where the slot goes`() {
        var retried = false
        composeRule.setContent {
            MaterialTheme {
                HomeErrorBody(
                    state = HomeUiState.Error(UnexpectedClientError(), TimeOfDay.Evening),
                    onRetry = { retried = true },
                    appHeader = testAppHeader,
                    contentPadding = PaddingValues(),
                )
            }
        }
        composeRule.onNodeWithText("Good evening").assertIsDisplayed()
        composeRule.onNodeWithText("We couldn't load Home.").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        retried shouldBe true
    }

    @Test
    fun `offline with a failed Continue Listening shows the pill and the line together`() {
        composeRule.setContent {
            MaterialTheme {
                HomeBody(
                    state =
                        homeReady(
                            slot = HomeSlot.Absent,
                            everythingInProgress = emptyList(),
                            connectivity = HomeConnectivity.Offline,
                            continueListeningError = UnexpectedClientError(),
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
        composeRule.onNodeWithText("Offline · downloaded books play as usual").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't load what you're listening to").assertIsDisplayed()
    }
}
