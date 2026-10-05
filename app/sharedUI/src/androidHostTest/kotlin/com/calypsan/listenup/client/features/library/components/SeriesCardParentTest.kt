package com.calypsan.listenup.client.features.library.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesProgress
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.core.SeriesId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Library grid shows top-level series only, so a parent card counts what is inside it. */
@RunWith(RobolectricTestRunner::class)
class SeriesCardParentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun card(subSeriesCount: Int) {
        composeRule.setContent {
            SeriesCard(
                seriesWithBooks =
                    SeriesWithBooks(
                        series = Series(id = SeriesId("cosmere"), name = "Cosmere"),
                        books = emptyList(),
                        bookSequences = emptyMap(),
                        subSeriesCount = subSeriesCount,
                    ),
                progress = SeriesProgress(finishedCount = 0, totalCount = 0),
                onClick = {},
            )
        }
    }

    @Test
    fun `a parent card counts its series and its books`() {
        card(subSeriesCount = 4)

        composeRule.onNodeWithText("4 series · 0 books", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a series with no sub-series keeps its book count`() {
        card(subSeriesCount = 0)

        composeRule.onNodeWithText("0 books", useUnmergedTree = true).assertExists()
    }
}
