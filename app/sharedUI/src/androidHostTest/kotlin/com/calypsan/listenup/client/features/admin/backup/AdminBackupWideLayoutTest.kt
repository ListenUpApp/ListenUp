package com.calypsan.listenup.client.features.admin.backup

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.api.dto.imports.ImportStatus
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.client.domain.model.BackupInfo
import com.calypsan.listenup.client.presentation.admin.AdminBackupUiState
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.client.testing.assertSideBySide
import com.calypsan.listenup.client.testing.assertStacked
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.core.Timestamp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Backups on a tablet run ListenUp's own backups and the Audiobookshelf imports as two lanes side by
 * side; on a phone the imports follow the backups down one list.
 */
@RunWith(RobolectricTestRunner::class)
class AdminBackupWideLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet backups and imports are two lanes`() {
        setContent()

        assertSideBySide(composeRule.onNodeWithText("Restore from file"), composeRule.onNodeWithText("Upload new import"))
        assertSideBySide(composeRule.onNodeWithText("backup-2026-09-01"), composeRule.onNodeWithText("42 books"))
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone the imports follow the backups`() {
        setContent()

        assertStacked(composeRule.onNodeWithText("Restore from file"), composeRule.onNodeWithText("Upload new import"))
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                AdminBackupReadyContent(
                    state = AdminBackupUiState.Ready(backups = listOf(BACKUP)),
                    absImports = listOf(IMPORT),
                    isLoadingImports = false,
                    onRestoreClick = {},
                    onRestoreFromFileClick = {},
                    onDeleteClick = {},
                    onDownloadClick = {},
                    onABSImportClick = {},
                    onDeleteImportClick = {},
                    onUploadABSBackup = {},
                )
            }
        }
    }

    private companion object {
        val BACKUP = BackupInfo(id = "backup-2026-09-01", size = 12_000_000, createdAt = Timestamp(1_788_000_000_000))
        val IMPORT =
            ImportSummary(
                id = ImportId("imp1"),
                createdAt = 1_788_100_000_000,
                status = ImportStatus.ANALYZED,
                bookCount = 42,
                userCount = 3,
            )
    }
}
