package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.features.bookdetail.components.DetailsSection
import com.calypsan.listenup.client.presentation.bookdetail.AudioFormat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DetailsSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `shows metadata rows when present`() {
        composeRule.setContent {
            DetailsSection(
                publisher = "Tor Books",
                publishYear = 2021,
                language = "en",
                audioFormat = AudioFormat(codec = "AAC", approxBitrateKbps = 125),
                credits = listOf(BookContributor(id = "1", name = "Gre7g Luterman", roles = listOf("author"))),
                onContributorClick = {},
            )
        }
        composeRule.onNodeWithText("Tor Books").assertIsDisplayed()
        composeRule.onNodeWithText("2021").assertIsDisplayed()
        composeRule.onNodeWithText("English").assertIsDisplayed()
        composeRule.onNodeWithText("AAC · ~125 kbps").assertIsDisplayed()
        composeRule.onNodeWithText("Gre7g Luterman").assertIsDisplayed()
    }

    @Test
    fun `omits rows whose data is null`() {
        composeRule.setContent {
            DetailsSection(
                publisher = null,
                publishYear = null,
                language = null,
                audioFormat = null,
                credits = listOf(BookContributor(id = "1", name = "Gre7g Luterman", roles = listOf("author"))),
                onContributorClick = {},
            )
        }
        composeRule.onNodeWithText("Publisher").assertDoesNotExist()
        composeRule.onNodeWithText("Gre7g Luterman").assertIsDisplayed()
    }
}
