package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.readers.BookReaders
import com.calypsan.listenup.client.domain.repository.BookReadersRepository
import com.calypsan.listenup.client.navigation.PaneSized
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersViewModel
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
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
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinApplication
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Book Detail chooses its two-column layout from the width it is given, so beside a list on a
 * 1280dp tablet — in a pane narrower than the 840dp that layout needs — it is the single column.
 *
 * The wide layout's chapter pane shows ten chapters before "Show all"; the single column shows
 * five. The sixth chapter tells the two apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Windows.TABLET)
class BookDetailPaneWidthTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The composable KoinApplication registers its container as the global Koin context. Left
     * running, a later spec that starts its own (ApplicationBootTest, CoverContentProviderTest)
     * fails with KoinApplicationAlreadyStartedException.
     */
    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    @Test
    fun `filling a tablet window, book detail uses its wide layout`() {
        setContent { BookDetailUnderTest() }

        composeRule.onNodeWithText(SIXTH_CHAPTER).assertExists()
    }

    @Test
    fun `in a pane narrower than the wide layout, book detail uses the single column`() {
        setContent {
            PaneSized(modifier = Modifier.width(768.dp)) { BookDetailUnderTest() }
        }

        composeRule.onNodeWithText(SIXTH_CHAPTER).assertDoesNotExist()
    }

    private fun setContent(content: @Composable () -> Unit) {
        val readers =
            mock<BookReadersRepository>(MockMode.autoUnit) {
                every { observeReadersFor(any()) } returns flowOf(BookReaders(readers = emptyList()))
            }
        composeRule.setContent {
            KoinApplication(
                application = {
                    modules(module { viewModel { (bookId: String) -> BookReadersViewModel(readers, bookId) } })
                },
            ) {
                MaterialTheme { content() }
            }
        }
    }

    private companion object {
        const val SIXTH_CHAPTER = "Chapter title 6"
    }
}

@Composable
private fun BookDetailUnderTest() {
    BookDetailContent(
        bookId = "b1",
        state =
            BookDetailUiState.Ready(
                book =
                    BookDetail(
                        id = BookId("b1"),
                        libraryId = LibraryId("lib"),
                        folderId = FolderId("folder"),
                        title = "Words of Radiance",
                        authors = emptyList(),
                        narrators = emptyList(),
                        duration = 1_200_000L,
                        // A local path keeps the cover on its synchronous path, clear of global Koin.
                        coverPath = "/tmp/cover-b1.webp",
                        addedAt = Timestamp(0L),
                        updatedAt = Timestamp(0L),
                    ),
                chapters =
                    List(12) { i ->
                        ChapterUiModel(id = "c$i", title = "Chapter title ${i + 1}", duration = "10:00", imageUrl = null)
                    },
            ),
        downloadStatus = BookDownloadStatus.NotDownloaded("b1"),
        isComplete = false,
        hasProgress = false,
        isAdmin = false,
        isWaitingForWifi = false,
        showPlaybackActions = true,
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
        canPlay = true,
        canDownload = true,
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
    )
}
