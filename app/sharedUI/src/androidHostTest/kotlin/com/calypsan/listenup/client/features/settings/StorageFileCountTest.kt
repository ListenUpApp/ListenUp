package com.calypsan.listenup.client.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.DownloadedBookSummary
import com.calypsan.listenup.client.presentation.storage.StorageUiState
import com.calypsan.listenup.client.testing.Windows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A downloaded book's size line counts its files in words that agree with the number. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Windows.PHONE)
class StorageFileCountTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a single-file book says one file`() {
        setContent(fileCount = 1)

        composeRule.onNodeWithText("1 file", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("1 files", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a many-file book says files`() {
        setContent(fileCount = 12)

        composeRule.onNodeWithText("12 files", substring = true).assertIsDisplayed()
    }

    private fun setContent(fileCount: Int) {
        composeRule.setContent {
            MaterialTheme {
                StorageContent(
                    state =
                        StorageUiState(
                            isLoading = false,
                            totalStorageUsed = 18_000_000L,
                            availableStorage = 8_000_000_000L,
                            downloadedBooks =
                                listOf(
                                    DownloadedBookSummary(
                                        bookId = "b1",
                                        title = "The Art of War",
                                        authorNames = "Sun Tzu",
                                        sizeBytes = 18_000_000L,
                                        fileCount = fileCount,
                                        // A local path takes the cover's synchronous path; a null one resolves through Koin, absent here.
                                        coverPath = "/tmp/cover-b1.webp",
                                    ),
                                ),
                        ),
                    onDeleteBook = {},
                )
            }
        }
    }
}
