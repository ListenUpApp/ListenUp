package com.calypsan.listenup.client.features.contributormetadata

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.design.ReadingMaxWidth
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorContext
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataUiState
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorSearchLoadState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A representative reading-width screen on a tablet: the search column stops at [ReadingMaxWidth]
 * instead of stretching a phone form across 1280dp.
 */
@Config(qualifiers = "w1280dp-h800dp")
@RunWith(RobolectricTestRunner::class)
class ContributorMetadataSearchWidthTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `on a tablet the search field sits inside the reading-width column`() {
        composeRule.setContent {
            MaterialTheme {
                ContributorMetadataSearchScreen(
                    state =
                        ContributorMetadataUiState.Search(
                            region = MetadataLocale.DEFAULT,
                            context = ContributorContext(contributorId = "c1", current = null),
                            query = "",
                            loadState = ContributorSearchLoadState.Idle,
                        ),
                    onQueryChange = {},
                    onSearch = {},
                    onRegionSelected = {},
                    onResultClick = {},
                    onBack = {},
                )
            }
        }

        // The column's 16dp side padding sits inside the cap.
        composeRule
            .onNode(hasSetTextAction())
            .assertWidthIsEqualTo(ReadingMaxWidth - 32.dp)
    }
}
