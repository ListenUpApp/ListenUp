package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.presentation.bookdetail.PrimaryAction
import com.calypsan.listenup.client.testing.assertNotClipped
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The tablet hero's labelled action group at its real width (about 640 dp, the board's max-width). The board gives
 * Download and Add to shelf their own width (`flex: 0 0 auto`) and lets the row wrap, so a long label like "Delete
 * download" is never cut to "Delete"; Resume still holds at least half of its row (spec §3.2.1).
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp")
class ListenActionGroupTabletLabelsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setGroup(status: BookDownloadStatus) =
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(640.dp)) {
                    ListenActionGroup(
                        primaryAction = PrimaryAction.Resume,
                        isPreparing = false,
                        downloadStatus = status,
                        isWaitingForWifi = false,
                        downloadSizeBytes = 1_288_490_189L,
                        supportsDownloads = true,
                        canDownload = true,
                        labelled = true,
                        onPrimaryClick = {},
                        onDownload = {},
                        onCancelDownload = {},
                        onRemoveDownload = {},
                        onAddToShelf = {},
                    )
                }
            }
        }

    private fun assertEveryLabelWhole(vararg labels: String) {
        labels.forEach { composeRule.onNodeWithText(it, useUnmergedTree = true).assertNotClipped() }
        val resume = composeRule.onNode(hasText("Resume") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val row = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        (resume.width / row.width >= 0.5f) shouldBe true
    }

    @Test
    fun `downloaded, Delete download and Add to shelf read in full`() {
        setGroup(BookDownloadStatus.Completed("b1", totalBytes = 1_288_490_189L))
        assertEveryLabelWhole("Resume", "Delete download", "Add to shelf")
    }

    @Test
    fun `not downloaded, Download and Add to shelf read in full`() {
        setGroup(BookDownloadStatus.NotDownloaded("b1"))
        assertEveryLabelWhole("Resume", "Download", "Add to shelf")
    }
}
