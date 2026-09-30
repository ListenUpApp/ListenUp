package com.calypsan.listenup.client.features.contributormetadata

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorContext
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataUiState
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorPreviewLoadState
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.core.ContributorId
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Ready preview, as web has it: the region can be switched without leaving the preview (Audible
 * localises contributor profiles, so another region may hold a better biography), and an identical
 * biography says "No change" instead of leaving the reader to diff two paragraphs by eye.
 */
@RunWith(RobolectricTestRunner::class)
class ContributorMetadataPreviewReadyTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone the Ready preview switches region`() {
        assertSwitchesRegion()
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the Ready preview switches region`() {
        assertSwitchesRegion()
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone an identical biography says No change`() {
        setContent(localBio = SAME_BIO, audibleBio = SAME_BIO)
        composeRule.onNodeWithText("No change").assertExists()
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet an identical biography says No change`() {
        setContent(localBio = SAME_BIO, audibleBio = SAME_BIO)
        composeRule.onNodeWithText("No change").assertExists()
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `a different biography does not claim No change`() {
        setContent(localBio = "A writer of epic fantasy.", audibleBio = SAME_BIO)
        composeRule.onAllNodesWithText("No change").assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `two empty biographies do not claim No change`() {
        setContent(localBio = null, audibleBio = null)
        composeRule.onAllNodesWithText("No change").assertCountEquals(0)
    }

    private fun assertSwitchesRegion() {
        val picked = mutableListOf<MetadataLocale>()
        setContent(localBio = "A writer.", audibleBio = SAME_BIO, onRegionSelected = { picked += it })

        composeRule.onNodeWithText("United Kingdom").performScrollTo().performClick()

        picked shouldContainExactly listOf(MetadataLocale("uk"))
    }

    private fun setContent(
        localBio: String?,
        audibleBio: String?,
        onRegionSelected: (MetadataLocale) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                ContributorMetadataPreviewScreen(
                    state = state(localBio, audibleBio),
                    onRegionSelected = onRegionSelected,
                    onApply = {},
                    onChangeMatch = {},
                    onBack = {},
                )
            }
        }
    }

    private companion object {
        const val SAME_BIO = "Brandon Sanderson grew up in Lincoln, Nebraska."

        fun state(
            localBio: String?,
            audibleBio: String?,
        ) = ContributorMetadataUiState.Preview(
            region = MetadataLocale.DEFAULT,
            context =
                ContributorContext(
                    contributorId = "c1",
                    current = Contributor(id = ContributorId("c1"), name = "B. Sanderson", description = localBio),
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
                            description = audibleBio,
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
