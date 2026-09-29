package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.domain.model.ExternalRating
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The per-source breakdown sheet one tap away from the Book Detail headline. */
@RunWith(RobolectricTestRunner::class)
class RatingBreakdownSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `one row per source, highest rating count first`() {
        composeRule.setContent {
            RatingBreakdownSheet(
                breakdown =
                    listOf(
                        ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.5, count = 8_100),
                        ExternalRating(source = ExternalRatingSource.HARDCOVER, average = 4.0, count = 300),
                    ),
                canRefresh = false,
                isRefreshingExternal = false,
                onRefresh = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Audible · 4.5 · 8.1k").assertIsDisplayed()
        composeRule.onNodeWithText("Hardcover · 4.0 · 300").assertIsDisplayed()
    }

    @Test
    fun `no refresh action when the signed-in listener cannot refresh`() {
        composeRule.setContent {
            RatingBreakdownSheet(
                breakdown = emptyList(),
                canRefresh = false,
                isRefreshingExternal = false,
                onRefresh = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Refresh ratings").assertDoesNotExist()
    }

    @Test
    fun `refresh action is shown when canRefresh and calls the view model`() {
        var refreshed = false
        composeRule.setContent {
            RatingBreakdownSheet(
                breakdown = listOf(ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.5, count = 8_100)),
                canRefresh = true,
                isRefreshingExternal = false,
                onRefresh = { refreshed = true },
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Refresh ratings").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh ratings").performClick()

        refreshed shouldBe true
    }

    @Test
    fun `the refresh button is busy and disabled while a refresh is in flight`() {
        composeRule.setContent {
            RatingBreakdownSheet(
                breakdown = listOf(ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.5, count = 8_100)),
                canRefresh = true,
                isRefreshingExternal = true,
                onRefresh = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithTag("refreshRatingsButton").assertIsNotEnabled()
        composeRule.onNodeWithText("Refresh ratings").assertDoesNotExist()
    }
}
