package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.presentation.bookdetail.PrimaryAction
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
    ) = composeRule.setContent {
        MaterialTheme {
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
    fun `the size caption sits under the group while not downloaded (N3)`() {
        setGroup(PrimaryAction.Play)
        composeRule.onNodeWithText("Download · 1.2 GB · plays offline once saved").assertExists()
    }

    @Test
    fun `a downloaded book reads Downloaded, drops the caption, and a tap asks to remove it`() {
        var remove = false
        setGroup(
            PrimaryAction.Resume,
            status = BookDownloadStatus.Completed("b1", totalBytes = 1_288_490_189L),
            onRemoveDownload = { remove = true },
        )
        composeRule.onNodeWithText("plays offline once saved", substring = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Downloaded").performClick()
        remove shouldBe true
    }

    @Test
    fun `on a tablet the segments are labelled`() {
        setGroup(PrimaryAction.Resume, labelled = true)
        composeRule.onNodeWithText("Download").assertExists()
        composeRule.onNodeWithText("Add to shelf").assertExists()
    }
}
