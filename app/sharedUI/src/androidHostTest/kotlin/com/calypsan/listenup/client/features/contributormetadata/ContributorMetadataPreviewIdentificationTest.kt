package com.calypsan.listenup.client.features.contributormetadata

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorContext
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataUiState
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorPreviewLoadState
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.core.ContributorId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Apply updates the biography and the photo — it never renames the contributor. So the preview names
 * WHO was matched (the Audible name plus "Audible · <region>") instead of setting the local name
 * against the Audible one, which would promise a rename that never happens.
 */
@RunWith(RobolectricTestRunner::class)
class ContributorMetadataPreviewIdentificationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone the matched person is identified, not compared by name`() {
        setContent()
        assertIdentifiedNotRenamed()
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the matched person is identified, not compared by name`() {
        setContent()
        assertIdentifiedNotRenamed()
    }

    private fun assertIdentifiedNotRenamed() {
        // No name before-and-after: neither the local name as a "Current" value nor a "Name" row.
        composeRule.onAllNodesWithText(LOCAL_NAME).assertCountEquals(0)
        composeRule.onAllNodesWithText("Name").assertCountEquals(0)

        // The matched name appears once, as identification, with where it came from.
        composeRule.onAllNodesWithText(AUDIBLE_NAME).assertCountEquals(1)
        composeRule.onNodeWithText("Audible · ${MetadataLocale.DEFAULT.displayName}").assertExists()

        // What Apply actually changes is still compared.
        composeRule.onNodeWithText("Biography").assertExists()
        composeRule.onNodeWithText(LOCAL_BIO).assertExists()
        composeRule.onNodeWithText(AUDIBLE_BIO).assertExists()
        composeRule.onNodeWithText("Image").assertExists()
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
        const val LOCAL_NAME = "B. Sanderson"
        const val AUDIBLE_NAME = "Brandon Sanderson"
        const val LOCAL_BIO = "A writer of epic fantasy."
        const val AUDIBLE_BIO = "Grew up in Lincoln, Nebraska."

        val STATE =
            ContributorMetadataUiState.Preview(
                region = MetadataLocale.DEFAULT,
                context =
                    ContributorContext(
                        contributorId = "c1",
                        current =
                            Contributor(
                                id = ContributorId("c1"),
                                name = LOCAL_NAME,
                                description = LOCAL_BIO,
                            ),
                    ),
                query = "Sanderson",
                searchResults = emptyList(),
                match = MetadataContributorHit(asin = "B1", name = AUDIBLE_NAME),
                loadState =
                    ContributorPreviewLoadState.Ready(
                        profile =
                            MetadataContributorProfile(
                                asin = "B1",
                                name = AUDIBLE_NAME,
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
