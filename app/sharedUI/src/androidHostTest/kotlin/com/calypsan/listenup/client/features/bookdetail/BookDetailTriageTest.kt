package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A held book's detail page is triage-only (spec §8, §10): the held section with Release, Edit,
 * Match and Edit chapters, and no Play, no Download, no overflow menu (shelf, collection, share),
 * no rating, readers or Hardcover.
 *
 * No Koin is started here on purpose. The rating, readers and Hardcover sections each resolve a
 * ViewModel through `koinViewModel()`; rendering any of them would throw, so this spec passing on
 * both layouts is itself the proof that the triage layout leaves them out.
 */
@RunWith(RobolectricTestRunner::class)
class BookDetailTriageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var releases = 0
    private var edits = 0
    private var matches = 0
    private var chapterEdits = 0

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone, a held book offers Release, Edit, Match and Edit chapters, and nothing else`() {
        setContent()
        assertTriageOnly()
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet, a held book offers Release, Edit, Match and Edit chapters, and nothing else`() {
        setContent()
        assertTriageOnly()
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `the four triage actions report their taps`() {
        setContent()

        composeRule.onNodeWithText("Release").performClick()
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithText("Match metadata").performClick()
        composeRule.onNodeWithText("Edit chapters").performClick()
        composeRule.runOnIdle {
            releases shouldBe 1
            edits shouldBe 1
            matches shouldBe 1
            chapterEdits shouldBe 1
        }
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `while a release is in flight, Release is busy and the rest wait`() {
        setContent(isReleasing = true)

        composeRule.onNodeWithText("Release").assertDoesNotExist()
        composeRule.onNodeWithText("Edit").assertIsNotEnabled()
        composeRule.onNodeWithText("Match metadata").assertIsNotEnabled()
        composeRule.onNodeWithText("Edit chapters").assertIsNotEnabled()
    }

    private fun assertTriageOnly() {
        composeRule.onNodeWithText("Held for review").assertExists()
        composeRule.onNodeWithText("Hidden from all members.").assertExists()
        composeRule.onNodeWithText("It can’t be played until you release it.").assertExists()
        // Present: the four triage actions (spec §8, §10).
        composeRule.onNodeWithText("Release").assertExists()
        composeRule.onNodeWithText("Edit").assertExists()
        composeRule.onNodeWithText("Match metadata").assertExists()
        composeRule.onNodeWithText("Edit chapters").assertExists()
        // Absent: play, download, and the overflow menu that carries shelf, collection and share.
        // Rating, readers and Hardcover are absent too: see the class KDoc (no Koin is started).
        composeRule.onNodeWithText("Play").assertDoesNotExist()
        composeRule.onNodeWithText("Download").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("More options").assertDoesNotExist()
        composeRule.onNodeWithText("Add to shelf").assertDoesNotExist()
        composeRule.onNodeWithText("Add to collection").assertDoesNotExist()
        composeRule.onNodeWithText("Share").assertDoesNotExist()
    }

    private fun setContent(isReleasing: Boolean = false) {
        composeRule.setContent {
            MaterialTheme {
                BookDetailContent(
                    bookId = "b1",
                    state =
                        BookDetailUiState.Ready(
                            book =
                                BookDetail(
                                    id = BookId("b1"),
                                    libraryId = LibraryId("lib"),
                                    folderId = FolderId("folder"),
                                    title = "The Ministry of Time",
                                    authors = emptyList(),
                                    narrators = emptyList(),
                                    duration = 42_720_000L,
                                    // A local path keeps the cover on its synchronous path, clear of Koin.
                                    coverPath = "/tmp/cover-b1.webp",
                                    addedAt = Timestamp(0L),
                                    updatedAt = Timestamp(0L),
                                ),
                            isAdmin = true,
                            isHeld = true,
                            isReleasingFromInbox = isReleasing,
                            canPlay = false,
                            canDownload = false,
                        ),
                    downloadStatus = BookDownloadStatus.NotDownloaded("b1"),
                    isComplete = false,
                    hasProgress = false,
                    isAdmin = true,
                    isWaitingForWifi = false,
                    // True on purpose: the triage layout must hide Play even when playback exists.
                    showPlaybackActions = true,
                    onBackClick = {},
                    onEditClick = { edits++ },
                    onFindMetadataClick = { matches++ },
                    onEditChaptersClick = { chapterEdits++ },
                    onMarkCompleteClick = {},
                    onMarkNotStartedClick = {},
                    onRestartClick = {},
                    onAddToShelfClick = {},
                    onAddToCollectionClick = {},
                    onShareClick = {},
                    onDeleteBookClick = {},
                    onPlayClick = {},
                    canPlay = false,
                    canDownload = false,
                    showServerWarning = false,
                    onRetryConnection = {},
                    onPlayDisabledClick = {},
                    onDownloadClick = {},
                    onCancelClick = {},
                    onDeleteClick = {},
                    onSeriesClick = {},
                    onContributorClick = {},
                    onGenreClick = {},
                    onTagClick = { _, _ -> },
                    onMoodClick = { _, _ -> },
                    onUserProfileClick = {},
                    onReleaseFromInboxClick = { releases++ },
                )
            }
        }
    }
}
