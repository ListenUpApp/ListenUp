package com.calypsan.listenup.client.features.admin.upload

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.presentation.admin.upload.UploadBooksUiState
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
 * Uploading on a tablet keeps the upload's steps in a panel beside the working area, so the choice,
 * the transfer and the server's import read as one journey; on a phone each state is its own column.
 */
@RunWith(RobolectricTestRunner::class)
class UploadBooksWideLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the choice sits beside the upload steps`() {
        setContent(UploadBooksUiState.Idle)

        assertSideBySide(composeRule.onNodeWithText("Choose books"), composeRule.onNodeWithText(DESCRIPTION, substring = true))
        assertRightOf(composeRule.onNodeWithText("Choose Folder"), composeRule.onNodeWithText("Choose books"))
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the transfer sits beside the upload steps`() {
        setContent(UPLOADING)

        composeRule.onNodeWithText("Import").assertIsDisplayed()
        assertRightOf(composeRule.onNodeWithText("Cancel Upload"), composeRule.onNodeWithText("Import"))
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone the choice is one column without steps`() {
        setContent(UploadBooksUiState.Idle)

        assertStacked(composeRule.onNodeWithText("Choose Folder"), composeRule.onNodeWithText("Choose Files"))
        composeRule.onNodeWithText("Choose books").assertDoesNotExist()
    }

    private fun setContent(state: UploadBooksUiState) {
        composeRule.setContent {
            MaterialTheme {
                UploadBooksContent(
                    state = state,
                    onChooseFolder = {},
                    onChooseFiles = {},
                    onCancel = {},
                    onDone = {},
                    onTryAgain = {},
                )
            }
        }
    }

    private companion object {
        const val DESCRIPTION = "Pick a folder and everything inside it comes across"
        val UPLOADING =
            UploadBooksUiState.Uploading(fileIndex = 2, fileCount = 9, filename = "chapter-03.m4b", fraction = 0.3f)
    }
}
