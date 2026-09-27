package com.calypsan.listenup.client.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.DownloadedBookSummary
import com.calypsan.listenup.client.presentation.storage.StorageUiState
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.client.testing.assertSideBySide
import com.calypsan.listenup.client.testing.assertStacked
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Storage on a tablet keeps the usage summary in a side panel and flows the downloaded books into
 * columns beside it; on a phone it is the summary card over a single list.
 */
@RunWith(RobolectricTestRunner::class)
class StorageWideLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the summary sits beside the books, which flow into columns`() {
        setContent()

        assertSideBySide(composeRule.onNodeWithText("2.0 GB"), composeRule.onNodeWithText("Downloaded Books"))
        assertSideBySide(composeRule.onNodeWithText("The Way of Kings"), composeRule.onNodeWithText("Words of Radiance"))
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone the books stay one list`() {
        setContent()

        assertStacked(composeRule.onNodeWithText("The Way of Kings"), composeRule.onNodeWithText("Words of Radiance"))
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                StorageContent(state = STATE, onDeleteBook = {})
            }
        }
    }

    private companion object {
        val STATE =
            StorageUiState(
                isLoading = false,
                totalStorageUsed = 2L * 1024 * 1024 * 1024,
                availableStorage = 8L * 1024 * 1024 * 1024,
                downloadedBooks =
                    listOf(
                        DownloadedBookSummary("b1", "The Way of Kings", "Brandon Sanderson", 1_000_000_000, 40),
                        DownloadedBookSummary("b2", "Words of Radiance", "Brandon Sanderson", 1_100_000_000, 48),
                        DownloadedBookSummary("b3", "Oathbringer", "Brandon Sanderson", 1_200_000_000, 55),
                    ),
            )
    }
}
