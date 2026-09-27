package com.calypsan.listenup.client.features.contributormetadata

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorContext
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataUiState
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorPreviewLoadState
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.client.testing.assertRightOf
import com.calypsan.listenup.client.testing.assertSideBySide
import com.calypsan.listenup.client.testing.assertStacked
import com.calypsan.listenup.core.ContributorId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Previewing a contributor match on a tablet keeps who they are — the photo, the name and the actions
 * — in a side panel, and reads the two biographies side by side beside it; on a phone every
 * comparison stacks in one column above the action bar.
 */
@RunWith(RobolectricTestRunner::class)
class ContributorMetadataPreviewWideLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the identity panel sits beside the biographies, read side by side`() {
        setContent()

        assertSideBySide(composeRule.onNodeWithText("Image"), composeRule.onNodeWithText("Biography"))
        assertSideBySide(composeRule.onNodeWithText(LOCAL_BIO), composeRule.onNodeWithText(AUDIBLE_BIO))
        assertRightOf(composeRule.onNodeWithText("Biography"), composeRule.onNodeWithText("Apply"))
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone the comparisons stack in one column`() {
        setContent()

        assertStacked(composeRule.onNodeWithText("Image"), composeRule.onNodeWithText("Biography"))
        assertStacked(composeRule.onNodeWithText(LOCAL_BIO), composeRule.onNodeWithText(AUDIBLE_BIO))
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                ContributorMetadataPreviewScreen(
                    state = STATE,
                    onRegionSelected = {},
                    onApply = {},
                    onChangeMatch = {},
                    onBack = {},
                )
            }
        }
    }

    private companion object {
        const val LOCAL_BIO = "A writer of epic fantasy."
        const val AUDIBLE_BIO = "Brandon Sanderson grew up in Lincoln, Nebraska."

        val STATE =
            ContributorMetadataUiState.Preview(
                region = MetadataLocale.DEFAULT,
                context =
                    ContributorContext(
                        contributorId = "c1",
                        current =
                            Contributor(
                                id = ContributorId("c1"),
                                name = "B. Sanderson",
                                description = LOCAL_BIO,
                            ),
                    ),
                query = "Sanderson",
                searchResults = emptyList(),
                match = MetadataContributorHit(asin = "B1", name = "Brandon Sanderson"),
                loadState =
                    ContributorPreviewLoadState.Ready(
                        profile =
                            MetadataContributorProfile(
                                asin = "B1",
                                name = "Brandon Sanderson",
                                sortName = null,
                                description = AUDIBLE_BIO,
                                imageUrl = null,
                                birthDate = null,
                                deathDate = null,
                                website = null,
                            ),
                        isApplying = false,
                        applyError = null,
                    ),
            )
    }
}
