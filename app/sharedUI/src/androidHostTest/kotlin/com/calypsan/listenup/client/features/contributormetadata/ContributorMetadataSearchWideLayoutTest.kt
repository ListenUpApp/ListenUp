package com.calypsan.listenup.client.features.contributormetadata

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorContext
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataUiState
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorSearchLoadState
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.client.testing.assertRightOf
import com.calypsan.listenup.client.testing.assertSideBySide
import com.calypsan.listenup.client.testing.assertStacked
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Searching Audible for a contributor on a tablet keeps the name field and region in a side panel and
 * flows the candidates into a grid beside it; a foldable-width window keeps the controls on top but
 * still flows the candidates into columns; a phone is the one column it always was.
 */
@RunWith(RobolectricTestRunner::class)
class ContributorMetadataSearchWideLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the search controls sit beside a grid of candidates`() {
        setContent()

        assertRightOf(composeRule.onNodeWithText(FIRST), composeRule.onNode(hasSetTextAction()))
        assertSideBySide(composeRule.onNodeWithText(FIRST), composeRule.onNodeWithText(SECOND))
    }

    @Test
    @Config(qualifiers = "w700dp-h800dp")
    fun `on a medium window the candidates still flow into columns`() {
        setContent()

        assertSideBySide(composeRule.onNodeWithText(FIRST), composeRule.onNodeWithText(SECOND))
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone the candidates stay one list`() {
        setContent()

        assertStacked(composeRule.onNodeWithText(FIRST), composeRule.onNodeWithText(SECOND))
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                ContributorMetadataSearchScreen(
                    state = STATE,
                    onQueryChange = {},
                    onSearch = {},
                    onRegionSelected = {},
                    onResultClick = {},
                    onBack = {},
                )
            }
        }
    }

    private companion object {
        const val FIRST = "Brandon Sanderson"
        const val SECOND = "Brandon Mull"

        val STATE =
            ContributorMetadataUiState.Search(
                region = MetadataLocale.DEFAULT,
                context = ContributorContext(contributorId = "c1", current = null),
                query = "Brandon",
                loadState =
                    ContributorSearchLoadState.Loaded(
                        listOf(
                            MetadataContributorHit(asin = "B1", name = FIRST),
                            MetadataContributorHit(asin = "B2", name = SECOND),
                            MetadataContributorHit(asin = "B3", name = "Brandon Q. Morris"),
                        ),
                    ),
            )
    }
}
