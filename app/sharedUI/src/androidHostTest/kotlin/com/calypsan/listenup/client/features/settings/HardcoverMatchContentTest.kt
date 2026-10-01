package com.calypsan.listenup.client.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.client.presentation.hardcover.HardcoverCandidateRow
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchedBook
import com.calypsan.listenup.client.presentation.hardcover.HardcoverSearchState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val REAL = HardcoverCandidateRow(427_578L, 9_001L, "Project Hail Mary", listOf("Andy Weir", "Ray Porter"), 2021, 8_107, true, true)
private val SUMMARY = HardcoverCandidateRow(1L, null, "Project Hail Mary (Summary)", emptyList(), 2022, 0, false, false)

private fun ready(
    search: HardcoverSearchState = HardcoverSearchState.Results(listOf(REAL, SUMMARY)),
    current: HardcoverMatchedBook? = null,
    linkingId: Long? = null,
    query: String = "Project Hail Mary",
    suggestions: List<String> = emptyList(),
) = HardcoverMatchUiState.Ready(
    "b1",
    "Project Hail Mary",
    "Andy Weir",
    // A local path keeps BookCoverImage on its synchronous path; its async fallback needs global Koin.
    "/tmp/cover-b1.webp",
    null,
    query,
    search,
    current,
    linkingId,
    false,
    suggestions,
)

/** Find on Hardcover shows what tells the real book from a summary, and links the one tapped. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class HardcoverMatchContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val picked = mutableListOf<Long>()
    private val suggested = mutableListOf<String>()
    private var searches = 0
    private var removes = 0

    private fun render(state: HardcoverMatchUiState) {
        composeRule.setContent {
            MaterialTheme {
                HardcoverMatchContent(
                    state = state,
                    onQueryChange = {},
                    onSearch = { searches++ },
                    onSearchFor = { suggested += it },
                    onPick = { picked += it },
                    onRemoveMatch = { removes++ },
                )
            }
        }
    }

    @Test
    fun `the book's own author comes first under By, everything else under Other results`() {
        render(ready())
        composeRule.onNodeWithText("Matching", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("By Andy Weir").assertIsDisplayed()
        composeRule.onNodeWithText("Other results").assertIsDisplayed()
    }

    @Test
    fun `each result shows its authors, its format, year and ratings`() {
        render(ready())
        composeRule.onNodeWithText("Andy Weir, Ray Porter").assertIsDisplayed()
        composeRule.onNodeWithText("Audiobook").assertIsDisplayed()
        composeRule.onNodeWithText("2021 · 8.1k ratings").assertIsDisplayed()
        composeRule.onNodeWithText("Unknown author").assertIsDisplayed()
        composeRule.onNodeWithText("2022 · No ratings yet").assertIsDisplayed()
    }

    @Test
    fun `tapping a result picks it`() {
        render(ready())
        composeRule.onNodeWithText("Project Hail Mary (Summary)").performClick()
        picked shouldBe listOf(1L)
    }

    @Test
    fun `each result says what tapping it does`() {
        render(ready())
        composeRule.onNodeWithContentDescription("Match to Project Hail Mary (Summary)").assertIsDisplayed()
    }

    @Test
    fun `while a pick links, no other result can be picked`() {
        render(ready(linkingId = 427_578L))
        composeRule.onNodeWithText("Project Hail Mary (Summary)").performClick()
        picked shouldBe emptyList()
    }

    @Test
    fun `nothing by the author says so, and offers the fuller search`() {
        render(
            ready(
                search = HardcoverSearchState.Results(listOf(SUMMARY)),
                query = "Hail Mary",
                suggestions = listOf("Project Hail Mary Andy Weir"),
            ),
        )
        composeRule.onNodeWithText("Lots of results — none by Andy Weir").assertIsDisplayed()
        composeRule.onNodeWithText("Use the full title, or add the author.").assertIsDisplayed()
        composeRule.onNodeWithText("Results").assertIsDisplayed()
        composeRule.onNodeWithText("Project Hail Mary Andy Weir").performClick()
        suggested shouldBe listOf("Project Hail Mary Andy Weir")
    }

    @Test
    fun `nothing found says so and offers shorter searches`() {
        render(
            ready(
                search = HardcoverSearchState.NoResults,
                query = "Project Hail Mary (Unabridged)",
                suggestions = listOf("Project Hail Mary", "Andy Weir"),
            ),
        )
        composeRule.onNodeWithText("No matches on Hardcover").assertIsDisplayed()
        composeRule
            .onNodeWithText("Nothing found for “Project Hail Mary (Unabridged)”. Fewer words usually help — try one of these.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Andy Weir").performClick()
        suggested shouldBe listOf("Andy Weir")
    }

    @Test
    fun `searching says so`() {
        render(ready(search = HardcoverSearchState.Searching))
        composeRule.onNodeWithContentDescription("Searching Hardcover…").assertExists()
    }

    @Test
    fun `a failed search says so and offers to search again`() {
        render(ready(search = HardcoverSearchState.Failed(HardcoverError.Unavailable())))
        composeRule.onNodeWithText("Hardcover couldn't be searched just now.").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        searches shouldBe 1
    }

    @Test
    fun `a current match shows above the search, with Remove match`() {
        render(ready(current = HardcoverMatchedBook(427_578L, "Project Hail Mary", listOf("Andy Weir"), 2021, false)))
        composeRule.onNodeWithText("Matched now").assertIsDisplayed()
        composeRule.onNodeWithText("Remove match").performScrollTo().performClick()
        removes shouldBe 1
    }

    @Test
    fun `a book gone from the library says so`() {
        render(HardcoverMatchUiState.BookMissing)
        composeRule.onNodeWithText("This book is no longer in your library.").assertIsDisplayed()
    }
}
