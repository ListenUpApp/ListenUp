package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the book-detail scan-warning advisory banner appears only when the
 * book carries a scan warning.
 *
 * The banner is the final hop of the `hasScanWarning` thread (server → wire →
 * Room → [com.calypsan.listenup.client.domain.model.BookDetail] →
 * [com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState.Ready]):
 * it surfaces a heads-up to the user when the scanner flagged the book's files.
 *
 * JUnit4 + Robolectric (consistent with [com.calypsan.listenup.client.features.nowplaying.WavySeekBarTest]).
 */
@RunWith(RobolectricTestRunner::class)
class BookDetailScanWarningTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `banner is shown when the book has a scan warning`() {
        composeRule.setContent {
            BookDetailScanWarning(hasScanWarning = true)
        }

        composeRule.waitForIdle()

        composeRule
            .onNodeWithText(SCAN_WARNING_TEXT)
            .assertExists()
    }

    @Test
    fun `banner is absent when the book has no scan warning`() {
        composeRule.setContent {
            BookDetailScanWarning(hasScanWarning = false)
        }

        composeRule.waitForIdle()

        composeRule
            .onNodeWithText(SCAN_WARNING_TEXT)
            .assertDoesNotExist()
    }

    private companion object {
        const val SCAN_WARNING_TEXT =
            "This book had a scanning error — double-check the files aren’t corrupted."
    }
}
