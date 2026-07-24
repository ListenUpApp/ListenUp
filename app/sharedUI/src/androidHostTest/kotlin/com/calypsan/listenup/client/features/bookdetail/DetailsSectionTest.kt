package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.AudioFile
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.features.bookdetail.components.DetailsSection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
                audioFiles =
                    listOf(
                        AudioFile(
                            id = "1",
                            index = 0,
                            filename = "01.m4b",
                            format = "m4b",
                            codec = "ac4",
                            duration = 3_600_000L,
                            size = 1_000_000L,
                            spatial = "atmos",
                            bitrate = 320_000,
                            sampleRate = 48_000,
                            channels = 6,
                        ),
                    ),
                credits = listOf(BookContributor(id = "1", name = "Gre7g Luterman", roles = listOf("author"))),
                onContributorClick = {},
            )
        }
        composeRule.onNodeWithText("Tor Books").assertIsDisplayed()
        composeRule.onNodeWithText("2021").assertIsDisplayed()
        composeRule.onNodeWithText("English").assertIsDisplayed()
        composeRule.onNodeWithText("Dolby Atmos").assertIsDisplayed()
        composeRule.onNodeWithText("320 kbps").assertIsDisplayed()
        composeRule.onNodeWithText("48 kHz").assertIsDisplayed()
        // The bottom rows fall below the fixed test viewport; assert they're composed.
        composeRule.onNodeWithText("5.1").assertExists()
        composeRule.onNodeWithText("Gre7g Luterman").assertExists()
    }

    @Test
    fun `omits rows whose data is null`() {
        composeRule.setContent {
            DetailsSection(
                publisher = null,
                publishYear = null,
                language = null,
                audioFiles = emptyList(),
                credits = listOf(BookContributor(id = "1", name = "Gre7g Luterman", roles = listOf("author"))),
                onContributorClick = {},
            )
        }
        composeRule.onNodeWithText("Publisher").assertDoesNotExist()
        composeRule.onNodeWithText("Bitrate").assertDoesNotExist()
        composeRule.onNodeWithText("Sample rate").assertDoesNotExist()
        composeRule.onNodeWithText("Channels").assertDoesNotExist()
        composeRule.onNodeWithText("Gre7g Luterman").assertIsDisplayed()
    }
}
