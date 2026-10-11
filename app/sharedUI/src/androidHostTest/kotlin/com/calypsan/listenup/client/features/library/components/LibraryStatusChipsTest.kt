package com.calypsan.listenup.client.features.library.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.presentation.library.BookStatusCounts
import com.calypsan.listenup.client.presentation.library.BookStatusFilter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryStatusChipsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val counts = BookStatusCounts(all = 248, inProgress = 4, notStarted = 183, finished = 61)

    @Test
    fun `every chip carries its whole-library count and the active one is selected`() {
        composeRule.setContent {
            MaterialTheme { LibraryStatusChips(selected = BookStatusFilter.FINISHED, counts = counts, onSelect = {}) }
        }

        composeRule.onNodeWithText("All 248").assertIsNotSelected()
        composeRule.onNodeWithText("In progress 4").assertIsNotSelected()
        composeRule.onNodeWithText("Not started 183").assertIsNotSelected()
        composeRule.onNodeWithText("Finished 61").assertIsSelected()
    }

    @Test
    fun `tapping a chip reports its filter`() {
        var chosen: BookStatusFilter? = null
        composeRule.setContent {
            MaterialTheme { LibraryStatusChips(selected = BookStatusFilter.ALL, counts = counts, onSelect = { chosen = it }) }
        }

        composeRule.onNodeWithText("Not started 183").performClick()

        assertEquals(BookStatusFilter.NOT_STARTED, chosen)
    }
}
