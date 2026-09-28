package com.calypsan.listenup.client.features.admin

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.AccessMode
import com.calypsan.listenup.client.domain.model.Library
import com.calypsan.listenup.client.domain.model.LibraryFolderRef
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsUiState
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.client.testing.assertSideBySide
import com.calypsan.listenup.client.testing.assertStacked
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Library settings on a tablet put the scan paths and the scanning controls side by side; on a phone
 * they stack.
 */
@RunWith(RobolectricTestRunner::class)
class LibrarySettingsWideLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet scan paths and scanning sit side by side`() {
        setContent()

        assertSideBySide(composeRule.onNodeWithText("Scan paths"), composeRule.onNodeWithText("Scanning"))
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone scan paths and scanning stack`() {
        setContent()

        assertStacked(composeRule.onNodeWithText("Scan paths"), composeRule.onNodeWithText("Scanning"))
    }

    private fun setContent() {
        composeRule.setContent {
            MaterialTheme {
                LibrarySettingsContent(state = STATE, onRemoveFolder = {}, onAddFolder = {}, onTriggerScan = {})
            }
        }
    }

    private companion object {
        val STATE =
            LibrarySettingsUiState.Ready(
                library =
                    Library(
                        id = "lib1",
                        name = "Audiobooks",
                        folders =
                            listOf(
                                LibraryFolderRef("f1", "/srv/audiobooks"),
                                LibraryFolderRef("f2", "/mnt/nas/more-audiobooks"),
                            ),
                        metadataPrecedence = "embedded",
                        accessMode = AccessMode.entries.first(),
                        createdByUserId = null,
                        createdAt = 0L,
                        revision = 1L,
                    ),
            )
    }
}
