package com.calypsan.listenup.client.features.library.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Library's one sort control (spec §5.1 c5): a tonal trigger that says the sort in words
 * ("Title, A–Z"), and a menu whose labels come from the string catalog (R12), not shared code.
 */
@RunWith(RobolectricTestRunner::class)
class LibrarySortCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the tonal trigger reads Title, A–Z and its menu holds categories, both directions and Ignore articles`() {
        composeRule.setContent {
            MaterialTheme {
                LibrarySortCard(
                    state = SortState(SortCategory.TITLE, SortDirection.ASCENDING),
                    categories = listOf(SortCategory.TITLE, SortCategory.AUTHOR, SortCategory.DURATION),
                    count = null,
                    unit = "",
                    ignoreArticles = true,
                    showArticleToggle = true,
                    onCategorySelected = {},
                    onDirectionToggle = {},
                    onToggleArticles = {},
                    visible = true,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Sort: Title, A–Z").assertIsDisplayed().performClick()
        listOf("Title", "Author", "Length", "A–Z", "Z–A", "Ignore “A”, “An”, “The”").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test
    fun `a length sort names its direction in its own words`() {
        composeRule.setContent {
            MaterialTheme {
                LibrarySortCard(
                    state = SortState(SortCategory.DURATION, SortDirection.DESCENDING),
                    categories = listOf(SortCategory.TITLE, SortCategory.DURATION),
                    count = null,
                    unit = "",
                    ignoreArticles = true,
                    showArticleToggle = false,
                    onCategorySelected = {},
                    onDirectionToggle = {},
                    onToggleArticles = {},
                    visible = true,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Sort: Length, Longest").assertIsDisplayed()
    }

    @Test
    fun `choosing the other direction toggles once, and the current one does nothing`() {
        var toggles = 0
        composeRule.setContent {
            MaterialTheme {
                LibrarySortCard(
                    state = SortState(SortCategory.NAME, SortDirection.ASCENDING),
                    categories = listOf(SortCategory.NAME, SortCategory.BOOK_COUNT),
                    count = 12,
                    unit = "authors",
                    ignoreArticles = true,
                    showArticleToggle = false,
                    onCategorySelected = {},
                    onDirectionToggle = { toggles++ },
                    onToggleArticles = {},
                    visible = true,
                )
            }
        }

        composeRule.onNodeWithText("12 authors").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Sort: Name, A–Z").performClick()
        composeRule.onNodeWithText("A–Z").performClick()
        assertEquals(0, toggles)
        composeRule.onNodeWithText("Z–A").performClick()
        assertEquals(1, toggles)
    }
}
