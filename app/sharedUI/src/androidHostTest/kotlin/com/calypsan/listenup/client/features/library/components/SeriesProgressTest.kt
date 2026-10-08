package com.calypsan.listenup.client.features.library.components

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesProgress
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.core.SeriesId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Series tab says how far through each series the listener is — "Complete", "Not started", or
 * an "X of Y" bar — matching iOS's `SeriesProgressBadge` and web's series cards.
 */
@RunWith(RobolectricTestRunner::class)
class SeriesProgressTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val stormlight =
        SeriesWithBooks(
            series = Series(id = SeriesId("s1"), name = "Stormlight"),
            books = emptyList(),
            bookSequences = emptyMap(),
        )

    private fun card(progress: SeriesProgress) {
        composeRule.setContent { SeriesCard(seriesWithBooks = stormlight, progress = progress, onClick = {}) }
    }

    @Test
    fun `a finished series reads as complete`() {
        card(SeriesProgress(finishedCount = 5, totalCount = 5))

        composeRule.onNodeWithText("Complete", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `an untouched series reads as not started`() {
        card(SeriesProgress(finishedCount = 0, totalCount = 5))

        composeRule.onNodeWithText("Not started", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a series part-way through shows how many are finished, as a bar and as a count`() {
        card(SeriesProgress(finishedCount = 3, totalCount = 8))

        composeRule.onNodeWithText("3 of 8", useUnmergedTree = true).assertIsDisplayed()
        composeRule
            .onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(3f / 8f, 0f..1f)), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `the series tab hands each card its own series' progress`() {
        composeRule.setContent {
            SeriesContent(
                series = listOf(stormlight),
                seriesProgress = mapOf(SeriesId("s1") to SeriesProgress(finishedCount = 2, totalCount = 4)),
                sortState = SortState(SortCategory.NAME, SortDirection.ASCENDING),
                ignoreArticles = false,
                onCategorySelected = {},
                onDirectionToggle = {},
                onToggleIgnoreArticles = {},
                onSeriesClick = {},
            )
        }

        composeRule.onNodeWithText("2 of 4", useUnmergedTree = true).assertIsDisplayed()
    }
}
