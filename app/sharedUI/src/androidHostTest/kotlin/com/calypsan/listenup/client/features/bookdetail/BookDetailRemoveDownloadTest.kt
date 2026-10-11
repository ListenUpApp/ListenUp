package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.navigation.PaneSized
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsViewModel
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersViewModel
import com.calypsan.listenup.client.testing.Windows
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.error.ErrorBus
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
 * A saved book's download control is a trash bin, and its tap goes to the remove-download confirm (Simon, 2026-10-11)
 * rather than deleting anything itself: the page reports [BookDetailActions.onDeleteClick], which [BookDetailScreen]
 * turns into the Delete download dialog. Both layouts, the single column and the wide one, wire it the same way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Windows.TABLET)
class BookDetailRemoveDownloadTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The composable KoinApplication registers a global Koin; a later spec starting its own would fail. */
    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    @Test
    fun `in the wide layout the trash asks to remove the download and nothing else`() {
        val taps = Taps()
        setContent { SavedBookDetail(taps) }

        composeRule.onNodeWithContentDescription(DELETE_DOWNLOAD).performClick()

        composeRule.runOnIdle { taps.reported shouldBe listOf(REMOVE_DOWNLOAD) }
    }

    @Test
    fun `in the single column the trash asks to remove the download and nothing else`() {
        val taps = Taps()
        setContent { PaneSized(modifier = Modifier.width(400.dp)) { SavedBookDetail(taps) } }

        composeRule.onNodeWithContentDescription(DELETE_DOWNLOAD).performClick()

        composeRule.runOnIdle { taps.reported shouldBe listOf(REMOVE_DOWNLOAD) }
    }

    private fun setContent(content: @Composable () -> Unit) {
        val readers =
            mock<BookReadersRepository>(MockMode.autoUnit) {
                every { observeReadersFor(any()) } returns flowOf(BookReaders(readers = emptyList()))
            }
        val ratings =
            mock<BookRatingRepository>(MockMode.autoUnit) {
                every { observeForBook(any()) } returns flowOf(emptyList())
                every { observeExternalForBook(any()) } returns flowOf(emptyList())
                every { observeCombinedScore(any()) } returns flowOf(null)
                every { observeExternalCheck(any()) } returns flowOf()
            }
        val users = mock<UserRepository>(MockMode.autoUnit) { every { observeIsAdmin() } returns flowOf(false) }
        composeRule.setContent {
            KoinApplication(
                configuration =
                    koinConfiguration {
                        modules(
                            module {
                                viewModel { (bookId: String) -> BookReadersViewModel(readers, bookId) }
                                viewModel { (bookId: String) ->
                                    BookRatingsViewModel(bookId, ratings, flowOf(null), ErrorBus(), users)
                                }
                            },
                        )
                    },
            ) {
                MaterialTheme {
                    CompositionLocalProvider(LocalSnackbarHostState provides SnackbarHostState()) { content() }
                }
            }
        }
    }

    /** The download-related taps the page reported, plus Delete Book's, which must never be the one a trash fires. */
    private class Taps {
        val reported = mutableListOf<String>()
    }

    @Composable
    private fun SavedBookDetail(taps: Taps) {
        BookDetailContent(
            bookId = BOOK_ID,
            state =
                BookDetailUiState.Ready(
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
                    canDownload = true,
                    downloadStatus = BookDownloadStatus.Completed(BOOK_ID, totalBytes = 2_350_000_000L),
                ),
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
                    onDeleteBookClick = { taps.reported += "deleteBook" },
                    onPlayClick = {},
                    onRetryConnection = {},
                    onDownloadClick = { taps.reported += "startDownload" },
                    onCancelClick = { taps.reported += "cancelDownload" },
                    onDeleteClick = { taps.reported += REMOVE_DOWNLOAD },
                    onSeriesClick = {},
                    onContributorClick = {},
                    onGenreClick = {},
                    onTagClick = { _, _ -> },
                    onMoodClick = { _, _ -> },
                    onUserProfileClick = {},
                ),
        )
    }

    private companion object {
        const val BOOK_ID = "b1"
        const val DELETE_DOWNLOAD = "Delete download"
        const val REMOVE_DOWNLOAD = "removeDownload"
    }
}
