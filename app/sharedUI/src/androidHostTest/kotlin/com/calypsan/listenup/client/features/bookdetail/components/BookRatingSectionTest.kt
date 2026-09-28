package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
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
                state = BookRatingsUiState.Ready(listeners = null, mine = null),
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
                state = BookRatingsUiState.Ready(listeners = ListenerAverage(8.0, 1), mine = mine),
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
                state = BookRatingsUiState.Ready(listeners = ListenerAverage(8.0, 1), mine = null),
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
                state = BookRatingsUiState.Ready(listeners = ListenerAverage(7.0, 3), mine = null),
                onRate = {},
                onEdit = {},
            )
        }

        composeRule
            .onNodeWithContentDescription("Your listeners: 3.5 out of 5 stars, from 3 ratings")
            .assertIsDisplayed()
    }
}
