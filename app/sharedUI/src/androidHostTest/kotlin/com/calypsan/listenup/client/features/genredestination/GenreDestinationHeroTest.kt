package com.calypsan.listenup.client.features.genredestination

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.client.presentation.genredestination.FacetIcon
import com.calypsan.listenup.client.presentation.genredestination.GenreIdentity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The genre hero offers only controls that do something: TalkBack must not announce buttons with
 * nothing behind them.
 */
@RunWith(RobolectricTestRunner::class)
class GenreDestinationHeroTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the hero has a back button and no inert search or overflow buttons`() {
        composeRule.setContent {
            MaterialTheme {
                GenreDestinationHero(
                    identity =
                        GenreIdentity(name = "Fantasy", slug = "fantasy", blurb = null, icon = FacetIcon.FANTASY, hue = "#3A5F8F"),
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
}
