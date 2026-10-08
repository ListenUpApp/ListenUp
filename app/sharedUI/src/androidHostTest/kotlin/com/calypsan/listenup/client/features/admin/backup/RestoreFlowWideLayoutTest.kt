package com.calypsan.listenup.client.features.admin.backup

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.presentation.admin.RestoreBackupUiState
import com.calypsan.listenup.client.presentation.admin.RestoreFromFileUiState
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
 * The restore flow on a tablet keeps its steps in a panel beside whichever step is being worked —
 * choosing the file, then reviewing the backup — so the admin sees where a destructive operation is
 * heading. On a phone each step is its single column, with no panel.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreFlowWideLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet choosing a file sits beside the restore steps`() {
        setFromFileContent()

        assertSideBySide(composeRule.onNodeWithText("Choose a backup"), composeRule.onNodeWithText(FROM_FILE_DESCRIPTION))
        assertRightOf(composeRule.onNodeWithText("Choose backup file"), composeRule.onNodeWithText("Choose a backup"))
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone choosing a file is one column without steps`() {
        setFromFileContent()

        composeRule.onNodeWithText("Choose a backup").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the review sits beside the restore steps`() {
        setReviewContent()

        assertSideBySide(composeRule.onNodeWithText("Choose a backup"), composeRule.onNodeWithText("Destructive action"))
        composeRule.onNodeWithText("Review").assertIsDisplayed()
        assertRightOf(composeRule.onNodeWithText("Restore this backup"), composeRule.onNodeWithText("Review"))
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone the review is one column without steps`() {
        setReviewContent()

        assertStacked(
            composeRule.onNodeWithText("Restoring replaces all current server data", substring = true),
            composeRule.onNodeWithText("backup-2026-09-01"),
        )
        composeRule.onNodeWithText("Review").assertDoesNotExist()
    }

    private fun setFromFileContent() {
        composeRule.setContent {
            MaterialTheme {
                RestoreFromFileContent(state = RestoreFromFileUiState.Idle, onChooseFile = {}, onTryAgain = {})
            }
        }
    }

    private fun setReviewContent() {
        composeRule.setContent {
            MaterialTheme {
                RestoreBackupContent(
                    backupId = "backup-2026-09-01",
                    state = RestoreBackupUiState.Idle(),
                    progress = null,
                    onRestoreClick = {},
                    onConfirmRestore = {},
                    onCancelRestore = {},
                    onDone = {},
                )
            }
        }
    }

    private companion object {
        const val FROM_FILE_DESCRIPTION = "Upload a .listenup.zip backup file from your device."
    }
}
