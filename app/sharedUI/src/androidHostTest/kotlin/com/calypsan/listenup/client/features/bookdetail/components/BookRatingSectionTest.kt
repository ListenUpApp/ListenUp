package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ExternalRating
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Book Detail rating section: an invitation before you rate, your stars after. */
@RunWith(RobolectricTestRunner::class)
class BookRatingSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `offers Rate when you have not rated the book`() {
        composeRule.setContent {
            BookRatingSection(
                state =
                    BookRatingsUiState.Ready(
                        listeners = null,
                        mine = null,
                        external = null,
                        breakdown = emptyList(),
                        canRefresh = false,
                    ),
                onRate = {},
                onEdit = {},
            )
        }

        composeRule.onNodeWithText("Rate").assertIsDisplayed()
        composeRule.onNodeWithText("Your rating").assertDoesNotExist()
        composeRule.onNodeWithText("Edit").assertDoesNotExist()
    }

    @Test
    fun `shows your rating with Edit once you have rated it`() {
        val mine = ListenerRating(bookId = "b1", userId = "me", halfStars = 8, note = null, ratedAtMs = 1L)
        composeRule.setContent {
            BookRatingSection(
                state =
                    BookRatingsUiState.Ready(
                        listeners = ListenerAverage(8.0, 1),
                        mine = mine,
                        external = null,
                        breakdown = emptyList(),
                        canRefresh = false,
                    ),
                onRate = {},
                onEdit = {},
            )
        }

        composeRule.onNodeWithText("Your rating").assertIsDisplayed()
        composeRule.onNodeWithText("Edit").assertIsDisplayed()
        composeRule.onNodeWithText("Rate").assertDoesNotExist()
    }

    @Test
    fun `one listener's rating is read as one rating`() {
        composeRule.setContent {
            BookRatingSection(
                state =
                    BookRatingsUiState.Ready(
                        listeners = ListenerAverage(8.0, 1),
                        mine = null,
                        external = null,
                        breakdown = emptyList(),
                        canRefresh = false,
                    ),
                onRate = {},
                onEdit = {},
            )
        }

        composeRule
            .onNodeWithContentDescription("Your listeners: 4 out of 5 stars, from 1 rating")
            .assertIsDisplayed()
    }

    @Test
    fun `several listeners' ratings are read as ratings`() {
        composeRule.setContent {
            BookRatingSection(
                state =
                    BookRatingsUiState.Ready(
                        listeners = ListenerAverage(7.0, 3),
                        mine = null,
                        external = null,
                        breakdown = emptyList(),
                        canRefresh = false,
                    ),
                onRate = {},
                onEdit = {},
            )
        }

        composeRule
            .onNodeWithContentDescription("Your listeners: 3.5 out of 5 stars, from 3 ratings")
            .assertIsDisplayed()
    }

    @Test
    fun `no outside headline when no enabled source has rated the book`() {
        composeRule.setContent {
            BookRatingSection(
                state =
                    BookRatingsUiState.Ready(
                        listeners = null,
                        mine = null,
                        external = null,
                        breakdown = emptyList(),
                        canRefresh = false,
                    ),
                onRate = {},
                onEdit = {},
            )
        }

        composeRule.onNodeWithContentDescription("Rated 4.4 out of 5 stars by 12k readers elsewhere").assertDoesNotExist()
    }

    @Test
    fun `the outside headline reads its average to one decimal and compact count`() {
        composeRule.setContent {
            BookRatingSection(
                state =
                    BookRatingsUiState.Ready(
                        listeners = null,
                        mine = null,
                        external = CombinedScore(average = 4.4, count = 12_000),
                        breakdown =
                            listOf(ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.4, count = 12_000)),
                        canRefresh = false,
                    ),
                onRate = {},
                onEdit = {},
            )
        }

        // averageLabel(4.4) is "4.4" verbatim — no half-star rounding.
        composeRule
            .onNodeWithContentDescription("Rated 4.4 out of 5 stars by 12k readers elsewhere")
            .assertIsDisplayed()
    }

    @Test
    fun `a whole-number outside average reads with its trailing zero`() {
        composeRule.setContent {
            BookRatingSection(
                state =
                    BookRatingsUiState.Ready(
                        listeners = null,
                        mine = null,
                        external = CombinedScore(average = 4.0, count = 12_000),
                        breakdown =
                            listOf(ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.0, count = 12_000)),
                        canRefresh = false,
                    ),
                onRate = {},
                onEdit = {},
            )
        }

        composeRule
            .onNodeWithContentDescription("Rated 4.0 out of 5 stars by 12k readers elsewhere")
            .assertIsDisplayed()
    }

    @Test
    fun `one outside rating is read as one reader`() {
        composeRule.setContent {
            BookRatingSection(
                state =
                    BookRatingsUiState.Ready(
                        listeners = null,
                        mine = null,
                        external = CombinedScore(average = 4.0, count = 1),
                        breakdown =
                            listOf(ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.0, count = 1)),
                        canRefresh = false,
                    ),
                onRate = {},
                onEdit = {},
            )
        }

        composeRule
            .onNodeWithContentDescription("Rated 4.0 out of 5 stars by 1 reader elsewhere")
            .assertIsDisplayed()
    }

    @Test
    fun `tapping the outside headline opens the breakdown`() {
        var opened = false
        composeRule.setContent {
            BookRatingSection(
                state =
                    BookRatingsUiState.Ready(
                        listeners = null,
                        mine = null,
                        external = CombinedScore(average = 4.4, count = 12_000),
                        breakdown =
                            listOf(ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.4, count = 12_000)),
                        canRefresh = false,
                    ),
                onRate = {},
                onEdit = {},
                onOpenBreakdown = { opened = true },
            )
        }

        composeRule
            .onNodeWithContentDescription("Rated 4.4 out of 5 stars by 12k readers elsewhere")
            .performClick()

        opened shouldBe true
    }

    @Test
    fun `an admin can refresh ratings before any score exists`() {
        var refreshed = false
        composeRule.setContent {
            BookRatingSection(
                state =
                    BookRatingsUiState.Ready(
                        listeners = null,
                        mine = null,
                        external = null,
                        breakdown = emptyList(),
                        canRefresh = true,
                    ),
                onRate = {},
                onEdit = {},
                onRefreshExternal = { refreshed = true },
            )
        }

        composeRule.onNodeWithText("Refresh ratings").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh ratings").performClick()

        refreshed shouldBe true
    }

    @Test
    fun `the refresh action is busy while a refresh is in flight`() {
        composeRule.setContent {
            BookRatingSection(
                state =
                    BookRatingsUiState.Ready(
                        listeners = null,
                        mine = null,
                        external = null,
                        breakdown = emptyList(),
                        canRefresh = true,
                        isRefreshingExternal = true,
                    ),
                onRate = {},
                onEdit = {},
                onRefreshExternal = {},
            )
        }

        composeRule.onNodeWithTag("refreshRatingsInlineButton").assertIsNotEnabled()
        composeRule.onNodeWithText("Refresh ratings").assertDoesNotExist()
    }

    @Test
    fun `a non-admin sees no refresh action before any score exists`() {
        composeRule.setContent {
            BookRatingSection(
                state =
                    BookRatingsUiState.Ready(
                        listeners = null,
                        mine = null,
                        external = null,
                        breakdown = emptyList(),
                        canRefresh = false,
                    ),
                onRate = {},
                onEdit = {},
            )
        }

        composeRule.onNodeWithText("Refresh ratings").assertDoesNotExist()
    }

    @Test
    fun `no refresh action in the section once a score exists`() {
        composeRule.setContent {
            BookRatingSection(
                state =
                    BookRatingsUiState.Ready(
                        listeners = null,
                        mine = null,
                        external = CombinedScore(average = 4.4, count = 12_000),
                        breakdown =
                            listOf(ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.4, count = 12_000)),
                        canRefresh = true,
                    ),
                onRate = {},
                onEdit = {},
            )
        }

        composeRule.onNodeWithText("Refresh ratings").assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription("Rated 4.4 out of 5 stars by 12k readers elsewhere")
            .assertIsDisplayed()
    }
}
