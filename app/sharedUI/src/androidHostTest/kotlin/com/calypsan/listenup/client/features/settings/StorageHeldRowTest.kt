package com.calypsan.listenup.client.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.DownloadedBookSummary
import com.calypsan.listenup.client.presentation.storage.StorageUiState
import com.calypsan.listenup.client.testing.Windows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A held book's download stays listed — bytes on disk are never stranded — marked, with the way to play it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Windows.PHONE)
class StorageHeldRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a held download is marked Held and says how to play it`() {
        setContent(isHeld = true)

        composeRule.onNodeWithText("The Ministry of Time").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(HELD_A11Y, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Release it to play").assertIsDisplayed()
    }

    @Test
    fun `an ordinary download carries neither`() {
        setContent(isHeld = false)

        composeRule.onNodeWithContentDescription(HELD_A11Y, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Release it to play").assertDoesNotExist()
    }

    private fun setContent(isHeld: Boolean) {
        composeRule.setContent {
            MaterialTheme {
                StorageContent(
                    state =
                        StorageUiState(
                            isLoading = false,
                            totalStorageUsed = 1_000_000_000L,
                            availableStorage = 8_000_000_000L,
                            downloadedBooks =
                                listOf(
                                    DownloadedBookSummary(
                                        bookId = "b1",
                                        title = "The Ministry of Time",
                                        authorNames = "Kaliane Bradley",
                                        sizeBytes = 400_000_000L,
                                        fileCount = 12,
                                        isHeld = isHeld,
                                    ),
                                ),
                        ),
                    onDeleteBook = {},
                )
            }
        }
    }

    private companion object {
        const val HELD_A11Y = "Held for review, hidden from all members"
    }
}
