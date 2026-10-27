package com.calypsan.listenup.client.features.library.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.presentation.library.BookStatusCounts
import com.calypsan.listenup.client.presentation.library.BookStatusFilter
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import io.kotest.matchers.shouldBe
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

    // TalkBack activates a control through its click ACTION, not a touch: a trigger that names
    // itself but carries no action is announced as a button and then does nothing on a double-tap.
    @Test
    fun `the trigger is a button with a click action, so TalkBack can open it`() {
        composeRule.setContent {
            MaterialTheme {
                LibrarySortCard(
                    state = SortState(SortCategory.TITLE, SortDirection.ASCENDING),
                    categories = listOf(SortCategory.TITLE, SortCategory.AUTHOR),
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

        composeRule
            .onNodeWithContentDescription("Sort: Title, A–Z")
            .assert(hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun `every category names both its directions from the catalog`() {
        val expected =
            listOf(
                Triple(SortCategory.TITLE, "A–Z", "Z–A"),
                Triple(SortCategory.DURATION, "Shortest", "Longest"),
                Triple(SortCategory.YEAR, "Oldest", "Newest"),
                Triple(SortCategory.ADDED, "Oldest first", "Newest first"),
                Triple(SortCategory.BOOK_COUNT, "Fewest", "Most"),
                Triple(SortCategory.RATING, "Lowest", "Highest"),
                Triple(SortCategory.SERIES, "1 → N", "N → 1"),
            )
        composeRule.setContent {
            MaterialTheme {
                Column {
                    expected.forEach { (category, _, _) ->
                        Text(
                            "${category.catalogLabel()}: " +
                                "${category.catalogDirectionLabel(SortDirection.ASCENDING)} / " +
                                category.catalogDirectionLabel(SortDirection.DESCENDING),
                        )
                    }
                }
            }
        }

        listOf("Title", "Length", "Year published", "Recently added", "Number of books", "Rating", "Series")
            .zip(expected)
            .forEach { (label, row) -> composeRule.onNodeWithText("$label: ${row.second} / ${row.third}").assertIsDisplayed() }
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

    private fun booksCard(
        status: LibraryStatusChoice?,
        categories: List<SortCategory> = listOf(SortCategory.TITLE, SortCategory.AUTHOR),
    ) = composeRule.setContent {
        MaterialTheme {
            LibrarySortCard(
                state = SortState(SortCategory.TITLE, SortDirection.ASCENDING),
                categories = categories,
                count = 248,
                unit = "books",
                ignoreArticles = true,
                showArticleToggle = true,
                onCategorySelected = {},
                onDirectionToggle = {},
                onToggleArticles = {},
                visible = true,
                status = status,
            )
        }
    }

    private fun choice(onSelect: (BookStatusFilter) -> Unit = {}) =
        LibraryStatusChoice(
            selected = BookStatusFilter.ALL,
            counts = BookStatusCounts(all = 248, inProgress = 4, notStarted = 183, finished = 61),
            onSelect = onSelect,
        )

    @Test
    fun `Books - the trigger reads the status and the sort, behind a filter glyph`() {
        booksCard(status = choice())

        composeRule
            .onNodeWithContentDescription("Filter and sort: All, Title, A–Z")
            .assertIsDisplayed()
            .assert(hasClickAction())
        composeRule.onNodeWithText("All · Title, A–Z", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Sort: Title, A–Z").assertDoesNotExist()
    }

    @Test
    fun `the menu opens on Show, with each status's count, above Sort by`() {
        val picked = mutableListOf<BookStatusFilter>()
        booksCard(status = choice { picked += it })
        composeRule.onNodeWithContentDescription("Filter and sort: All, Title, A–Z").performClick()

        val show = composeRule.onNode(isHeading() and hasText("Show")).getUnclippedBoundsInRoot()
        val sortBy = composeRule.onNode(isHeading() and hasText("Sort by")).getUnclippedBoundsInRoot()
        (show.top < sortBy.top) shouldBe true
        listOf("All" to "248", "In progress" to "4", "Not started" to "183", "Finished" to "61").forEach { (label, count) ->
            composeRule
                .onNode(hasText(label) and hasText(count))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        }
        composeRule.onNode(hasText("All") and hasText("248")).assertIsSelected()
        composeRule.onNode(hasText("In progress") and hasText("4")).assertIsNotSelected()
        val inProgress = composeRule.onNode(hasText("In progress") and hasText("4")).getUnclippedBoundsInRoot()
        (inProgress.top < sortBy.top) shouldBe true

        composeRule.onNode(hasText("In progress") and hasText("4")).performClick()
        picked shouldBe listOf(BookStatusFilter.IN_PROGRESS)
        composeRule.onNode(isHeading() and hasText("Show")).assertDoesNotExist()
    }

    @Test
    fun `Series, Authors and Narrators keep the plain sort, with no Show group`() {
        booksCard(status = null)

        composeRule.onNodeWithContentDescription("Sort: Title, A–Z").performClick()
        composeRule.onNode(isHeading() and hasText("Show")).assertDoesNotExist()
        composeRule.onNodeWithText("In progress").assertDoesNotExist()
        composeRule.onNodeWithText("Title, A–Z", useUnmergedTree = true).assertExists()
    }
}
