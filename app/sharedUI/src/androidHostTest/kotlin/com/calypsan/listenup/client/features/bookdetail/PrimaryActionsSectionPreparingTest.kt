package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.features.bookdetail.components.PrimaryActionsSection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Accessibility regression: before this test existed, [PrimaryActionsSection]'s play button kept
 * announcing "Play" for the whole play-request-in-flight window — a screen-reader user got the
 * same silence a sighted user got before the busy spinner was added (see the play-pending arc).
 * The button's Material3 `Button` merges descendant semantics, so changing the visible label is
 * also what changes the announced accessible name — this test locks that in.
 *
 * JUnit4 + Robolectric (consistent with [BookDetailScanWarningTest], [DetailsSectionTest]).
 */
@RunWith(RobolectricTestRunner::class)
class PrimaryActionsSectionPreparingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `play button reads Play when no prepare is in flight`() {
        composeRule.setContent {
            PrimaryActionsSection(
                downloadStatus = BookDownloadStatus.NotDownloaded(""),
                onPlayClick = {},
                onDownloadClick = {},
                onCancelClick = {},
                onDeleteClick = {},
                isPreparing = false,
            )
        }

        composeRule.onNodeWithText("Play").assertExists()
        composeRule.onNodeWithText("Preparing…").assertDoesNotExist()
    }

    @Test
    fun `play button announces Preparing while a play request is in flight`() {
        composeRule.setContent {
            PrimaryActionsSection(
                downloadStatus = BookDownloadStatus.NotDownloaded(""),
                onPlayClick = {},
                onDownloadClick = {},
                onCancelClick = {},
                onDeleteClick = {},
                isPreparing = true,
            )
        }

        composeRule.onNodeWithText("Preparing…").assertExists()
        composeRule.onNodeWithText("Play").assertDoesNotExist()
    }
}
