package com.calypsan.listenup.client.features.genredestination

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.client.presentation.genredestination.FacetIcon
import com.calypsan.listenup.client.presentation.genredestination.GenreDestinationUiState
import com.calypsan.listenup.client.presentation.genredestination.GenreIdentity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The genre page says what it holds: the hero offers only controls that do something (TalkBack must
 * not announce buttons with nothing behind them), and a genre with no books says so.
 */
@RunWith(RobolectricTestRunner::class)
class GenreDestinationPageTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the hero has a back button and no inert search or overflow buttons`() {
        composeRule.setContent {
            MaterialTheme {
                GenreDestinationHero(
                    identity = FANTASY,
                    breadcrumb = emptyList(),
                    stats = FacetStats(bookCount = 3, totalDurationMs = 3_600_000L),
                    onBackClick = {},
                    onGenreClick = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Back").assertExists()
        composeRule.onNodeWithContentDescription("Search").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("More options").assertDoesNotExist()
    }

    @Test
    fun `a genre with no books says so`() {
        composeRule.setContent {
            MaterialTheme {
                GenreDestinationContent(
                    state =
                        GenreDestinationUiState.Ready(
                            identity = FANTASY,
                            breadcrumb = emptyList(),
                            subGenres = emptyList(),
                            hasSubs = false,
                            includeSubGenres = false,
                            stats = FacetStats.EMPTY,
                            books = emptyList(),
                        ),
                    onBackClick = {},
                    onBookClick = {},
                    onGenreClick = {},
                    onToggleIncludeSubGenres = {},
                )
            }
        }

        composeRule.onNodeWithText("No books in this genre.").performScrollTo().assertIsDisplayed()
    }

    private companion object {
        val FANTASY = GenreIdentity(name = "Fantasy", slug = "fantasy", blurb = null, icon = FacetIcon.FANTASY, hue = "#3A5F8F")
    }
}
