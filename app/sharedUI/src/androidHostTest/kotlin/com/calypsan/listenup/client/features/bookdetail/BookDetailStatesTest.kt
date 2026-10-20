package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import com.calypsan.listenup.client.features.bookdetail.components.BookDetailErrorState
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersViewModel
import com.calypsan.listenup.client.presentation.bookdetail.PrimaryAction
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinApplication
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Book Detail's states (spec §3.2.3): the error with Retry and Back to Library, the admin-only scan warning with
 * Review, and the offline reason between the progress unit and the disabled primary.
 */
@RunWith(RobolectricTestRunner::class)
class BookDetailStatesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var retries = 0
    private var backs = 0
    private var reviews = 0

    /** The composable KoinApplication registers a global Koin; a later spec starting its own would fail. */
    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    @Test
    fun `an error says what happened and offers Retry and Back to Library`() {
        composeRule.setContent {
            MaterialTheme {
                BookDetailErrorState(error = BookError.NotFound(), onRetry = { retries++ }, onBack = { backs++ })
            }
        }
        composeRule.onNodeWithText("Couldn’t load this book").assertExists()
        composeRule.onNodeWithText(BookError.NotFound().message).assertExists()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.onNodeWithText("Back to Library").performClick()
        composeRule.runOnIdle {
            retries shouldBe 1
            backs shouldBe 1
        }
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `an admin sees the scan warning with Review, which opens Files and audio`() {
        setContent(ready(isAdmin = true, hasScanWarning = true))
        composeRule.onNodeWithText("The scanner flagged this book’s files.").assertExists()
        composeRule.onNodeWithText("Review").performClick()
        composeRule.runOnIdle { reviews shouldBe 1 }
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `a member never sees the scan warning`() {
        setContent(ready(isAdmin = false, hasScanWarning = true))
        composeRule.onNodeWithText("The scanner flagged this book’s files.").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `offline and not downloaded, the reason sits above the disabled primary, with Retry`() {
        setContent(ready(showServerWarning = true, canPlay = false))
        composeRule
            .onNodeWithText("Can’t reach your server. This book isn’t downloaded, so it can’t play right now.")
            .assertExists()
        composeRule.onNodeWithText("Unavailable offline").assertIsNotEnabled()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.runOnIdle { retries shouldBe 1 }
        // Above the primary, not at the top of the page.
        val reason = composeRule.onNodeWithText("Can’t reach", substring = true).fetchSemanticsNode().boundsInRoot
        val primary = composeRule.onNodeWithText("Unavailable offline").fetchSemanticsNode().boundsInRoot
        (reason.bottom <= primary.top) shouldBe true
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `the tablet hero carries the same reason`() {
        setContent(ready(showServerWarning = true, canPlay = false))
        composeRule.onNodeWithText("Can’t reach", substring = true).assertExists()
    }

    private fun ready(
        isAdmin: Boolean = false,
        hasScanWarning: Boolean = false,
        showServerWarning: Boolean = false,
        canPlay: Boolean = true,
    ) = BookDetailUiState.Ready(
        book =
            BookDetail(
                id = BookId(BOOK_ID),
                libraryId = LibraryId("lib"),
                folderId = FolderId("folder"),
                title = "The Way of Kings",
                authors = emptyList(),
                narrators = emptyList(),
                duration = 1_200_000L,
                // A local path keeps the cover on its synchronous path, clear of global Koin.
                coverPath = "/tmp/cover-$BOOK_ID.webp",
                addedAt = Timestamp(0L),
                updatedAt = Timestamp(0L),
            ),
        isAdmin = isAdmin,
        hasScanWarning = hasScanWarning,
        // As toReady derives it: members are never shown the scanner's flag.
        showScanWarning = isAdmin && hasScanWarning,
        showServerWarning = showServerWarning,
        canPlay = canPlay,
        primaryAction = if (showServerWarning && !canPlay) PrimaryAction.UnavailableOffline else PrimaryAction.Play,
        downloadStatus = BookDownloadStatus.NotDownloaded(BOOK_ID),
    )

    private fun setContent(state: BookDetailUiState.Ready) {
        val readers =
            mock<BookReadersRepository>(MockMode.autoUnit) {
                every { observeReadersFor(any()) } returns flowOf(BookReaders(readers = emptyList()))
            }
        composeRule.setContent {
            KoinApplication(
                configuration =
                    koinConfiguration {
                        modules(
                            module {
                                viewModel { (bookId: String) -> BookReadersViewModel(readers, bookId, FakeLoadedBooks()) }
                            },
                        )
                    },
            ) {
                MaterialTheme {
                    CompositionLocalProvider(LocalSnackbarHostState provides SnackbarHostState()) {
                        Page(state)
                    }
                }
            }
        }
    }

    @Composable
    private fun Page(state: BookDetailUiState.Ready) {
        BookDetailContent(
            bookId = BOOK_ID,
            state = state,
            actions =
                BookDetailActions(
                    onBackClick = {},
                    onEditClick = {},
                    onFindMetadataClick = {},
                    onEditChaptersClick = {},
                    onMarkCompleteClick = {},
                    onMarkNotStartedClick = {},
                    onRestartClick = {},
                    onAddToShelfClick = {},
                    onAddToCollectionClick = {},
                    onShareClick = {},
                    onDeleteBookClick = {},
                    onPlayClick = {},
                    onRetryConnection = { retries++ },
                    onDownloadClick = {},
                    onCancelClick = {},
                    onDeleteClick = {},
                    onSeriesClick = {},
                    onContributorClick = {},
                    onGenreClick = {},
                    onTagClick = { _, _ -> },
                    onMoodClick = { _, _ -> },
                    onUserProfileClick = {},
                    onOpenFilesAndAudio = { reviews++ },
                ),
        )
    }

    private companion object {
        const val BOOK_ID = "b1"
    }
}
