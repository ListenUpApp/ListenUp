package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
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
 * The tablet hero's labelled action group at its real width (about 640 dp, the board's max-width): one M3E button
 * group on one row, every visible label whole, Resume at least half the row (spec §3.2.1). A downloaded book's side
 * button reads "Remove" beside its icon (Simon, 2026-10-19); TalkBack still hears "Delete download".
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp")
class ListenActionGroupTabletLabelsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setGroup(
        status: BookDownloadStatus,
        width: Dp = 640.dp,
    ) = composeRule.setContent {
        MaterialTheme {
            Box(Modifier.width(width)) {
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

    private fun assertOneRowEveryLabelWhole(
        vararg labels: String,
        resumeHoldsHalf: Boolean = true,
    ) {
        labels.forEach { composeRule.onNodeWithText(it, useUnmergedTree = true).assertNotClipped() }
        val buttons = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { it.boundsInRoot }
        buttons.size shouldBe 3
        // One row: the three buttons share a top.
        buttons.map { it.top }.distinct().size shouldBe 1
        val resume = composeRule.onNode(hasText("Resume") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val row = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        if (resumeHoldsHalf) (resume.width / row.width >= 0.5f) shouldBe true
    }

    @Test
    fun `downloaded, the side button reads Remove on one row and is still named Delete download`() {
        setGroup(BookDownloadStatus.Completed("b1", totalBytes = 1_288_490_189L))
        assertOneRowEveryLabelWhole("Resume", "Remove", "Add to shelf")
        composeRule.onNode(hasContentDescription("Delete download") and hasClickAction()).assertExists()
    }

    @Test
    fun `not downloaded, Download and Add to shelf read in full on one row`() {
        setGroup(BookDownloadStatus.NotDownloaded("b1"))
        assertOneRowEveryLabelWhole("Resume", "Download", "Add to shelf")
    }

    // A narrower hero than the rig's: the side buttons still take the width their labels need, never a share that
    // cuts them (the old 2.4 : 1 : 1 weights cut "Add to shelf" to "Add to" on a real tablet). Resume gives way.
    @Test
    fun `in a narrower hero the side labels still read in full on one row`() {
        setGroup(BookDownloadStatus.Completed("b1", totalBytes = 1_288_490_189L), width = 560.dp)
        assertOneRowEveryLabelWhole("Resume", "Remove", "Add to shelf", resumeHoldsHalf = false)
    }
}
