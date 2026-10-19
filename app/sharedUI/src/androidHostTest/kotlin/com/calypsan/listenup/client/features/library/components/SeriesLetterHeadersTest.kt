package com.calypsan.listenup.client.features.library.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.presentation.library.LetterSection
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.core.SeriesId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Spec L25: Series sorted by name carry letter headers from the shared `seriesLetters`, as Books,
 * Authors and Narrators do. Any other sort has none.
 */
@RunWith(RobolectricTestRunner::class)
class SeriesLetterHeadersTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val series = listOf(series("s1", "Dune"), series("s2", "Dungeon Crawler Carl"), series("s3", "Wool"))

    private fun render(
        sortState: SortState,
        letters: List<LetterSection>,
    ) {
        composeRule.setContent {
            MaterialTheme {
                SeriesContent(
                    series = series,
                    seriesProgress = emptyMap(),
                    sortState = sortState,
                    letters = letters,
                    ignoreArticles = true,
                    onCategorySelected = {},
                    onDirectionToggle = {},
                    onToggleIgnoreArticles = {},
                    onSeriesClick = {},
                )
            }
        }
    }

    @Test
    fun `series by name get a header per letter`() {
        render(SortState(SortCategory.NAME, SortDirection.ASCENDING), listOf(LetterSection("D", 0, 2), LetterSection("W", 2, 1)))

        composeRule.onAllNodes(isHeading())[0].assertTextEquals("D")
        // The grid is lazy: W's header exists once the grid scrolls to it.
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(isHeading() and hasText("W"))
        composeRule.onNode(isHeading() and hasText("W")).assertIsDisplayed()
    }

    @Test
    fun `a book-count sort has no headers`() {
        render(SortState(SortCategory.BOOK_COUNT, SortDirection.DESCENDING), emptyList())

        composeRule.onAllNodes(isHeading()).assertCountEquals(0)
    }

    private fun series(
        id: String,
        name: String,
    ) = SeriesWithBooks(series = Series(id = SeriesId(id), name = name), books = emptyList(), bookSequences = emptyMap())
}
