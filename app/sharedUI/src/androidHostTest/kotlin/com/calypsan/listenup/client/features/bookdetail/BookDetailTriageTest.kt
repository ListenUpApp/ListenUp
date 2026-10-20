package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailSection
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
 * both layouts is itself the proof that the triage layout leaves them out. On a phone they are lazy
 * items far below the fold, so the spec scrolls the page through to the chapters first: an item never
 * composed could not throw, and its absence would prove nothing.
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
        // Walk the lazy page item by item down to the chapters, past where rating, readers and
        // Hardcover would sit, so each of them would have been composed (and thrown) were it there.
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Chapters"))
        composeRule.onNodeWithText("Chapters").assertIsDisplayed()
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

        composeRule.onNodeWithText("Release to members").performClick()
        composeRule.onNodeWithContentDescription("Edit details").performClick()
        composeRule.onNodeWithContentDescription("Match details").performClick()
        composeRule.onNodeWithContentDescription("Edit chapters").performClick()
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

        composeRule.onNodeWithText("Release to members").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Edit details").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Match details").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Edit chapters").assertIsNotEnabled()
    }

    // One row, short labels (the M3E ButtonGroup rule): Edit, Match and Chapters side by side, none wrapping, each
    // still named in full for TalkBack.
    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone the three fixes share one row, with short labels that never wrap`() {
        setContent()
        val tops =
            listOf("Edit details", "Match details", "Edit chapters").map {
                composeRule.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot.top
            }
        tops.distinct().size shouldBe 1
        listOf("Edit", "Match", "Chapters").forEach { label ->
            val results = mutableListOf<TextLayoutResult>()
            composeRule
                .onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            results.single().lineCount shouldBe 1
        }
    }

    private fun assertTriageOnly() {
        composeRule.onNodeWithText("Held for review").assertExists()
        composeRule.onNodeWithText("Hidden from every member, and it can’t be played until you release it.").assertExists()
        // The top bar's Held pill stands where the ⋮ would.
        composeRule.onNodeWithContentDescription("Held for review, hidden from all members").assertExists()
        // Present: the four triage actions (spec §8, §10).
        composeRule.onNodeWithText("Release to members").assertExists()
        composeRule.onNodeWithContentDescription("Edit details").assertExists()
        composeRule.onNodeWithContentDescription("Match details").assertExists()
        composeRule.onNodeWithContentDescription("Edit chapters").assertExists()
        // Absent: play, download, Add to shelf, and the overflow menu that carries collection and share.
        // Rating, readers and Hardcover are absent too: see the class KDoc (no Koin is started).
        composeRule.onNodeWithText("Play").assertDoesNotExist()
        composeRule.onNodeWithText("Resume").assertDoesNotExist()
        // Download and Add to shelf are icon buttons on a phone: their names are their content descriptions
        // ("Download, 1.2 GB" carries the size, spec N2). Neither is drawn, labelled or not.
        composeRule.onNodeWithContentDescription("Download,", substring = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("More actions").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Add to shelf").assertDoesNotExist()
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
                            canEditMetadata = true,
                            downloadStatus = BookDownloadStatus.NotDownloaded("b1"),
                            // True on purpose: the triage layout must hide Play even when playback exists.
                            isPlaybackAvailable = true,
                            isHeld = true,
                            isReleasingFromInbox = isReleasing,
                            canPlay = false,
                            canDownload = false,
                            // Every section, social ones included: the held layout must drop them itself.
                            sectionOrder =
                                listOf(
                                    BookDetailSection.About,
                                    BookDetailSection.Ratings,
                                    BookDetailSection.Hardcover,
                                    BookDetailSection.Readers,
                                    BookDetailSection.Chapters,
                                    BookDetailSection.Details,
                                ),
                        ),
                    actions =
                        BookDetailActions(
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
                            onRetryConnection = {},
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
                        ),
                )
            }
        }
    }
}
