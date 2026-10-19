package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.presentation.bookdetail.PrimaryAction
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ListenActionGroupTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setGroup(
        action: PrimaryAction,
        labelled: Boolean = false,
        isPreparing: Boolean = false,
        status: BookDownloadStatus = BookDownloadStatus.NotDownloaded("b1"),
        onPrimary: () -> Unit = {},
        onAddToShelf: () -> Unit = {},
        onRemoveDownload: () -> Unit = {},
        width: Dp? = null,
    ) = composeRule.setContent {
        MaterialTheme {
            Box(if (width != null) Modifier.width(width) else Modifier) {
                ListenActionGroup(
                    primaryAction = action,
                    isPreparing = isPreparing,
                    downloadStatus = status,
                    isWaitingForWifi = false,
                    downloadSizeBytes = 1_288_490_189L,
                    supportsDownloads = true,
                    canDownload = true,
                    labelled = labelled,
                    onPrimaryClick = onPrimary,
                    onDownload = {},
                    onCancelDownload = {},
                    onRemoveDownload = onRemoveDownload,
                    onAddToShelf = onAddToShelf,
                )
            }
        }
    }

    @Test
    fun `in progress reads Resume and is enabled`() {
        var played = false
        setGroup(PrimaryAction.Resume, onPrimary = { played = true })
        composeRule.onNodeWithText("Resume").assertIsEnabled().performClick()
        played shouldBe true
    }

    @Test
    fun `not started reads Play`() {
        setGroup(PrimaryAction.Play)
        composeRule.onNodeWithText("Play").assertIsEnabled()
    }

    @Test
    fun `finished reads Listen again and is enabled`() {
        setGroup(PrimaryAction.ListenAgain)
        composeRule.onNodeWithText("Listen again").assertIsEnabled()
    }

    @Test
    fun `only Unavailable offline is disabled`() {
        setGroup(PrimaryAction.UnavailableOffline)
        composeRule.onNodeWithText("Unavailable offline").assertIsNotEnabled()
    }

    @Test
    fun `preparing acknowledges the tap and stops saying Resume`() {
        setGroup(PrimaryAction.Resume, isPreparing = true)
        composeRule.onNodeWithText("Preparing…").assertExists()
        composeRule.onNodeWithText("Resume").assertDoesNotExist()
    }

    @Test
    fun `on a phone Download and Add to shelf are icon buttons named by the size and the action`() {
        var shelved = false
        setGroup(PrimaryAction.Resume, onAddToShelf = { shelved = true })
        composeRule.onNodeWithContentDescription("Download, 1.2 GB").assertExists()
        composeRule.onNodeWithContentDescription("Add to shelf").performClick()
        shelved shouldBe true
    }

    @Test
    fun `no caption sits under the group, the size lives in the download's name`() {
        setGroup(PrimaryAction.Play)
        composeRule.onNodeWithText("plays offline once saved", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("1.2 GB", substring = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Download, 1.2 GB").assertExists()
    }

    @Test
    fun `a downloaded book shows a trash bin named Delete download, and a tap asks to remove it`() {
        var remove = false
        setGroup(
            PrimaryAction.Resume,
            status = BookDownloadStatus.Completed("b1", totalBytes = 1_288_490_189L),
            onRemoveDownload = { remove = true },
        )
        composeRule.onNodeWithContentDescription("Downloaded").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Delete download").performClick()
        remove shouldBe true
    }

    @Test
    fun `on a tablet a downloaded book's segment reads Remove, is named Delete download, and removes it`() {
        var remove = false
        setGroup(
            PrimaryAction.Resume,
            labelled = true,
            status = BookDownloadStatus.Completed("b1", totalBytes = 1_288_490_189L),
            onRemoveDownload = { remove = true },
        )
        composeRule.onNodeWithText("Remove", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Delete download").performClick()
        remove shouldBe true
    }

    @Test
    fun `on a phone the primary and download are one joined split pair and Add to shelf stands apart`() {
        setGroup(PrimaryAction.Resume)
        val resume = composeRule.onNode(hasText("Resume") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val download = composeRule.onNodeWithContentDescription("Download, 1.2 GB").fetchSemanticsNode().boundsInRoot
        val shelf = composeRule.onNodeWithContentDescription("Add to shelf").fetchSemanticsNode().boundsInRoot
        val dp = composeRule.density.density

        // Joined: the split pair's 2 dp seam, not the 8 dp gap between separate buttons.
        ((download.left - resume.right) / dp <= 3f) shouldBe true
        // Apart: Add to shelf keeps a standard gap from the pair.
        ((shelf.left - download.right) / dp >= 8f) shouldBe true
        // The pair reads left to right: Resume, then download, then Add to shelf.
        (resume.right <= download.left && download.right <= shelf.left) shouldBe true
    }

    @Test
    fun `on a phone both halves of the pair are 56 dp tall`() {
        setGroup(PrimaryAction.Resume)
        val resume = composeRule.onNode(hasText("Resume") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val download = composeRule.onNodeWithContentDescription("Download, 1.2 GB").fetchSemanticsNode().boundsInRoot
        val dp = composeRule.density.density
        resume.height / dp shouldBe (56f plusOrMinus 0.5f)
        download.height / dp shouldBe (56f plusOrMinus 0.5f)
    }

    @Test
    fun `on a tablet the segments are labelled`() {
        setGroup(PrimaryAction.Resume, labelled = true)
        composeRule.onNodeWithText("Download").assertExists()
        composeRule.onNodeWithText("Add to shelf").assertExists()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `on a tablet Resume holds at least half the row (spec 3_2_1)`() {
        // The tablet hero's action row is about 640 dp wide.
        setGroup(PrimaryAction.Resume, labelled = true, width = 640.dp)
        val resume = composeRule.onNode(hasText("Resume") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val row = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        (resume.width / row.width >= 0.5f) shouldBe true
    }
}
