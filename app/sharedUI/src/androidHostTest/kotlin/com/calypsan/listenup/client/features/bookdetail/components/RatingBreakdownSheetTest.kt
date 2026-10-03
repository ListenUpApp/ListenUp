package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ExternalRating
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ScoreSource
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val DAY = 86_400_000L
private const val NOW = 1_000 * DAY

private val AUDIBLE =
    ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.8, count = 11_000, fetchedAtMs = NOW - 3 * DAY)
private val HARDCOVER =
    ExternalRating(source = ExternalRatingSource.HARDCOVER, average = 4.5, count = 1_200, fetchedAtMs = NOW - 1 * DAY)
private val SCORE =
    CombinedScore(
        average = 4.6,
        count = 12_203,
        shares =
            mapOf(
                ScoreSource.Outside(ExternalRatingSource.AUDIBLE) to 0.62,
                ScoreSource.Outside(ExternalRatingSource.HARDCOVER) to 0.30,
                ScoreSource.Listeners to 0.08,
            ),
    )

/** The sources sheet: what the ListenUp score is made of, how fresh each part is, and a quiet refresh. */
@RunWith(RobolectricTestRunner::class)
class RatingBreakdownSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(
        breakdown: List<ExternalRating> = listOf(AUDIBLE, HARDCOVER),
        score: CombinedScore? = SCORE,
        listeners: ListenerAverage? = ListenerAverage(8.0, 3),
        canRefresh: Boolean = false,
        isRefreshing: Boolean = false,
        onRefresh: () -> Unit = {},
    ) {
        composeRule.setContent {
            RatingBreakdownSheet(
                breakdown = breakdown,
                canRefresh = canRefresh,
                score = score,
                listeners = listeners,
                isRefreshingExternal = isRefreshing,
                onRefresh = onRefresh,
                onDismiss = {},
                nowMs = NOW,
            )
        }
    }

    @Test
    fun `the head shows the ListenUp score and how many it is combined from`() {
        show()

        composeRule.onNodeWithText("Combined from 3 sources").assertIsDisplayed()
        composeRule.onNodeWithText("ListenUp score · 12k ratings").assertIsDisplayed()
    }

    @Test
    fun `each source shows its average, count, share and how fresh it is`() {
        show()

        composeRule.onNodeWithText("Audible").assertIsDisplayed()
        composeRule.onNodeWithText("11k ratings").assertIsDisplayed()
        composeRule.onNodeWithText("62%").assertIsDisplayed()
        composeRule.onNodeWithText("Updated 3 days ago").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Updated yesterday").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `your listeners get their own row, with no freshness line`() {
        show()

        composeRule.onNodeWithText("Your listeners").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("3 ratings").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("8%").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a source from an older server has no freshness line`() {
        show(breakdown = listOf(AUDIBLE.copy(fetchedAtMs = null)), score = null, listeners = null)

        composeRule.onNodeWithText("Audible").assertIsDisplayed()
        composeRule.onNodeWithText("Updated", substring = true).assertDoesNotExist()
    }

    @Test
    fun `no refresh for a listener`() {
        show(canRefresh = false)

        composeRule.onNodeWithTag("refreshRatingsButton").assertDoesNotExist()
    }

    @Test
    fun `an admin refreshes from a quiet action`() {
        var refreshes = 0
        show(canRefresh = true, onRefresh = { refreshes++ })
        composeRule.onNodeWithTag("refreshRatingsButton").performScrollTo().performClick()
        refreshes shouldBe 1
    }

    @Test
    fun `the refresh is disabled while one is in flight`() {
        show(canRefresh = true, isRefreshing = true)

        composeRule.onNodeWithTag("refreshRatingsButton").assertIsNotEnabled()
    }
}
