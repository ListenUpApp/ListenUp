package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadFailureReason
import com.calypsan.listenup.client.domain.model.DownloadPauseReason
import com.calypsan.listenup.client.presentation.bookdetail.PrimaryAction
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The line under Book Detail's action row (canvas decision 1): shown while a download is active, held or failed, in
 * the canvas's words, with Cancel whenever the button does something else, and absent at rest.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadStatusLineTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val mib = 1024L * 1024
    private val taps = mutableListOf<String>()

    private fun show(
        status: BookDownloadStatus,
        waitingForWifi: Boolean = false,
    ) = composeRule.setContent {
        MaterialTheme {
            ListenActionGroup(
                primaryAction = PrimaryAction.Resume,
                isPreparing = false,
                downloadStatus = status,
                isWaitingForWifi = waitingForWifi,
                downloadSizeBytes = 640 * mib,
                supportsDownloads = true,
                canDownload = true,
                labelled = false,
                onPrimaryClick = {},
                onDownload = { taps += "download" },
                onPauseDownload = { taps += "pause" },
                onCancelDownload = { taps += "cancel" },
                onRemoveDownload = { taps += "remove" },
                onAddToShelf = {},
            )
        }
    }

    private fun paused(reason: DownloadPauseReason) =
        BookDownloadStatus.Paused(
            "b1",
            pausedFiles = 1,
            completedFiles = 0,
            downloadedBytes = 212 * mib,
            totalBytes = 640 * mib,
            reason = reason,
        )

    @Test
    fun `downloading reads its progress, the ring pauses, and the line cancels`() {
        show(BookDownloadStatus.InProgress("b1", 14, 1, 2, totalBytes = 640 * mib, downloadedBytes = 212 * mib))

        composeRule.onNodeWithText("Downloading · 212 of 640 MB").assertExists()
        composeRule.onNodeWithContentDescription("Pause download, 33%").performClick()
        composeRule.onNode(hasText("Cancel") and hasClickAction()).performClick()
        taps shouldBe listOf("pause", "cancel")
    }

    @Test
    fun `queued says it starts after the current download, and the button cancels`() {
        show(BookDownloadStatus.InProgress("b1", 14, 0, 0, totalBytes = 640 * mib, downloadedBytes = 0))

        composeRule.onNodeWithText("Queued · starts after the current download").assertExists()
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Cancel download, queued").performClick()
        taps shouldBe listOf("cancel")
    }

    @Test
    fun `held by Wi-Fi only says so`() {
        show(BookDownloadStatus.InProgress("b1", 14, 0, 0, totalBytes = 640 * mib, downloadedBytes = 0), waitingForWifi = true)

        composeRule.onNodeWithText("Waiting for Wi-Fi · downloads use Wi-Fi only").assertExists()
        composeRule.onNodeWithContentDescription("Cancel download, waiting for Wi-Fi").assertExists()
    }

    @Test
    fun `a system stop resumes automatically, and the button cancels`() {
        show(paused(DownloadPauseReason.NETWORK_LOST))

        composeRule.onNodeWithText("Waiting · resumes automatically").assertExists()
        composeRule.onNodeWithContentDescription("Cancel download, waiting to resume").assertExists()
    }

    @Test
    fun `paused by you says what is saved, the ring resumes, and the line cancels`() {
        show(paused(DownloadPauseReason.PAUSED_BY_YOU))

        composeRule.onNodeWithText("Paused · 212 of 640 MB saved").assertExists()
        composeRule.onNodeWithContentDescription("Resume download, 33%").performClick()
        taps shouldBe listOf("download")
    }

    @Test
    fun `a failure gives its reason`() {
        show(
            BookDownloadStatus.Failed(
                "b1",
                errorMessage = "Not enough storage space.",
                partiallyDownloadedFiles = 0,
                reason = DownloadFailureReason.NOT_ENOUGH_SPACE,
            ),
        )

        composeRule.onNodeWithText("Couldn’t download · not enough space on this device").assertExists()
        composeRule.onNodeWithContentDescription("Retry download").assertExists()
    }

    @Test
    fun `at rest there is no line`() {
        show(BookDownloadStatus.Completed("b1", totalBytes = 640 * mib))

        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Delete download").assertExists()
    }
}
